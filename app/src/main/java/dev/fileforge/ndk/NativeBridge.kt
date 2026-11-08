package dev.fileforge.ndk

/**
 * 原生加速桥接。库加载失败时 available() 返回 false，调用方自动回退纯 Kotlin 实现。
 */
object NativeBridge {

    private val loaded: Boolean = runCatching { System.loadLibrary("fileforge") }.isSuccess

    fun available(): Boolean = loaded

    /** 内核零拷贝拷贝，返回字节数，负数表示失败。 */
    external fun copyFile(src: String, dst: String): Long

    /** 字节串查找，未找到返回 -1。 */
    external fun indexOf(haystack: ByteArray, needle: ByteArray): Int

    /**
     * 批量扫描 DEX，一次返回全部类表文本（避免 Java 侧数万对象分配）。
     * 失败或格式非法返回 null，调用方回退纯 Kotlin 解析。
     */
    external fun scanDex(dex: ByteArray): String?
}
