package com.advr.luaeditor.lua

import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.FileContext
import com.advr.luaeditor.api.ModKind

enum class Severity { ERROR, WARNING, INFO }

class Diagnostic(
    @JvmField val severity: Severity,
    @JvmField val message: String,
    @JvmField val offset: Int,
    @JvmField val length: Int,
    @JvmField val line: Int,
)

/**
 * The checks the ADVR Modding Tools extension performs, plus the ones that only make sense once you
 * know each file has its own globals.
 */
object LuaDiagnostics {

    fun run(text: String, model: FileModel, ctx: FileContext, api: ApiIndex): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val kind = ctx.kind

        // 1. Callbacks this folder requires.
        for (required in kind.requiredFunctions) {
            if (required !in model.definedFunctionNames) {
                out.add(
                    Diagnostic(
                        Severity.ERROR,
                        "${ctx.fileName} is missing the ${kind.folder}/ callback `$required`",
                        0, firstLineLength(text), 0,
                    )
                )
            }
        }

        // 2. Callbacks that belong to a different kind of file.
        for (fn in model.functions) {
            val table = fn.ownerChain.getOrNull(1) ?: continue
            if (fn.ownerChain.firstOrNull() != "ADVR") continue
            if (ctx.isForeignEventTable(table)) {
                val owner = ModKind.entries.firstOrNull { it.eventTable == table }
                out.add(
                    Diagnostic(
                        Severity.WARNING,
                        "`${fn.fullName}` belongs to ${owner?.folder ?: table}/ files, not ${kind.folder}/",
                        fn.declOffset, fn.fullName.length + 9, lineOf(text, fn.declOffset),
                    )
                )
            }
        }

        // 3. `CallFunctionIn("name")` with no matching function in this file. Because globals are
        //    per file, the target has to be defined right here.
        for (ref in model.callFunctionInRefs) {
            if (ref.name !in model.definedFunctionNames) {
                out.add(
                    Diagnostic(
                        Severity.WARNING,
                        "`${ref.name}` is not defined in this file - CallFunctionIn can only reach this file's functions",
                        ref.offset, ref.length, lineOf(text, ref.offset),
                    )
                )
            }
        }

        // 4. Per-file globals from another kind of file.
        for (idx in model.toks.indices) {
            val t = model.toks[idx]
            if (t.type != TokType.IDENT) continue
            val name = text.substring(t.start, t.end)
            if (!ctx.isForeignSelfGlobal(name)) continue
            val prev = model.toks.getOrNull(idx - 1)
            if (prev != null && prev.type == TokType.PUNCT && (text[prev.start] == '.' || text[prev.start] == ':')) continue
            if (model.globals.containsKey(name) || model.lookup(name, t.start) != null) continue
            val owner = ModKind.entries.first { it.selfGlobal == name }
            out.add(
                Diagnostic(
                    Severity.WARNING,
                    "`$name` only exists in ${owner.folder}/ files; this file is a ${kind.label.lowercase()} and gets `${kind.selfGlobal}`",
                    t.start, t.end - t.start, t.line,
                )
            )
        }

        // 5. Identifiers that are never bound in this file and are not part of the API.
        val reported = HashSet<String>()
        for (ref in model.freeIdentifiers) {
            if (!reported.add(ref.name)) continue
            out.add(
                Diagnostic(
                    Severity.WARNING,
                    "`${ref.name}` is not defined in this file - ADVR gives every Lua file its own globals",
                    ref.offset, ref.length, lineOf(text, ref.offset),
                )
            )
        }

        // 6. Fields assigned on the file's own global that the stubs do not declare.
        if (kind.selfGlobal.isNotEmpty()) {
            val selfClass = api.globalType(kind.selfGlobal)
            if (selfClass != null && api.classOf(selfClass) != null) {
                for (d in unknownSelfFields(text, model, kind.selfGlobal, selfClass, api)) out.add(d)
            }
        }

        // 7. Block balance.
        blockBalance(text, model)?.let { out.add(it) }

        out.sortBy { it.offset }
        return out
    }

    /**
     * A field assigned on the file's own global that the stubs do not declare *and* that looks like
     * a near miss for one that does. Modders legitimately hang their own state off `pickup`, so only
     * probable typos are worth interrupting for.
     */
    private fun unknownSelfFields(
        text: String,
        model: FileModel,
        self: String,
        selfClass: String,
        api: ApiIndex,
    ): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val toks = model.toks
        val known: List<String> by lazy {
            api.fieldsOf(selfClass).map { it.name } + api.methodsOf(selfClass).map { it.first().name }
        }
        for (i in toks.indices) {
            val t = toks[i]
            if (t.type != TokType.IDENT || !t.matches(text, self)) continue
            val dot = toks.getOrNull(i + 1) ?: continue
            if (dot.type != TokType.PUNCT || text[dot.start] != '.') continue
            val member = toks.getOrNull(i + 2) ?: continue
            if (member.type != TokType.IDENT) continue
            // `function pickup.helper()` is the modder adding their own helper: allowed.
            val prev = toks.getOrNull(i - 1)
            if (prev != null && prev.isKeyword(text, "function")) continue

            val name = text.substring(member.start, member.end)
            if (api.findField(selfClass, name) != null) continue
            if (api.findMethods(selfClass, name).isNotEmpty()) continue
            if (name.length < 4) continue

            val suggestion = known.firstOrNull { near(name, it) } ?: continue
            out.add(
                Diagnostic(
                    Severity.WARNING,
                    "`$self` has no field `$name` - did you mean `$suggestion`?",
                    member.start, member.end - member.start, member.line,
                )
            )
        }
        return out
    }

    /** Edit distance of at most two, which covers the swaps and slips people actually make. */
    private fun near(a: String, b: String): Boolean {
        if (a == b) return false
        if (kotlin.math.abs(a.length - b.length) > 2) return false
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var best = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1].equals(b[j - 1], ignoreCase = true)) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                if (cur[j] < best) best = cur[j]
            }
            if (best > 2) return false
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length] <= 2
    }

    /**
     * `function`, `if` and `do` each need one `end`; `repeat` needs one `until`. Counting keywords is
     * enough to catch the mistake that actually happens on a phone - a dropped `end`.
     */
    private fun blockBalance(text: String, model: FileModel): Diagnostic? {
        var open = 0
        var ends = 0
        var repeats = 0
        var untils = 0
        var lastOpenOffset = 0
        var lastOpenLine = 0
        for (t in model.toks) {
            if (t.type != TokType.KEYWORD) continue
            when (text.substring(t.start, t.end)) {
                "function", "if", "do" -> { open++; lastOpenOffset = t.start; lastOpenLine = t.line }
                "end" -> ends++
                "repeat" -> repeats++
                "until" -> untils++
            }
        }
        if (open > ends) {
            val missing = open - ends
            return Diagnostic(
                Severity.ERROR,
                "missing $missing `end`",
                lastOpenOffset, 3, lastOpenLine,
            )
        }
        if (ends > open) {
            return Diagnostic(Severity.ERROR, "${ends - open} unmatched `end`", text.length.coerceAtLeast(1) - 1, 1, lineOf(text, text.length - 1))
        }
        if (repeats > untils) {
            return Diagnostic(Severity.ERROR, "missing `until`", lastOpenOffset, 6, lastOpenLine)
        }
        return null
    }

    private fun lineOf(text: String, offset: Int): Int {
        var line = 0
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }

    private fun firstLineLength(text: String): Int {
        val nl = text.indexOf('\n')
        return if (nl < 0) text.length.coerceAtLeast(1) else nl.coerceAtLeast(1)
    }
}
