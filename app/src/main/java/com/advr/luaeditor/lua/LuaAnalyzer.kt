package com.advr.luaeditor.lua

import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.ApiParam
import com.advr.luaeditor.api.FileContext
import com.advr.luaeditor.api.TypeNames

/**
 * A structural (not full-grammar) walk of a Lua file that keeps a scope stack and infers types well
 * enough to answer "what are the fields of this thing?" while the user is still typing.
 *
 * It deliberately tolerates half written code: unbalanced blocks simply stay open to end of file.
 */
class LuaAnalyzer(
    private val src: String,
    private val toks: List<Tok>,
    private val api: ApiIndex,
    private val ctx: FileContext,
) {

    private class Scope(val start: Int) {
        val syms = ArrayList<Symbol>(8)
    }

    private val scopes = ArrayList<Scope>()
    private val allSymbols = ArrayList<Symbol>(32)
    private val userTypes = LinkedHashMap<String, UserType>()
    private val userClasses = LinkedHashMap<String, UserType>()
    private val functions = ArrayList<LuaFunction>()
    private val globals = LinkedHashMap<String, Symbol>()
    private val callFunctionInRefs = ArrayList<Reference>()
    private val declaredNames = HashSet<String>()
    private var typeCounter = 0

    /** Shares the member lookup rules with completion; the maps below are live. */
    private val resolver = TypeResolver(api, userTypes, userClasses) { name, offset ->
        lookupVisible(name, offset)
    }

    /** LuaLS annotations gathered from the `---` block immediately above a statement. */
    private class DocBlock {
        val lines = ArrayList<String>()
        var type: String = ""
        var className: String = ""
        var classParent: String = ""
        val fields = LinkedHashMap<String, Pair<String, String>>()
        val params = LinkedHashMap<String, String>()
        var ret: String = ""
        val text: String get() = lines.joinToString(" ").trim()
        fun isEmpty() = lines.isEmpty() && type.isEmpty() && className.isEmpty() &&
            fields.isEmpty() && params.isEmpty() && ret.isEmpty()
    }

    private var pendingDoc = DocBlock()

    fun analyze(): FileModel {
        scopes.add(Scope(0))
        var i = 0
        var braceDepth = 0
        var parenDepth = 0
        val n = toks.size

        while (i < n) {
            val t = toks[i]

            if (t.type == TokType.COMMENT) { i++; continue }
            if (t.type == TokType.DOC) { absorbDoc(t); i++; continue }

            if (t.type == TokType.STRING) {
                collectCallFunctionIn(i)
                i++
                pendingDoc = DocBlock()
                continue
            }

            if (t.type == TokType.PUNCT) {
                when (src[t.start]) {
                    '{' -> { braceDepth++; i++; continue }
                    '}' -> { if (braceDepth > 0) braceDepth--; i++; continue }
                    '(' -> { parenDepth++; i++; continue }
                    ')' -> { if (parenDepth > 0) parenDepth--; i++; continue }
                }
            }

            if (t.type == TokType.KEYWORD) {
                when (src.substring(t.start, t.end)) {
                    "local" -> { i = parseLocal(i); pendingDoc = DocBlock(); continue }
                    "function" -> { i = parseFunction(i, isLocal = false); pendingDoc = DocBlock(); continue }
                    "for" -> { i = parseFor(i); pendingDoc = DocBlock(); continue }
                    "do", "then", "repeat" -> { pushScope(t.end); i++; continue }
                    "end", "until" -> { popScope(t.start); i++; continue }
                    "else" -> { popScope(t.start); pushScope(t.end); i++; continue }
                    "elseif" -> { popScope(t.start); i++; continue }
                }
                i++
                continue
            }

            if (t.type == TokType.IDENT && braceDepth == 0 && parenDepth == 0 && isStatementStart(i)) {
                val next = tryAssignment(i)
                if (next > i) { i = next; pendingDoc = DocBlock(); continue }
            }
            i++
        }

        // Drain directly: popScope always re-seeds a file scope, so it can never empty the stack.
        val end = src.length
        while (scopes.isNotEmpty()) {
            val scope = scopes.removeAt(scopes.size - 1)
            for (sym in scope.syms) if (sym.scopeEnd == Int.MAX_VALUE) sym.scopeEnd = end
        }

        val free = collectFreeIdentifiers()
        return FileModel(
            text = src,
            toks = toks,
            symbols = allSymbols,
            userTypes = userTypes,
            userClasses = userClasses,
            functions = functions,
            globals = globals,
            callFunctionInRefs = callFunctionInRefs,
            freeIdentifiers = free,
        )
    }

    // ------------------------------------------------------------------ scopes

    private fun pushScope(at: Int) = scopes.add(Scope(at))

    private fun popScope(at: Int) {
        if (scopes.isEmpty()) return
        val s = scopes.removeAt(scopes.size - 1)
        for (sym in s.syms) if (sym.scopeEnd == Int.MAX_VALUE) sym.scopeEnd = at
        if (scopes.isEmpty()) scopes.add(Scope(at))   // never run out of a file scope
    }

    private fun declare(sym: Symbol) {
        allSymbols.add(sym)
        declaredNames.add(sym.name)
        scopes.lastOrNull()?.syms?.add(sym)
        if (sym.kind == SymbolKind.GLOBAL) globals[sym.name] = sym
    }

    // ------------------------------------------------------------------ doc comments

    private fun absorbDoc(t: Tok) {
        var body = src.substring(t.start, t.end)
        body = body.removePrefix("---").trim()
        if (body.startsWith("@")) {
            val sp = body.indexOf(' ')
            val tag = if (sp < 0) body.substring(1) else body.substring(1, sp)
            val rest = if (sp < 0) "" else body.substring(sp + 1).trim()
            when (tag) {
                "type" -> pendingDoc.type = firstWord(rest)
                "class" -> {
                    val colon = rest.indexOf(':')
                    pendingDoc.className = firstWord(if (colon < 0) rest else rest.substring(0, colon))
                    pendingDoc.classParent = if (colon < 0) "" else firstWord(rest.substring(colon + 1))
                    if (pendingDoc.className.isNotEmpty()) {
                        val ut = userClasses.getOrPut(pendingDoc.className) {
                            UserType("#${pendingDoc.className}", pendingDoc.className).also {
                                it.declaredName = pendingDoc.className
                            }
                        }
                        ut.parent = pendingDoc.classParent
                    }
                }
                "field" -> {
                    val name = firstWord(rest)
                    val after = rest.removePrefix(name).trim()
                    val ftype = firstTypeWord(after)
                    pendingDoc.fields[name] = ftype to after.removePrefix(ftype).trim()
                    if (pendingDoc.className.isNotEmpty()) {
                        userClasses[pendingDoc.className]?.members?.put(
                            name, UserMember(name, ftype, after.removePrefix(ftype).trim(),
                                callable = TypeNames.isFunction(ftype),
                                params = TypeNames.functionParams(ftype), declOffset = t.start)
                        )
                    }
                }
                "param" -> {
                    val name = firstWord(rest)
                    pendingDoc.params[name] = firstTypeWord(rest.removePrefix(name).trim())
                }
                "return" -> pendingDoc.ret = firstTypeWord(rest)
                "meta", "diagnostic" -> Unit
                else -> pendingDoc.lines.add(body)
            }
        } else if (body.isNotEmpty()) {
            pendingDoc.lines.add(body)
        }
    }

    private fun firstWord(s: String): String {
        val t = s.trim()
        val i = t.indexOfFirst { it == ' ' || it == '\t' }
        return if (i < 0) t else t.substring(0, i)
    }

    /** Like [firstWord] but keeps `fun(a: X):Y` in one piece. */
    private fun firstTypeWord(s: String): String {
        val t = s.trim()
        if (!t.startsWith("fun(")) return firstWord(t)
        var depth = 0
        var i = 3
        while (i < t.length) {
            when (t[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) { i++; break } }
            }
            i++
        }
        if (i < t.length && t[i] == ':') {
            i++
            while (i < t.length && !t[i].isWhitespace()) i++
        }
        return t.substring(0, i.coerceAtMost(t.length))
    }

    // ------------------------------------------------------------------ statements

    private fun isStatementStart(i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && (toks[j].type == TokType.COMMENT || toks[j].type == TokType.DOC)) j--
        if (j < 0) return true
        val p = toks[j]
        if (p.line < toks[i].line) return true
        if (p.type == TokType.PUNCT && (src[p.start] == ';')) return true
        if (p.type == TokType.KEYWORD) {
            return when (src.substring(p.start, p.end)) {
                "do", "then", "else", "end", "repeat", "until" -> true
                else -> false
            }
        }
        return false
    }

    /** `local a, b = expr, expr` and `local function f(...)`. */
    private fun parseLocal(start: Int): Int {
        var i = start + 1
        if (i < toks.size && toks[i].isKeyword(src, "function")) {
            return parseFunction(i, isLocal = true)
        }
        val names = ArrayList<Pair<String, Int>>(2)
        while (i < toks.size && toks[i].type == TokType.IDENT) {
            names.add(src.substring(toks[i].start, toks[i].end) to toks[i].start)
            i++
            // LuaLS style attributes: `local x <const> = 1`
            if (i < toks.size && toks[i].isPunct(src, "<")) {
                while (i < toks.size && !toks[i].isPunct(src, ">")) i++
                i++
            }
            if (i < toks.size && toks[i].isPunct(src, ",")) { i++; continue }
            break
        }
        if (names.isEmpty()) return start + 1

        val doc = pendingDoc
        val types = ArrayList<String>(names.size)
        var after = i
        if (i < toks.size && isAssign(toks[i])) {
            var e = i + 1
            for (k in names.indices) {
                if (e >= toks.size) break
                val (ty, next) = inferExpr(e)
                types.add(ty)
                e = next
                if (e < toks.size && toks[e].isPunct(src, ",")) e++ else break
            }
            after = i + 1     // let the main loop walk the right hand side normally
        }

        val scopeStart = if (after < toks.size) toks[after].start else src.length
        names.forEachIndexed { idx, (name, off) ->
            var ty = types.getOrElse(idx) { "" }
            if (doc.type.isNotEmpty() && idx == 0) ty = doc.type
            if (ty.isEmpty() && doc.type.isNotEmpty()) ty = doc.type
            val sym = Symbol(name, ty, SymbolKind.LOCAL, off, scopeStart, Int.MAX_VALUE, doc.text)
            if (ty.startsWith("@")) userTypes[ty]?.displayName = name
            declare(sym)
        }
        return after
    }

    private fun isAssign(t: Tok): Boolean =
        t.type == TokType.OPERATOR && t.end - t.start == 1 && src[t.start] == '='

    /**
     * `function name(...)`, `function a.b.c(...)`, `function a:b(...)` and anonymous
     * `function(...)`. Declares the symbol, then opens a scope holding the parameters.
     */
    private fun parseFunction(start: Int, isLocal: Boolean): Int {
        val doc = pendingDoc
        var i = start + 1
        val chain = ArrayList<String>(2)
        var nameOffset = toks.getOrNull(i)?.start ?: toks[start].start
        var methodStyle = false

        while (i < toks.size && toks[i].type == TokType.IDENT) {
            chain.add(src.substring(toks[i].start, toks[i].end))
            i++
            if (i < toks.size && toks[i].type == TokType.PUNCT) {
                val c = src[toks[i].start]
                if (c == '.' || c == ':') { methodStyle = methodStyle || c == ':'; i++; continue }
            }
            break
        }

        val params = ArrayList<ApiParam>(4)
        if (i < toks.size && toks[i].isPunct(src, "(")) {
            i++
            while (i < toks.size && !toks[i].isPunct(src, ")")) {
                val t = toks[i]
                if (t.type == TokType.IDENT || (t.type == TokType.PUNCT && t.matches(src, "..."))) {
                    val pn = src.substring(t.start, t.end)
                    params.add(ApiParam(pn, doc.params[pn] ?: "any"))
                }
                i++
            }
            if (i < toks.size) i++     // consume ')'
        }

        val bodyStart = if (i < toks.size) toks[i - 1].end else src.length
        val fullName = chain.joinToString(".")
        val simple = chain.lastOrNull() ?: ""

        if (chain.isNotEmpty()) {
            functions.add(
                LuaFunction(
                    fullName = fullName, simpleName = simple, ownerChain = chain.dropLast(1),
                    params = params, declOffset = toks[start].start, nameOffset = nameOffset,
                    doc = doc.text, ret = doc.ret, isLocal = isLocal,
                )
            )
            if (chain.size == 1) {
                val kind = if (isLocal) SymbolKind.LOCAL else SymbolKind.FUNCTION
                declare(
                    Symbol(simple, "function", kind, toks[start].start,
                        if (isLocal) bodyStart else 0, Int.MAX_VALUE, doc.text, params, doc.ret)
                )
            } else {
                // `function myTable.doThing(a)` grows the owner's shape in real time.
                attachMember(chain.dropLast(1), simple, "function", doc.text, params, doc.ret, toks[start].start)
            }
        }

        pushScope(bodyStart)
        if (methodStyle) {
            val ownerType = resolveChainType(chain.dropLast(1), bodyStart)
            declare(Symbol("self", ownerType, SymbolKind.PARAM, bodyStart, bodyStart, Int.MAX_VALUE))
        }
        for (p in params) {
            declare(Symbol(p.name, if (p.type == "any") "" else p.type, SymbolKind.PARAM, bodyStart, bodyStart, Int.MAX_VALUE))
        }
        return i
    }

    /** `for i = a, b do` and `for k, v in expr do`. */
    private fun parseFor(start: Int): Int {
        var i = start + 1
        val names = ArrayList<Pair<String, Int>>(2)
        while (i < toks.size && toks[i].type == TokType.IDENT) {
            names.add(src.substring(toks[i].start, toks[i].end) to toks[i].start)
            i++
            if (i < toks.size && toks[i].isPunct(src, ",")) { i++; continue }
            break
        }
        val types = ArrayList<String>(names.size)
        if (i < toks.size && isAssign(toks[i])) {
            repeat(names.size) { types.add("number") }
        } else if (i < toks.size && toks[i].isKeyword(src, "in")) {
            val iterStart = i + 1
            val iterName = toks.getOrNull(iterStart)?.takeIf { it.type == TokType.IDENT }
                ?.let { src.substring(it.start, it.end) } ?: ""
            when (iterName) {
                "ipairs" -> {
                    val inner = inferInsideCall(iterStart)
                    types.add("number")
                    types.add(TypeNames.element(inner).ifEmpty { elementOfUserType(inner) })
                }
                "pairs" -> {
                    val inner = inferInsideCall(iterStart)
                    types.add("string")
                    types.add(TypeNames.element(inner).ifEmpty { elementOfUserType(inner) })
                }
                else -> {
                    val (ty, _) = inferExpr(iterStart)
                    types.add(TypeNames.element(ty).ifEmpty { ty })
                    while (types.size < names.size) types.add("")
                }
            }
        }
        // Open the loop body at `do`; the matching `end` closes it through the main loop.
        while (i < toks.size && !toks[i].isKeyword(src, "do")) i++
        val bodyStart = if (i < toks.size) toks[i].end else src.length
        pushScope(bodyStart)
        names.forEachIndexed { idx, (name, off) ->
            declare(Symbol(name, types.getOrElse(idx) { "" }, SymbolKind.LOOP_VAR, off, bodyStart, Int.MAX_VALUE))
        }
        return if (i < toks.size) i + 1 else i
    }

    /** `a = expr`, `a.b.c = expr`, `a, b = expr, expr`. Returns the index to resume from. */
    private fun tryAssignment(start: Int): Int {
        val chains = ArrayList<List<String>>(2)
        var i = start
        while (i < toks.size) {
            val chain = ArrayList<String>(2)
            if (toks[i].type != TokType.IDENT) return start
            chain.add(src.substring(toks[i].start, toks[i].end))
            i++
            var bracketed = false
            while (i < toks.size) {
                val t = toks[i]
                if (t.type == TokType.PUNCT && (src[t.start] == '.' || src[t.start] == ':') &&
                    i + 1 < toks.size && toks[i + 1].type == TokType.IDENT
                ) {
                    chain.add(src.substring(toks[i + 1].start, toks[i + 1].end))
                    i += 2
                    continue
                }
                if (t.isPunct(src, "[")) {
                    bracketed = true
                    var depth = 0
                    while (i < toks.size) {
                        if (toks[i].isPunct(src, "[")) depth++
                        if (toks[i].isPunct(src, "]")) { depth--; if (depth == 0) { i++; break } }
                        i++
                    }
                    continue
                }
                break
            }
            if (bracketed) return start
            chains.add(chain)
            if (i < toks.size && toks[i].isPunct(src, ",")) { i++; continue }
            break
        }
        if (i >= toks.size || !isAssign(toks[i])) return start

        val doc = pendingDoc
        var e = i + 1
        for (chain in chains) {
            if (e >= toks.size) break
            val (inferred, next) = inferExpr(e)
            val ty = if (doc.type.isNotEmpty()) doc.type else inferred
            applyAssignment(chain, ty, doc.text, toks[start].start)
            e = next
            if (e < toks.size && toks[e].isPunct(src, ",")) e++ else break
        }
        return i + 1
    }

    private fun applyAssignment(chain: List<String>, type: String, doc: String, offset: Int) {
        if (chain.size == 1) {
            val name = chain[0]
            val existing = lookupVisible(name, offset)
            if (existing != null) {
                if (existing.type.isEmpty() && type.isNotEmpty()) existing.type = type
                if (type.startsWith("@")) {
                    userTypes[type]?.displayName = name
                    if (existing.type.startsWith("@") && existing.type != type) mergeUserTypes(existing.type, type)
                    else existing.type = type
                }
                return
            }
            // No local in scope: this is a file global. ADVR gives every file its own global table,
            // so it is only ever offered as a completion inside this file.
            val g = globals[name]
            if (g != null) {
                if (g.type.isEmpty()) g.type = type
                return
            }
            val sym = Symbol(name, type, SymbolKind.GLOBAL, offset, 0, Int.MAX_VALUE, doc)
            if (type.startsWith("@")) userTypes[type]?.displayName = name
            declare(sym)
        } else {
            attachMember(chain.dropLast(1), chain.last(), type, doc, emptyList(), "", offset)
        }
    }

    /** Ensures `owner` has a mutable shape and records `member` on it. */
    private fun attachMember(
        ownerChain: List<String>,
        member: String,
        type: String,
        doc: String,
        params: List<ApiParam>,
        ret: String,
        offset: Int,
    ) {
        if (ownerChain.isEmpty()) return
        val ownerType = ensureUserType(ownerChain, offset) ?: return
        val ut = userTypes[ownerType] ?: userClasses.values.firstOrNull { it.id == ownerType } ?: return
        val existing = ut.members[member]
        if (existing == null) {
            ut.members[member] = UserMember(member, type, doc, type == "function", params, offset)
        } else {
            if (existing.type.isEmpty() || existing.type == "any") existing.type = type
            if (type == "function") { existing.callable = true; existing.params = params }
            if (doc.isNotEmpty()) existing.doc = doc
        }
    }

    /**
     * Walks/creates the chain of user types for `a.b.c`, so `a.b.c.d = 1` teaches the editor about
     * every level. Returns the type id of the deepest owner, or null when the root is an API object
     * (those are described by the stubs and are not extended here).
     */
    private fun ensureUserType(chain: List<String>, offset: Int): String? {
        val rootName = chain[0]
        var rootSym = lookupVisible(rootName, offset) ?: globals[rootName]
        if (rootSym == null) {
            if (api.globalType(rootName) != null || rootName in LuaLexer.STDLIB) {
                // `ADVR.onLoad = function() end` targets an API table; keep the stub's own shape.
                return null
            }
            rootSym = Symbol(rootName, "", SymbolKind.GLOBAL, offset, 0, Int.MAX_VALUE)
            declare(rootSym)
        }
        var typeId = rootSym.type
        if (!typeId.startsWith("@")) {
            if (typeId.isNotEmpty() && (api.classOf(typeId) != null || userClasses.containsKey(typeId))) return null
            typeId = newUserType(rootName)
            rootSym.type = typeId
        }
        for (k in 1 until chain.size) {
            val ut = userTypes[typeId] ?: return null
            val m = ut.members.getOrPut(chain[k]) { UserMember(chain[k], "", declOffset = offset) }
            if (!m.type.startsWith("@")) {
                if (m.type.isNotEmpty() && api.classOf(m.type) != null) return null
                m.type = newUserType(chain[k])
            }
            typeId = m.type
        }
        return typeId
    }

    private fun newUserType(name: String): String {
        val id = "@${typeCounter++}"
        userTypes[id] = UserType(id, name)
        return id
    }

    private fun mergeUserTypes(into: String, from: String) {
        val a = userTypes[into] ?: return
        val b = userTypes[from] ?: return
        for ((k, v) in b.members) a.members.putIfAbsent(k, v)
    }

    private fun lookupVisible(name: String, offset: Int): Symbol? {
        var best: Symbol? = null
        for (s in allSymbols) {
            if (s.name != name) continue
            if (offset < s.scopeStart) continue
            if (s.scopeEnd != Int.MAX_VALUE && offset > s.scopeEnd) continue
            if (best == null || s.scopeStart >= best.scopeStart) best = s
        }
        return best
    }

    // ------------------------------------------------------------------ expressions

    /** Returns the inferred type and the token index just past the expression. */
    private fun inferExpr(start: Int): Pair<String, Int> {
        var (type, i) = inferUnary(start)
        // Binary continuation, enough to keep obvious cases right.
        while (i < toks.size) {
            val t = toks[i]
            val txt = src.substring(t.start, t.end)
            if (t.type == TokType.OPERATOR) {
                when (txt) {
                    ".." -> { val (_, n) = inferUnary(i + 1); type = "string"; i = n; continue }
                    "+", "-", "*", "/", "%", "^", "//" -> { val (_, n) = inferUnary(i + 1); type = "number"; i = n; continue }
                    "==", "~=", "<", ">", "<=", ">=" -> { val (_, n) = inferUnary(i + 1); type = "boolean"; i = n; continue }
                }
                break
            }
            if (t.type == TokType.KEYWORD && (txt == "and" || txt == "or")) {
                val (rt, n) = inferUnary(i + 1)
                if (type.isEmpty() || type == "boolean") type = rt
                i = n
                continue
            }
            break
        }
        return type to i
    }

    private fun inferUnary(start: Int): Pair<String, Int> {
        var i = start
        while (i < toks.size) {
            val t = toks[i]
            if (t.type == TokType.COMMENT || t.type == TokType.DOC) { i++; continue }
            if (t.type == TokType.OPERATOR && (t.matches(src, "-") || t.matches(src, "#"))) {
                val (_, n) = inferUnary(i + 1)
                return (if (t.matches(src, "#")) "number" else "number") to n
            }
            if (t.type == TokType.KEYWORD && t.matches(src, "not")) {
                val (_, n) = inferUnary(i + 1)
                return "boolean" to n
            }
            break
        }
        if (i >= toks.size) return "" to i
        val t = toks[i]
        return when {
            t.type == TokType.NUMBER -> "number" to i + 1
            t.type == TokType.STRING -> "string" to i + 1
            t.isKeyword(src, "true") || t.isKeyword(src, "false") -> "boolean" to i + 1
            t.isKeyword(src, "nil") -> "" to i + 1
            t.isKeyword(src, "function") -> "function" to skipFunctionBody(i)
            t.isPunct(src, "{") -> parseTableLiteral(i)
            t.isPunct(src, "(") -> {
                val (ty, _) = inferExpr(i + 1)
                ty to skipBalanced(i, '(', ')')
            }
            t.type == TokType.IDENT -> resolveChainAt(i)
            else -> "" to i + 1
        }
    }

    /** `{ a = 1, b = "x" }` becomes a fresh user type; array style items give an element type. */
    private fun parseTableLiteral(start: Int): Pair<String, Int> {
        val id = newUserType("table")
        val ut = userTypes[id]!!
        var i = start + 1
        var depth = 1
        val elementTypes = HashSet<String>()
        while (i < toks.size && depth > 0) {
            val t = toks[i]
            if (t.isPunct(src, "{")) { depth++; i++; continue }
            if (t.isPunct(src, "}")) { depth--; i++; continue }
            if (depth == 1 && t.type == TokType.IDENT && i + 1 < toks.size && isAssign(toks[i + 1])) {
                val key = src.substring(t.start, t.end)
                val (ty, next) = inferExpr(i + 2)
                ut.members[key] = UserMember(key, ty, declOffset = t.start)
                i = next
                continue
            }
            if (depth == 1 && t.isPunct(src, "[")) {
                val close = skipBalanced(i, '[', ']')
                if (close < toks.size && isAssign(toks[close])) {
                    val keyTok = toks.getOrNull(i + 1)
                    val (ty, next) = inferExpr(close + 1)
                    if (keyTok != null && keyTok.type == TokType.STRING) {
                        val key = src.substring(keyTok.start + 1, (keyTok.end - 1).coerceAtLeast(keyTok.start + 1))
                        ut.members[key] = UserMember(key, ty, declOffset = keyTok.start)
                    }
                    i = next
                    continue
                }
                i = close
                continue
            }
            if (depth == 1 && !t.isPunct(src, ",") && !t.isPunct(src, ";")) {
                val (ty, next) = inferExpr(i)
                if (ty.isNotEmpty()) elementTypes.add(ty)
                i = if (next > i) next else i + 1
                continue
            }
            i++
        }
        if (ut.members.isEmpty() && elementTypes.size == 1) {
            ut.displayName = "${elementTypes.first()}[]"
            return "${elementTypes.first()}[]" to i
        }
        return id to i
    }

    /** Resolves `a.b.c(...)` / `a:b()` starting at an identifier, returning the resulting type. */
    private fun resolveChainAt(start: Int): Pair<String, Int> {
        var i = start
        val first = src.substring(toks[i].start, toks[i].end)
        var type: String
        i++

        if (i < toks.size && toks[i].isPunct(src, "(")) {
            val argc = countArgs(i)
            type = callReturnOfName(first, argc, toks[start].start)
            i = skipBalanced(i, '(', ')')
        } else {
            type = typeOfName(first, toks[start].start)
        }

        while (i < toks.size) {
            val t = toks[i]
            if (t.type == TokType.PUNCT && (src[t.start] == '.' || src[t.start] == ':') &&
                i + 1 < toks.size && toks[i + 1].type == TokType.IDENT
            ) {
                val member = src.substring(toks[i + 1].start, toks[i + 1].end)
                if (i + 2 < toks.size && toks[i + 2].isPunct(src, "(")) {
                    val argc = countArgs(i + 2)
                    type = memberCallReturn(type, member, argc)
                    i = skipBalanced(i + 2, '(', ')')
                } else {
                    type = memberType(type, member)
                    i += 2
                }
                continue
            }
            if (t.isPunct(src, "[")) {
                val el = TypeNames.element(type)
                type = el.ifEmpty { elementOfUserType(type) }
                i = skipBalanced(i, '[', ']')
                continue
            }
            if (t.isPunct(src, "(")) {
                type = ""      // calling a value we did not resolve
                i = skipBalanced(i, '(', ')')
                continue
            }
            break
        }
        return type to i
    }

    fun typeOfName(name: String, offset: Int): String = resolver.typeOfName(name, offset)

    /** Type of a dotted name such as `myTable.inner`, used for `function a.b:c()` receivers. */
    private fun resolveChainType(chain: List<String>, offset: Int): String {
        if (chain.isEmpty()) return ""
        var type = resolver.typeOfName(chain[0], offset)
        for (k in 1 until chain.size) {
            if (type.isEmpty()) return ""
            type = resolver.memberType(type, chain[k])
        }
        return type
    }

    fun memberType(type: String, member: String): String = resolver.memberType(type, member)

    fun memberCallReturn(type: String, member: String, argc: Int): String =
        resolver.memberCallReturn(type, member, argc)

    private fun callReturnOfName(name: String, argc: Int, offset: Int): String {
        functions.firstOrNull { it.fullName == name }?.let { return it.ret }
        when (name) {
            "tostring", "type" -> return "string"
            "tonumber" -> return "number"
            "require", "pcall", "select" -> return ""
        }
        lookupVisible(name, offset)?.let { s ->
            if (s.ret.isNotEmpty()) return s.ret
        }
        // `vector3(1, 2, 3)` style: an API global standing in for its constructor.
        api.globalType(name)?.let { cls ->
            api.findMethods(cls, "__new").firstOrNull()?.let { return it.ret }
        }
        return ""
    }

    private fun elementOfUserType(type: String): String = resolver.elementOf(type)

    private fun inferInsideCall(identIndex: Int): String {
        val open = identIndex + 1
        if (open >= toks.size || !toks[open].isPunct(src, "(")) return ""
        val (ty, _) = inferExpr(open + 1)
        return ty
    }

    // ------------------------------------------------------------------ token skipping

    private fun skipBalanced(openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = openIndex
        while (i < toks.size) {
            val t = toks[i]
            if (t.type == TokType.PUNCT && t.end - t.start == 1) {
                val c = src[t.start]
                if (c == open) depth++
                else if (c == close) { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return toks.size
    }

    private fun skipFunctionBody(functionIndex: Int): Int {
        var i = functionIndex + 1
        if (i < toks.size && toks[i].isPunct(src, "(")) i = skipBalanced(i, '(', ')')
        var depth = 1
        while (i < toks.size && depth > 0) {
            val t = toks[i]
            if (t.type == TokType.KEYWORD) {
                when (src.substring(t.start, t.end)) {
                    "function", "do", "if" -> depth++
                    "end" -> depth--
                }
            }
            i++
        }
        return i
    }

    private fun countArgs(openIndex: Int): Int {
        var depth = 0
        var count = 0
        var sawAny = false
        var i = openIndex
        while (i < toks.size) {
            val t = toks[i]
            if (t.type == TokType.PUNCT && t.end - t.start == 1) {
                when (src[t.start]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> { depth--; if (depth == 0) return if (sawAny) count + 1 else 0 }
                    ',' -> if (depth == 1) count++
                }
            }
            if (depth == 1 && t.type != TokType.PUNCT) sawAny = true
            if (depth == 1 && t.type == TokType.PUNCT && src[t.start] !in charArrayOf('(', ',')) sawAny = true
            i++
        }
        return if (sawAny) count + 1 else 0
    }

    // ------------------------------------------------------------------ extras

    /** `.CallFunctionIn("name", ...)` names, so a missing target can be flagged. */
    private fun collectCallFunctionIn(stringIndex: Int) {
        if (stringIndex < 3) return
        if (!toks[stringIndex - 1].isPunct(src, "(")) return
        val nameTok = toks[stringIndex - 2]
        if (nameTok.type != TokType.IDENT || !nameTok.matches(src, "CallFunctionIn")) return
        val raw = src.substring(toks[stringIndex].start, toks[stringIndex].end)
        if (raw.length < 2) return
        callFunctionInRefs.add(Reference(raw.substring(1, raw.length - 1), toks[stringIndex].start, raw.length))
    }

    /**
     * Identifiers that are read but never bound anywhere in this file and are not part of the ADVR
     * API. With per-file globals these are the ones that silently blow up at runtime.
     */
    private fun collectFreeIdentifiers(): List<Reference> {
        val out = ArrayList<Reference>()
        val known = HashSet<String>(declaredNames)
        functions.forEach { known.add(it.fullName); known.add(it.simpleName) }
        for (i in toks.indices) {
            val t = toks[i]
            if (t.type != TokType.IDENT) continue
            val prev = toks.getOrNull(i - 1)
            if (prev != null && prev.type == TokType.PUNCT &&
                (src[prev.start] == '.' || src[prev.start] == ':')
            ) continue
            if (prev != null && prev.isKeyword(src, "function")) continue
            if (prev != null && prev.isKeyword(src, "local")) continue
            if (prev != null && prev.isKeyword(src, "for")) continue
            val name = src.substring(t.start, t.end)
            if (name in known || name in LuaLexer.STDLIB) continue
            if (api.globalType(name) != null) continue
            if (userClasses.containsKey(name)) continue
            // A table key inside `{ key = ... }` is not a variable read.
            val next = toks.getOrNull(i + 1)
            if (next != null && isAssign(next)) continue
            out.add(Reference(name, t.start, t.end - t.start))
        }
        return out
    }

    companion object {
        fun analyze(src: String, api: ApiIndex, ctx: FileContext): FileModel {
            val toks = LuaLexer.lex(src)
            return LuaAnalyzer(src, toks, api, ctx).analyze()
        }
    }
}
