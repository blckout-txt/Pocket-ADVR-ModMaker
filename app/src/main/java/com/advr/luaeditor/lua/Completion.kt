package com.advr.luaeditor.lua

import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.ApiParam
import com.advr.luaeditor.api.FileContext
import com.advr.luaeditor.api.ModKind
import com.advr.luaeditor.api.TypeNames

enum class CompletionKind {
    FIELD, METHOD, EVENT, LOCAL, PARAM, LOOP_VAR, FILE_GLOBAL, FILE_FUNCTION,
    SELF_GLOBAL, API_GLOBAL, KEYWORD, SNIPPET, CLASS,
}

class Completion(
    @JvmField val label: String,
    @JvmField val detail: String,
    @JvmField val doc: String,
    @JvmField val kind: CompletionKind,
    /** Text that replaces the typed prefix. `$0` marks where the caret should land. */
    @JvmField val insert: String,
    @JvmField val score: Int,
    @JvmField val badge: String = "",
) {
    val insertText: String get() = insert.replace("$0", "")
    val caretOffset: Int
        get() = insert.indexOf("$0").let { if (it < 0) insert.length else it }
}

class SignatureInfo(
    @JvmField val label: String,
    @JvmField val params: List<ApiParam>,
    @JvmField val activeParam: Int,
    @JvmField val doc: String,
    @JvmField val overloadIndex: Int,
    @JvmField val overloadCount: Int,
)

class CompletionResult(
    @JvmField val items: List<Completion>,
    @JvmField val replaceStart: Int,
    @JvmField val replaceEnd: Int,
    /** Short line describing what is being completed, e.g. `pickup : ItemInterpreter…`. */
    @JvmField val header: String,
    @JvmField val isMemberAccess: Boolean,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val NONE = CompletionResult(emptyList(), 0, 0, "", false)
    }
}

/**
 * Turns a caret position into a ranked completion list.
 *
 * Members come from whichever is more specific: a shape the user built in this file, or the ADVR
 * stubs. Bare identifiers respect ADVR's per-file globals - a `potion` is never offered inside a
 * file that lives under `items/`.
 */
class CompletionEngine(private val api: ApiIndex) {

    fun complete(text: String, cursor: Int, model: FileModel, ctx: FileContext, limit: Int = 200): CompletionResult {
        if (cursor > text.length) return CompletionResult.NONE
        val resolver = model.resolver(api)

        var prefixStart = cursor
        while (prefixStart > 0 && isIdentChar(text[prefixStart - 1])) prefixStart--
        val prefix = text.substring(prefixStart, cursor)

        var p = prefixStart - 1
        while (p >= 0 && (text[p] == ' ' || text[p] == '\t')) p--

        if (p >= 0 && (text[p] == '.' || text[p] == ':')) {
            val colonCall = text[p] == ':'
            val receiverType = resolveReceiverType(text, p, model, resolver)
            val items = memberCompletions(receiverType, prefix, resolver, ctx, colonCall, limit)
            val header = if (receiverType.isEmpty()) "unknown type"
            else resolver.display(receiverType)
            return CompletionResult(items, prefixStart, cursor, header, true)
        }

        // Without a prefix a bare caret would offer every global in the game; wait for a letter.
        if (prefix.isEmpty()) return CompletionResult.NONE

        return CompletionResult(
            scopeCompletions(prefix, cursor, model, ctx, resolver, limit),
            prefixStart, cursor,
            if (ctx.kind == ModKind.UNKNOWN) "globals in this file" else "${ctx.kind.label} file",
            false,
        )
    }

    // ------------------------------------------------------------------ members

