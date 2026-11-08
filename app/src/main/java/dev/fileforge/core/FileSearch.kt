package dev.fileforge.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class SearchResult(
    val name: String,
    val path: String,
    /** 相对搜索根目录的路径，如 "Download/notes.txt"。 */
    val relative: String,
    val isDir: Boolean,
    val size: Long
)

/**
 * 递归文件名搜索：忽略大小写的子串匹配。
 * 深度限制 + canonical 路径去重防符号链接循环；结果达上限即停，支持外部取消。
 */
object FileSearch {

    const val MAX_RESULTS = 200
    private const val MAX_DEPTH = 14

    fun search(
        root: File,
        query: String,
        includeHidden: Boolean,
        cancel: AtomicBoolean? = null
    ): List<SearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || !root.isDirectory) return emptyList()
        val visited = HashSet<String>()
        val out = ArrayList<SearchResult>(64)
        walk(root, root, q, includeHidden, visited, out, cancel, 0)
        return out
    }

    private fun walk(
        root: File,
        dir: File,
        q: String,
        hidden: Boolean,
        visited: MutableSet<String>,
        out: MutableList<SearchResult>,
        cancel: AtomicBoolean?,
        depth: Int
    ) {
        if (out.size >= MAX_RESULTS || depth > MAX_DEPTH) return
        if (cancel?.get() == true) return
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonical)) return
        val children = dir.listFiles() ?: return

        for (f in children) {
            if (cancel?.get() == true || out.size >= MAX_RESULTS) return
            if (!hidden && f.name.startsWith(".")) continue
            if (q in f.name.lowercase()) {
                val rel = runCatching { f.toRelativeString(root) }.getOrDefault(f.name)
                out += SearchResult(f.name, f.absolutePath, rel, f.isDirectory, f.length())
            }
            if (f.isDirectory) walk(root, f, q, hidden, visited, out, cancel, depth + 1)
        }
    }
}
