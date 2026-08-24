package com.advr.luaeditor.lua

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The typing help that makes Lua bearable on a touch keyboard: keep the indent on Enter, open a
 * block when the line asks for one, pull `end` back a level, and close brackets and quotes.
 *
 * Everything here is a pure function over the text field value so the behaviour is predictable and
 * easy to reason about from the call site.
 */
object EditAssist {

    private val OPENERS = charArrayOf('(', '[', '{')
    private val CLOSERS = charArrayOf(')', ']', '}')
    private val QUOTES = charArrayOf('"', '\'')

    private val BLOCK_ENDERS = setOf("end", "else", "until", "elseif", "}", ")")

    class Options(
        val autoIndent: Boolean = true,
        val autoClose: Boolean = true,
        val useTabs: Boolean = true,
        val indentWidth: Int = 4,
    ) {
        val unit: String get() = if (useTabs) "\t" else " ".repeat(indentWidth)
    }

    /**
     * Applies the assists to a raw edit. Returns the value the editor should actually adopt, which
     * may differ from [new] (an inserted `(` also gets its `)`, for instance).
     */
    fun process(old: TextFieldValue, new: TextFieldValue, opt: Options): TextFieldValue {
        val inserted = insertedChar(old, new)
        if (inserted != null) {
            val caret = new.selection.start
            if (opt.autoIndent && inserted == '\n') return handleNewline(new, caret, opt)
            if (opt.autoClose) {
                handleClose(old, new, inserted, caret)?.let { return it }
            }
            if (opt.autoIndent && inserted in BLOCK_ENDER_CHARS) {
                dedentIfBlockEnd(new, caret, opt)?.let { return it }
            }
            return new
        }
        if (opt.autoClose) {
            deletedPair(old, new)?.let { return it }
        }
        return new
    }

    // Last character of each word in BLOCK_ENDERS: end, else, elseif, until, }
    private val BLOCK_ENDER_CHARS = charArrayOf('d', 'e', 'f', 'l', '}')

    // ------------------------------------------------------------------ newline

    private fun handleNewline(v: TextFieldValue, caret: Int, opt: Options): TextFieldValue {
        val text = v.text
        val lineStart = text.lastIndexOf('\n', (caret - 2).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val prevLine = text.substring(lineStart, (caret - 1).coerceAtLeast(lineStart))
        var indent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val trimmed = prevLine.trim()

        if (opensBlock(trimmed)) indent += opt.unit

        val nextChar = text.getOrNull(caret)
        // Typing Enter between `{` and `}` (or before an `end`) opens the block out properly.
        if (indent.length > prevLine.takeWhile { it == ' ' || it == '\t' }.length &&
            (nextChar == '}' || text.startsWith("end", caret) || text.startsWith("\tend", caret))
        ) {
            val outer = prevLine.takeWhile { it == ' ' || it == '\t' }
            val body = indent
            val newText = text.substring(0, caret) + body + "\n" + outer + text.substring(caret)
            return TextFieldValue(newText, TextRange(caret + body.length))
        }

        if (indent.isEmpty()) return v
        val newText = text.substring(0, caret) + indent + text.substring(caret)
        return TextFieldValue(newText, TextRange(caret + indent.length))
    }

    private fun opensBlock(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith("{") || trimmed.endsWith("(")) return true
        val lastWord = trimmed.substringAfterLast(' ').substringAfterLast('\t')
        if (lastWord == "then" || lastWord == "do" || lastWord == "else" || lastWord == "repeat") return true
        if (trimmed == "else" || trimmed == "repeat" || trimmed == "do" || trimmed == "then") return true
        // `function name(a, b)` and `local function name()`
        if (trimmed.endsWith(")") && (trimmed.startsWith("function ") || trimmed.startsWith("local function ") ||
                trimmed.contains("= function") || trimmed.startsWith("function("))
        ) return true
        return false
    }

    // ------------------------------------------------------------------ dedent

    private fun dedentIfBlockEnd(v: TextFieldValue, caret: Int, opt: Options): TextFieldValue? {
        val text = v.text
        val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val line = text.substring(lineStart, caret)
        val trimmed = line.trim()
        if (trimmed !in BLOCK_ENDERS) return null
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        if (indent.isEmpty()) return null
        val remove = when {
            indent.endsWith("\t") -> 1
            indent.length >= opt.indentWidth && indent.endsWith(" ".repeat(opt.indentWidth)) -> opt.indentWidth
            else -> return null
        }
        val newText = text.removeRange(lineStart + indent.length - remove, lineStart + indent.length)
        return TextFieldValue(newText, TextRange(caret - remove))
    }

    // ------------------------------------------------------------------ brackets

