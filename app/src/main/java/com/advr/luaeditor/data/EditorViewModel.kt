package com.advr.luaeditor.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.FileContext
import com.advr.luaeditor.api.ModKind
import com.advr.luaeditor.lua.Completion
import com.advr.luaeditor.lua.CompletionEngine
import com.advr.luaeditor.lua.CompletionResult
import com.advr.luaeditor.lua.Diagnostic
import com.advr.luaeditor.lua.EditAssist
import com.advr.luaeditor.lua.FileModel
import com.advr.luaeditor.lua.LuaAnalyzer
import com.advr.luaeditor.lua.LuaDiagnostics
import com.advr.luaeditor.lua.SignatureInfo
import com.advr.luaeditor.lua.Snippets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One open file. */
@Stable
class Buffer(
    val node: FileNode,
    initialText: String,
    val context: FileContext,
) {
    var value by mutableStateOf(TextFieldValue(initialText, TextRange(0)))
    var savedText by mutableStateOf(initialText)
    var model by mutableStateOf(FileModel.empty(initialText))
    var diagnostics by mutableStateOf<List<Diagnostic>>(emptyList())
    val undo = UndoStack()

    /** The debounced analysis in flight for this buffer, cancelled when a newer edit lands. */
    var analysisJob: Job? = null

    val dirty: Boolean get() = value.text != savedText
    val title: String get() = node.name
}

class SearchHit(
    val node: FileNode,
    val line: Int,
    val preview: String,
    val offset: Int,
)

