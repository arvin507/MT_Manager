package dev.fileforge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fileforge.core.FsEntry
import dev.fileforge.core.SearchResult
import dev.fileforge.core.SortMode
import dev.fileforge.core.ZipPaths
import dev.fileforge.util.formatSize
import dev.fileforge.util.formatTime
import java.io.File

sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data class Rename(val entry: FsEntry) : BrowserDialog
    data object Delete : BrowserDialog
}

@Composable
fun BrowserScreen(vm: BrowserViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val activePane = vm.pane(vm.active)

    LaunchedEffect(vm.message) {
        val msg = vm.message
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.consumeMessage()
        }
    }

    // 搜索态下系统返回键先关闭搜索，不退出应用
    androidx.activity.compose.BackHandler(enabled = vm.searchState != null) {
        vm.closeSearch()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val wide = maxWidth >= 720.dp

        Column(Modifier.fillMaxSize()) {
            AppHeader(
                pane = activePane,
                wide = wide,
                activePaneId = vm.active,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                bookmarks = vm.bookmarks,
                searchState = vm.searchState,
                onUp = { vm.up(vm.active) },
                onRefresh = { vm.refresh(vm.active) },
                onToggleHidden = { vm.toggleHidden(vm.active) },
                onSort = { vm.setSort(vm.active, it) },
                onSwitchPane = { vm.activate(if (vm.active == PaneId.A) PaneId.B else PaneId.A) },
                onGo = { vm.goTo(vm.active, it) },
                onSearchOpen = { vm.openSearch() },
                onSearchQuery = { vm.startSearch(it) },
                onSearchClose = { vm.closeSearch() },
                onBookmarkAdd = { vm.addBookmark() },
                onBookmarkGo = { vm.goBookmark(it) },
                onBookmarkRemove = { vm.removeBookmark(it) },
                onOpenApps = { vm.openApps() }
            )

            Box(Modifier.weight(1f)) {
                val search = vm.searchState
                if (search != null) {
                    SearchResultsPane(
                        state = search,
                        onClick = { result ->
                            if (result.isDir) vm.closeSearch()
                            vm.open(
                                vm.active,
                                FsEntry(result.name, result.path, result.isDir, result.size, 0L)
                            )
                        }
                    )
                } else if (wide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilePane(
                            state = vm.paneA,
                            active = vm.active == PaneId.A,
                            onClick = { vm.open(PaneId.A, it) },
                            onLongClick = { vm.toggleSelect(PaneId.A, it) },
                            modifier = Modifier.weight(1f)
                        )
                        FilePane(
                            state = vm.paneB,
                            active = vm.active == PaneId.B,
                            onClick = { vm.open(PaneId.B, it) },
                            onLongClick = { vm.toggleSelect(PaneId.B, it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        FilePane(
                            state = activePane,
                            active = true,
                            onClick = { vm.open(vm.active, it) },
                            onLongClick = { vm.toggleSelect(vm.active, it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            BottomBar(
                insideZip = ZipPaths.isZipPath(activePane.path),
                selectionCount = activePane.selection.size,
                onHex = { vm.openHexViewer(vm.active) },
                onSelectAll = { vm.selectAll(vm.active) },
                onClear = { vm.clearSelection(vm.active) },
                onNewFolder = { dialog = BrowserDialog.NewFolder },
                onCopy = { vm.copySelected(vm.active) },
                onMove = { vm.moveSelected(vm.active) },
                onDelete = { dialog = BrowserDialog.Delete },
                onRename = {
                    val path = activePane.selection.firstOrNull()
                    val entry = activePane.entries.firstOrNull { it.path == path }
                    if (entry != null) dialog = BrowserDialog.Rename(entry)
                },
                onCompress = { vm.compressSelected(vm.active) },
                onExtract = { vm.extractSelected(vm.active) }
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 76.dp)
        )
    }

    when (val current = dialog) {
        is BrowserDialog.NewFolder -> TextInputDialog(
            title = "新建文件夹",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                vm.createFolder(vm.active, name)
                dialog = null
            }
        )

        is BrowserDialog.Rename -> TextInputDialog(
            title = "重命名",
            initial = current.entry.name,
            confirmLabel = "重命名",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                vm.rename(vm.active, current.entry, name)
                dialog = null
            }
        )

        is BrowserDialog.Delete -> ConfirmDialog(
            title = "删除 ${activePane.selection.size} 项",
            text = "此操作不可撤销。",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.deleteSelected(vm.active)
                dialog = null
            }
        )

        null -> Unit
    }
}

@Composable
private fun AppHeader(
    pane: PaneState,
    wide: Boolean,
    activePaneId: PaneId,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    bookmarks: List<String>,
    searchState: SearchState?,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onSort: (SortMode) -> Unit,
    onSwitchPane: () -> Unit,
    onGo: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearchClose: () -> Unit,
    onBookmarkAdd: () -> Unit,
    onBookmarkGo: (String) -> Unit,
    onBookmarkRemove: (String) -> Unit,
    onOpenApps: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.statusBarsPadding()) {
            if (searchState != null) {
                // 搜索态：[关闭] [搜索框]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onSearchClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "关闭搜索",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    SearchField(
                        initial = searchState.query,
                        onQuery = onSearchQuery,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上级目录",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = folderLabel(pane.path),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${pane.entries.size} 项" +
                                if (pane.selection.isNotEmpty()) " · 已选 ${pane.selection.size}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!wide) {
                        PaneToggle(activePaneId, onSwitchPane)
                    }
                    BookmarksMenu(
                        bookmarks = bookmarks,
                        currentPath = pane.path,
                        onAdd = {
                            onBookmarkAdd()
                            onMenuOpenChange(false)
                        },
                        onGo = {
                            onBookmarkGo(it)
                            onMenuOpenChange(false)
                        },
                        onRemove = onBookmarkRemove
                    )
                    IconButton(onClick = onSearchOpen) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { onMenuOpenChange(true) }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { onMenuOpenChange(false) }
                        ) {
                            Text(
                                text = "排序",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            for (mode in SortMode.entries) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (pane.sort == mode) "· 按${mode.label}" else "按${mode.label}")
                                    },
                                    onClick = {
                                        onSort(mode)
                                        onMenuOpenChange(false)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                onClick = {
                                    onRefresh()
                                    onMenuOpenChange(false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (pane.showHidden) "不显示隐藏文件" else "显示隐藏文件") },
                                onClick = {
                                    onToggleHidden()
                                    onMenuOpenChange(false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("应用管理（提取安装包）") },
                                onClick = {
                                    onMenuOpenChange(false)
                                    onOpenApps()
                                }
                            )
                        }
                    }
                }
                Breadcrumbs(pane.path, onGo)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 搜索框：进入搜索态后显示，输入即搜。 */
@Composable
private fun SearchField(initial: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onQuery(it)
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        placeholder = { Text("搜索当前目录", style = MaterialTheme.typography.bodySmall) },
        modifier = modifier.padding(vertical = 4.dp)
    )
}

/** 书签下拉菜单：点击跳转、单条删除、底部“添加当前目录”。 */
@Composable
private fun BookmarksMenu(
    bookmarks: List<String>,
    currentPath: String,
    onAdd: () -> Unit,
    onGo: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "书签",
                tint = if (bookmarks.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (bookmarks.isEmpty()) {
                Text(
                    text = "还没有书签",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            for (path in bookmarks) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(folderLabel(path), style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = { onRemove(path) }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "删除书签",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = { onGo(path) }
                )
            }
            if (bookmarks.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("把当前目录加入书签") },
                onClick = onAdd
            )
        }
    }
}

