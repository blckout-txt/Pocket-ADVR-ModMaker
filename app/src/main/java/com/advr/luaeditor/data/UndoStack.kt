package com.advr.luaeditor.data

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.abs

/**
 * Undo history for the editor. Consecutive typing inside one word collapses into a single step, so
 * undo moves in units a person recognises instead of one character at a time.
 *
 * The stacks hold *past* and *future* states; the current value always lives in the buffer.
 */
class UndoStack {

    private class Snapshot(val text: String, val selection: TextRange)

    private val past = ArrayDeque<Snapshot>()
    private val future = ArrayDeque<Snapshot>()
    private var lastPushAt = 0L

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Call with the value before and after an edit. */
    fun record(old: TextFieldValue, new: TextFieldValue) {
        if (old.text == new.text) return
        future.clear()
        val now = System.currentTimeMillis()
        if (past.isNotEmpty() && now - lastPushAt < COALESCE_MS && isSimpleTyping(old.text, new.text)) {
            lastPushAt = now
            return
        }
        past.addLast(Snapshot(old.text, old.selection))
        while (past.size > MAX_DEPTH) past.removeFirst()
        lastPushAt = now
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(Snapshot(current.text, current.selection))
        lastPushAt = 0L
        return TextFieldValue(prev.text, clamp(prev.selection, prev.text.length))
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(Snapshot(current.text, current.selection))
        lastPushAt = 0L
        return TextFieldValue(next.text, clamp(next.selection, next.text.length))
    }

    /** Ends the current coalescing run, e.g. after a save or a completion insert. */
    fun breakRun() {
        lastPushAt = 0L
    }

    private fun clamp(range: TextRange, length: Int) =
        TextRange(range.start.coerceIn(0, length), range.end.coerceIn(0, length))

    /** True when [next] differs from [prev] by one word character at a single point. */
    private fun isSimpleTyping(prev: String, next: String): Boolean {
        val delta = next.length - prev.length
        if (delta == 0 || abs(delta) != 1) return false
        val shorter = if (delta > 0) prev else next
        val longer = if (delta > 0) next else prev
        var i = 0
        while (i < shorter.length && shorter[i] == longer[i]) i++
        if (i >= longer.length) return false
        val ch = longer[i]
        return ch.isLetterOrDigit() || ch == '_'
    }

    private companion object {
        const val MAX_DEPTH = 300
        const val COALESCE_MS = 900L
    }
}
