package dev.fileforge.core

import com.github.junrar.Archive as JunrarArchive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/** zip 虚拟路径的编解码：zip://<archive 绝对路径>!/inner/path（zip/jar/7z/rar 共用） */
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

    /** 列出压缩包内 innerDir 的直接子项；目录大小为其全部后代之和。zip/jar/7z/rar 共用。 */
    fun list(archive: File, innerDir: String): List<FsEntry> {
        val inner = innerDir.trim('/')
        val prefix = if (inner.isEmpty()) "" else "$inner/"
        val children = LinkedHashMap<String, Child>()

        when (ArchiveFormats.sniff(archive)) {
            ArchiveFormats.Type.SEVEN_Z -> listSevenZ(archive, prefix, children)
            ArchiveFormats.Type.RAR -> listRar(archive, prefix, children)
            else -> listZip(archive, prefix, children)
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

    private fun listZip(archive: File, prefix: String, children: LinkedHashMap<String, Child>) {
        ZipFile(archive).use { zf ->
            for (entry in zf.entries()) {
                collect(entry.name, entry.size, entry.time, entry.isDirectory, prefix, children)
            }
        }
    }

    private fun listSevenZ(archive: File, prefix: String, children: LinkedHashMap<String, Child>) {
        SevenZFile(archive).use { sz ->
            for (entry in sz.entries) {
                val time = runCatching { entry.lastModifiedDate.time }.getOrDefault(0L)
                collect(entry.name, entry.size, time, entry.isDirectory, prefix, children)
            }
        }
    }

    private fun listRar(archive: File, prefix: String, children: LinkedHashMap<String, Child>) {
        JunrarArchive(archive).use { rar ->
            for (h in rar.fileHeaders) {
                val time = runCatching { h.mTime.time }.getOrDefault(0L)
                collect(
                    h.fileName.replace('\\', '/'),
                    h.fullUnpackSize, time, h.isDirectory, prefix, children
                )
            }
        }
    }

    private fun collect(
        name: String, size: Long, time: Long, isDir: Boolean,
        prefix: String, children: LinkedHashMap<String, Child>
    ) {
        if (!name.startsWith(prefix) || name == prefix) return
        val rest = name.removePrefix(prefix)
        if (rest.isEmpty()) return

        val slash = rest.indexOf('/')
        if (slash == -1) {
            val child = children.getOrPut(rest) { Child() }
            if (isDir) {
                child.isDir = true
            } else {
                child.size += size
                if (time > child.time) child.time = time
            }
        } else {
            children.getOrPut(rest.substring(0, slash)) { Child() }.isDir = true
        }
    }

    /**
     * 解压选中的若干顶层项（压缩包内相对路径，文件或目录）到 destDir。
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

        when (ArchiveFormats.sniff(archive)) {
            ArchiveFormats.Type.SEVEN_Z -> {
                SevenZFile(archive).use { sz ->
                    for (entry in sz.entries) {
                        val key = entry.name.trimEnd('/')
                        val hit = prefixes.firstOrNull { p -> key == p || key.startsWith("$p/") } ?: continue
                        val base = targets[hit] ?: continue
                        val rel = if (key == hit) "" else key.removePrefix("$hit/")
                        if (entry.isDirectory) {
                            writeOut(base, rel) { it.mkdirs() }
                            continue
                        }
                        count += writeOut(base, rel) { out ->
                            sz.getInputStream(entry).use { input ->
                                out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                            }
                        }
                    }
                }
            }

            ArchiveFormats.Type.RAR -> {
                JunrarArchive(archive).use { rar ->
                    for (h in rar.fileHeaders) {
                        val key = h.fileName.replace('\\', '/').trimEnd('/')
                        val hit = prefixes.firstOrNull { p -> key == p || key.startsWith("$p/") } ?: continue
                        val base = targets[hit] ?: continue
                        val rel = if (key == hit) "" else key.removePrefix("$hit/")
                        if (h.isDirectory) {
                            writeOut(base, rel) { it.mkdirs() }
                            continue
                        }
                        count += writeOut(base, rel) { out ->
                            out.parentFile?.mkdirs()
                            rar.extractFile(h, out.outputStream())
                        }
                    }
                }
            }

            else -> {
                ZipFile(archive).use { zf ->
                    for (entry in zf.entries()) {
                        val key = entry.name.trimEnd('/')
                        val hit = prefixes.firstOrNull { p -> key == p || key.startsWith("$p/") } ?: continue
                        val base = targets[hit] ?: continue
                        val rel = if (key == hit) "" else key.removePrefix("$hit/")
                        if (entry.isDirectory) {
                            writeOut(base, rel) { it.mkdirs() }
                            continue
                        }
                        count += writeOut(base, rel) { out ->
                            zf.getInputStream(entry).use { input ->
                                out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                            }
                        }
                    }
                }
            }
        }
        return count
    }

    /** 目标路径越界（zip-slip 等）直接放弃；返回是否写出了文件。 */
    private inline fun writeOut(base: File, rel: String, write: (File) -> Unit): Int {
        val out = if (rel.isEmpty()) base else File(base, rel)
        val baseCanonical = base.canonicalFile
        val canonical = out.canonicalFile
        if (canonical != baseCanonical &&
            !canonical.path.startsWith(baseCanonical.path + File.separator)
        ) {
            return 0
        }
        write(canonical)
        return 1
    }
}
