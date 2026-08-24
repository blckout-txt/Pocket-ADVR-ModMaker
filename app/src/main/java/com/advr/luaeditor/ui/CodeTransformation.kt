package com.advr.luaeditor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.FileContext

/**
 * Colours the buffer and renders tab characters as real indentation.
 *
 * Android lays tabs out on 20px stops, which at code font sizes collapses nested Lua into a wall of
 * text. Expanding them here keeps the file on disk byte for byte unchanged - only the display and
 * the caret mapping shift.
 */
class LuaVisualTransformation(
    private val colors: CodeColors,
    private val api: ApiIndex,
    private val ctx: FileContext,
    private val tabWidth: Int,
) : VisualTransformation {

    private var cachedSource: String? = null
    private var cached: TransformedText? = null
    private var cachedMap: IntArray? = null

    /**
     * Source offset to laid-out offset, using the mapping built by the last [filter] call. The
     * gutter and the diagnostic underlays need this because they draw in laid-out coordinates.
     */
    fun toDisplay(offset: Int): Int {
        val map = cachedMap ?: return offset
        return map[offset.coerceIn(0, map.size - 1)]
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        cached?.let { if (cachedSource === src || cachedSource == src) return it }

        val spans = Highlighter.spans(src, colors, api, ctx)
        val result = if (src.indexOf('\t') < 0) {
            cachedMap = null
            val b = AnnotatedString.Builder(src)
            for (s in spans) b.addStyle(s.style, s.start, s.end)
            TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
        } else {
            val expanded = expandTabs(src, tabWidth)
            cachedMap = expanded.map
            val b = AnnotatedString.Builder(expanded.text)
            val map = expanded.map
            for (s in spans) {
                val st = map[s.start.coerceIn(0, src.length)]
                val en = map[s.end.coerceIn(0, src.length)]
                if (en > st) b.addStyle(s.style, st, en)
            }
            TransformedText(b.toAnnotatedString(), TabOffsetMapping(map, expanded.text.length))
        }
        cachedSource = src
        cached = result
        return result
    }

    private class Expanded(val text: String, val map: IntArray)

    private class TabOffsetMapping(private val map: IntArray, private val transformedLength: Int) : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int =
            map[offset.coerceIn(0, map.size - 1)]

        override fun transformedToOriginal(offset: Int): Int {
            val target = offset.coerceIn(0, transformedLength)
            var lo = 0
            var hi = map.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (map[mid] <= target) lo = mid else hi = mid - 1
            }
            return lo
        }
    }

    private companion object {
        /** Tabs advance to the next multiple of [width], the way every code editor does it. */
        fun expandTabs(src: String, width: Int): Expanded {
            val sb = StringBuilder(src.length + 32)
            val map = IntArray(src.length + 1)
            var col = 0
            for (i in src.indices) {
                map[i] = sb.length
                when (val c = src[i]) {
                    '\t' -> {
                        val w = width - (col % width)
                        repeat(w) { sb.append(' ') }
                        col += w
                    }
                    '\n' -> { sb.append(c); col = 0 }
                    else -> { sb.append(c); col++ }
                }
            }
            map[src.length] = sb.length
            return Expanded(sb.toString(), map)
        }
    }
}
