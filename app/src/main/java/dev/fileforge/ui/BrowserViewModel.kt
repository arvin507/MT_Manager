package dev.fileforge.ui

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.fileforge.apk.ApkInspector
import dev.fileforge.core.ArchiveBrowser
import dev.fileforge.core.ArchiveFormats
import dev.fileforge.core.ArchiveOps
import dev.fileforge.core.Bookmarks
import dev.fileforge.core.FileOps
import dev.fileforge.core.FilePreview
import dev.fileforge.core.FileSearch
import dev.fileforge.core.FsEntry
import dev.fileforge.core.SearchResult
import dev.fileforge.core.SortMode
import dev.fileforge.core.ZipPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PaneId { A, B }

data class PaneState(
    val path: String,
    val entries: List<FsEntry> = emptyList(),
    val selection: Set<String> = emptySet(),
    val sort: SortMode = SortMode.NAME,
    val showHidden: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
)

data class SearchState(
    val query: String,
    val root: String,
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val previewDir: File = File(app.cacheDir, "preview")

    private val rootPath: String =
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"

    var paneA by mutableStateOf(PaneState(rootPath))
        private set
    var paneB by mutableStateOf(PaneState(rootPath))
        private set
    var active by mutableStateOf(PaneId.A)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var report by mutableStateOf<ApkInspector.ApkReport?>(null)
        private set
    var reportLoading by mutableStateOf(false)
        private set
    var viewer by mutableStateOf<ViewerData?>(null)
        private set
    var searchState by mutableStateOf<SearchState?>(null)
        private set
    var bookmarks by mutableStateOf<List<String>>(emptyList())
        private set
    var apps by mutableStateOf<List<dev.fileforge.core.AppInfo>?>(null)
        private set
    var appsLoading by mutableStateOf(false)
        private set
    var showSystemApps by mutableStateOf(false)
        private set
    var showApps by mutableStateOf(false)
        private set

    /** 最近一次提取的结果，非空时 AppsScreen 弹「提取完成」对话框。 */
    data class ExtractResult(val path: String, val isDir: Boolean, val summary: String)
    var extracted by mutableStateOf<ExtractResult?>(null)
        private set

    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchCancel = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        bookmarks = Bookmarks.load(app)
        refresh(PaneId.A)
        refresh(PaneId.B)
    }

    fun pane(id: PaneId): PaneState = if (id == PaneId.A) paneA else paneB

    fun activate(id: PaneId) {
        active = id
    }

    private fun update(id: PaneId, transform: (PaneState) -> PaneState) {
        if (id == PaneId.A) paneA = transform(paneA) else paneB = transform(paneB)
    }

    private fun other(id: PaneId): PaneId = if (id == PaneId.A) PaneId.B else PaneId.A

    private fun inZip(id: PaneId): Boolean = ZipPaths.isZipPath(pane(id).path)

    fun refresh(id: PaneId) {
        val state = pane(id)
        update(id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (ZipPaths.isZipPath(state.path)) {
                        ArchiveBrowser.list(
                            archive = File(ZipPaths.archivePath(state.path)),
                            innerDir = ZipPaths.inner(state.path)
                        )
                    } else {
                        FileOps.list(File(state.path), state.showHidden, state.sort)
                    }
                }
            }
            update(id) { current ->
                result.fold(
                    onSuccess = { list -> current.copy(entries = list, loading = false) },
                    onFailure = { e -> current.copy(loading = false, error = e.message ?: "读取失败") }
                )
            }
        }
    }

    fun open(id: PaneId, entry: FsEntry) {
        active = id
        val insideZip = inZip(id)
        when {
            entry.isDir -> goTo(id, entry.path)

            !insideZip && entry.name.endsWith(".apk", ignoreCase = true) -> inspectApk(entry.path)

            !insideZip && ArchiveFormats.sniff(File(entry.path)) != null ->
                goTo(id, ZipPaths.encode(File(entry.path), ""))

            FilePreview.kindOf(entry.name) != FilePreview.Kind.BINARY -> openViewer(id, entry)

            else -> toggleSelect(id, entry)
        }
    }

    fun goTo(id: PaneId, path: String) {
        update(id) { it.copy(path = path, selection = emptySet()) }
        refresh(id)
    }

    fun up(id: PaneId) {
        val state = pane(id)
        if (ZipPaths.isZipPath(state.path)) {
            val archive = File(ZipPaths.archivePath(state.path))
            val inner = ZipPaths.inner(state.path)
            if (inner.isEmpty()) {
                val parent = archive.parentFile ?: return
                goTo(id, parent.absolutePath)
            } else {
                goTo(id, ZipPaths.encode(archive, inner.substringBeforeLast('/', "")))
            }
            return
        }
        val parent = File(state.path).parentFile ?: return
        goTo(id, parent.absolutePath)
    }

    fun toggleSelect(id: PaneId, entry: FsEntry) {
        active = id
        update(id) {
            val selection =
                if (it.selection.contains(entry.path)) it.selection - entry.path
                else it.selection + entry.path
            it.copy(selection = selection)
        }
    }

    fun selectAll(id: PaneId) {
        active = id
        update(id) { it.copy(selection = it.entries.map { e -> e.path }.toSet()) }
    }

    fun clearSelection(id: PaneId) {
        update(id) { it.copy(selection = emptySet()) }
    }

    fun setSort(id: PaneId, mode: SortMode) {
        update(id) { it.copy(sort = mode) }
        refresh(id)
    }

    fun toggleHidden(id: PaneId) {
        update(id) { it.copy(showHidden = !it.showHidden) }
        refresh(id)
    }

    fun createFolder(id: PaneId, name: String) = runOp(id) {
        require(!inZip(id)) { "压缩包内只读，无法新建文件夹" }
        FileOps.mkdir(File(pane(id).path), name)
        "已创建 $name"
    }

    fun rename(id: PaneId, entry: FsEntry, newName: String) = runOp(id) {
        require(!inZip(id)) { "压缩包内只读，无法重命名" }
        FileOps.rename(File(entry.path), newName)
        "已重命名为 $newName"
    }

    fun deleteSelected(id: PaneId) = runOp(id) {
        require(!inZip(id)) { "压缩包内只读，无法删除" }
        val targets = pane(id).selection.map { File(it) }
        require(targets.isNotEmpty()) { "请先选中要删除的项目" }
        targets.forEach { FileOps.delete(it) }
        "已删除 ${targets.size} 项"
    }

    fun copySelected(id: PaneId) = runOp(id) {
        val sources = pane(id).selection
        require(sources.isNotEmpty()) { "请先选中要复制的项目" }
        val destPath = pane(other(id)).path
        require(!ZipPaths.isZipPath(destPath)) { "目标栏也在压缩包内，请先切换到普通目录" }

        if (inZip(id)) {
            val archive = File(ZipPaths.archivePath(pane(id).path))
            val wanted = sources.map { ZipPaths.inner(it) }
            val count = ArchiveBrowser.extract(archive, wanted, File(destPath))
            "已解压 $count 个文件到 ${File(destPath).name}"
        } else {
            val destDir = File(destPath)
            sources.forEach { FileOps.copy(File(it), destDir) }
            "已复制 ${sources.size} 项到 ${destDir.name}"
        }
    }

    fun moveSelected(id: PaneId) = runOp(id) {
        require(!inZip(id)) { "压缩包内只读，无法移动" }
        val sources = pane(id).selection.map { File(it) }
        require(sources.isNotEmpty()) { "请先选中要移动的项目" }
        val destPath = pane(other(id)).path
        require(!ZipPaths.isZipPath(destPath)) { "目标栏也在压缩包内，请先切换到普通目录" }
        val destDir = File(destPath)
        sources.forEach { FileOps.move(it, destDir) }
        "已移动 ${sources.size} 项到 ${destDir.name}"
    }

    fun compressSelected(id: PaneId) = runOp(id) {
        require(!inZip(id)) { "压缩包内只读，无法再压缩" }
        val items = pane(id).selection.map { File(it) }
        require(items.isNotEmpty()) { "请先选中要压缩的项目" }
        val baseDir = File(pane(id).path)
        val name = if (items.size == 1) items.first().name + ".zip" else "archive.zip"
        val out = FileOps.uniqueTarget(baseDir, name)
        val count = ArchiveOps.compress(items, baseDir, out)
        "已压缩 $count 个文件 → ${out.name}"
    }

    fun extractSelected(id: PaneId) = runOp(id) {
        require(!inZip(id)) { "已在压缩包内：选中条目后用「复制」即可解压" }
        val archives = pane(id).selection.map { File(it) }
            .filter { ArchiveFormats.sniff(it) != null }
        require(archives.isNotEmpty()) { "请先选中压缩包文件（zip/7z/rar）" }
        var total = 0
        val baseDir = File(pane(id).path)
        archives.forEach { zip ->
            val dest = FileOps.uniqueTarget(baseDir, zip.nameWithoutExtension)
            total += ArchiveOps.extract(zip, dest)
        }
        "已解压 $total 个文件"
    }

    private fun runOp(id: PaneId, block: () -> String) {
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            message = result.fold({ it }, { it.message ?: "操作失败" })
            update(id) { it.copy(selection = emptySet()) }
            refresh(PaneId.A)
            refresh(PaneId.B)
        }
    }

    fun consumeMessage() {
        message = null
    }

    fun inspectApk(path: String) {
        reportLoading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ApkInspector.inspect(File(path)) }
            }
            reportLoading = false
            report = result.getOrNull()
            result.exceptionOrNull()?.let { message = it.message ?: "APK 解析失败" }
        }
    }

    fun closeReport() {
        report = null
    }

    fun openViewer(id: PaneId, entry: FsEntry) {
        viewer = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file: File = if (ZipPaths.isZipPath(entry.path)) {
                        val archive = File(ZipPaths.archivePath(entry.path))
                        FilePreview.extractToCache(archive, ZipPaths.inner(entry.path), previewDir)
                    } else {
                        File(entry.path)
                    }
                    when (FilePreview.kindOf(entry.name)) {
                        FilePreview.Kind.TEXT -> {
                            val (content, truncated) = FilePreview.readText(file)
                            ViewerData.Text(file.absolutePath, entry.name, content, truncated)
                        }

                        FilePreview.Kind.IMAGE ->
                            ViewerData.Image(file.absolutePath, entry.name, decodeImage(file))

                        else -> {
                            val (bytes, truncated, total) = FilePreview.readHex(file)
                            ViewerData.Hex(
                                file.absolutePath, entry.name,
                                FilePreview.hexLines(bytes), truncated, total
                            )
                        }
                    }
                }
            }
            viewer = result.getOrNull()
            result.exceptionOrNull()?.let { message = it.message ?: "无法打开文件" }
        }
    }

    /** 溢出菜单入口：对单个选中项强制用十六进制查看。 */
    fun openHexViewer(id: PaneId) {
        val path = pane(id).selection.firstOrNull() ?: return
        val entry = pane(id).entries.firstOrNull { it.path == path } ?: return
        viewer = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file: File = if (ZipPaths.isZipPath(path)) {
                        val archive = File(ZipPaths.archivePath(path))
                        FilePreview.extractToCache(archive, ZipPaths.inner(path), previewDir)
                    } else {
                        File(path)
                    }
                    val (bytes, truncated, total) = FilePreview.readHex(file)
                    ViewerData.Hex(file.absolutePath, entry.name, FilePreview.hexLines(bytes), truncated, total)
                }
            }
            viewer = result.getOrNull()
            result.exceptionOrNull()?.let { message = it.message ?: "无法打开文件" }
        }
    }

    private fun decodeImage(file: File): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return bitmap.asImageBitmap()
    }

    fun closeViewer() {
        viewer = null
    }

    /* ---------- 文件搜索 ---------- */

    fun openSearch() {
        val root = pane(active).path
        if (ZipPaths.isZipPath(root)) {
            message = "压缩包内不支持搜索"
            return
        }
        searchCancel.set(true)
        searchJob?.cancel()
        searchState = SearchState("", root)
    }

    fun startSearch(query: String) {
        searchCancel.set(true)
        searchJob?.cancel()
        val root = pane(active).path
        if (query.isBlank() || ZipPaths.isZipPath(root)) {
            if (query.isBlank()) searchState = searchState?.copy(query = "", searching = false, results = emptyList())
            return
        }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        searchCancel = cancel
        searchState = SearchState(query, root, searching = true)
        searchJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    FileSearch.search(File(root), query, pane(active).showHidden, cancel)
                }
            }
            if (cancel.get()) return@launch
            searchState = SearchState(query, root, results.getOrDefault(emptyList()))
        }
    }

    fun closeSearch() {
        searchCancel.set(true)
        searchJob?.cancel()
        searchState = null
    }

    /* ---------- 书签 ---------- */

    fun addBookmark() {
        val path = pane(active).path.trimEnd('/')
        if (path.isEmpty() || ZipPaths.isZipPath(path) || path in bookmarks) return
        bookmarks = bookmarks + path
        Bookmarks.save(getApplication(), bookmarks)
        message = "已加入书签"
    }

    fun removeBookmark(path: String) {
        bookmarks = bookmarks - path
        Bookmarks.save(getApplication(), bookmarks)
    }

    fun goBookmark(path: String) {
        closeSearch()
        goTo(active, path)
    }

    /* ---------- 应用管理：提取安装包 ---------- */

    fun openApps() {
        showApps = true
        if (apps == null) reloadApps()
    }

    fun closeApps() {
        showApps = false
    }

    fun reloadApps() {
        appsLoading = true
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) {
                runCatching {
                    dev.fileforge.core.AppRepository.load(getApplication(), showSystemApps)
                }.getOrDefault(emptyList())
            }
            appsLoading = false
        }
    }

    fun toggleSystemApps() {
        showSystemApps = !showSystemApps
        reloadApps()
    }

    /**
     * 提取安装包到 Download：
     * 纯 base.apk → 单文件「应用名_v版本.apk」；
     * 含 split APK → 文件夹「应用名_v版本/」（base + 所有 split）。
     */
    fun extractApp(target: dev.fileforge.core.AppInfo) {
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val download = Environment
                        .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    download.mkdirs()
                    val stem = "${target.safeLabel}_v${target.versionName}"

                    if (target.splitPaths.isEmpty()) {
                        val out = FileOps.copyNamed(File(target.apkPath), download, "$stem.apk")
                        ExtractResult(out.absolutePath, isDir = false, out.name)
                    } else {
                        val dir = FileOps.uniqueTarget(download, stem)
                        dir.mkdirs()
                        FileOps.copyNamed(File(target.apkPath), dir, "base.apk")
                        target.splitPaths.forEachIndexed { i, split ->
                            val name = File(split).name.ifBlank { "split_$i.apk" }
                            FileOps.copyNamed(File(split), dir, name)
                        }
                        ExtractResult(
                            dir.absolutePath, isDir = true,
                            summary = "$stem/（${target.splitPaths.size + 1} 个 APK）"
                        )
                    }
                }
            }
            busy = false
            result.fold(
                onSuccess = { extracted = it },
                onFailure = { message = "提取失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    /** 关闭提取完成对话框。 */
    fun dismissExtracted() {
        extracted = null
    }

    /** 跳转到提取产物位置：目录直接进入，文件进入所在目录并选中高亮。 */
    fun locateExtracted() {
        val dest = extracted ?: return
        extracted = null
        closeApps()
        if (dest.isDir) {
            goTo(active, dest.path)
        } else {
            val file = File(dest.path)
            goTo(active, file.parent ?: return)
            update(active) { it.copy(selection = setOf(file.absolutePath)) }
        }
    }

    fun openAppInfo(target: dev.fileforge.core.AppInfo) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${target.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }
    }
}
