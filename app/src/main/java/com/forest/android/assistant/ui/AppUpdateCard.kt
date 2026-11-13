package com.forest.android.assistant.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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
    val statusStyle = MaterialTheme.typography.bodyMedium
    val statusHeight = with(LocalDensity.current) { statusStyle.lineHeight.toDp() }
    val update = state.availableUpdate
    val phase = state.downloadPhase
    val downloading = phase == DownloadPhase.DOWNLOADING
    val progress = if (downloading) {
        (state.receivedBytes.toDouble() / state.downloadSizeBytes.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
    } else 0f
    val status = when {
        state.official == false -> "应用内更新适用于正式签名版本。"
        state.checking -> "正在检查…"
        state.notice != null -> state.notice
        phase == DownloadPhase.STARTING -> "正在准备下载…"
        downloading && state.paused -> "下载已暂停，等待重试"
        downloading && state.incremental -> "正在下载增量包 ${(progress * 100).toInt()}%"
        downloading -> "正在从 GitHub 下载 ${(progress * 100).toInt()}%"
        phase == DownloadPhase.ASSEMBLING -> "正在合成安装包…"
        phase == DownloadPhase.VERIFYING -> "正在校验安装包…"
        phase == DownloadPhase.READY -> "下载完成，可以安装。"
        update != null -> "新版 ${update.tag} · ${Formatter.formatShortFileSize(context, update.sizeBytes)}"
        else -> ""
    }
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
            }
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(statusHeight), contentAlignment = Alignment.CenterStart) {
                    Text(status, style = statusStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (downloading) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (state.official == true) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (phase) {
                        DownloadPhase.IDLE -> {
                            TextButton(
                                onClick = { model.check(manual = true) },
                                modifier = Modifier.weight(1f, fill = false),
                                enabled = !state.checking,
                            ) {
                                Text("检查更新", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (update != null && !state.checking) {
                                TextButton(onClick = model::startDownload, modifier = Modifier.weight(1f, fill = false)) {
                                    Text(if (state.useFullPackage) "下载完整包" else "从 GitHub 下载",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        DownloadPhase.DOWNLOADING -> TextButton(onClick = model::cancelDownload) {
                            Text("取消下载", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DownloadPhase.READY -> {
                            Button(onClick = onInstall, modifier = Modifier.weight(1f, fill = false)) {
                                Text("立即安装", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = model::cancelDownload, modifier = Modifier.weight(1f, fill = false)) {
                                Text("删除安装包", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        DownloadPhase.STARTING, DownloadPhase.ASSEMBLING, DownloadPhase.VERIFYING -> Unit
                    }
                }
            }
        }
    }
}
