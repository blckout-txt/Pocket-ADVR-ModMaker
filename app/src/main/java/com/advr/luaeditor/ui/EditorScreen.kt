package com.advr.luaeditor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advr.luaeditor.api.ModKind
import com.advr.luaeditor.data.Buffer
import com.advr.luaeditor.data.EditorViewModel
import com.advr.luaeditor.data.FileNode
import com.advr.luaeditor.lua.Severity
import kotlinx.coroutines.launch

private sealed interface Dialog {
    class NewFile(val parent: FileNode) : Dialog
    class NewFolder(val parent: FileNode) : Dialog
    class Rename(val node: FileNode, val parent: FileNode?) : Dialog
    class Delete(val node: FileNode, val parent: FileNode?) : Dialog
}

private enum class Sheet { SETTINGS, PROBLEMS, SEARCH, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val colors = LocalCodeColors.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var keyBarMode by remember { mutableStateOf(KeyBarMode.SYMBOLS) }
    var overflowOpen by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            vm.openWorkspace(it)
            scope.launch { drawerState.open() }
        }
    }

    LaunchedEffect(vm.statusMessage) {
        vm.statusMessage?.let {
            snackbar.showSnackbar(it)
            vm.statusMessage = null
        }
    }

    val buffer = vm.active

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerTonalElevation = 0.dp) {
                FileExplorer(
                    vm = vm,
                    onOpenFile = { node ->
                        // On a phone the drawer covers the editor, so step out of the way.
                        vm.openFile(node)
                        scope.launch { drawerState.close() }
                    },
                    onOpenFolder = { folderPicker.launch(null) },
                    onOpenSearch = { sheet = Sheet.SEARCH },
                    onNewFile = { dialog = Dialog.NewFile(it) },
                    onNewFolder = { dialog = Dialog.NewFolder(it) },
                    onRename = { node, parent -> dialog = Dialog.Rename(node, parent) },
                    onDelete = { node, parent -> dialog = Dialog.Delete(node, parent) },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = colors.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, "Open the file explorer")
                        }
                    },
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    buffer?.title ?: "ADVR Lua",
                                    fontSize = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (buffer?.dirty == true) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("●", color = colors.selfGlobal, fontSize = 12.sp)
                                }
                            }
                            val subtitle = buffer?.node?.path ?: vm.workspace?.rootName ?: "no folder open"
                            Text(
                                subtitle,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (buffer != null && buffer.context.kind != ModKind.UNKNOWN) {
                            ContextChip(buffer.context.kind.label)
                            Spacer(Modifier.width(4.dp))
                        }
                        if (buffer != null) {
                            ProblemsAction(buffer) { sheet = Sheet.PROBLEMS }
                            IconButton(onClick = { vm.save() }) {
                                Icon(
                                    Icons.Filled.Save,
                                    "Save",
                                    tint = if (buffer.dirty) colors.selfGlobal else colors.gutter,
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, "More")
                            }
                            DropdownMenu(overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save all") },
                                    onClick = { overflowOpen = false; vm.saveAll() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Find in workspace") },
                                    onClick = { overflowOpen = false; sheet = Sheet.SEARCH },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (vm.settings.softWrap) "Stop wrapping lines" else "Wrap long lines") },
                                    onClick = {
                                        overflowOpen = false
                                        vm.updateSettings { it.copyWith(softWrap = !it.softWrap) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { overflowOpen = false; sheet = Sheet.SETTINGS },
                                )
                                DropdownMenuItem(
                                    text = { Text("About this file's globals") },
                                    onClick = { overflowOpen = false; sheet = Sheet.ABOUT },
                                )
                            }
                        }
                    },
                )
            },
        ) { inner ->
            Column(
                Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                if (vm.buffers.isNotEmpty()) {
                    TabStrip(vm)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (buffer == null) {
                        WelcomePanel(
                            hasWorkspace = vm.workspace != null,
                            apiStatus = vm.apiStatus,
                            onOpenFolder = { folderPicker.launch(null) },
                            onBrowse = { scope.launch { drawerState.open() } },
                        )
                    } else {
                        CodeEditor(
                            buffer = buffer,
                            settings = vm.settings,
                            api = vm.api,
                            onValueChange = { vm.onTextChange(buffer, it) },
                        )
                    }
                }

                if (buffer != null) {
                    if (vm.settings.showDiagnostics && buffer.diagnostics.isNotEmpty()) {
                        DiagnosticsBar(buffer) { sheet = Sheet.PROBLEMS }
                    }
                    vm.signature?.let { SignatureStrip(it) }
                    SuggestionStrip(
                        result = vm.completion,
                        expanded = vm.completionExpanded,
                        onPick = { vm.applyCompletion(it) },
                        onToggleExpand = { vm.completionExpanded = !vm.completionExpanded },
                        onDismiss = { vm.dismissCompletion() },
                    )
                    KeyBar(
                        mode = keyBarMode,
                        onModeChange = { keyBarMode = it },
                        canUndo = buffer.undo.canUndo,
                        canRedo = buffer.undo.canRedo,
                        dirty = buffer.dirty,
                        actions = KeyBarActions(
                            insert = vm::insertText,
                            undo = vm::undo,
                            redo = vm::redo,
                            save = { vm.save() },
                            backspace = vm::backspace,
                            moveChar = vm::moveCaret,
                            moveLine = vm::moveCaretLine,
                            moveLineEdge = vm::moveCaretToLineEdge,
                            indent = vm::indent,
                            toggleComment = vm::toggleComment,
                        ),
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        is Dialog.NewFile -> NameDialog(
            title = "New file in ${d.parent.name}",
            label = "File name",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { vm.createFile(d.parent, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is Dialog.NewFolder -> NameDialog(
            title = "New folder in ${d.parent.name}",
            label = "Folder name",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { vm.createFolder(d.parent, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is Dialog.Rename -> NameDialog(
            title = "Rename",
            label = "New name",
            initial = d.node.name,
            confirmLabel = "Rename",
            onConfirm = { vm.renameNode(d.node, it, d.parent); dialog = null },
            onDismiss = { dialog = null },
        )
        is Dialog.Delete -> ConfirmDialog(
            title = "Delete ${d.node.name}?",
            message = if (d.node.isDirectory)
                "The folder and everything inside it is removed. This cannot be undone."
            else "This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteNode(d.node, d.parent); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) {
            when (sheet) {
                Sheet.SETTINGS -> SettingsSheetContent(vm.settings) { vm.updateSettings(it) }
                Sheet.PROBLEMS -> DiagnosticsSheetContent(buffer?.diagnostics.orEmpty()) { d ->
                    vm.goToOffset(d.offset)
                    sheet = null
                }
                Sheet.SEARCH -> SearchSheetContent(
                    query = vm.searchQuery,
                    results = vm.searchResults,
                    searching = vm.searching,
                    onQueryChange = { vm.searchQuery = it },
                    onSearch = { vm.runSearch(it) },
                    onOpen = { vm.openSearchHit(it); sheet = null },
                )
                Sheet.ABOUT -> GlobalsExplainer(buffer)
                null -> Unit
            }
        }
    }
}

@Composable
private fun TabStrip(vm: EditorViewModel) {
    val colors = LocalCodeColors.current
    LazyRow(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(vm.buffers, key = { _, b -> b.node.documentId }) { index, b ->
            val active = index == vm.activeIndex
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable { vm.setActive(index) }
                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    b.title,
                    color = if (active) colors.plain else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
                if (b.dirty) {
                    Spacer(Modifier.width(4.dp))
                    Text("●", color = colors.selfGlobal, fontSize = 9.sp)
                }
                IconButton(onClick = { vm.closeBuffer(index) }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Close, "Close ${b.title}", tint = colors.gutter, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun ProblemsAction(buffer: Buffer, onClick: () -> Unit) {
    val colors = LocalCodeColors.current
    val errors = buffer.diagnostics.count { it.severity == Severity.ERROR }
    val warnings = buffer.diagnostics.count { it.severity == Severity.WARNING }
    if (errors == 0 && warnings == 0) return
    Row(
        Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            "Problems",
            tint = if (errors > 0) colors.error else colors.warning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (errors > 0) "$errors" else "$warnings",
            color = if (errors > 0) colors.error else colors.warning,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DiagnosticsBar(buffer: Buffer, onClick: () -> Unit) {
    val colors = LocalCodeColors.current
    val first = buffer.diagnostics.firstOrNull { it.severity == Severity.ERROR }
        ?: buffer.diagnostics.first()
    val tint = when (first.severity) {
        Severity.ERROR -> colors.error
        Severity.WARNING -> colors.warning
        Severity.INFO -> colors.info
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("line ${first.line + 1}", color = tint, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            first.message,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (buffer.diagnostics.size > 1) {
            Text("+${buffer.diagnostics.size - 1}", color = tint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WelcomePanel(
    hasWorkspace: Boolean,
    apiStatus: String,
    onOpenFolder: () -> Unit,
    onBrowse: () -> Unit,
) {
    val colors = LocalCodeColors.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ADVR Lua", fontSize = 26.sp, color = colors.apiGlobal, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.size(6.dp))
        Text(
            "A Lua editor for Ancient Dungeon VR mods, with the game's own API definitions built in.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        Spacer(Modifier.size(20.dp))
        Bullet("Completion for every ADVR type, plus the tables you build in the file you are editing.")
        Bullet("Each file is checked against the folder it lives in, because ADVR gives every Lua file its own globals.")
        Bullet("New files in items/, potions/, weapons/ and friends start from the right template.")
        Spacer(Modifier.size(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenFolder) { Text(if (hasWorkspace) "Open another folder" else "Open mod folder") }
            if (hasWorkspace) TextButton(onClick = onBrowse) { Text("Browse files") }
        }
        Spacer(Modifier.size(12.dp))
        Text(apiStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("·  ", color = LocalCodeColors.current.selfGlobal, fontSize = 14.sp)
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

/** Explains, for the file that is open, which globals ADVR hands it and which it will not see. */
@Composable
private fun GlobalsExplainer(buffer: Buffer?) {
    val colors = LocalCodeColors.current
    Column(Modifier.padding(20.dp)) {
        Text("Globals in this file", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(10.dp))
        if (buffer == null) {
            Text("Open a Lua file to see what it is handed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(24.dp))
            return@Column
        }
        val kind = buffer.context.kind
        Text(
            if (kind == ModKind.UNKNOWN)
                "${buffer.node.path} is not inside a folder ADVR recognises, so every API global is offered."
            else "${buffer.node.path} sits under ${kind.folder}/, so ADVR loads it as a ${kind.label.lowercase()}.",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
        )
        if (kind != ModKind.UNKNOWN) {
            Spacer(Modifier.size(12.dp))
            Text("It is given", style = MaterialTheme.typography.labelLarge)
            Text(
                "`${kind.selfGlobal}`" + if (kind.eventTable.isNotEmpty()) " and ADVR.${kind.eventTable}" else "",
                color = colors.selfGlobal,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (kind.requiredFunctions.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                Text("It must define", style = MaterialTheme.typography.labelLarge)
                kind.requiredFunctions.forEach {
                    Text(it, color = colors.call, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.size(12.dp))
            Text("It will not see", style = MaterialTheme.typography.labelLarge)
            Text(
                ModKind.allSelfGlobals.filter { it != kind.selfGlobal }.sorted().joinToString(", "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            "Globals you assign here stay in this file - another Lua file in the same mod starts with a clean table.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.size(24.dp))
    }
}
