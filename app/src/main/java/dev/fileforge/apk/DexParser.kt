package dev.fileforge.apk

import dev.fileforge.ndk.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEX 头解析器：读出字符串/类型/方法/类定义表，统计每个类的字段与方法数量。
 * 只做只读展示，不做反编译与回编。
 *
 * 双路径：native 库可用时走 [NativeBridge.scanDex] 批量扫描（类表以单条文本承载，
 * 搜索与展示只物化需要的行）；否则回退纯 Kotlin 解析并完整物化 [classes]。
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
        /** 物化的类表；native 路径下访问时才从 raw 构造，平时用 [search] 避免全量分配。 */
        val classes: List<DexClass> = emptyList(),
        /** native 批量文本，null 表示 classes 已完整物化。 */
        internal val raw: String? = null
    ) {
        private val materialized: List<DexClass> by lazy {
            raw?.let { parseRaw(it).second } ?: classes
        }

        /** 按描述符子串搜索（不区分大小写）；点分包名自动转成描述符的斜杠形式。 */
        fun search(query: String, limit: Int = 400): List<DexClass> {
            val raw = query.trim()
            if (raw.isEmpty()) return firstClasses(limit)
            // "bin.mt.plus.ui.X" 与 "bin/mt/plus/ui/X" 应等价；前导 L 与结尾 ; 可有可无
            val q = raw.removePrefix("L").removeSuffix(";").replace('.', '/')
            return match(q, limit)
        }

        private fun firstClasses(limit: Int): List<DexClass> {
            val text = raw
            if (text != null) {
                val out = ArrayList<DexClass>(minOf(limit, 256))
                for (line in text.lineSequence()) {
                    if (out.size >= limit) break
                    if (line.isEmpty() || line[0] == '#') continue
                    parseRawLine(line)?.let { out.add(it) }
                }
                return out
            }
            return materialized.take(limit)
        }

        private fun match(q: String, limit: Int): List<DexClass> {
            val text = raw
            if (text != null) {
                val out = ArrayList<DexClass>(minOf(limit, 256))
                for (line in text.lineSequence()) {
                    if (out.size >= limit) break
                    if (line.isEmpty() || line[0] == '#') continue
                    if (line.contains(q, ignoreCase = true)) {
                        parseRawLine(line)?.let { out.add(it) }
                    }
                }
                return out
            }
            return materialized.asSequence()
                .filter { it.descriptor.contains(q, ignoreCase = true) }
                .take(limit)
                .toList()
        }

        /** 真正物化全部类表（旧调用方兼容；大 DEX 上代价高）。 */
        fun allClasses(): List<DexClass> = materialized
    }

    fun isDex(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    fun parse(bytes: ByteArray): DexSummary {
        if (NativeBridge.available()) {
            val fast = runCatching { NativeBridge.scanDex(bytes) }.getOrNull()
            if (fast != null) return fromNative(fast, bytes)
        }
        return parseKotlin(bytes)
    }

    /** native 文本 → 摘要。首行 "#strings\ttypes\tmethodIds\tclassDefs\ttotalMethods"。 */
    private fun fromNative(text: String, bytes: ByteArray): DexSummary {
        val head = text.lineSequence().firstOrNull { it.startsWith("#") }
            ?: throw IllegalStateException("native 输出缺头部")
        val p = head.removePrefix("#").split('\t')
        require(p.size >= 5) { "native 输出头部不完整" }
        val (strings, types, methodIds, classDefs, totalMethods) = p
        return DexSummary(
            version = if (bytes.size > 7) String(bytes, 4, 3) else "?",
            stringCount = strings.toInt(),
            typeCount = types.toInt(),
            methodCount = maxOf(totalMethods.toInt(), methodIds.toInt()),
            classCount = classDefs.toInt(),
            classes = emptyList(),
            raw = text
        )
    }

    private fun parseRaw(text: String): Pair<Int, List<DexClass>> {
        val out = ArrayList<DexClass>(1024)
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line[0] == '#') continue
            parseRawLine(line)?.let { out.add(it) }
        }
        return out.size to out
    }

    private fun parseRawLine(line: String): DexClass? {
        val f = line.split('\t')
        if (f.size < 4) return null
        return DexClass(
            descriptor = f[0],
            superDescriptor = f[1].takeUnless { it == "-" },
            methodCount = f[2].toIntOrNull() ?: 0,
            fieldCount = f[3].toIntOrNull() ?: 0
        )
    }

    private fun parseKotlin(bytes: ByteArray): DexSummary {
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