    private fun handleClose(old: TextFieldValue, new: TextFieldValue, ch: Char, caret: Int): TextFieldValue? {
        val text = new.text
        // Typing the closer that is already sitting there just steps over it.
        if ((ch in CLOSERS || ch in QUOTES) && old.text.getOrNull(old.selection.start) == ch) {
            return TextFieldValue(old.text, TextRange(old.selection.start + 1))
        }
        val closer = when (ch) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '"' -> '"'
            '\'' -> '\''
            else -> return null
        }
        if (ch in QUOTES) {
            // Do not double up quotes in the middle of a word, or when closing an open string.
            val before = text.getOrNull(caret - 2)
            if (before != null && (before.isLetterOrDigit() || before == ch || before == '\\')) return null
        }
        val after = text.getOrNull(caret)
        if (after != null && (after.isLetterOrDigit() || after == '_')) return null
        return TextFieldValue(
            text.substring(0, caret) + closer + text.substring(caret),
            TextRange(caret),
        )
    }

    private fun deletedPair(old: TextFieldValue, new: TextFieldValue): TextFieldValue? {
        if (old.text.length != new.text.length + 1) return null
        val caret = new.selection.start
        val deleted = old.text.getOrNull(caret) ?: return null
        val idx = OPENERS.indexOf(deleted)
        val closer = when {
            idx >= 0 -> CLOSERS[idx]
            deleted in QUOTES -> deleted
            else -> return null
        }
        if (new.text.getOrNull(caret) != closer) return null
        return TextFieldValue(new.text.removeRange(caret, caret + 1), TextRange(caret))
    }

    private fun insertedChar(old: TextFieldValue, new: TextFieldValue): Char? {
        if (new.text.length != old.text.length + 1) return null
        if (!new.selection.collapsed) return null
        val caret = new.selection.start
        if (caret <= 0 || caret > new.text.length) return null
        return new.text[caret - 1]
    }

    // ------------------------------------------------------------------ commands

    /** Toggles `--` on every line the selection touches. */
    fun toggleComment(v: TextFieldValue): TextFieldValue {
        val text = v.text
        val start = lineStartOf(text, v.selection.min)
        val end = lineEndOf(text, v.selection.max)
        val block = text.substring(start, end)
        val lines = block.split("\n")
        val allCommented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith("--") }
        val updated = lines.joinToString("\n") { line ->
            if (line.isBlank()) line
            else if (allCommented) {
                val i = line.indexOf("--")
                line.removeRange(i, i + 2)
            } else {
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                indent + "--" + line.substring(indent.length)
            }
        }
        val delta = updated.length - block.length
        return TextFieldValue(
            text.substring(0, start) + updated + text.substring(end),
            TextRange((v.selection.min).coerceAtMost(text.length + delta), (v.selection.max + delta).coerceAtLeast(0)),
        )
    }

    fun indentSelection(v: TextFieldValue, opt: Options, out: Boolean): TextFieldValue {
        val text = v.text
        val start = lineStartOf(text, v.selection.min)
        val end = lineEndOf(text, v.selection.max)
        val lines = text.substring(start, end).split("\n")
        val unit = opt.unit
        val updated = lines.joinToString("\n") { line ->
            if (out) {
                when {
                    line.startsWith("\t") -> line.substring(1)
                    line.startsWith(" ".repeat(opt.indentWidth)) -> line.substring(opt.indentWidth)
                    else -> line.trimStart(' ', '\t').let { line.substring(line.length - it.length) }
                }
            } else unit + line
        }
        val delta = updated.length - (end - start)
        return TextFieldValue(
            text.substring(0, start) + updated + text.substring(end),
            TextRange((v.selection.min + if (out) 0 else unit.length).coerceIn(0, text.length + delta),
                (v.selection.max + delta).coerceIn(0, text.length + delta)),
        )
    }

    /** Inserts [snippet] at the caret, honouring the current line's indentation for extra lines. */
    fun insertSnippet(v: TextFieldValue, snippet: String, opt: Options): TextFieldValue {
        val caretMarker = snippet.indexOf("$0")
        val body = snippet.replace("$0", "")
        val text = v.text
        val start = v.selection.min
        val end = v.selection.max
        val indent = currentIndent(text, start)
        val laidOut = if (body.contains('\n')) {
            body.split("\n").mapIndexed { i, l ->
                if (i == 0) l else indent + l.replace("\t", opt.unit)
            }.joinToString("\n")
        } else body
        val extraBefore = if (caretMarker < 0) laidOut.length else
            laidOut.length - (body.length - caretMarker) + indentGrowth(body, caretMarker, indent, opt)
        return TextFieldValue(
            text.substring(0, start) + laidOut + text.substring(end),
            TextRange(start + extraBefore.coerceIn(0, laidOut.length)),
        )
    }

    private fun indentGrowth(body: String, marker: Int, indent: String, opt: Options): Int {
        var growth = 0
        for (i in 0 until marker) {
            if (body[i] == '\n') growth += indent.length
            if (body[i] == '\t') growth += opt.unit.length - 1
        }
        return growth
    }

    fun currentIndent(text: String, offset: Int): String {
        val ls = lineStartOf(text, offset)
        return text.substring(ls, offset.coerceAtLeast(ls)).takeWhile { it == ' ' || it == '\t' }
    }

    fun lineStartOf(text: String, offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        val i = text.lastIndexOf('\n', (o - 1).coerceAtLeast(0))
        return if (i < 0 || o == 0) 0 else i + 1
    }

    fun lineEndOf(text: String, offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        val i = text.indexOf('\n', o)
        return if (i < 0) text.length else i
    }

    fun lineNumberOf(text: String, offset: Int): Int {
        var line = 0
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }

    fun offsetOfLine(text: String, line: Int): Int {
        if (line <= 0) return 0
        var count = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                count++
                if (count == line) return i + 1
            }
        }
        return text.length
    }
}
