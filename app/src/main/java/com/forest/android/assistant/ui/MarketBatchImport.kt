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

/** 确认前固定本次导入的名称与覆盖范围，避免同批脚本使用重复名称。 */
internal data class MarketBatchItem(val release: MarketRelease, val taskName: String, val overwrite: Boolean)

internal data class MarketBatchProgress(val index: Int, val total: Int, val title: String)

/** 每项独立记录结果，失败不会撤销已经成功导入的任务。 */
internal data class MarketBatchResult(
    val item: MarketBatchItem,
    val taskName: String? = null,
    val error: String? = null
)

@Composable
internal fun MarketBatchConfirmDialog(items: List<MarketBatchItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val overwriteCount = items.count { it.overwrite }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text("批量导入 ${items.size} 个脚本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("共约 ${(items.sumOf { it.release.sizeBytes } + 1023) / 1024} KB，将按列表顺序逐个导入。")
                if (overwriteCount > 0) {
                    Text("其中 $overwriteCount 个已导入任务将覆盖脚本、素材和配置。",
                        color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.forEach { item ->
                        Column {
                            Text(item.taskName, style = MaterialTheme.typography.titleSmall)
                            Text(if (item.overwrite) "覆盖已有任务" else "新建任务",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.overwrite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun MarketBatchProgressDialog(progress: MarketBatchProgress) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = {},
        title = { Text("正在批量导入（${progress.index}/${progress.total}）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(progress.title, style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { (progress.index - 1).toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth())
                Text("正在下载和校验，请稍候。单个脚本失败后会继续导入其余脚本。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {}
    )
}

@Composable
internal fun MarketBatchResultDialog(results: List<MarketBatchResult>, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val failed = results.count { it.taskName == null }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text("批量导入完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("成功 ${results.size - failed} 个，失败 $failed 个。")
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    results.forEachIndexed { index, result ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (result.taskName != null) "已导入 · ${result.taskName}" else "失败 · ${result.item.release.title}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (result.taskName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                            result.error?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error) }
                        }
                        if (index < results.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回列表") } },
        dismissButton = {
            if (failed > 0) TextButton(onClick = onRetry) { Text("重试失败项（$failed）") }
        }
    )
}
