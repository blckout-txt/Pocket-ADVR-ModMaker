package com.advr.luaeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the scrolling half of the key bar is currently showing. */
enum class KeyBarMode { SYMBOLS, WORDS, NAVIGATE }

class KeyBarActions(
    val insert: (String) -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val save: () -> Unit,
    val backspace: () -> Unit,
    val moveChar: (Int) -> Unit,
    val moveLine: (Int) -> Unit,
    val moveLineEdge: (Boolean) -> Unit,
    val indent: (Boolean) -> Unit,
    val toggleComment: () -> Unit,
)

private val SYMBOLS = listOf(
    "=", "(", ")", "{", "}", "[", "]", "\"", "'", ",", ".", ":", "..", "==", "~=",
    "<", ">", "<=", ">=", "+", "-", "*", "/", "%", "#", "_", ";", "--", "->",
)

private val WORDS = listOf(
    "local ", "function ", "end", "then", "if ", "elseif ", "else", "for ", "in ", "do", "while ",
    "return ", "nil", "true", "false", "and ", "or ", "not ", "#", "self",
)

/**
 * The row that sits between the editor and the system keyboard. Phone keyboards hide every
 * character Lua needs behind a modifier page, so they live here instead - along with caret keys,
 * because dragging a text handle to fix one character is miserable.
 */
@Composable
fun KeyBar(
    mode: KeyBarMode,
    onModeChange: (KeyBarMode) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    dirty: Boolean,
    actions: KeyBarActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCodeColors.current
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconKey(Icons.Filled.SwapHoriz, "Switch key row") {
            onModeChange(
                when (mode) {
                    KeyBarMode.SYMBOLS -> KeyBarMode.WORDS
                    KeyBarMode.WORDS -> KeyBarMode.NAVIGATE
                    KeyBarMode.NAVIGATE -> KeyBarMode.SYMBOLS
                }
            )
        }
        IconKey(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = canUndo, onClick = actions.undo)
        IconKey(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = canRedo, onClick = actions.redo)

        Box(Modifier.weight(1f)) {
            when (mode) {
                KeyBarMode.SYMBOLS -> KeyRow(SYMBOLS) { actions.insert(it) }
                KeyBarMode.WORDS -> KeyRow(WORDS) { actions.insert(it) }
                KeyBarMode.NAVIGATE -> NavigationRow(actions)
            }
        }

        IconKey(Icons.AutoMirrored.Filled.KeyboardTab, "Indent") { actions.indent(false) }
        IconKey(
            Icons.Filled.Save,
            "Save",
            tint = if (dirty) colors.selfGlobal else colors.gutter,
            onClick = actions.save,
        )
    }
}

@Composable
private fun KeyRow(keys: List<String>, onInsert: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(keys, key = { it }) { key ->
            TextKey(key.trim().ifEmpty { "␣" }) { onInsert(key) }
        }
    }
}

@Composable
private fun NavigationRow(actions: KeyBarActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextKey("⇤") { actions.moveLineEdge(true) }
        IconKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { actions.moveChar(-1) }
        IconKey(Icons.Filled.KeyboardArrowUp, "Up") { actions.moveLine(-1) }
        IconKey(Icons.Filled.KeyboardArrowDown, "Down") { actions.moveLine(1) }
        IconKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { actions.moveChar(1) }
        TextKey("⇥") { actions.moveLineEdge(false) }
        IconKey(Icons.AutoMirrored.Filled.Backspace, "Backspace", onClick = actions.backspace)
        TextKey("--") { actions.toggleComment() }
        TextKey("<|") { actions.indent(true) }
    }
}

@Composable
private fun TextKey(label: String, onClick: () -> Unit) {
    val colors = LocalCodeColors.current
    Box(
        Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = colors.plain,
            fontSize = if (label.length > 3) 13.sp else 16.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun IconKey(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = LocalCodeColors.current.gutterActive,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}
