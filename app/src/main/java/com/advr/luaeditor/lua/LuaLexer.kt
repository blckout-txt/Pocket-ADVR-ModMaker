package com.advr.luaeditor.lua

enum class TokType {
    KEYWORD,
    IDENT,
    NUMBER,
    STRING,
    COMMENT,
    /** A `---` documentation comment; LuaLS annotations live in these. */
    DOC,
    OPERATOR,
    PUNCT,
    UNKNOWN,
}

/** Half open [start, end) range over the source text. */
class Tok(
    @JvmField val type: TokType,
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val line: Int,
) {
    fun text(src: String): String = src.substring(start, end)
    fun isKeyword(src: String, kw: String) = type == TokType.KEYWORD && matches(src, kw)
    fun isPunct(src: String, p: String) = (type == TokType.PUNCT || type == TokType.OPERATOR) && matches(src, p)

    fun matches(src: String, s: String): Boolean {
        if (end - start != s.length) return false
        for (i in s.indices) if (src[start + i] != s[i]) return false
        return true
    }

    override fun toString() = "$type[$start,$end)"
}

/**
 * A single pass Lua 5.x scanner. Whitespace is dropped; every other run of characters becomes one
 * token so both the highlighter and the analyzer work off the same list.
 */
object LuaLexer {

    val KEYWORDS = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if",
        "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
    )

    /** Lua standard library roots that ADVR leaves reachable, used for completion and linting. */
    val STDLIB = setOf(
        "assert", "collectgarbage", "error", "getmetatable", "ipairs", "next", "pairs", "pcall",
        "print", "rawequal", "rawget", "rawlen", "rawset", "require", "select", "setmetatable",
        "tonumber", "tostring", "type", "unpack", "xpcall", "string", "table", "math", "os", "io",
        "coroutine", "_G", "_VERSION",
    )

    fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>(src.length / 4 + 16)
        var i = 0
        var line = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\n') { line++; i++; continue }
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue }

            // -- comment, ---doc comment, --[[ long comment ]]
            if (c == '-' && i + 1 < n && src[i + 1] == '-') {
                val startLine = line
                var j = i + 2
                val longLevel = longBracketLevel(src, j)
                if (longLevel >= 0) {
                    val close = findLongClose(src, j + longLevel + 2, longLevel)
                    val end = if (close < 0) n else close
                    line += countLines(src, i, end)
                    out.add(Tok(TokType.COMMENT, i, end, startLine))
                    i = end
                    continue
                }
                val isDoc = j < n && src[j] == '-'
                while (j < n && src[j] != '\n') j++
                out.add(Tok(if (isDoc) TokType.DOC else TokType.COMMENT, i, j, startLine))
                i = j
                continue
            }

            // long string [[ ]] / [=[ ]=]
            if (c == '[') {
                val lvl = longBracketLevel(src, i)
                if (lvl >= 0) {
                    val startLine = line
                    val close = findLongClose(src, i + lvl + 2, lvl)
                    val end = if (close < 0) n else close
                    line += countLines(src, i, end)
                    out.add(Tok(TokType.STRING, i, end, startLine))
                    i = end
                    continue
                }
            }

            if (c == '"' || c == '\'') {
                val startLine = line
                var j = i + 1
                while (j < n) {
                    val d = src[j]
                    if (d == '\\') { j += 2; continue }
                    if (d == c) { j++; break }
                    if (d == '\n') break          // unterminated: stop at the newline
                    j++
                }
                out.add(Tok(TokType.STRING, i, j.coerceAtMost(n), startLine))
                i = j.coerceAtMost(n)
                continue
            }

            if (c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit())) {
                var j = i
                if (c == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                    j = i + 2
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '.')) j++
                } else {
                    var seenExp = false
                    while (j < n) {
                        val d = src[j]
                        if (d.isDigit() || d == '.') { j++ }
                        else if ((d == 'e' || d == 'E') && !seenExp) { seenExp = true; j++
                            if (j < n && (src[j] == '+' || src[j] == '-')) j++ }
                        else break
                    }
                }
                out.add(Tok(TokType.NUMBER, i, j, line))
                i = j
                continue
            }

            if (c == '_' || c.isLetter()) {
                var j = i
                while (j < n && (src[j] == '_' || src[j].isLetterOrDigit())) j++
                val word = src.substring(i, j)
                out.add(Tok(if (word in KEYWORDS) TokType.KEYWORD else TokType.IDENT, i, j, line))
                i = j
                continue
            }

            // multi-character operators, longest match first
            val two = if (i + 1 < n) src.substring(i, i + 2) else ""
            val three = if (i + 2 < n) src.substring(i, i + 3) else ""
            if (three == "...") { out.add(Tok(TokType.PUNCT, i, i + 3, line)); i += 3; continue }
            if (two == "==" || two == "~=" || two == "<=" || two == ">=" || two == ".." ||
                two == "::" || two == "//" || two == "<<" || two == ">>"
            ) {
                out.add(Tok(TokType.OPERATOR, i, i + 2, line)); i += 2; continue
            }

            val type = when (c) {
                '+', '-', '*', '/', '%', '^', '#', '<', '>', '=', '&', '~', '|' -> TokType.OPERATOR
                '(', ')', '{', '}', '[', ']', ';', ':', ',', '.' -> TokType.PUNCT
                else -> TokType.UNKNOWN
            }
            out.add(Tok(type, i, i + 1, line))
            i++
        }
        return out
    }

    /** `[[` is level 0, `[=[` level 1, ... ; -1 when [at] does not open a long bracket. */
    private fun longBracketLevel(src: String, at: Int): Int {
        if (at >= src.length || src[at] != '[') return -1
        var j = at + 1
        var level = 0
        while (j < src.length && src[j] == '=') { level++; j++ }
        return if (j < src.length && src[j] == '[') level else -1
    }

    /** Index just past the matching `]]` / `]=]`, or -1 when the bracket is never closed. */
    private fun findLongClose(src: String, from: Int, level: Int): Int {
        var j = from
        while (j < src.length) {
            if (src[j] == ']') {
                var k = j + 1
                var eq = 0
                while (k < src.length && src[k] == '=') { eq++; k++ }
                if (eq == level && k < src.length && src[k] == ']') return k + 1
            }
            j++
        }
        return -1
    }

    private fun countLines(src: String, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to.coerceAtMost(src.length)) if (src[i] == '\n') c++
        return c
    }
}
