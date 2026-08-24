package com.advr.luaeditor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.advr.luaeditor.lua.EditAssist
import org.junit.Assert.assertEquals
import org.junit.Test

class EditAssistTest {

    private val opts = EditAssist.Options(useTabs = true)

    /** Simulates typing [ch] at the caret and running it through the assists. */
    private fun type(before: String, ch: Char): TextFieldValue {
        val caret = before.indexOf('|')
        val text = before.removeRange(caret, caret + 1)
        val old = TextFieldValue(text, TextRange(caret))
        val raw = TextFieldValue(
            text.substring(0, caret) + ch + text.substring(caret),
            TextRange(caret + 1),
        )
        return EditAssist.process(old, raw, opts)
    }

    private fun render(v: TextFieldValue): String =
        v.text.substring(0, v.selection.start) + "|" + v.text.substring(v.selection.start)

    @Test
    fun `enter keeps the current indentation`() {
        val result = type("\tpickup.name = \"x\"|", '\n')
        assertEquals("\tpickup.name = \"x\"\n\t|", render(result))
    }

    @Test
    fun `enter after then opens a new level`() {
        val result = type("if a then|", '\n')
        assertEquals("if a then\n\t|", render(result))
    }

    @Test
    fun `enter after a function header opens a new level`() {
        val result = type("function ADVR.onLoad()|", '\n')
        assertEquals("function ADVR.onLoad()\n\t|", render(result))
    }

    @Test
    fun `enter between braces opens the block out`() {
        val result = type("local t = {|}", '\n')
        assertEquals("local t = {\n\t|\n}", render(result))
    }

    @Test
    fun `typing end pulls the line back a level`() {
        val result = type("function f()\n\t\ten|", 'd')
        assertEquals("function f()\n\tend|", render(result))
    }

    @Test
    fun `brackets close themselves`() {
        assertEquals("logging.Log(|)", render(type("logging.Log|", '(')))
        assertEquals("local t = {|}", render(type("local t = |", '{')))
        assertEquals("x = \"|\"", render(type("x = |", '"')))
    }

    @Test
    fun `typing a closer steps over the one already there`() {
        assertEquals("logging.Log()|", render(type("logging.Log(|)", ')')))
    }

    @Test
    fun `quotes are not doubled mid word`() {
        assertEquals("dont'|", render(type("dont|", '\'')))
    }

    @Test
    fun `deleting an opener removes its partner`() {
        val old = TextFieldValue("f()", TextRange(2))
        val raw = TextFieldValue("f)", TextRange(1))
        val result = EditAssist.process(old, raw, opts)
        assertEquals("f|", render(result))
    }

    @Test
    fun `comment toggling is symmetric`() {
        val v = TextFieldValue("\tpickup.name = \"x\"\n\tpickup.tier = 1\n", TextRange(0, 34))
        val commented = EditAssist.toggleComment(v)
        assertEquals("\t--pickup.name = \"x\"\n\t--pickup.tier = 1\n", commented.text)
        val back = EditAssist.toggleComment(commented)
        assertEquals(v.text, back.text)
    }

    @Test
    fun `indent and outdent round trip`() {
        val v = TextFieldValue("a = 1\nb = 2", TextRange(0, 11))
        val inned = EditAssist.indentSelection(v, opts, out = false)
        assertEquals("\ta = 1\n\tb = 2", inned.text)
        val outed = EditAssist.indentSelection(inned, opts, out = true)
        assertEquals("a = 1\nb = 2", outed.text)
    }

    @Test
    fun `line helpers agree with the text`() {
        val text = "one\ntwo\nthree"
        assertEquals(1, EditAssist.lineNumberOf(text, 5))
        assertEquals(4, EditAssist.lineStartOf(text, 5))
        assertEquals(7, EditAssist.lineEndOf(text, 5))
        assertEquals(8, EditAssist.offsetOfLine(text, 2))
    }
}
