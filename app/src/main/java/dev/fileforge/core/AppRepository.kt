package dev.fileforge.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/**
 * 已安装应用信息：用于「应用管理 → 提取安装包」。
 * 图标在加载时一次性转为 Bitmap，列表滚动零额外开销。
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,          // base.apk
    val splitPaths: List<String>, // split APKs（App Bundle 安装的应用才有）
    val sizeBytes: Long,          // base + splits 总大小
    val isSystem: Boolean,
    val icon: Bitmap?
) {
    /** 提取文件/目录的安全名称（去掉文件系统非法字符）。 */
    val safeLabel: String = label.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { packageName }
}

object AppRepository {

    fun load(context: Context, includeSystem: Boolean): List<AppInfo> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val packages = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        val apps = packages.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val source = app.sourceDir ?: return@mapNotNull null
            val splits = app.splitSourceDirs?.toList().orEmpty().filter { it.isNotBlank() }

            var size = 0L
            runCatching { size = java.io.File(source).length() }
            for (s in splits) runCatching { size += java.io.File(s).length() }

            AppInfo(
                label = pm.getApplicationLabel(app).toString(),
                packageName = info.packageName,
                versionName = info.versionName ?: "-",
                versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
                else @Suppress("DEPRECATION") info.versionCode.toLong(),
                apkPath = source,
                splitPaths = splits,
                sizeBytes = size,
                isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                icon = toBitmap(pm.getApplicationIcon(app))
            )
        }

        val filtered = if (includeSystem) apps else apps.filter { !it.isSystem }
        return filtered.sortedWith(compareBy<AppInfo> { it.label.lowercase() }.thenBy { it.packageName })
    }

    private fun toBitmap(drawable: Drawable): Bitmap? = runCatching {
        when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> drawable.toBitmap()
        }
    }.getOrNull()
}