@Composable
private fun PaneToggle(active: PaneId, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .padding(end = 4.dp)
            .clickable { onToggle() }
    ) {
        Row(Modifier.padding(3.dp)) {
            PaneSegment("A", active == PaneId.A)
            PaneSegment("B", active == PaneId.B)
        }
    }
}

@Composable
private fun PaneSegment(label: String, active: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Breadcrumbs(path: String, onGo: (String) -> Unit) {
    val scrollState = rememberScrollState()
    val crumbs = remember(path) { buildCrumbs(path) }
    LaunchedEffect(path, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { index, (label, target) ->
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            Crumb(
                label = label,
                target = target,
                current = index == crumbs.lastIndex,
                onGo = onGo
            )
        }
    }
}

private fun buildCrumbs(path: String): List<Pair<String, String>> {
    if (ZipPaths.isZipPath(path)) {
        val archive = File(ZipPaths.archivePath(path))
        val inner = ZipPaths.inner(path)
        val list = buildCrumbs(archive.parent ?: "/").toMutableList()
        list.add(archive.name to ZipPaths.encode(archive, ""))
        var acc = ""
        for (part in inner.split('/')) {
            if (part.isEmpty()) continue
            acc = if (acc.isEmpty()) part else "$acc/$part"
            list.add(part to ZipPaths.encode(archive, acc))
        }
        return list
    }

    val storageRoot = runCatching {
        android.os.Environment.getExternalStorageDirectory()?.absolutePath
    }.getOrNull()?.trimEnd('/')

    if (storageRoot != null && (path == storageRoot || path.startsWith("$storageRoot/"))) {
        val list = mutableListOf("内部存储" to storageRoot)
        var acc = storageRoot
        for (part in path.removePrefix(storageRoot).trim('/').split('/')) {
            if (part.isEmpty()) continue
            acc = "$acc/$part"
            list.add(part to acc)
        }
        return list
    }

    val list = mutableListOf("根目录" to "/")
    var acc = ""
    for (part in path.trim('/').split('/')) {
        if (part.isEmpty()) continue
        acc = "$acc/$part"
        list.add(part to acc)
    }
    return list
}

@Composable
private fun Crumb(label: String, target: String, current: Boolean, onGo: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.clickable { onGo(target) }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (current) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun FilePane(
    state: PaneState,
    active: Boolean,
    onClick: (FsEntry) -> Unit,
    onLongClick: (FsEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            width = 1.dp,
            color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            if (state.entries.isEmpty() && !state.loading && state.error == null) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            selected = state.selection.contains(entry.path),
                            onClick = { onClick(entry) },
                            onLongClick = { onLongClick(entry) }
                        )
                    }
                }
            }
        }
    }
}

