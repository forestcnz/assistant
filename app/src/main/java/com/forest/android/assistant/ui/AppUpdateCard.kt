package com.forest.android.assistant.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forest.android.assistant.core.update.AppUpdateModel
import com.forest.android.assistant.core.update.AppUpdateState
import com.forest.android.assistant.core.update.DownloadPhase

@Composable
internal fun AppUpdateCard(
    state: AppUpdateState,
    model: AppUpdateModel,
    onInstall: () -> Unit,
) {
    val context = LocalContext.current
    val update = state.availableUpdate
    val phase = state.downloadPhase
    WorkCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text(
                    "当前版本 ${state.currentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.official == true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动检查更新", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.automatic, onCheckedChange = model::setAutomatic)
                }
                if (update != null) {
                    Text(
                        "新版 ${update.tag} · ${Formatter.formatShortFileSize(context, update.sizeBytes)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when (phase) {
                    DownloadPhase.IDLE -> FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = { model.check(manual = true) }, enabled = !state.checking) {
                            Text(if (state.checking) "正在检查…" else "检查更新")
                        }
                        if (update != null && !state.checking) {
                            TextButton(onClick = model::startDownload) { Text("从 GitHub 下载") }
                        }
                    }
                    DownloadPhase.STARTING -> Text("正在准备下载…")
                    DownloadPhase.DOWNLOADING -> {
                        val size = checkNotNull(update).sizeBytes
                        val progress = (state.receivedBytes.toDouble() / size).toFloat().coerceIn(0f, 1f)
                        Text(if (state.paused) "下载已暂停，等待重试" else "正在从 GitHub 下载 ${(progress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = model::cancelDownload) { Text("取消下载") }
                    }
                    DownloadPhase.VERIFYING -> Text("正在校验安装包…")
                    DownloadPhase.READY -> {
                        Text("下载完成，可以安装。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onInstall) { Text("立即安装") }
                            TextButton(onClick = model::cancelDownload) { Text("删除安装包") }
                        }
                    }
                }
            } else if (state.official == false) {
                Text("应用内更新适用于正式签名版本。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
