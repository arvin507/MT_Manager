import dev.fileforge.core.ArchiveBrowser
import dev.fileforge.core.ZipPaths
import java.io.File

fun main() {
    val zip = File("/tmp/sample.zip")

    val root = ArchiveBrowser.list(zip, "")
    println("根目录 ${root.size} 项: " + root.joinToString { "${it.name}${if (it.isDir) "/" else "(${it.size}B)"}" })

    val docs = ArchiveBrowser.list(zip, "docs")
    println("docs/ ${docs.size} 项: " + docs.joinToString { it.name })

    // 虚拟路径往返
    val vpath = ZipPaths.encode(zip, "docs/readme.txt")
    check(ZipPaths.isZipPath(vpath))
    check(ZipPaths.archivePath(vpath) == zip.absolutePath)
    check(ZipPaths.inner(vpath) == "docs/readme.txt")
    println("虚拟路径往返: OK -> $vpath")

    // 选择性解压：一个文件 + 一个目录
    val dest = File("/tmp/ziptest_out").apply { deleteRecursively(); mkdirs() }
    val n = ArchiveBrowser.extract(zip, listOf("docs/readme.txt", "photos"), dest)
    println("解压文件数: $n")
    val readme = File(dest, "readme.txt")
    check(readme.exists() && readme.readText() == "hello fileforge\n") { "readme 内容不符" }
    check(File(dest, "photos/a.png").readText() == "fakepng\n") { "photos 解压不符" }
    check(!File(dest, "top.txt").exists()) { "不应解压未选项" }

    // 同名去重：再解压一次应生成 readme (1).txt
    ArchiveBrowser.extract(zip, listOf("docs/readme.txt"), dest)
    check(File(dest, "readme (1).txt").exists()) { "去重命名失败" }
    println("选择性解压 + 去重: OK")
}
