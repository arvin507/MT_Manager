package dev.fileforge.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简二进制 AndroidManifest（AXML）解析器。
 * 只解析资源块里的字符串池与元素/属性，够读出包名、版本、权限、组件。
 */
object AxmlParser {

    data class ManifestInfo(
        val packageName: String? = null,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val minSdk: Int? = null,
        val targetSdk: Int? = null,
        val label: String? = null,
        val debuggable: Boolean = false,
        val allowBackup: Boolean? = null,
        val permissions: List<String> = emptyList(),
        val components: List<String> = emptyList(),
        val exportedCount: Int = 0
    )

    private const val TYPE_XML = 0x0003
    private const val TYPE_STRING_POOL = 0x0001
    private const val TYPE_START_ELEMENT = 0x0102

    private const val ATTR_TYPE_STRING = 0x03
    private const val ATTR_TYPE_INT_DEC = 0x10
    private const val ATTR_TYPE_INT_HEX = 0x11
    private const val ATTR_TYPE_BOOLEAN = 0x12

    fun parse(bytes: ByteArray): ManifestInfo? {
        if (bytes.size < 8) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (u16(buf, 0) != TYPE_XML) return null

        val headerSize = u16(buf, 2)
        val strings = ArrayList<String>()
        val permissions = LinkedHashSet<String>()
        val components = ArrayList<String>()

        var packageName: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var label: String? = null
        var debuggable = false
        var allowBackup: Boolean? = null
        var exported = 0

        var offset = headerSize
        while (offset + 8 <= bytes.size) {
            val chunkType = u16(buf, offset)
            val chunkHeader = u16(buf, offset + 2)
            val chunkSize = u32(buf, offset + 4).toInt()
            if (chunkSize <= 0 || offset + chunkSize > bytes.size) break

            when (chunkType) {
                TYPE_STRING_POOL -> readStringPool(buf, offset, chunkHeader, strings)

                TYPE_START_ELEMENT -> {
                    val nameIndex = u32(buf, offset + 20).toInt()
                    val tag = stringAt(strings, nameIndex) ?: ""
                    val attrStart = u16(buf, offset + 24)
                    val attrCount = u16(buf, offset + 28)
                    val attrBase = offset + chunkHeader + attrStart

                    val attrs = HashMap<String, Any?>(attrCount)
                    for (i in 0 until attrCount) {
                        val a = attrBase + i * 20
                        if (a + 20 > bytes.size) break
                        val attrName = stringAt(strings, u32(buf, a + 4).toInt()) ?: continue
                        val rawIndex = u32(buf, a + 8).toInt()
                        val dataType = bytes[a + 15].toInt() and 0xFF
                        val data = u32(buf, a + 16)
                        attrs[attrName] = when (dataType) {
                            ATTR_TYPE_STRING -> stringAt(strings, data.toInt())
                            ATTR_TYPE_INT_DEC, ATTR_TYPE_INT_HEX -> data
                            ATTR_TYPE_BOOLEAN -> data != 0L
                            else -> if (rawIndex >= 0) stringAt(strings, rawIndex) else data
                        }
                    }

                    when (tag) {
                        "manifest" -> {
                            packageName = attrs["package"] as? String
                            versionName = attrs["versionName"] as? String
                            versionCode = attrs["versionCode"] as? Long
                        }

                        "uses-sdk" -> {
                            minSdk = (attrs["minSdkVersion"] as? Long)?.toInt()
                            targetSdk = (attrs["targetSdkVersion"] as? Long)?.toInt()
                        }

                        "uses-permission", "uses-permission-sdk-23" -> {
                            (attrs["name"] as? String)?.let { permissions.add(it) }
                        }

                        "application" -> {
                            label = attrs["label"] as? String
                            debuggable = attrs["debuggable"] == true
                            allowBackup = attrs["allowBackup"] as? Boolean
                        }

                        "activity", "activity-alias", "service", "receiver", "provider" -> {
                            val name = attrs["name"] as? String
                            if (name != null) components.add("$tag  $name")
                            if (attrs["exported"] == true) exported++
                        }
                    }
                }
            }
            offset += chunkSize
        }

        return ManifestInfo(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            label = label,
            debuggable = debuggable,
            allowBackup = allowBackup,
            permissions = permissions.toList(),
            components = components,
            exportedCount = exported
        )
    }

    private fun readStringPool(
        buf: ByteBuffer,
        chunkOffset: Int,
        headerSize: Int,
        out: MutableList<String>
    ) {
        val count = u32(buf, chunkOffset + 8).toInt()
        val flags = u32(buf, chunkOffset + 16).toInt()
        val stringsStart = u32(buf, chunkOffset + 20).toInt()
        val isUtf8 = (flags and 0x100) != 0
        val offsetsBase = chunkOffset + headerSize

        for (i in 0 until count) {
            val rel = u32(buf, offsetsBase + i * 4).toInt()
            val pos = chunkOffset + stringsStart + rel
            out.add(if (isUtf8) readUtf8(buf, pos) else readUtf16(buf, pos))
        }
    }

    private fun readUtf8(buf: ByteBuffer, start: Int): String {
        var pos = start
        var charLen = buf.get(pos).toInt() and 0xFF
        if (charLen and 0x80 != 0) {
            charLen = ((charLen and 0x7F) shl 8) or (buf.get(pos + 1).toInt() and 0xFF)
            pos += 2
        } else {
            pos += 1
        }
        var byteLen = buf.get(pos).toInt() and 0xFF
        if (byteLen and 0x80 != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or (buf.get(pos + 1).toInt() and 0xFF)
            pos += 2
        } else {
            pos += 1
        }
        val data = ByteArray(byteLen)
        buf.get(pos, data)
        return String(data, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, start: Int): String {
        var pos = start
        var len = buf.getShort(pos).toInt() and 0xFFFF
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (buf.getShort(pos + 2).toInt() and 0xFFFF)
            pos += 4
        } else {
            pos += 2
        }
        val chars = CharArray(len)
        for (i in 0 until len) {
            chars[i] = buf.getChar(pos + i * 2)
        }
        return String(chars)
    }

    private fun stringAt(pool: List<String>, index: Int): String? {
        if (index < 0 || index >= pool.size) return null
        return pool[index]
    }

    private fun u16(buf: ByteBuffer, index: Int): Int = buf.getShort(index).toInt() and 0xFFFF

    private fun u32(buf: ByteBuffer, index: Int): Long = buf.getInt(index).toLong() and 0xFFFFFFFFL
}