class EditorSettings(
    val fontSize: Int = 14,
    val softWrap: Boolean = true,
    val autoIndent: Boolean = true,
    val autoClose: Boolean = true,
    val useTabs: Boolean = true,
    val indentWidth: Int = 4,
    val autoComplete: Boolean = true,
    val showDiagnostics: Boolean = true,
    val showLineNumbers: Boolean = true,
    val themeMode: Int = THEME_SYSTEM,
) {
    val editOptions: EditAssist.Options
        get() = EditAssist.Options(autoIndent, autoClose, useTabs, indentWidth)

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
    }
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("advr_lua_editor", Application.MODE_PRIVATE)

    var api by mutableStateOf(ApiIndex.EMPTY)
        private set
    var apiStatus by mutableStateOf("Loading ADVR API…")
        private set

    var workspace by mutableStateOf<Workspace?>(null)
        private set
    var workspaceError by mutableStateOf<String?>(null)

    val buffers: SnapshotStateList<Buffer> = mutableStateListOf()
    var activeIndex by mutableStateOf(-1)
        private set

    val active: Buffer? get() = buffers.getOrNull(activeIndex)

    var completion by mutableStateOf(CompletionResult.NONE)
        private set
    var signature by mutableStateOf<SignatureInfo?>(null)
        private set
    var completionExpanded by mutableStateOf(false)

    var settings by mutableStateOf(EditorSettings())
        private set

    /** Loaded children per directory document id. */
    val treeChildren = mutableStateMapOf<String, List<FileNode>>()
    val expanded = mutableStateListOf<String>()
    var treeLoading by mutableStateOf<String?>(null)

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<SearchHit>>(emptyList())
    var searching by mutableStateOf(false)

    var statusMessage by mutableStateOf<String?>(null)

    private var engine = CompletionEngine(ApiIndex.EMPTY)

    init {
        loadSettings()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                runCatching { ApiIndex.load(getApplication()) }
            }
            loaded.onSuccess {
                api = it
                engine = CompletionEngine(it)
                apiStatus = "ADVR ${it.version} · ${it.classes.size} types"
                reanalyzeAll()
            }.onFailure {
                apiStatus = "API index unavailable"
                workspaceError = "Could not load the bundled ADVR API: ${it.message}"
            }
        }
        prefs.getString(KEY_TREE_URI, null)?.let { saved ->
            runCatching { attachWorkspace(Uri.parse(saved), takePermission = false) }
        }
    }

    // ------------------------------------------------------------------ workspace

    fun openWorkspace(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags) }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        attachWorkspace(uri, takePermission = true)
    }

    private fun attachWorkspace(uri: Uri, takePermission: Boolean) {
        try {
            val ws = Workspace(getApplication(), uri)
            workspace = ws
            workspaceError = null
            treeChildren.clear()
            expanded.clear()
            expanded.add(ws.rootDocumentId)
            loadChildren(ws.root)
        } catch (e: Exception) {
            workspace = null
            workspaceError = "That folder could not be opened: ${e.message}"
        }
    }

    fun closeWorkspace() {
        workspace = null
        treeChildren.clear()
        expanded.clear()
        buffers.clear()
        activeIndex = -1
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    fun loadChildren(dir: FileNode, force: Boolean = false) {
        val ws = workspace ?: return
        if (!force && treeChildren.containsKey(dir.documentId)) return
        viewModelScope.launch {
            treeLoading = dir.documentId
            val kids = withContext(Dispatchers.IO) { ws.children(dir) }
            treeChildren[dir.documentId] = kids
            treeLoading = null
        }
    }

    fun toggleDirectory(dir: FileNode) {
        if (expanded.contains(dir.documentId)) {
            expanded.remove(dir.documentId)
        } else {
            expanded.add(dir.documentId)
            loadChildren(dir)
        }
    }

    fun isExpanded(dir: FileNode) = expanded.contains(dir.documentId)

    /** Flattened tree for the explorer list. */
    fun visibleRows(): List<TreeRow> {
        val ws = workspace ?: return emptyList()
        val out = ArrayList<TreeRow>(64)
        fun walk(node: FileNode, parent: FileNode?, depth: Int) {
            out.add(TreeRow(node, parent, depth))
            if (node.isDirectory && expanded.contains(node.documentId)) {
                treeChildren[node.documentId]?.forEach { walk(it, node, depth + 1) }
            }
        }
        walk(ws.root, null, 0)
        return out
    }

    class TreeRow(val node: FileNode, val parent: FileNode?, val depth: Int)

    // ------------------------------------------------------------------ buffers

    fun openFile(node: FileNode) {
        val existing = buffers.indexOfFirst { it.node.uri == node.uri }
        if (existing >= 0) {
            setActive(existing)
            return
        }
        val ws = workspace ?: return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ws.readText(node) } }
                .getOrElse {
                    statusMessage = "Could not read ${node.name}"
                    return@launch
                }
            val ctx = FileContext.of(node.path, node.name)
            val buffer = Buffer(node, text, ctx)
            buffers.add(buffer)
            setActive(buffers.size - 1)
            analyzeNow(buffer)
        }
    }

    fun setActive(index: Int) {
        activeIndex = index.coerceIn(-1, buffers.size - 1)
        completion = CompletionResult.NONE
        signature = null
        completionExpanded = false
    }

    fun closeBuffer(index: Int) {
        if (index !in buffers.indices) return
        buffers.removeAt(index)
        activeIndex = when {
            buffers.isEmpty() -> -1
            index <= activeIndex -> (activeIndex - 1).coerceAtLeast(0)
            else -> activeIndex
        }
        completion = CompletionResult.NONE
    }

    fun save(target: Buffer? = null) {
        val buffer = target ?: active ?: return
        val ws = workspace ?: return
        val text = buffer.value.text
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ws.writeText(buffer.node, text) }.isSuccess }
            if (ok) {
                buffer.savedText = text
                buffer.undo.breakRun()
                statusMessage = "Saved ${buffer.node.name}"
            } else {
                statusMessage = "Could not save ${buffer.node.name}"
            }
        }
    }

    fun saveAll() {
        buffers.filter { it.dirty }.forEach { save(it) }
    }

    // ------------------------------------------------------------------ editing

    fun onTextChange(buffer: Buffer, raw: TextFieldValue) {
        val old = buffer.value
        val processed = EditAssist.process(old, raw, settings.editOptions)
        buffer.undo.record(old, processed)
        buffer.value = processed
        if (old.text != processed.text) scheduleAnalysis(buffer)
        refreshAssist(buffer)
    }

    /** Selection-only moves still refresh completion, which is how the caret bar stays in sync. */
    fun onSelectionChange(buffer: Buffer, value: TextFieldValue) {
        buffer.value = value
        refreshAssist(buffer)
    }

    private fun refreshAssist(buffer: Buffer) {
        if (!settings.autoComplete) {
            completion = CompletionResult.NONE
            signature = null
            return
        }
        val text = buffer.value.text
        val caret = buffer.value.selection.start
        completion = if (buffer.value.selection.collapsed) {
            engine.complete(text, caret, buffer.model, buffer.context)
        } else CompletionResult.NONE
        signature = if (buffer.value.selection.collapsed)
            engine.signatureHelp(text, caret, buffer.model)
        else null
    }

    fun dismissCompletion() {
        completion = CompletionResult.NONE
        completionExpanded = false
    }

    fun applyCompletion(item: Completion) {
        val buffer = active ?: return
        val result = completion
        val text = buffer.value.text
        val start = result.replaceStart.coerceIn(0, text.length)
        val end = result.replaceEnd.coerceIn(start, text.length)

        val insert = item.insertText
        val caretIn = item.caretOffset
        val indent = EditAssist.currentIndent(text, start)
        val laidOut = if (insert.contains('\n')) {
            insert.split("\n").mapIndexed { i, l ->
                if (i == 0) l else indent + l.replace("\t", settings.editOptions.unit)
            }.joinToString("\n")
        } else insert

        var caretShift = caretIn
        if (insert.contains('\n')) {
            var growth = 0
            for (i in 0 until caretIn.coerceAtMost(insert.length)) {
                if (insert[i] == '\n') growth += indent.length
                if (insert[i] == '\t') growth += settings.editOptions.unit.length - 1
            }
            caretShift += growth
        }

        val newText = text.substring(0, start) + laidOut + text.substring(end)
        val newValue = TextFieldValue(newText, TextRange((start + caretShift).coerceIn(0, newText.length)))
        buffer.undo.record(buffer.value, newValue)
        buffer.undo.breakRun()
        buffer.value = newValue
        completion = CompletionResult.NONE
        completionExpanded = false
        scheduleAnalysis(buffer)
        signature = engine.signatureHelp(newText, newValue.selection.start, buffer.model)
    }

    fun insertText(s: String) {
        val buffer = active ?: return
        val v = buffer.value
        val start = v.selection.min
        val end = v.selection.max
        val newText = v.text.substring(0, start) + s + v.text.substring(end)
        val newValue = TextFieldValue(newText, TextRange(start + s.length))
        buffer.undo.record(v, newValue)
        buffer.value = newValue
        scheduleAnalysis(buffer)
        refreshAssist(buffer)
    }

    fun moveCaret(delta: Int) {
        val buffer = active ?: return
        val v = buffer.value
        val target = (v.selection.start + delta).coerceIn(0, v.text.length)
        buffer.value = v.copy(selection = TextRange(target))
        refreshAssist(buffer)
    }

    fun moveCaretLine(delta: Int) {
        val buffer = active ?: return
        val v = buffer.value
        val text = v.text
        val line = EditAssist.lineNumberOf(text, v.selection.start)
        val col = v.selection.start - EditAssist.lineStartOf(text, v.selection.start)
        val targetLine = (line + delta).coerceAtLeast(0)
        val ls = EditAssist.offsetOfLine(text, targetLine)
        val le = EditAssist.lineEndOf(text, ls)
        val target = (ls + col).coerceIn(ls, le)
        buffer.value = v.copy(selection = TextRange(target))
        refreshAssist(buffer)
    }

    fun moveCaretToLineEdge(toStart: Boolean) {
        val buffer = active ?: return
        val v = buffer.value
        val target = if (toStart) EditAssist.lineStartOf(v.text, v.selection.start)
        else EditAssist.lineEndOf(v.text, v.selection.start)
        buffer.value = v.copy(selection = TextRange(target))
        refreshAssist(buffer)
    }

    fun backspace() {
        val buffer = active ?: return
        val v = buffer.value
        if (v.selection.collapsed && v.selection.start == 0) return
        val start = if (v.selection.collapsed) v.selection.start - 1 else v.selection.min
        val end = v.selection.max
        val newText = v.text.removeRange(start, end)
        val newValue = TextFieldValue(newText, TextRange(start))
        buffer.undo.record(v, newValue)
        buffer.value = newValue
        scheduleAnalysis(buffer)
        refreshAssist(buffer)
    }

    fun undo() {
        val buffer = active ?: return
        buffer.undo.undo(buffer.value)?.let {
            buffer.value = it
            scheduleAnalysis(buffer)
            completion = CompletionResult.NONE
        }
    }

    fun redo() {
        val buffer = active ?: return
        buffer.undo.redo(buffer.value)?.let {
            buffer.value = it
            scheduleAnalysis(buffer)
            completion = CompletionResult.NONE
        }
    }

    fun toggleComment() {
        val buffer = active ?: return
        val newValue = EditAssist.toggleComment(buffer.value)
        buffer.undo.record(buffer.value, newValue)
        buffer.value = newValue
        scheduleAnalysis(buffer)
    }

    fun indent(out: Boolean) {
        val buffer = active ?: return
        val newValue = EditAssist.indentSelection(buffer.value, settings.editOptions, out)
        buffer.undo.record(buffer.value, newValue)
        buffer.value = newValue
        scheduleAnalysis(buffer)
    }

    fun goToOffset(offset: Int) {
        val buffer = active ?: return
        buffer.value = buffer.value.copy(
            selection = TextRange(offset.coerceIn(0, buffer.value.text.length))
        )
    }

    // ------------------------------------------------------------------ analysis

    private fun scheduleAnalysis(buffer: Buffer) {
        buffer.analysisJob?.cancel()
        buffer.analysisJob = viewModelScope.launch {
            delay(ANALYSIS_DEBOUNCE_MS)
            analyzeNow(buffer)
        }
    }

    private suspend fun analyzeNow(buffer: Buffer) {
        val text = buffer.value.text
        val index = api
        val result = withContext(Dispatchers.Default) {
            val model = LuaAnalyzer.analyze(text, index, buffer.context)
            val diags = if (buffer.node.isLua) LuaDiagnostics.run(text, model, buffer.context, index)
            else emptyList()
            model to diags
        }
        if (buffer.value.text === text || buffer.value.text == text) {
            buffer.model = result.first
            buffer.diagnostics = result.second
        }
    }

    private fun reanalyzeAll() {
        viewModelScope.launch { buffers.forEach { analyzeNow(it) } }
    }

    // ------------------------------------------------------------------ file operations

    fun createFile(parent: FileNode, name: String) {
        val ws = workspace ?: return
        viewModelScope.launch {
            val fileName = if (name.contains('.')) name else "$name.lua"
            val created = withContext(Dispatchers.IO) { ws.createFile(parent, fileName) }
            if (created == null) {
                statusMessage = "Could not create $fileName"
                return@launch
            }
            val kind = ModKind.forPath(created.path)
            val template = Snippets.fileTemplate(kind, fileName.removeSuffix(".lua"))
            if (template.isNotEmpty()) {
                withContext(Dispatchers.IO) { runCatching { ws.writeText(created, template) } }
            }
            loadChildren(parent, force = true)
            openFile(created)
            statusMessage = if (template.isEmpty()) "Created $fileName"
            else "Created $fileName from the ${kind.label.lowercase()} template"
        }
    }

    fun createFolder(parent: FileNode, name: String) {
        val ws = workspace ?: return
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) { ws.createDirectory(parent, name) }
            if (created == null) statusMessage = "Could not create $name"
            else {
                loadChildren(parent, force = true)
                statusMessage = "Created $name/"
            }
        }
    }

    fun deleteNode(node: FileNode, parent: FileNode?) {
        val ws = workspace ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { ws.delete(node) }
            if (ok) {
                buffers.indexOfFirst { it.node.uri == node.uri }.takeIf { it >= 0 }?.let { closeBuffer(it) }
                parent?.let { loadChildren(it, force = true) }
                treeChildren.remove(node.documentId)
                statusMessage = "Deleted ${node.name}"
            } else statusMessage = "Could not delete ${node.name}"
        }
    }

    fun renameNode(node: FileNode, newName: String, parent: FileNode?) {
        val ws = workspace ?: return
        viewModelScope.launch {
            val renamed = withContext(Dispatchers.IO) { ws.rename(node, newName) }
            if (renamed == null) {
                statusMessage = "Could not rename ${node.name}"
                return@launch
            }
            parent?.let { loadChildren(it, force = true) }
            val idx = buffers.indexOfFirst { it.node.uri == node.uri }
            if (idx >= 0) {
                val old = buffers[idx]
                val fresh = Buffer(renamed, old.value.text, FileContext.of(renamed.path, renamed.name))
                fresh.value = old.value
                fresh.savedText = old.savedText
                buffers[idx] = fresh
                analyzeNow(fresh)
            }
            statusMessage = "Renamed to $newName"
        }
    }

    // ------------------------------------------------------------------ search

    fun runSearch(query: String) {
        searchQuery = query
        val ws = workspace ?: return
        if (query.isBlank()) {
            searchResults = emptyList()
            return
        }
        viewModelScope.launch {
            searching = true
            val hits = withContext(Dispatchers.IO) {
                val out = ArrayList<SearchHit>(64)
                val needle = query.lowercase()
                ws.walk { node ->
                    if (out.size >= MAX_SEARCH_HITS) return@walk
                    if (node.isDirectory || !node.isText) return@walk
                    val text = runCatching { ws.readText(node) }.getOrNull() ?: return@walk
                    var idx = text.lowercase().indexOf(needle)
                    var perFile = 0
                    while (idx >= 0 && out.size < MAX_SEARCH_HITS && perFile < MAX_HITS_PER_FILE) {
                        val ls = EditAssist.lineStartOf(text, idx)
                        val le = EditAssist.lineEndOf(text, idx)
                        out.add(
                            SearchHit(node, EditAssist.lineNumberOf(text, idx), text.substring(ls, le).trim(), idx)
                        )
                        perFile++
                        idx = text.lowercase().indexOf(needle, idx + needle.length)
                    }
                }
                out
            }
            searchResults = hits
            searching = false
        }
    }

    fun openSearchHit(hit: SearchHit) {
        val already = buffers.indexOfFirst { it.node.uri == hit.node.uri }
        if (already >= 0) {
            setActive(already)
            goToOffset(hit.offset)
            return
        }
        val ws = workspace ?: return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ws.readText(hit.node) }.getOrNull() } ?: return@launch
            val buffer = Buffer(hit.node, text, FileContext.of(hit.node.path, hit.node.name))
            buffer.value = TextFieldValue(text, TextRange(hit.offset.coerceIn(0, text.length)))
            buffers.add(buffer)
            setActive(buffers.size - 1)
            analyzeNow(buffer)
        }
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(update: (EditorSettings) -> EditorSettings) {
        settings = update(settings)
        persistSettings()
        active?.let { scheduleAnalysis(it) }
    }

    private fun loadSettings() {
        settings = EditorSettings(
            fontSize = prefs.getInt("fontSize", 14),
            softWrap = prefs.getBoolean("softWrap", true),
            autoIndent = prefs.getBoolean("autoIndent", true),
            autoClose = prefs.getBoolean("autoClose", true),
            useTabs = prefs.getBoolean("useTabs", true),
            indentWidth = prefs.getInt("indentWidth", 4),
            autoComplete = prefs.getBoolean("autoComplete", true),
            showDiagnostics = prefs.getBoolean("showDiagnostics", true),
            showLineNumbers = prefs.getBoolean("showLineNumbers", true),
            themeMode = prefs.getInt("themeMode", EditorSettings.THEME_SYSTEM),
        )
    }

    private fun persistSettings() {
        prefs.edit()
            .putInt("fontSize", settings.fontSize)
            .putBoolean("softWrap", settings.softWrap)
            .putBoolean("autoIndent", settings.autoIndent)
            .putBoolean("autoClose", settings.autoClose)
            .putBoolean("useTabs", settings.useTabs)
            .putInt("indentWidth", settings.indentWidth)
            .putBoolean("autoComplete", settings.autoComplete)
            .putBoolean("showDiagnostics", settings.showDiagnostics)
            .putBoolean("showLineNumbers", settings.showLineNumbers)
            .putInt("themeMode", settings.themeMode)
            .apply()
    }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
        const val ANALYSIS_DEBOUNCE_MS = 180L
        const val MAX_SEARCH_HITS = 300
        const val MAX_HITS_PER_FILE = 20
    }
}
