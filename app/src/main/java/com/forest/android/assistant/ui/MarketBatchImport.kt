package com.forest.android.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forest.android.assistant.core.market.MarketRelease
import com.forest.android.assistant.core.market.MarketImportProgress
import com.forest.android.assistant.R
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp

/** 确认前固定本次导入的名称与覆盖范围，避免同批脚本使用重复名称。 */
internal data class MarketBatchItem(val release: MarketRelease, val taskName: String, val overwrite: Boolean)

internal data class MarketBatchProgress(val index: Int, val total: Int, val title: String,
    val current: MarketImportProgress = MarketImportProgress("检查脚本信息"),
    val completed: List<MarketBatchResult> = emptyList(), val items: List<MarketBatchItem> = emptyList())

/** 每项独立记录结果，失败不会撤销已经成功导入的任务。 */
internal data class MarketBatchResult(
    val item: MarketBatchItem,
    val taskName: String? = null,
    val error: String? = null
)

@Composable
internal fun MarketBatchConfirmDialog(items: List<MarketBatchItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val overwriteCount = items.count { it.overwrite }
    DetailPage("确认导入", "脚本市场 · ${items.size} 个脚本", onDismiss,
        footer = { PrimaryAction("确认导入（${items.size}）", onClick = onConfirm) }) {
        MetricStrip(listOf("${items.size}" to "所选脚本", "${(items.sumOf { it.release.sizeBytes } + 1023) / 1024} KB" to "下载大小", "$overwriteCount" to "覆盖任务"))
        if (overwriteCount > 0) InfoNotice("其中 $overwriteCount 个已导入任务将覆盖脚本、素材和配置。本机运行历史会保留。", warning = true)
        SectionLabel("导入清单")
        SectionCard {
            items.forEach { item -> DesignRow(item.taskName, if (item.overwrite) "覆盖已有任务" else "新建任务", R.drawable.ic_code,
                trailing = { StatusBadge(if (item.overwrite) "覆盖" else "新建", warning = item.overwrite) }, onClick = {}) }
        }
        InfoNotice("逐项下载、校验并导入。某项失败后会继续处理其他脚本。")
    }
}

@Composable
internal fun MarketBatchProgressDialog(progress: MarketBatchProgress) {
    DetailPage("正在导入", "脚本市场 · ${progress.index}/${progress.total}", onBack = {}) {
        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator()
            Text("正在准备你的任务", fontSize = 20.sp, modifier = Modifier.padding(top = 22.dp))
            Text("下载、校验并导入，请稍候。", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        ContentBlock("整体进度 · 已处理 ${progress.index - 1}/${progress.total}") {
            LinearProgressIndicator(progress = { (progress.index - 1).toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
        }
        SectionCard {
            progress.items.forEachIndexed { index, item ->
                val result = progress.completed.getOrNull(index)
                val label = when { result != null -> if (result.taskName != null) "已完成" else "失败"
                    index == progress.index - 1 -> progress.current.phase; else -> "等待导入" }
                DesignRow(item.taskName, result?.error ?: label, R.drawable.ic_code,
                    trailing = { StatusBadge(label, error = result != null && result.taskName == null) }, onClick = {})
            }
        }
        if (progress.current.totalBytes > 0) ContentBlock(progress.title) {
            Text("${progress.current.phase} · ${progress.current.receivedBytes / 1024} / ${(progress.current.totalBytes + 1023) / 1024} KB", fontSize = 12.sp)
            LinearProgressIndicator(progress = { (progress.current.receivedBytes.toFloat() / progress.current.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        InfoNotice("请等待当前导入完成。单个脚本失败后会继续导入其余脚本。")
    }
}

@Composable
internal fun MarketBatchResultDialog(results: List<MarketBatchResult>, onDismiss: () -> Unit, onRetry: () -> Unit,
    onOpenTask: (String) -> Unit = {}) {
    val failed = results.count { it.taskName == null }
    DetailPage("导入结果", "脚本市场", onDismiss, footer = { PrimaryAction("返回脚本市场", onClick = onDismiss) }) {
        EmptyState(if (failed > 0) "${if (failed == results.size) "这次导入未完成" else "部分任务已导入"}" else "任务已准备就绪",
            "成功 ${results.size - failed} 个，失败 $failed 个。", if (failed > 0) R.drawable.ic_tool_log else R.drawable.ic_check)
        SectionCard {
            results.forEach { result -> DesignRow(result.taskName ?: result.item.release.title,
                result.error ?: if (result.item.overwrite) "已覆盖本地任务" else "已创建新任务", R.drawable.ic_code,
                trailing = { StatusBadge(if (result.taskName != null) "成功" else "失败", error = result.taskName == null) },
                onClick = { result.taskName?.let(onOpenTask) }) }
        }
        if (failed > 0) SecondaryAction("重试失败项（$failed）", onClick = onRetry)
    }
}
