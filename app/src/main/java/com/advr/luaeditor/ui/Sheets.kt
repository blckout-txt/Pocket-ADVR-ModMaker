package com.advr.luaeditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advr.luaeditor.data.EditorSettings
import com.advr.luaeditor.data.SearchHit
import com.advr.luaeditor.lua.Diagnostic
import com.advr.luaeditor.lua.Severity

@Composable
fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsSheetContent(
    settings: EditorSettings,
    onChange: ((EditorSettings) -> EditorSettings) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Editor", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(12.dp))

        Text("Text size  ${settings.fontSize}sp", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.fontSize.toFloat(),
            onValueChange = { v -> onChange { it.copyWith(fontSize = v.toInt()) } },
            valueRange = 10f..26f,
            steps = 15,
        )

        SettingSwitch("Wrap long lines", settings.softWrap) { v -> onChange { it.copyWith(softWrap = v) } }
        SettingSwitch("Line numbers", settings.showLineNumbers) { v -> onChange { it.copyWith(showLineNumbers = v) } }
        SettingSwitch("Suggestions while typing", settings.autoComplete) { v -> onChange { it.copyWith(autoComplete = v) } }
        SettingSwitch("Keep indentation on Enter", settings.autoIndent) { v -> onChange { it.copyWith(autoIndent = v) } }
        SettingSwitch("Close brackets and quotes", settings.autoClose) { v -> onChange { it.copyWith(autoClose = v) } }
        SettingSwitch("Indent with tabs", settings.useTabs) { v -> onChange { it.copyWith(useTabs = v) } }
        SettingSwitch("Show problems", settings.showDiagnostics) { v -> onChange { it.copyWith(showDiagnostics = v) } }

        Spacer(Modifier.size(8.dp))
        Text("Theme", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                EditorSettings.THEME_SYSTEM to "System",
                EditorSettings.THEME_DARK to "Dark",
                EditorSettings.THEME_LIGHT to "Light",
            ).forEach { (mode, label) ->
                TextButton(
                    onClick = { onChange { it.copyWith(themeMode = mode) } },
                    enabled = settings.themeMode != mode,
                ) { Text(label) }
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun DiagnosticsSheetContent(
    diagnostics: List<Diagnostic>,
    onGoTo: (Diagnostic) -> Unit,
) {
    val colors = LocalCodeColors.current
    Column(Modifier.padding(horizontal = 16.dp).heightIn(max = 420.dp)) {
        Text(
            when (diagnostics.size) {
                0 -> "No problems in this file"
                1 -> "1 problem"
                else -> "${diagnostics.size} problems"
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn {
            items(diagnostics) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onGoTo(d) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        when (d.severity) {
                            Severity.ERROR -> "●"
                            Severity.WARNING -> "▲"
                            Severity.INFO -> "■"
                        },
                        color = when (d.severity) {
                            Severity.ERROR -> colors.error
                            Severity.WARNING -> colors.warning
                            Severity.INFO -> colors.info
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 10.dp, top = 2.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(d.message, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "line ${d.line + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
fun SearchSheetContent(
    query: String,
    results: List<SearchHit>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpen: (SearchHit) -> Unit,
) {
    val colors = LocalCodeColors.current
    Column(Modifier.padding(horizontal = 16.dp).heightIn(max = 480.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Find in workspace") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !searching) {
                Text(if (searching) "Searching…" else "Search")
            }
            Text(
                if (results.isEmpty()) "" else "${results.size} matches",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn {
            items(results) { hit ->
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(hit) }.padding(vertical = 8.dp),
                ) {
                    Text(
                        "${hit.node.path}:${hit.line + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.apiGlobal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        hit.preview,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

/** Copy helper so the settings sheet can update one field without a data class. */
fun EditorSettings.copyWith(
    fontSize: Int = this.fontSize,
    softWrap: Boolean = this.softWrap,
    autoIndent: Boolean = this.autoIndent,
    autoClose: Boolean = this.autoClose,
    useTabs: Boolean = this.useTabs,
    indentWidth: Int = this.indentWidth,
    autoComplete: Boolean = this.autoComplete,
    showDiagnostics: Boolean = this.showDiagnostics,
    showLineNumbers: Boolean = this.showLineNumbers,
    themeMode: Int = this.themeMode,
) = EditorSettings(
    fontSize, softWrap, autoIndent, autoClose, useTabs, indentWidth,
    autoComplete, showDiagnostics, showLineNumbers, themeMode,
)
