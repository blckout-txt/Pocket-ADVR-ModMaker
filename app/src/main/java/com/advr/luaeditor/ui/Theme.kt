package com.advr.luaeditor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Colours for one syntax token class, kept apart from Material so both themes can tune them. */
@Immutable
class CodeColors(
    val background: Color,
    val gutter: Color,
    val gutterActive: Color,
    val currentLine: Color,
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val doc: Color,
    val annotation: Color,
    val operator: Color,
    val apiGlobal: Color,
    val selfGlobal: Color,
    val member: Color,
    val call: Color,
    val local: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val selection: Color,
    val cursor: Color,
)

private val DarkCode = CodeColors(
    background = Color(0xFF0F1116),
    gutter = Color(0xFF3C4353),
    gutterActive = Color(0xFF9DA8BF),
    currentLine = Color(0x14FFFFFF),
    plain = Color(0xFFD7DEEA),
    keyword = Color(0xFFC792EA),
    string = Color(0xFF9BD17F),
    number = Color(0xFFF2C879),
    comment = Color(0xFF5C6579),
    doc = Color(0xFF6F7C93),
    annotation = Color(0xFF7FB6E8),
    operator = Color(0xFF89A0C0),
    apiGlobal = Color(0xFF7EE0C0),
    selfGlobal = Color(0xFFFFB86C),
    member = Color(0xFFA8D6F5),
    call = Color(0xFFE6D08A),
    local = Color(0xFFD7DEEA),
    error = Color(0xFFFF6B6B),
    warning = Color(0xFFE3B341),
    info = Color(0xFF6FA8DC),
    selection = Color(0x554A6FA5),
    cursor = Color(0xFF7EE0C0),
)

private val LightCode = CodeColors(
    background = Color(0xFFFBFCFE),
    gutter = Color(0xFFAAB3C2),
    gutterActive = Color(0xFF4A5468),
    currentLine = Color(0x0A000000),
    plain = Color(0xFF1F2430),
    keyword = Color(0xFF8B37B8),
    string = Color(0xFF2E7D32),
    number = Color(0xFFB35B00),
    comment = Color(0xFF8B94A6),
    doc = Color(0xFF6B7688),
    annotation = Color(0xFF1565C0),
    operator = Color(0xFF4A5468),
    apiGlobal = Color(0xFF00796B),
    selfGlobal = Color(0xFFB55A00),
    member = Color(0xFF1B6FA8),
    call = Color(0xFF8A6D00),
    local = Color(0xFF1F2430),
    error = Color(0xFFC62828),
    warning = Color(0xFF9A6700),
    info = Color(0xFF1565C0),
    selection = Color(0x333F7FD0),
    cursor = Color(0xFF00796B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7EE0C0),
    onPrimary = Color(0xFF07231C),
    primaryContainer = Color(0xFF1D4A40),
    onPrimaryContainer = Color(0xFFB6F3E1),
    secondary = Color(0xFFFFB86C),
    onSecondary = Color(0xFF3A2300),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFD7DEEA),
    surface = Color(0xFF151922),
    onSurface = Color(0xFFD7DEEA),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFF9DA8BF),
    outline = Color(0xFF39414F),
    outlineVariant = Color(0xFF262D3A),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7EFE3),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFFB55A00),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF1F2430),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2430),
    surfaceVariant = Color(0xFFEDF0F5),
    onSurfaceVariant = Color(0xFF4A5468),
    outline = Color(0xFFC3CAD6),
    outlineVariant = Color(0xFFE3E7EE),
    error = Color(0xFFC62828),
)

val LocalCodeColors: ProvidableCompositionLocal<CodeColors> = staticCompositionLocalOf { DarkCode }

private val AppTypography = Typography()

@Composable
fun AdvrLuaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCodeColors provides if (dark) DarkCode else LightCode
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Monospaced base style for everything that shows code. */
fun codeTextStyle(sizeSp: Int, color: Color): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.45f).sp,
    color = color,
    letterSpacing = 0.sp,
)
