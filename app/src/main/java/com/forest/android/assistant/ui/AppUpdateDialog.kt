package com.forest.android.assistant.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
internal fun AppUpdateDialog(
    state: AppUpdateState,
    model: AppUpdateModel,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val offer = state.download ?: state.offers.firstOrNull()
    val phase = state.downloadPhase
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用更新") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("当前版本 ${state.currentVersion}")
                if (state.official == true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("自动检查更新")
                        Switch(checked = state.automatic, onCheckedChange = model::setAutomatic)
                    }
                    offer?.let {
                        Text("${it.manifest.tag} · ${Formatter.formatShortFileSize(context, it.manifest.sizeBytes)}",
                            style = MaterialTheme.typography.titleMedium)
                        if (it.manifest.notes.isNotBlank()) Text(it.manifest.notes)
                    }
                    state.notice?.let { Text(it) }
                    when (phase) {
                        DownloadPhase.IDLE -> {
                            TextButton(onClick = { model.check(manual = true) }, enabled = !state.checking) {
                                Text(if (state.checking) "正在检查…" else "检查更新")
                            }
                            if (!state.checking) state.offers.getOrNull(1)?.let { alternate ->
                                TextButton(onClick = { model.startDownload(alternate.source) }) {
                                    Text("从 ${alternate.source.label} 下载")
                                }
                            }
                        }
                        DownloadPhase.STARTING -> Text("正在准备下载…")
                        DownloadPhase.DOWNLOADING -> {
                            val source = checkNotNull(state.download)
                            val progress = (state.receivedBytes.toDouble() / source.manifest.sizeBytes).toFloat().coerceIn(0f, 1f)
                            Text(if (state.paused) "${source.source.label} 下载已暂停，等待重试"
                                else "正在从 ${source.source.label} 下载 ${(progress * 100).toInt()}%")
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            if (state.canSwitchSource) {
                                TextButton(onClick = model::switchDownloadSource) { Text("切换下载源") }
                            }
                            TextButton(onClick = model::cancelDownload) { Text("取消下载") }
                        }
                        DownloadPhase.VERIFYING -> Text("正在校验安装包…")
                        DownloadPhase.READY -> {
                            Text("已从 ${offer?.source?.label.orEmpty()} 下载，可以安装。")
                            TextButton(onClick = model::cancelDownload) { Text("删除安装包") }
                        }
                    }
                } else if (state.official == false) {
                    Text("应用内更新适用于正式签名版本。")
                }
            }
        },
        confirmButton = {
            if (phase == DownloadPhase.READY) {
                TextButton(onClick = onInstall) { Text("立即安装") }
            } else if (phase == DownloadPhase.IDLE && offer != null && !state.checking) {
                TextButton(onClick = { model.startDownload(offer.source) }) { Text("从 ${offer.source.label} 下载") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (offer != null) "稍后" else "关闭") }
        },
    )
}
