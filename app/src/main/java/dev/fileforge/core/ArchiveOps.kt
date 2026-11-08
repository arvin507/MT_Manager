package dev.fileforge.core

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ArchiveEntry(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val isDir: Boolean
)

object ArchiveOps {

    fun list(archive: File): List<ArchiveEntry> = ZipFile(archive).use { zf ->
        zf.entries().asSequence().map { e ->
            ArchiveEntry(
                name = e.name,
                size = e.size,
                compressedSize = e.compressedSize,
                isDir = e.isDirectory
            )
        }.toList()
    }

    fun extract(archive: File, destDir: File): Int {
        val root = destDir.canonicalFile
        if (!root.exists()) root.mkdirs()

        // zip 走原有随机访问逻辑；7z/rar 复用 ArchiveBrowser 的全量前缀解压（rar 需单遍流式）
        return when (ArchiveFormats.sniff(archive)) {
            ArchiveFormats.Type.SEVEN_Z, ArchiveFormats.Type.RAR -> {
                val prefixes = ArchiveBrowser.list(archive, "").map { it.name }
                ArchiveBrowser.extract(archive, prefixes, root)
            }

            else -> {
                var count = 0
                ZipFile(archive).use { zf ->
                    for (entry in zf.entries()) {
                        val out = File(root, entry.name).canonicalFile
                        if (out != root && !out.path.startsWith(root.path + File.separator)) {
                            throw IOException("压缩包内存在非法路径：${entry.name}")
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                        }
                        count++
                    }
                }
                count
            }
        }
    }

    fun compress(items: List<File>, baseDir: File, outFile: File): Int {
        if (outFile.exists()) outFile.delete()
        val rootPath = baseDir.absolutePath.trimEnd(File.separatorChar)
        var count = 0

        ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
            for (item in items) {
                if (item.isDirectory) {
                    item.walkTopDown().forEach { child ->
                        if (child == item) return@forEach
                        val rel = child.absolutePath
                            .removePrefix(rootPath)
                            .trimStart(File.separatorChar)
                            .replace(File.separatorChar, '/')
                        if (child.isDirectory) {
                            zos.putNextEntry(ZipEntry("$rel/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(rel))
                            child.inputStream().use { it.copyTo(zos, 64 * 1024) }
                            zos.closeEntry()
                            count++
                        }
                    }
                } else {
                    val rel = item.absolutePath
                        .removePrefix(rootPath)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(rel))
                    item.inputStream().use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                    count++
                }
            }
        }
        return count
    }
}
