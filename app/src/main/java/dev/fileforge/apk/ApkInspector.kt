package dev.fileforge.apk

import dev.fileforge.core.ArchiveEntry
import dev.fileforge.ndk.NativeBridge
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

object ApkInspector {

    data class Group(val name: String, val count: Int, val size: Long)

    /** 签名校验结果：schemes 为命中的方案列表，verified 为 apksig 完整校验结论。 */
    data class SignatureInfo(val schemes: String, val verified: Boolean, val note: String? = null)

    data class ApkReport(
        val path: String,
        val name: String,
        val totalSize: Long,
        val entryCount: Int,
        val groups: List<Group>,
        val manifest: AxmlParser.ManifestInfo?,
        val dexes: List<DexParser.DexSummary>,
        val signature: SignatureInfo
    ) {
        val classCount: Int get() = dexes.sumOf { it.classCount }
        val methodCount: Int get() = dexes.sumOf { it.methodCount }
    }

    fun inspect(file: File): ApkReport {
        val t0 = System.nanoTime()
        val entries: List<ArchiveEntry>
        var manifest: AxmlParser.ManifestInfo? = null
        val dexes = ArrayList<DexParser.DexSummary>()
        var dexNanos = 0L

        ZipFile(file).use { zf ->
            entries = zf.entries().asSequence().map { e ->
                ArchiveEntry(e.name, e.size, e.compressedSize, e.isDirectory)
            }.toList()

            zf.getEntry("AndroidManifest.xml")?.let { entry ->
                if (entry.size in 1..(16L * 1024 * 1024)) {
                    zf.getInputStream(entry).use { input ->
                        manifest = AxmlParser.parse(input.readBytes())
                    }
                }
            }

            for (entry in zf.entries()) {
                if (!Regex("^classes\\d*\\.dex$").matches(entry.name)) continue
                if (entry.size <= 0 || entry.size > 96L * 1024 * 1024) continue
                zf.getInputStream(entry).use { input ->
                    val bytes = input.readBytes()
                    if (DexParser.isDex(bytes)) {
                        val t1 = System.nanoTime()
                        dexes.add(DexParser.parse(bytes))
                        dexNanos += System.nanoTime() - t1
                    }
                }
            }
        }

        android.util.Log.i(
            "FileForge",
            String.format(
                "inspect total=%dms dex=%dms native=%b classes=%d",
                (System.nanoTime() - t0) / 1_000_000,
                dexNanos / 1_000_000,
                NativeBridge.available(),
                dexes.sumOf { it.classCount }
            )
        )

        return ApkReport(
            path = file.absolutePath,
            name = file.name,
            totalSize = file.length(),
            entryCount = entries.size,
            groups = group(entries),
            manifest = manifest,
            dexes = dexes,
            signature = verifySignature(file, manifest?.minSdk)
        )
    }

    /**
     * 用 apksig 做真正的 v1/v2/v3 签名校验（验证证书链与摘要，不只是探测）。
     * apksig 无法处理的（损坏/超常 APK）退回魔数探测。
     */
    private fun verifySignature(file: File, minSdk: Int?): SignatureInfo {
        try {
            val builder = com.android.apksig.ApkVerifier.Builder(file)
            builder.setMinCheckedPlatformVersion(minSdk ?: 24)
            val result = builder.build().verify()

            val schemes = buildList {
                if (result.isVerifiedUsingV1Scheme()) add("v1")
                if (result.isVerifiedUsingV2Scheme()) add("v2")
                if (result.isVerifiedUsingV3Scheme()) add("v3")
            }
            val err = result.errors.firstOrNull()?.toString()
                ?: result.warnings.firstOrNull()?.toString()
            return SignatureInfo(
                schemes = if (schemes.isEmpty()) "未签名或无法识别" else schemes.joinToString(" + "),
                verified = result.isVerified,
                note = err
            )
        } catch (e: Exception) {
            // 退化为魔数探测，只标注方案存在与否
            return SignatureInfo(
                schemes = detectSignature(file),
                verified = false,
                note = "apksig 校验未完成：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun group(entries: List<ArchiveEntry>): List<Group> {
        val map = LinkedHashMap<String, LongArray>()
        for (e in entries) {
            val top = if (e.name.contains('/')) e.name.substringBefore('/') else "根级文件"
            val acc = map.getOrPut(top) { LongArray(2) }
            acc[0] += 1
            acc[1] += e.size
        }
        return map.map { (k, v) -> Group(k, v[0].toInt(), v[1]) }
            .sortedByDescending { it.size }
    }

    private fun detectSignature(file: File): String {
        val schemes = ArrayList<String>()

        ZipFile(file).use { zf ->
            val hasV1 = zf.entries().asSequence().any {
                it.name.startsWith("META-INF/") &&
                    (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC"))
            }
            if (hasV1) schemes.add("v1")
        }

        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val length = minOf(file.length(), 2L * 1024 * 1024)
        if (length > magic.size) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - length)
                val buf = ByteArray(length.toInt())
                raf.readFully(buf)
                val found = if (NativeBridge.available()) {
                    runCatching { NativeBridge.indexOf(buf, magic) }.getOrDefault(-1)
                } else {
                    indexOf(buf, magic)
                }
                if (found >= 0) schemes.add("v2/v3")
            }
        }

        return if (schemes.isEmpty()) "未签名或无法识别" else schemes.joinToString(" + ")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