/** 搜索结果列表：相对路径定位 + 类型徽标复用。 */
@Composable
private fun SearchResultsPane(state: SearchState, onClick: (SearchResult) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        when {
            state.query.isBlank() && !state.searching -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "输入关键字，搜索当前目录内的文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            state.searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.results.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "没有匹配「${state.query}」的文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                Text(
                    text = "${state.results.size} 项（最多 200）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    items(state.results, key = { it.path }) { result ->
                        val entry = FsEntry(result.name, result.path, result.isDir, result.size, 0L)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onClick(result) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileBadge(entry)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = result.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = result.relative,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (result.isDir) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            } else {
                                Text(
                                    text = formatSize(result.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            FolderGlyph(MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "这个文件夹是空的",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FsEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "rowBackground"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileBadge(entry)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatTime(entry.lastModified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            selected -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )

            entry.isDir -> Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )

            else -> Text(
                text = formatSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class BadgeStyle(val background: Color, val foreground: Color, val label: String)

@Composable
private fun FileBadge(entry: FsEntry) {
    val scheme = MaterialTheme.colorScheme
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    val style = when {
        entry.isDir -> BadgeStyle(scheme.primaryContainer, scheme.onPrimaryContainer, "")
        ext == "apk" || ext == "apks" || ext == "xapk" ->
            BadgeStyle(Color(0xFFFFE0B2), Color(0xFF8A4B00), "APK")

        ext == "zip" || ext == "rar" || ext == "7z" || ext == "tar" || ext == "gz" ->
            BadgeStyle(Color(0xFFE4DBFF), Color(0xFF46368A), "ZIP")

        ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp" || ext == "gif" ->
            BadgeStyle(Color(0xFFFFD9E2), Color(0xFF8C2F4A), "IMG")

        ext == "mp4" || ext == "mkv" || ext == "mov" || ext == "mp3" || ext == "flac" || ext == "wav" ->
            BadgeStyle(Color(0xFFFFE1C7), Color(0xFF8A4A16), "AV")

        ext == "txt" || ext == "md" || ext == "json" || ext == "xml" || ext == "kt" ||
            ext == "java" || ext == "js" || ext == "html" || ext == "css" || ext == "py" || ext == "sh" ->
            BadgeStyle(Color(0xFFD7E7FF), Color(0xFF1D4E8F), "TXT")

        else -> BadgeStyle(
            scheme.surfaceContainerHigh,
            scheme.onSurfaceVariant,
            ext.take(3).uppercase().ifEmpty { "?" }
        )
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(style.background),
        contentAlignment = Alignment.Center
    ) {
        if (entry.isDir) {
            FolderGlyph(style.foreground)
        } else {
            Text(
                text = style.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp
                ),
                color = style.foreground
            )
        }
    }
}

@Composable
private fun FolderGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.06f, h * 0.80f)
            lineTo(w * 0.06f, h * 0.24f)
            lineTo(w * 0.40f, h * 0.24f)
            lineTo(w * 0.50f, h * 0.37f)
            lineTo(w * 0.94f, h * 0.37f)
            lineTo(w * 0.94f, h * 0.80f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
private fun BottomBar(
    insideZip: Boolean,
    selectionCount: Int,
    onHex: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onNewFolder: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (selectionCount == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (insideZip) "压缩包内只读 · 选中后「复制」即解压" else "长按文件可多选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (!insideZip) {
                        BarAction("新建文件夹", onNewFolder)
                    }
                    BarAction("全选", onSelectAll)
                }
            } else {
                var moreOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "取消选择",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "已选 $selectionCount",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    BarAction("复制", onCopy)
                    BarAction("移动", onMove)
                    BarAction("重命名", onRename)
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("压缩为 zip") },
                                onClick = {
                                    moreOpen = false
                                    onCompress()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("解压到当前目录") },
                                onClick = {
                                    moreOpen = false
                                    onExtract()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "删除",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    moreOpen = false
                                    onDelete()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("全选") },
                                onClick = {
                                    moreOpen = false
                                    onSelectAll()
                                }
                            )
                            if (selectionCount == 1) {
                                DropdownMenuItem(
                                    text = { Text("十六进制查看") },
                                    onClick = {
                                        moreOpen = false
                                        onHex()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                label = { Text("名称") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun folderLabel(path: String): String {
    if (ZipPaths.isZipPath(path)) {
        val inner = ZipPaths.inner(path)
        return if (inner.isEmpty()) File(ZipPaths.archivePath(path)).name
        else inner.substringAfterLast('/')
    }
    val storageRoot = android.os.Environment.getExternalStorageDirectory()?.absolutePath
    if (storageRoot != null && path.trimEnd('/') == storageRoot) return "内部存储"
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "根目录"
    val name = trimmed.substringAfterLast('/')
    return name.ifEmpty { "根目录" }
}
