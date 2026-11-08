package dev.fileforge.core

import java.io.File
import java.util.zip.ZipFile

/** zip 虚拟路径的编解码：zip://<archive 绝对路径>!/inner/path */
object ZipPaths {
    const val SCHEME = "zip://"
    const val SEPARATOR = "!/"

    fun encode(archive: File, inner: String): String {
        val cleaned = inner.trim('/').trimEnd('/')
        return if (cleaned.isEmpty()) SCHEME + archive.absolutePath
        else SCHEME + archive.absolutePath + SEPARATOR + cleaned
    }

    fun isZipPath(path: String): Boolean = path.startsWith(SCHEME)

    fun archivePath(path: String): String =
        path.removePrefix(SCHEME).substringBefore(SEPARATOR)

    fun inner(path: String): String =
        path.removePrefix(SCHEME).substringAfter(SEPARATOR, "").trim('/')
}

/** 只读浏览 zip 内部：目录聚合、选择性解压。 */
object ArchiveBrowser {

    private class Child {
        var isDir = false
        var size = 0L
        var time = 0L
    }

    /** 列出 zip 内 innerDir 的直接子项；目录大小为其全部后代之和。 */
    fun list(archive: File, innerDir: String): List<FsEntry> {
        val inner = innerDir.trim('/')
        val prefix = if (inner.isEmpty()) "" else "$inner/"
        val children = LinkedHashMap<String, Child>()

        ZipFile(archive).use { zf ->
            for (entry in zf.entries()) {
                val name = entry.name
                if (!name.startsWith(prefix) || name == prefix) continue
                val rest = name.removePrefix(prefix)
                if (rest.isEmpty()) continue

                val slash = rest.indexOf('/')
                if (slash == -1) {
                    val child = children.getOrPut(rest) { Child() }
                    if (entry.isDirectory) {
                        child.isDir = true
                    } else {
                        child.size += entry.size
                        if (entry.time > child.time) child.time = entry.time
                    }
                } else {
                    children.getOrPut(rest.substring(0, slash)) { Child() }.isDir = true
                }
            }
        }

        return children.map { (name, child) ->
            FsEntry(
                name = name,
                path = ZipPaths.encode(archive, if (inner.isEmpty()) name else "$inner/$name"),
                isDir = child.isDir,
                size = child.size,
                lastModified = if (child.time > 0) child.time else archive.lastModified()
            )
        }.sortedWith(compareBy<FsEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }

    /**
     * 解压选中的若干顶层项（zip 内相对路径，文件或目录）到 destDir。
     * 顶层目标先做去重命名，避免覆盖；返回写出的文件数。
     */
    fun extract(archive: File, wanted: List<String>, destDir: File): Int {
        val prefixes = wanted.map { it.trim('/').trimEnd('/') }.filter { it.isNotEmpty() }
        if (prefixes.isEmpty()) return 0

        val targets = LinkedHashMap<String, File>()
        for (prefix in prefixes) {
            targets[prefix] = FileOps.uniqueTarget(destDir, prefix.substringAfterLast('/'))
        }

        var count = 0

        ZipFile(archive).use { zf ->
            for (entry in zf.entries()) {
                val key = entry.name.trimEnd('/')
                val hit = prefixes.firstOrNull { p -> key == p || key.startsWith("$p/") } ?: continue
                val base = targets[hit] ?: continue
                val rel = if (key == hit) "" else key.removePrefix("$hit/")
                val out = if (rel.isEmpty()) base else File(base, rel)
                val baseCanonical = base.canonicalFile
                val canonical = out.canonicalFile
                if (canonical != baseCanonical &&
                    !canonical.path.startsWith(baseCanonical.path + File.separator)
                ) {
                    continue
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                    continue
                }
                canonical.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    canonical.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                count++
            }
        }
        return count
    }
}
