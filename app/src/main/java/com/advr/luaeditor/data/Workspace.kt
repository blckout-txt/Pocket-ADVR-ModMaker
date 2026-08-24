package com.advr.luaeditor.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/** One entry in the opened workspace folder. [path] is relative to the workspace root. */
class FileNode(
    @JvmField val uri: Uri,
    @JvmField val documentId: String,
    @JvmField val name: String,
    @JvmField val path: String,
    @JvmField val isDirectory: Boolean,
    @JvmField val size: Long,
    @JvmField val lastModified: Long,
) {
    val isLua: Boolean get() = name.endsWith(".lua", ignoreCase = true)
    val isText: Boolean
        get() = isLua || TEXT_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        private val TEXT_SUFFIXES = listOf(
            ".lua", ".txt", ".json", ".csv", ".md", ".modinfo", ".cfg", ".ini", ".xml", ".yml", ".yaml",
        )
    }
}

/**
 * A workspace backed by a Storage Access Framework tree, so the app can open the mod folder wherever
 * the user keeps it without needing broad storage permission.
 *
 * Directory listings go through a single cursor query per folder rather than DocumentFile, which
 * matters on a phone where a mod folder can hold hundreds of rooms and textures.
 */
class Workspace(private val context: Context, val treeUri: Uri) {

    private val resolver: ContentResolver get() = context.contentResolver

    val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)
    val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)

    val rootName: String = queryName(rootUri) ?: rootDocumentId.substringAfterLast('/').ifEmpty { "workspace" }

    val root: FileNode = FileNode(rootUri, rootDocumentId, rootName, "", true, 0, 0)

    fun children(parent: FileNode): List<FileNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
        val out = ArrayList<FileNode>(32)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(childrenUri, projection, null, null, null)
            if (cursor != null) {
                val idIdx = 0
                val nameIdx = 1
                val mimeIdx = 2
                val sizeIdx = 3
                val modIdx = 4
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out.add(
                        FileNode(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            documentId = docId,
                            name = name,
                            path = if (parent.path.isEmpty()) name else "${parent.path}/$name",
                            isDirectory = isDir,
                            size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx),
                            lastModified = if (cursor.isNull(modIdx)) 0L else cursor.getLong(modIdx),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // A revoked permission or a provider that went away: show the folder as empty.
        } finally {
            cursor?.close()
        }
        out.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return out
    }

    fun readText(node: FileNode): String =
        resolver.openInputStream(node.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    fun writeText(node: FileNode, text: String) {
        // "wt" truncates; without it a shorter save leaves the old tail behind.
        resolver.openOutputStream(node.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw FileNotFoundException(node.path)
    }

    fun createFile(parent: FileNode, name: String): FileNode? {
        val mime = if (name.endsWith(".lua", true)) "text/x-lua" else "text/plain"
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name) ?: return null
        val docId = DocumentsContract.getDocumentId(uri)
        val actual = queryName(uri) ?: name
        return FileNode(uri, docId, actual, childPath(parent, actual), false, 0, System.currentTimeMillis())
    }

    fun createDirectory(parent: FileNode, name: String): FileNode? {
        val uri = DocumentsContract.createDocument(
            resolver, parent.uri, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: return null
        val docId = DocumentsContract.getDocumentId(uri)
        val actual = queryName(uri) ?: name
        return FileNode(uri, docId, actual, childPath(parent, actual), true, 0, System.currentTimeMillis())
    }

    fun delete(node: FileNode): Boolean = try {
        DocumentsContract.deleteDocument(resolver, node.uri)
    } catch (_: Exception) {
        false
    }

    fun rename(node: FileNode, newName: String): FileNode? = try {
        val uri = DocumentsContract.renameDocument(resolver, node.uri, newName)
        if (uri == null) null else FileNode(
            uri, DocumentsContract.getDocumentId(uri), newName,
            node.path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" },
            node.isDirectory, node.size, System.currentTimeMillis(),
        )
    } catch (_: Exception) {
        null
    }

    /** Depth first walk used by workspace-wide search. Directories are visited in listing order. */
    fun walk(start: FileNode = root, maxEntries: Int = 4000, onNode: (FileNode) -> Unit) {
        val stack = ArrayDeque<FileNode>()
        stack.addLast(start)
        var seen = 0
        while (stack.isNotEmpty() && seen < maxEntries) {
            val dir = stack.removeLast()
            for (child in children(dir)) {
                seen++
                onNode(child)
                if (child.isDirectory) stack.addLast(child)
                if (seen >= maxEntries) break
            }
        }
    }

    private fun childPath(parent: FileNode, name: String) =
        if (parent.path.isEmpty()) name else "${parent.path}/$name"

    private fun queryName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
