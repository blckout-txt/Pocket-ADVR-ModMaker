package com.advr.luaeditor

import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.FileContext
import com.advr.luaeditor.lua.CompletionEngine
import com.advr.luaeditor.lua.CompletionResult
import com.advr.luaeditor.lua.FileModel
import com.advr.luaeditor.lua.LuaAnalyzer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** Loads the generated index straight off disk so the tests exercise the real ADVR API surface. */
object TestApi {
    val index: ApiIndex by lazy {
        val candidates = listOf(
            File("src/main/assets/${ApiIndex.ASSET}"),
            File("app/src/main/assets/${ApiIndex.ASSET}"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("api asset not found from ${File(".").absolutePath}")
        ApiIndex.load(BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)))
    }

    fun context(path: String) = FileContext.of(path, path.substringAfterLast('/'))

    fun model(src: String, path: String): FileModel =
        LuaAnalyzer.analyze(src, index, context(path))

    /** Completes at the `|` marker in [srcWithCaret]. */
    fun completeAt(srcWithCaret: String, path: String): CompletionResult {
        val caret = srcWithCaret.indexOf('|')
        require(caret >= 0) { "no caret marker" }
        val src = srcWithCaret.removeRange(caret, caret + 1)
        val ctx = context(path)
        val model = LuaAnalyzer.analyze(src, index, ctx)
        return CompletionEngine(index).complete(src, caret, model, ctx)
    }

    fun labels(result: CompletionResult) = result.items.map { it.label }
}
