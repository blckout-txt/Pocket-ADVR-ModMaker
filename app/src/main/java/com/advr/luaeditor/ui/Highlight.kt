package com.advr.luaeditor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.FileContext
import com.advr.luaeditor.lua.LuaLexer
import com.advr.luaeditor.lua.Tok
import com.advr.luaeditor.lua.TokType

/**
 * Token colouring with a little semantic help: ADVR globals, the file's own injected global and
 * member names after a dot all get their own colour, which is what makes a mod file readable at
 * phone font sizes.
 */
object Highlighter {

    /** Above this the phone spends more time colouring than the user spends reading. */
    const val MAX_HIGHLIGHT_CHARS = 400_000

    class Span(@JvmField val style: SpanStyle, @JvmField val start: Int, @JvmField val end: Int)

    fun highlight(
        text: String,
        colors: CodeColors,
        api: ApiIndex,
        ctx: FileContext,
        toks: List<Tok>? = null,
    ): AnnotatedString {
        val b = AnnotatedString.Builder(text)
        for (s in spans(text, colors, api, ctx, toks)) b.addStyle(s.style, s.start, s.end)
        return b.toAnnotatedString()
    }

    /** Styles in *source* coordinates, so a caller that expands tabs can remap them. */
    fun spans(
        text: String,
        colors: CodeColors,
        api: ApiIndex,
        ctx: FileContext,
        toks: List<Tok>? = null,
    ): List<Span> {
        if (text.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        val tokens = toks ?: LuaLexer.lex(text)
        val out = ArrayList<Span>(tokens.size)
        val b = SpanSink(out)

        val keywordStyle = SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium)
        val stringStyle = SpanStyle(color = colors.string)
        val numberStyle = SpanStyle(color = colors.number)
        val commentStyle = SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        val docStyle = SpanStyle(color = colors.doc, fontStyle = FontStyle.Italic)
        val annotationStyle = SpanStyle(color = colors.annotation, fontStyle = FontStyle.Italic)
        val operatorStyle = SpanStyle(color = colors.operator)
        val apiStyle = SpanStyle(color = colors.apiGlobal)
        val selfStyle = SpanStyle(color = colors.selfGlobal, fontWeight = FontWeight.Medium)
        val memberStyle = SpanStyle(color = colors.member)
        val callStyle = SpanStyle(color = colors.call)

        for (i in tokens.indices) {
            val t = tokens[i]
            when (t.type) {
                TokType.KEYWORD -> b.addStyle(keywordStyle, t.start, t.end)
                TokType.STRING -> b.addStyle(stringStyle, t.start, t.end)
                TokType.NUMBER -> b.addStyle(numberStyle, t.start, t.end)
                TokType.COMMENT -> b.addStyle(commentStyle, t.start, t.end)
                TokType.DOC -> {
                    b.addStyle(docStyle, t.start, t.end)
                    // Colour the `@tag` inside a --- comment so annotations stand out.
                    val at = text.indexOf('@', t.start)
                    if (at in t.start until t.end) {
                        var e = at + 1
                        while (e < t.end && (text[e].isLetter())) e++
                        b.addStyle(annotationStyle, at, e)
                    }
                }
                TokType.OPERATOR -> b.addStyle(operatorStyle, t.start, t.end)
                TokType.PUNCT -> b.addStyle(operatorStyle, t.start, t.end)
                TokType.IDENT -> {
                    val prev = tokens.getOrNull(i - 1)
                    val next = tokens.getOrNull(i + 1)
                    val afterDot = prev != null && prev.type == TokType.PUNCT &&
                        (text[prev.start] == '.' || text[prev.start] == ':')
                    val isCall = next != null && next.type == TokType.PUNCT && text[next.start] == '('
                    val name = text.substring(t.start, t.end)
                    val style = when {
                        afterDot && isCall -> callStyle
                        afterDot -> memberStyle
                        name == ctx.kind.selfGlobal && name.isNotEmpty() -> selfStyle
                        api.globalType(name) != null -> apiStyle
                        name in LuaLexer.STDLIB -> apiStyle
                        isCall -> callStyle
                        else -> null
                    }
                    if (style != null) b.addStyle(style, t.start, t.end)
                }
                TokType.UNKNOWN -> Unit
            }
        }
        return out
    }

    /** Tiny sink so the token walk above reads the same as an AnnotatedString builder. */
    private class SpanSink(private val out: MutableList<Span>) {
        fun addStyle(style: SpanStyle, start: Int, end: Int) {
            out.add(Span(style, start, end))
        }
    }
}
