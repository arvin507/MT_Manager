import dev.fileforge.apk.AxmlParser
import dev.fileforge.apk.DexParser
import java.io.File
import java.util.zip.ZipFile

fun main(args: Array<String>) {
    val apk = File(args[0])

    var t = System.currentTimeMillis()
    val entries = ZipFile(apk).use { zf ->
        zf.entries().asSequence().map { it.name to it.size }.toList()
    }
    println("条目数: ${entries.size}  (${System.currentTimeMillis() - t} ms)")

    t = System.currentTimeMillis()
    var manifest: AxmlParser.ManifestInfo? = null
    ZipFile(apk).use { zf ->
        zf.getEntry("AndroidManifest.xml")?.let { e ->
            zf.getInputStream(e).use { manifest = AxmlParser.parse(it.readBytes()) }
        }
    }
    println("Manifest 解析: ${System.currentTimeMillis() - t} ms")
    manifest?.let { m ->
        println("  包名=${m.packageName} 版本=${m.versionName}(${m.versionCode}) min=${m.minSdk} target=${m.targetSdk}")
        println("  权限=${m.permissions.size} 组件=${m.components.size} 导出=${m.exportedCount} debuggable=${m.debuggable}")
        println("  权限示例: ${m.permissions.take(3)}")
        println("  组件示例: ${m.components.take(2)}")
    } ?: println("  Manifest 解析失败")

    t = System.currentTimeMillis()
    var totalClasses = 0
    var totalMethods = 0
    val names = mutableListOf<String>()
    ZipFile(apk).use { zf ->
        for (e in zf.entries()) {
            if (!Regex("^classes\\d*\\.dex$").matches(e.name)) continue
            val bytes = zf.getInputStream(e).use { it.readBytes() }
            val d = DexParser.parse(bytes)
            totalClasses += d.classCount
            totalMethods += d.methodCount
            names += "${e.name}: ${d.classCount} 类 / ${d.methodCount} 方法"
        }
    }
    println("DEX 全解析: ${System.currentTimeMillis() - t} ms")
    names.forEach { println("  $it") }
    println("  合计 $totalClasses 类, $totalMethods 方法")
}
