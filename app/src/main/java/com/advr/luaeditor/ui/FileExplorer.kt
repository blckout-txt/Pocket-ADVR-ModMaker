package com.advr.luaeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.advr.luaeditor.data.EditorViewModel
import com.advr.luaeditor.data.FileNode

/**
 * The workspace tree. Folders ADVR treats specially (`items/`, `potions/`, …) are labelled with the
 * kind of file they hold, which is the same signal the editor uses to decide what globals a file
 * gets.
 */
@Composable
fun FileExplorer(
    vm: EditorViewModel,
    onOpenFile: (FileNode) -> Unit,
    onOpenFolder: () -> Unit,
    onOpenSearch: () -> Unit,
    onNewFile: (FileNode) -> Unit,
    onNewFolder: (FileNode) -> Unit,
    onRename: (FileNode, FileNode?) -> Unit,
    onDelete: (FileNode, FileNode?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCodeColors.current
    val ws = vm.workspace

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ws?.rootName ?: "No folder open",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    vm.apiStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, "Search the workspace", tint = colors.gutterActive)
            }
            IconButton(onClick = { ws?.let { vm.loadChildren(it.root, force = true) } }) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = colors.gutterActive)
            }
            IconButton(onClick = onOpenFolder) {
                Icon(Icons.Filled.FolderOpen, "Open a folder", tint = colors.apiGlobal)
            }
        }

        if (ws == null) {
            EmptyWorkspace(vm.workspaceError, onOpenFolder)
            return@Column
        }

        val rows = vm.visibleRows()
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.node.documentId }) { row ->
                TreeRowItem(
                    node = row.node,
                    depth = row.depth,
                    expanded = vm.isExpanded(row.node),
                    isOpen = vm.buffers.any { it.node.uri == row.node.uri },
                    isActive = vm.active?.node?.uri == row.node.uri,
                    onClick = {
                        if (row.node.isDirectory) vm.toggleDirectory(row.node) else onOpenFile(row.node)
                    },
                    onNewFile = { onNewFile(row.node) },
                    onNewFolder = { onNewFolder(row.node) },
                    onRename = { onRename(row.node, row.parent) },
                    onDelete = { onDelete(row.node, row.parent) },
                )
            }
        }
    }
}

@Composable
private fun EmptyWorkspace(error: String?, onOpenFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Open the folder that holds your mod",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Everything under it stays browsable and editable, and Lua files are checked against the folder they live in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Spacer(Modifier.width(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onOpenFolder) { Text("Choose folder") }
    }
}

@Composable
private fun TreeRowItem(
    node: FileNode,
    depth: Int,
    expanded: Boolean,
    isOpen: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCodeColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val kind = remember(node.path, node.isDirectory) {
        if (node.isDirectory) ModKind.entries.firstOrNull { it.folder.isNotEmpty() && it.folder == node.name.lowercase() }
        else null
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = (8 + depth * 14).dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                node.isDirectory && expanded -> Icons.Filled.FolderOpen
                node.isDirectory -> Icons.Filled.Folder
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = when {
                node.isDirectory && kind != null -> colors.selfGlobal
                node.isDirectory -> colors.gutterActive
                node.isLua -> colors.apiGlobal
                else -> colors.gutter
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(
                node.name,
                color = if (isOpen) colors.plain else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = if (node.isLua) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (kind != null) {
                Text(
                    "${kind.label.lowercase()} files · gets `${kind.selfGlobal}`",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.MoreVert, "Actions", tint = colors.gutter, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (node.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("New Lua file") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                        onClick = { menuOpen = false; onNewFile() },
                    )
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = { menuOpen = false; onNewFolder() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** Rounded chip used for the mod-kind marker in the top bar. */
@Composable
fun ContextChip(label: String, modifier: Modifier = Modifier) {
    val colors = LocalCodeColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.selfGlobal.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = colors.selfGlobal, fontSize = 11.sp, maxLines = 1)
    }
}
