package com.advr.luaeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.data.Buffer
import com.advr.luaeditor.data.EditorSettings
import com.advr.luaeditor.lua.Severity
import kotlinx.coroutines.launch

/**
 * The editing surface: a text field plus everything drawn around it - line numbers that stay
 * aligned under soft wrap, the current line band, and diagnostic underlays.
 *
 * Only the rows inside the viewport are drawn, so scrolling a thousand line file stays smooth.
 */
@Composable
fun CodeEditor(
    buffer: Buffer,
    settings: EditorSettings,
    api: ApiIndex,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCodeColors.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    val transformation = remember(colors, api, buffer.context, settings.indentWidth) {
        LuaVisualTransformation(colors, api, buffer.context, settings.indentWidth)
    }
    val textStyle: TextStyle = remember(settings.fontSize, colors) {
        codeTextStyle(settings.fontSize, colors.plain)
    }
    val gutterSize = (settings.fontSize - 2).coerceAtLeast(8)
    val gutterStyle = remember(gutterSize, colors) {
        codeTextStyle(gutterSize, colors.gutter).copy(fontSize = gutterSize.sp)
    }
    val activeGutterStyle = remember(gutterSize, colors) {
        codeTextStyle(gutterSize, colors.gutterActive).copy(fontSize = gutterSize.sp)
    }

    val lineCount = remember(buffer.value.text) { buffer.value.text.count { it == '\n' } + 1 }
    val gutterWidth = remember(lineCount, gutterSize, settings.showLineNumbers) {
        if (!settings.showLineNumbers) 10.dp
        else ((lineCount.toString().length) * gutterSize * 0.62f + 20f).dp
    }

    // Line starts of the laid-out text, so the gutter can jump straight to the first visible row.
    val displayLineStarts = remember(layout) {
        val display = layout?.layoutInput?.text?.text ?: return@remember IntArray(1)
        val starts = ArrayList<Int>(display.length / 32 + 4)
        starts.add(0)
        for (i in display.indices) if (display[i] == '\n') starts.add(i + 1)
        starts.toIntArray()
    }

    LaunchedEffect(buffer.value.selection, layout, viewportHeight) {
        val lr = layout ?: return@LaunchedEffect
        if (viewportHeight <= 0) return@LaunchedEffect
        val displayOffset = transformation.toDisplay(buffer.value.selection.start)
            .coerceIn(0, lr.layoutInput.text.length)
        val rect = runCatching { lr.getCursorRect(displayOffset) }.getOrNull() ?: return@LaunchedEffect
        val pad = with(density) { 56.dp.toPx() }.toInt()
        val top = rect.top.toInt()
        val bottom = rect.bottom.toInt()
        when {
            top < vScroll.value -> scope.launch { vScroll.scrollTo((top - pad).coerceAtLeast(0)) }
            bottom > vScroll.value + viewportHeight ->
                scope.launch { vScroll.scrollTo((bottom - viewportHeight + pad).coerceAtLeast(0)) }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged { viewportHeight = it.height }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(vScroll)
                .then(if (settings.softWrap) Modifier else Modifier.horizontalScroll(hScroll))
        ) {
            Box(if (settings.softWrap) Modifier.fillMaxWidth() else Modifier) {
                BasicTextField(
                    value = buffer.value,
                    onValueChange = onValueChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.cursor),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .then(if (settings.softWrap) Modifier.fillMaxWidth() else Modifier)
                        .padding(PaddingValues(start = gutterWidth, end = 24.dp, top = 6.dp, bottom = 240.dp))
                        .drawBehind {
                            val lr = layout ?: return@drawBehind
                            val caret = transformation.toDisplay(buffer.value.selection.start)
                            drawCurrentLine(lr, caret, colors.currentLine, size)
                            drawDiagnostics(lr, buffer, transformation, colors)
                        },
                )

                if (settings.showLineNumbers) {
                    val topPadPx = with(density) { 6.dp.toPx() }
                    val gutterRightPx = with(density) { gutterWidth.toPx() } - with(density) { 10.dp.toPx() }
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawBehind {
                                val lr = layout ?: return@drawBehind
                                drawGutter(
                                    lr = lr,
                                    lineStarts = displayLineStarts,
                                    measurer = measurer,
                                    style = gutterStyle,
                                    activeStyle = activeGutterStyle,
                                    caretDisplayOffset = transformation.toDisplay(buffer.value.selection.start),
                                    gutterRightPx = gutterRightPx,
                                    topPaddingPx = topPadPx,
                                    scrollY = vScroll.value.toFloat(),
                                    viewportHeight = viewportHeight.toFloat(),
                                )
                            }
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawCurrentLine(
    lr: TextLayoutResult,
    caretOffset: Int,
    color: Color,
    fieldSize: Size,
) {
    val safe = caretOffset.coerceIn(0, lr.layoutInput.text.length)
    val line = runCatching { lr.getLineForOffset(safe) }.getOrNull() ?: return
    val top = lr.getLineTop(line)
    val bottom = lr.getLineBottom(line)
    drawRect(color = color, topLeft = Offset(-2000f, top), size = Size(fieldSize.width + 4000f, bottom - top))
}

private fun DrawScope.drawDiagnostics(
    lr: TextLayoutResult,
    buffer: Buffer,
    transformation: LuaVisualTransformation,
    colors: CodeColors,
) {
    val len = lr.layoutInput.text.length
    for (d in buffer.diagnostics) {
        val start = transformation.toDisplay(d.offset).coerceIn(0, len)
        val end = transformation.toDisplay(d.offset + d.length).coerceIn(start, len)
        if (end <= start) continue
        val color = when (d.severity) {
            Severity.ERROR -> colors.error
            Severity.WARNING -> colors.warning
            Severity.INFO -> colors.info
        }
        runCatching { lr.getPathForRange(start, end) }.getOrNull()?.let {
            drawPath(it, color = color.copy(alpha = 0.12f))
        }
        val line = runCatching { lr.getLineForOffset(start) }.getOrNull() ?: continue
        val y = lr.getLineBottom(line) - 2.5f
        val x0 = runCatching { lr.getHorizontalPosition(start, true) }.getOrNull() ?: continue
        val x1 = runCatching { lr.getHorizontalPosition(end, true) }.getOrNull() ?: continue
        if (x1 > x0) drawLine(color.copy(alpha = 0.85f), Offset(x0, y), Offset(x1, y), strokeWidth = 2.5f)
    }
}

/**
 * Numbers are drawn per *logical* line at the top of its first visual row, so a wrapped line keeps
 * one number instead of gaining a phantom one.
 */
private fun DrawScope.drawGutter(
    lr: TextLayoutResult,
    lineStarts: IntArray,
    measurer: TextMeasurer,
    style: TextStyle,
    activeStyle: TextStyle,
    caretDisplayOffset: Int,
    gutterRightPx: Float,
    topPaddingPx: Float,
    scrollY: Float,
    viewportHeight: Float,
) {
    if (lineStarts.isEmpty()) return
    val display = lr.layoutInput.text.text
    val caretLine = runCatching {
        lr.getLineForOffset(caretDisplayOffset.coerceIn(0, display.length))
    }.getOrNull() ?: -1

    val visibleTop = (scrollY - topPaddingPx - 64f).coerceAtLeast(0f)
    val visibleBottom = scrollY + (if (viewportHeight > 0f) viewportHeight else 2000f) + 64f

    val firstVisual = runCatching { lr.getLineForVerticalPosition(visibleTop) }.getOrNull() ?: 0
    val firstOffset = runCatching { lr.getLineStart(firstVisual) }.getOrNull() ?: 0
    var index = lineStarts.binarySearch(firstOffset).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }

    while (index < lineStarts.size) {
        val offset = lineStarts[index]
        val visualLine = runCatching { lr.getLineForOffset(offset) }.getOrNull() ?: break
        val top = lr.getLineTop(visualLine) + topPaddingPx
        if (top > visibleBottom) break
        if (top >= visibleTop - 64f) {
            val active = visualLine == caretLine
            val measured = measurer.measure(text = (index + 1).toString(), style = if (active) activeStyle else style)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(gutterRightPx - measured.size.width, top + 1f),
            )
        }
        index++
    }
}