    private fun memberCompletions(
        type: String,
        prefix: String,
        resolver: TypeResolver,
        ctx: FileContext,
        colonCall: Boolean,
        limit: Int,
    ): List<Completion> {
        if (type.isEmpty()) return emptyList()
        val out = ArrayList<Completion>(64)
        val isAdvrRoot = type == "ADVREvents"

        for (m in resolver.membersOf(type)) {
            if (isAdvrRoot && ctx.isForeignEventTable(m.name)) continue
            val s = match(prefix, m.name) ?: continue
            val kind = when (m.kind) {
                MemberKind.METHOD -> CompletionKind.METHOD
                MemberKind.EVENT -> CompletionKind.EVENT
                MemberKind.FIELD -> CompletionKind.FIELD
            }
            val bonus = when {
                m.fromUserCode -> 140
                m.kind == MemberKind.FIELD -> 100
                else -> 95
            }
            val detail = when (m.kind) {
                MemberKind.FIELD -> TypeNames.display(m.type)
                else -> signatureLabel(m.name, m.params, m.ret) +
                    if (m.overloads > 1) "  +${m.overloads - 1}" else ""
            }
            val insert = when (m.kind) {
                MemberKind.FIELD -> m.name
                else -> if (m.params.isEmpty()) "${m.name}()$0" else "${m.name}($0)"
            }
            out.add(
                Completion(
                    label = m.name,
                    detail = detail,
                    doc = m.doc,
                    kind = kind,
                    insert = insert,
                    score = s + bonus,
                    badge = if (m.fromUserCode) "this file" else m.owner,
                )
            )
        }
        if (colonCall) {
            // ADVR's API is written with `.` throughout; nudge without hiding anything.
            out.sortByDescending { it.score + if (it.kind == CompletionKind.METHOD) 40 else 0 }
        } else {
            out.sortByDescending { it.score }
        }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /** Walks backwards over `a.b(c).d` to find the value the caret's `.` is attached to. */
    private fun resolveReceiverType(
        text: String,
        dotIndex: Int,
        model: FileModel,
        resolver: TypeResolver,
    ): String {
        val from = (dotIndex - MAX_CHAIN_LOOKBACK).coerceAtLeast(0)
        val slice = text.substring(from, dotIndex)
        val toks = LuaLexer.lex(slice)
        if (toks.isEmpty()) return ""

        // Collect the chain that ends at the dot, right to left.
        var i = toks.size - 1
        val parts = ArrayList<ChainPart>(4)
        while (i >= 0) {
            val t = toks[i]
            if (t.type == TokType.PUNCT && slice[t.start] == ')') {
                val open = matchBackwards(slice, toks, i, '(', ')')
                if (open <= 0) return ""
                val nameTok = toks.getOrNull(open - 1) ?: return ""
                if (nameTok.type != TokType.IDENT) return ""
                parts.add(ChainPart(slice.substring(nameTok.start, nameTok.end), true, countArgsForward(slice, toks, open)))
                i = open - 2
            } else if (t.type == TokType.PUNCT && slice[t.start] == ']') {
                val open = matchBackwards(slice, toks, i, '[', ']')
                if (open <= 0) return ""
                parts.add(ChainPart("[]", false, 0))
                i = open - 1
                continue
            } else if (t.type == TokType.IDENT) {
                parts.add(ChainPart(slice.substring(t.start, t.end), false, 0))
                i--
            } else if (t.type == TokType.STRING && parts.isEmpty()) {
                return "string"
            } else {
                return ""
            }
            // Continue only while the chain is joined by `.` or `:`.
            val sep = toks.getOrNull(i) ?: break
            if (sep.type == TokType.PUNCT && (slice[sep.start] == '.' || slice[sep.start] == ':')) {
                i--
                continue
            }
            break
        }
        if (parts.isEmpty()) return ""
        parts.reverse()

        // The walk stopped just before the leftmost token of the chain; that is where it starts.
        val rootTok = toks.getOrNull((i + 1).coerceIn(0, toks.size - 1)) ?: toks[0]
        val offset = from + rootTok.start
        var type = ""
        parts.forEachIndexed { idx, part ->
            type = when {
                part.name == "[]" -> resolver.elementOf(type)
                idx == 0 && part.isCall ->
                    fileFunctionReturn(model, part.name).ifEmpty {
                        resolver.memberCallReturn(
                            resolver.typeOfName(part.name, offset).ifEmpty { part.name }, "__new", part.argc
                        )
                    }
                idx == 0 -> resolver.typeOfName(part.name, offset)
                part.isCall -> resolver.memberCallReturn(type, part.name, part.argc)
                else -> resolver.memberType(type, part.name)
            }
        }
        return type
    }

    private class ChainPart(val name: String, val isCall: Boolean, val argc: Int)

    private fun fileFunctionReturn(model: FileModel, name: String): String =
        model.functions.firstOrNull { it.fullName == name }?.ret ?: ""

    // ------------------------------------------------------------------ scope

    private fun scopeCompletions(
        prefix: String,
        cursor: Int,
        model: FileModel,
        ctx: FileContext,
        resolver: TypeResolver,
        limit: Int,
    ): List<Completion> {
        val out = ArrayList<Completion>(128)
        val seen = HashSet<String>()

        fun add(label: String, detail: String, doc: String, kind: CompletionKind, insert: String, bonus: Int, badge: String = "") {
            if (!seen.add(label)) return
            val s = match(prefix, label) ?: run { seen.remove(label); return }
            out.add(Completion(label, detail, doc, kind, insert, s + bonus, badge))
        }

        // 1. Locals, parameters and loop variables in scope.
        for (sym in model.symbols) {
            if (!sym.visibleAt(cursor)) continue
            val kind = when (sym.kind) {
                SymbolKind.PARAM -> CompletionKind.PARAM
                SymbolKind.LOOP_VAR -> CompletionKind.LOOP_VAR
                SymbolKind.GLOBAL -> CompletionKind.FILE_GLOBAL
                SymbolKind.FUNCTION -> CompletionKind.FILE_FUNCTION
                SymbolKind.LOCAL -> CompletionKind.LOCAL
            }
            val bonus = when (sym.kind) {
                SymbolKind.PARAM, SymbolKind.LOOP_VAR -> 160
                SymbolKind.LOCAL -> 155
                SymbolKind.GLOBAL -> 145
                SymbolKind.FUNCTION -> 150
            }
            val insert = if (sym.kind == SymbolKind.FUNCTION) {
                if (sym.params.isEmpty()) "${sym.name}()$0" else "${sym.name}($0)"
            } else sym.name
            val detail = if (sym.kind == SymbolKind.FUNCTION)
                signatureLabel(sym.name, sym.params, sym.ret)
            else resolver.display(sym.type)
            add(sym.name, detail, sym.doc, kind, insert, bonus, "this file")
        }

        // 2. Functions declared in this file, including dotted ones like `ADVR.onLoad`.
        for (fn in model.functions) {
            if (fn.ownerChain.isEmpty()) continue
            val label = fn.fullName
            add(
                label, signatureLabel(fn.simpleName, fn.params, fn.ret), fn.doc,
                CompletionKind.FILE_FUNCTION,
                if (fn.params.isEmpty()) "$label()$0" else "$label($0)",
                130, "this file",
            )
        }

        // 3. The global this file is handed by ADVR.
        if (ctx.kind.selfGlobal.isNotEmpty()) {
            val g = ctx.kind.selfGlobal
            add(g, api.globalType(g) ?: g, "The ${ctx.kind.label.lowercase()} this file defines.",
                CompletionKind.SELF_GLOBAL, g, 200, ctx.kind.label)
        }

        // 4. ADVR API globals, minus the per-file globals belonging to other kinds of file.
        for ((name, cls) in api.rootGlobals) {
            if (ctx.isForeignSelfGlobal(name)) continue
            val doc = api.classOf(cls)?.doc ?: ""
            add(name, cls, doc, CompletionKind.API_GLOBAL, name, 70, "ADVR")
        }

        // 5. User declared `---@class` names, usable as types.
        for (name in model.userClasses.keys) {
            add(name, "class", "", CompletionKind.CLASS, name, 120, "this file")
        }

        // 6. Language keywords and the reachable standard library.
        for (kw in LuaLexer.KEYWORDS) add(kw, "keyword", "", CompletionKind.KEYWORD, kw, 40)
        for (fn in LuaLexer.STDLIB) add(fn, "lua", "", CompletionKind.API_GLOBAL, fn, 45, "lua")

        // 7. Templates, with this file's required callbacks first.
        for (snip in Snippets.forContext(ctx)) {
            add(snip.label, snip.detail, snip.doc, CompletionKind.SNIPPET, snip.body, snip.bonus, "template")
        }

        out.sortByDescending { it.score }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    // ------------------------------------------------------------------ signature help

    fun signatureHelp(text: String, cursor: Int, model: FileModel, overload: Int = 0): SignatureInfo? {
        val resolver = model.resolver(api)
        var depth = 0
        var i = cursor - 1
        var commas = 0
        val stop = (cursor - MAX_CHAIN_LOOKBACK).coerceAtLeast(0)
        while (i >= stop) {
            when (text[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) break
                    depth--
                }
                ',' -> if (depth == 0) commas++
                '"', '\'' -> {
                    val quote = text[i]
                    i--
                    while (i >= stop && text[i] != quote) i--
                }
            }
            i--
        }
        if (i < stop || text.getOrNull(i) != '(') return null

        var nameEnd = i
        while (nameEnd > 0 && (text[nameEnd - 1] == ' ' || text[nameEnd - 1] == '\t')) nameEnd--
        var nameStart = nameEnd
        while (nameStart > 0 && isIdentChar(text[nameStart - 1])) nameStart--
        if (nameStart == nameEnd) return null
        val name = text.substring(nameStart, nameEnd)

        var q = nameStart - 1
        while (q >= 0 && (text[q] == ' ' || text[q] == '\t')) q--
        val sigs: List<MemberInfo> = if (q >= 0 && (text[q] == '.' || text[q] == ':')) {
            val recv = resolveReceiverType(text, q, model, resolver)
            if (recv.isEmpty()) emptyList() else resolver.signaturesOf(recv, name)
        } else {
            val fn = model.functions.firstOrNull { it.fullName == name }
            if (fn != null) listOf(MemberInfo(name, "function", fn.doc, MemberKind.METHOD, fn.params, fn.ret, "this file", fromUserCode = true))
            else emptyList()
        }
        if (sigs.isEmpty()) return null
        val idx = overload.coerceIn(0, sigs.size - 1)
        val chosen = sigs[idx]
        return SignatureInfo(
            label = signatureLabel(chosen.name, chosen.params, chosen.ret),
            params = chosen.params,
            activeParam = commas,
            doc = chosen.doc,
            overloadIndex = idx,
            overloadCount = sigs.size,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun signatureLabel(name: String, params: List<ApiParam>, ret: String): String {
        val ps = params.joinToString(", ") { "${it.name}: ${TypeNames.display(it.type)}" }
        val r = if (ret.isNotEmpty() && ret != "any") " -> ${TypeNames.display(ret)}" else ""
        return "$name($ps)$r"
    }

    /** Prefix and subsequence matching; null when [candidate] does not match at all. */
    private fun match(prefix: String, candidate: String): Int? {
        if (prefix.isEmpty()) return 10
        if (candidate.startsWith(prefix)) return 1000 - candidate.length.coerceAtMost(40)
        if (candidate.startsWith(prefix, ignoreCase = true)) return 820 - candidate.length.coerceAtMost(40)
        // Subsequence, rewarding matches that land on word boundaries.
        var ci = 0
        var score = 380
        var lastHit = -1
        for (ch in prefix) {
            var hit = -1
            var k = ci
            while (k < candidate.length) {
                if (candidate[k].equals(ch, ignoreCase = true)) { hit = k; break }
                k++
            }
            if (hit < 0) return null
            if (hit > 0 && (candidate[hit - 1] == '_' || candidate[hit - 1].isLowerCase() && candidate[hit].isUpperCase())) score += 12
            if (lastHit >= 0) score -= (hit - lastHit - 1).coerceAtMost(8)
            lastHit = hit
            ci = hit + 1
        }
        return score - candidate.length.coerceAtMost(30)
    }

    private fun isIdentChar(c: Char) = c == '_' || c.isLetterOrDigit()

    private fun matchBackwards(src: String, toks: List<Tok>, closeIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = closeIndex
        while (i >= 0) {
            val t = toks[i]
            if (t.type == TokType.PUNCT && t.end - t.start == 1) {
                val c = src[t.start]
                if (c == close) depth++
                else if (c == open) { depth--; if (depth == 0) return i }
            }
            i--
        }
        return -1
    }

    private fun countArgsForward(src: String, toks: List<Tok>, openIndex: Int): Int {
        var depth = 0
        var commas = 0
        var any = false
        var i = openIndex
        while (i < toks.size) {
            val t = toks[i]
            if (t.type == TokType.PUNCT && t.end - t.start == 1) {
                when (src[t.start]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> { depth--; if (depth == 0) return if (any) commas + 1 else 0 }
                    ',' -> if (depth == 1) commas++
                }
            } else if (depth == 1) any = true
            i++
        }
        return if (any) commas + 1 else 0
    }

    private companion object {
        /** Chains longer than this are not worth walking backwards for. */
        const val MAX_CHAIN_LOOKBACK = 512
    }
}
