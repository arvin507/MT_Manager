package dev.fileforge.core

data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long
)

enum class SortMode(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    TIME("时间")
}
