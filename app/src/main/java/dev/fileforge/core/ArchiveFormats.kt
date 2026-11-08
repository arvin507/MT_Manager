package dev.fileforge.core

import java.io.File
import java.io.FileInputStream

/** 压缩包格式识别：只认魔数，扩展名仅供参考。 */
object ArchiveFormats {

    enum class Type(val label: String) {
        ZIP("zip"), SEVEN_Z("7z"), RAR("rar")
    }

    private val ZIP_MAGIC: List<ByteArray> = listOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),  // PK.. 标准 zip
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),  // PK.. 空包
        byteArrayOf(0x50, 0x4B, 0x07, 0x08)   // PK.. 跨卷
    )
    private val SEVEN_Z_MAGIC = byteArrayOf(
        0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C
    )  // 37 7A BC AF 27 1C
    private val RAR_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21)  // Rar!

    fun sniff(file: File): Type? {
        if (!file.isFile || file.length() < 6) return null
        val head = ByteArray(8)
        var read = 0
        FileInputStream(file).use { input ->
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        return sniff(head, read)
    }

    internal fun sniff(head: ByteArray, len: Int): Type? {
        if (len >= 4 && ZIP_MAGIC.any { m -> head.startsWith(m) }) return Type.ZIP
        if (len >= SEVEN_Z_MAGIC.size && head.startsWith(SEVEN_Z_MAGIC)) return Type.SEVEN_Z
        if (len >= 4 && head.startsWith(RAR_MAGIC)) return Type.RAR
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
