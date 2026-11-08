package dev.fileforge.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEX 头解析器：读出字符串/类型/方法/类定义表，统计每个类的字段与方法数量。
 * 只做只读展示，不做反编译与回编。
 */
object DexParser {

    data class DexClass(
        val descriptor: String,
        val superDescriptor: String?,
        val methodCount: Int,
        val fieldCount: Int
    )

    data class DexSummary(
        val version: String,
        val stringCount: Int,
        val typeCount: Int,
        val methodCount: Int,
        val classCount: Int,
        val classes: List<DexClass>
    )

    fun isDex(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    fun parse(bytes: ByteArray): DexSummary {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = if (bytes.size > 7) String(bytes, 4, 3) else "?"

        val stringIdsSize = u32(buf, 56).toInt()
        val stringIdsOff = u32(buf, 60).toInt()
        val typeIdsSize = u32(buf, 64).toInt()
        val typeIdsOff = u32(buf, 68).toInt()
        val methodIdsSize = u32(buf, 88).toInt()
        val classDefsSize = u32(buf, 96).toInt()
        val classDefsOff = u32(buf, 100).toInt()

        val stringCache = arrayOfNulls<String>(stringIdsSize)

        fun stringAt(index: Int): String {
            if (index < 0 || index >= stringIdsSize) return ""
            stringCache[index]?.let { return it }
            val dataOff = u32(buf, stringIdsOff + index * 4).toInt()
            val cursor = IntArray(1)
            readUleb(buf, dataOff, cursor)
            val value = readMutf8(buf, cursor[0])
            stringCache[index] = value
            return value
        }

        fun typeAt(index: Int): String {
            if (index < 0 || index >= typeIdsSize) return ""
            return stringAt(u32(buf, typeIdsOff + index * 4).toInt())
        }

        val classes = ArrayList<DexClass>(minOf(classDefsSize, 8192))
        var totalMethods = 0
        var totalFields = 0

        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            if (base + 32 > bytes.size) break
            val classIdx = u32(buf, base).toInt()
            val superIdx = u32(buf, base + 8).toInt()
            val classDataOff = u32(buf, base + 24).toInt()

            val descriptor = typeAt(classIdx)
            val superDescriptor = if (superIdx == -1 || superIdx == 0xFFFFFFFF.toInt()) null else typeAt(superIdx)

            var methodCount = 0
            var fieldCount = 0
            if (classDataOff != 0 && classDataOff < bytes.size) {
                val cursor = IntArray(1)
                cursor[0] = classDataOff
                val staticFields = readUleb(buf, cursor[0], cursor)
                val instanceFields = readUleb(buf, cursor[0], cursor)
                val directMethods = readUleb(buf, cursor[0], cursor)
                val virtualMethods = readUleb(buf, cursor[0], cursor)

                repeat(staticFields + instanceFields) {
                    readUleb(buf, cursor[0], cursor)
                    readUleb(buf, cursor[0], cursor)
                }
                repeat(directMethods + virtualMethods) {
                    readUleb(buf, cursor[0], cursor)
                    readUleb(buf, cursor[0], cursor)
                    readUleb(buf, cursor[0], cursor)
                }

                fieldCount = staticFields + instanceFields
                methodCount = directMethods + virtualMethods
            }

            totalFields += fieldCount
            totalMethods += methodCount
            classes.add(DexClass(descriptor, superDescriptor, methodCount, fieldCount))
        }

        return DexSummary(
            version = version,
            stringCount = stringIdsSize,
            typeCount = typeIdsSize,
            methodCount = maxOf(totalMethods, methodIdsSize),
            classCount = classDefsSize,
            classes = classes
        )
    }

    private fun readUleb(buf: ByteBuffer, offset: Int, cursor: IntArray): Int {
        var pos = offset
        var result = 0
        var shift = 0
        while (pos < buf.capacity()) {
            val b = buf.get(pos).toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) break
        }
        cursor[0] = pos
        return result
    }

    private fun readMutf8(buf: ByteBuffer, start: Int): String {
        val sb = StringBuilder()
        var pos = start
        while (pos < buf.capacity()) {
            val a = buf.get(pos).toInt() and 0xFF
            when {
                a == 0 -> break
                a < 0x80 -> {
                    sb.append(a.toChar())
                    pos += 1
                }

                a and 0xE0 == 0xC0 -> {
                    val b = buf.get(pos + 1).toInt() and 0xFF
                    sb.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                    pos += 2
                }

                else -> {
                    val b = buf.get(pos + 1).toInt() and 0xFF
                    val c = buf.get(pos + 2).toInt() and 0xFF
                    sb.append((((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar())
                    pos += 3
                }
            }
        }
        return sb.toString()
    }

    private fun u32(buf: ByteBuffer, index: Int): Long = buf.getInt(index).toLong() and 0xFFFFFFFFL
}
