package dev.fileforge.core

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** 按扩展名分流预览：文本 / 图片 / 十六进制。 */
object FilePreview {

    enum class Kind { TEXT, IMAGE, BINARY }

    const val TEXT_LIMIT = 512 * 1024
    const val HEX_LIMIT = 8 * 1024

    private val textExts = setOf(
        "txt", "md", "json", "xml", "kt", "java", "js", "ts", "html", "css",
        "py", "sh", "log", "csv", "yml", "yaml", "toml", "ini", "properties",
        "gradle", "pro", "c", "h", "cpp", "hpp", "m", "smali", "svg", "sql", "bat"
    )
    private val imageExts = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun kindOf(name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in textExts -> Kind.TEXT
            ext in imageExts -> Kind.IMAGE
            else -> Kind.BINARY
        }
    }

    /** 读文本；超过 limit 截断，第二个返回值表示是否被截断。 */
    fun readText(file: File, limit: Int = TEXT_LIMIT): Pair<String, Boolean> {
        val total = file.length()
        if (total <= limit) return file.readText() to false
        val buf = ByteArray(limit)
        var read = 0
        file.inputStream().use { input ->
            while (read < limit) {
                val r = input.read(buf, read, limit - read)
                if (r < 0) break
                read += r
            }
        }
        return String(buf, 0, read) to true
    }

    /** 读十六进制原始字节；返回 (字节, 是否截断, 文件总大小)。 */
    fun readHex(file: File, limit: Int = HEX_LIMIT): Triple<ByteArray, Boolean, Long> {
        val total = file.length()
        val len = minOf(total, limit.toLong()).toInt()
        val buf = ByteArray(len)
        var read = 0
        file.inputStream().use { input ->
            while (read < len) {
                val r = input.read(buf, read, len - read)
                if (r < 0) break
                read += r
            }
        }
        return Triple(buf, total > limit, total)
    }

    /** 16 字节一行：偏移 + 十六进制 + ASCII。 */
    fun hexLines(bytes: ByteArray): List<String> {
        val lines = ArrayList<String>(bytes.size / 16 + 1)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + 16, bytes.size)
            val sb = StringBuilder()
            sb.append(String.format("%08X  ", i))
            for (j in i until end) sb.append(String.format("%02X ", bytes[j]))
            repeat(16 - (end - i)) { sb.append("   ") }
            sb.append(" |")
            for (j in i until end) {
                val b = bytes[j].toInt() and 0xFF
                sb.append(if (b in 0x20..0x7E) b.toChar() else '·')
            }
            sb.append('|')
            lines.add(sb.toString())
            i = end
        }
        return lines
    }

    /** 把压缩包内的单个文件解到缓存目录用于预览（zip/7z/rar 通用）。 */
    fun extractToCache(archive: File, inner: String, cacheDir: File): File {
        cacheDir.mkdirs()
        val out = FileOps.uniqueTarget(cacheDir, inner.substringAfterLast('/'))
        when (ArchiveFormats.sniff(archive)) {
            ArchiveFormats.Type.SEVEN_Z -> org.apache.commons.compress.archivers.sevenz.SevenZFile(archive).use { sz ->
                val entry = sz.entries.firstOrNull { it.name == inner }
                    ?: throw IOException("压缩包内找不到 $inner")
                sz.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }

            ArchiveFormats.Type.RAR -> com.github.junrar.Archive(archive).use { rar ->
                val header = rar.fileHeaders.firstOrNull { it.fileName.replace('\\', '/') == inner }
                    ?: throw IOException("压缩包内找不到 $inner")
                rar.extractFile(header, out.outputStream())
            }

            else -> ZipFile(archive).use { zf ->
                val entry = zf.getEntry(inner) ?: throw IOException("压缩包内找不到 $inner")
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
        }
        return out
    }
}
