package dev.fileforge.core

import dev.fileforge.ndk.NativeBridge
import java.io.File
import java.io.IOException

object FileOps {

    /** 超过这个大小才走原生零拷贝，小文件走流式更快（省掉一次 syscall 往返） */
    private const val LARGE_FILE_THRESHOLD = 4L * 1024 * 1024

    fun list(dir: File, showHidden: Boolean, sort: SortMode): List<FsEntry> {
        val children = dir.listFiles() ?: return emptyList()
        val entries = children.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                FsEntry(
                    name = f.name,
                    path = f.absolutePath,
                    isDir = f.isDirectory,
                    size = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified()
                )
            }
            .toList()

        val comparator = when (sort) {
            SortMode.NAME -> compareBy<FsEntry> { !it.isDir }.thenBy { it.name.lowercase() }
            SortMode.SIZE -> compareBy<FsEntry> { !it.isDir }.thenByDescending { it.size }
            SortMode.TIME -> compareBy<FsEntry> { !it.isDir }.thenByDescending { it.lastModified }
        }
        return entries.sortedWith(comparator)
    }

    fun uniqueTarget(destDir: File, name: String): File {
        var target = File(destDir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (target.exists()) {
            target = File(destDir, "$base ($i)$ext")
            i++
        }
        return target
    }

    fun copy(src: File, destDir: File): File {
        val target = uniqueTarget(destDir, src.name)
        copyTo(src, target)
        return target
    }

    /** 复制并改名为 [name]（目标冲突自动去重）。 */
    fun copyNamed(src: File, destDir: File, name: String): File {
        val target = uniqueTarget(destDir, name)
        copyTo(src, target)
        return target
    }

    private fun copyTo(src: File, target: File) {
        if (src.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) {
                throw IOException("无法创建目录：${target.absolutePath}")
            }
            val children = src.listFiles() ?: return
            for (child in children) {
                copyTo(child, File(target, child.name))
            }
        } else {
            target.parentFile?.mkdirs()
            val handled = copyLargeFile(src, target)
            if (!handled) {
                src.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
            target.setLastModified(src.lastModified())
        }
    }

    private fun copyLargeFile(src: File, target: File): Boolean {
        if (!NativeBridge.available()) return false
        if (src.length() < LARGE_FILE_THRESHOLD) return false
        val copied = runCatching { NativeBridge.copyFile(src.absolutePath, target.absolutePath) }
            .getOrDefault(-1L)
        return copied >= 0 && copied == src.length()
    }

    fun move(src: File, destDir: File): File {
        val target = uniqueTarget(destDir, src.name)
        if (src.renameTo(target)) return target
        copyTo(src, target)
        delete(src)
        return target
    }

    fun delete(target: File): Boolean {
        if (target.isDirectory) {
            target.listFiles()?.forEach { delete(it) }
        }
        return target.delete()
    }

    fun rename(src: File, newName: String): File {
        val name = newName.trim()
        require(name.isNotEmpty()) { "名称不能为空" }
        require(!name.contains('/')) { "名称不能包含 /" }
        val target = File(src.parentFile, name)
        if (target.exists() && target.absolutePath != src.absolutePath) {
            throw IOException("已存在同名文件")
        }
        if (!src.renameTo(target)) throw IOException("重命名失败")
        return target
    }

    fun mkdir(parent: File, name: String): File {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "名称不能为空" }
        val dir = File(parent, trimmed)
        if (dir.exists()) throw IOException("已存在同名文件")
        if (!dir.mkdirs()) throw IOException("创建目录失败")
        return dir
    }
}
