package dev.fileforge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fileforge.apk.ApkInspector
import dev.fileforge.apk.AxmlParser
import dev.fileforge.util.formatSize
import kotlin.math.roundToInt

private val TABS = listOf("概览", "内容", "清单", "DEX")

@Composable
fun ApkScreen(report: ApkInspector.ApkReport, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ApkHeader(report, onBack)
        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            TABS.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }
        when (tab) {
            0 -> OverviewTab(report)
            1 -> ContentTab(report)
            2 -> ManifestTab(report.manifest)
            else -> DexTab(report, query) { query = it }
        }
    }
}

@Composable
private fun ApkHeader(report: ApkInspector.ApkReport, onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 2.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color(0xFFFFE0B2),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "APK",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color(0xFF8A4B00)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = report.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        report.manifest?.packageName,
                        formatSize(report.totalSize)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OverviewTab(report: ApkInspector.ApkReport) {
    val manifest = report.manifest
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("DEX", "${report.dexes.size}", Modifier.weight(1f))
            StatCard("类", compactNumber(report.classCount), Modifier.weight(1f))
            StatCard("方法", compactNumber(report.methodCount), Modifier.weight(1f))
        }

        SectionTitle("基本信息")
        InfoCard {
            InfoRow("包名", manifest?.packageName ?: "读取失败")
            InfoRow("版本", listOfNotNull(manifest?.versionName, manifest?.versionCode?.let { "($it)" }).joinToString(" "))
            InfoRow("SDK", "min ${manifest?.minSdk ?: "?"} · target ${manifest?.targetSdk ?: "?"}")
            InfoRow(
                "组件",
                "${manifest?.components?.size ?: 0} 个 · 导出 ${manifest?.exportedCount ?: 0}"
            )
            InfoRow("权限", "${manifest?.permissions?.size ?: 0} 项")
        }

        SectionTitle("结构与签名")
        InfoCard {
            InfoRow("大小", formatSize(report.totalSize))
            InfoRow("条目", "${report.entryCount}")
            InfoRow("签名", signatureText(report.signature))
            InfoRow("调试", if (manifest?.debuggable == true) "是" else "否")
            InfoRow("备份", manifest?.allowBackup?.let { if (it) "允许" else "禁止" } ?: "-")
        }

        SectionTitle("路径")
        InfoCard {
            Text(
                text = report.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, top = 8.dp)
    )
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column { content() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

@Composable
private fun ContentTab(report: ApkInspector.ApkReport) {
    val totalSize = report.groups.sumOf { it.size }.coerceAtLeast(1L)
    val palette = listOf(
        Color(0xFF16624F),
        Color(0xFF7C5800),
        Color(0xFF4B635B),
        Color(0xFF8C2F4A),
        Color(0xFF1D4E8F),
        Color(0xFF46368A),
        Color(0xFF8A4A16)
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "共 ${formatSize(totalSize)} · ${report.entryCount} 个条目",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
            ) {
                report.groups.forEachIndexed { index, group ->
                    val weight = (group.size.toFloat() / totalSize).coerceAtLeast(0.004f)
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(palette[index % palette.size])
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            itemsIndexed(report.groups, key = { _, group -> group.name }) { index, group ->
                val share = group.size * 100.0 / totalSize
                val shareText = if (share < 1.0) "<1%" else "${share.roundToInt()}%"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(50))
                            .background(palette[index % palette.size])
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${group.count} 项 · ${formatSize(group.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "   $shareText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ManifestTab(manifest: AxmlParser.ManifestInfo?) {
    if (manifest == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "未能解析 AndroidManifest.xml",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { SectionTitle("权限（${manifest.permissions.size}）") }
        items(manifest.permissions) { permission ->
            Text(
                text = permission,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            )
        }
        item { SectionTitle("组件（${manifest.components.size}）") }
        items(manifest.components) { component ->
            Text(
                text = component.replace("  ", " · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun DexTab(report: ApkInspector.ApkReport, query: String, onQueryChange: (String) -> Unit) {
    // 类表按需物化：native 批量文本只在展示/搜索时取需要的行，不为 3 万个类建对象
    val matches = remember(report, query) {
        report.dexes.flatMap { it.search(query, 401) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            label = { Text("搜索类名") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
        Text(
            text = "${String.format("%,d", report.classCount)} 个类 · 显示 ${minOf(matches.size, 400)} 个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(matches.take(400)) { dexClass ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(
                        text = prettyDescriptor(dexClass.descriptor),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${dexClass.methodCount} 方法 · ${dexClass.fieldCount} 字段",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun signatureText(info: ApkInspector.SignatureInfo): String = when {
    info.verified -> "${info.schemes} ✓ 校验通过"
    info.note != null && info.schemes == "未签名或无法识别" -> "${info.schemes}${if (info.note.isNotEmpty()) "（${info.note}）" else ""}"
    else -> "${info.schemes} ⚠ ${info.note ?: "校验未通过"}"
}

private fun prettyDescriptor(descriptor: String): String =
    descriptor.trimEnd(';').removePrefix("L").replace('/', '.')

private fun compactNumber(value: Int): String = when {
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000f)
    value >= 10_000 -> String.format("%.1fK", value / 1000f)
    else -> "$value"
}
