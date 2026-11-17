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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.forest.android.assistant.R
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
internal fun AppUpdatePage(
    state: AppUpdateState,
    model: AppUpdateModel,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val statusStyle = MaterialTheme.typography.bodyMedium
    val statusHeight = with(LocalDensity.current) { statusStyle.lineHeight.toDp() }
    val update = state.availableUpdate
    val phase = state.downloadPhase
    val downloading = phase == DownloadPhase.DOWNLOADING
    val interrupted = phase == DownloadPhase.INTERRUPTED
    val showProgress = downloading || interrupted
    val progress = if (showProgress) {
        (state.receivedBytes.toDouble() / state.downloadSizeBytes.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
    } else 0f
    val status = when {
        state.official == false -> "应用内更新适用于正式签名版本。"
        state.checking -> "正在检查…"
        state.notice != null -> state.notice
        phase == DownloadPhase.STARTING -> "正在准备下载…"
        downloading && state.incremental -> "正在下载增量包 ${(progress * 100).toInt()}%"
        downloading -> "正在从 GitHub 下载 ${(progress * 100).toInt()}%"
        phase == DownloadPhase.ASSEMBLING -> "正在合成安装包…"
        phase == DownloadPhase.VERIFYING -> "正在校验安装包…"
        phase == DownloadPhase.READY -> "下载完成，可以安装。"
        update != null -> "新版 ${update.tag} · ${Formatter.formatShortFileSize(context, update.sizeBytes)}"
        else -> ""
    }
    // 更新状态从设置卡片提升为独立页面，操作区随当前阶段切换。
    DetailPage("应用更新", "保持轻快，持续进步", onBack, footer = {
        if (state.official == true && !state.checking) when (phase) {
            DownloadPhase.IDLE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryAction("检查更新") { model.check(manual = true) } }
                if (update != null) Box(Modifier.weight(1f)) { PrimaryAction("下载更新", icon = R.drawable.ic_import, onClick = model::startDownload) }
            }
            DownloadPhase.DOWNLOADING -> SecondaryAction("取消下载", onClick = model::cancelDownload)
            DownloadPhase.INTERRUPTED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryAction("下载完整包", onClick = model::downloadFullPackage) }
                Box(Modifier.weight(1f)) { PrimaryAction("立即重试", onClick = model::retryDownload) }
            }
            DownloadPhase.READY -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryAction("删除安装包", onClick = model::cancelDownload) }
                Box(Modifier.weight(1f)) { PrimaryAction("立即安装", onClick = onInstall) }
            }
            else -> Unit
        }
    }) {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(64.dp))
            Text("雕雕助手", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 15.dp, bottom = 7.dp))
            Text("当前版本 ${state.currentVersion}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动检查更新", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("及时发现正式版本更新", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                }
                Switch(checked = state.automatic, onCheckedChange = model::setAutomatic, enabled = state.official == true)
            }
        }
        ContentBlock(when {
            state.official == false -> "当前版本暂不支持应用内更新"
            state.checking -> "检查新版本"
            update != null -> "新版本 ${update.versionName}"
            else -> "版本状态"
        }) {
            Text(status.ifBlank { "点击检查更新，获取最新正式版本。" }, fontSize = 12.sp, lineHeight = 21.sp)
            if (state.checking || phase in listOf(DownloadPhase.STARTING, DownloadPhase.ASSEMBLING, DownloadPhase.VERIFYING)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (showProgress) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${Formatter.formatShortFileSize(context, state.receivedBytes)} / ${Formatter.formatShortFileSize(context, state.downloadSizeBytes)}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else update?.let { Text("完整包 ${Formatter.formatShortFileSize(context, it.sizeBytes)} · 优先下载增量包", fontSize = 11.sp) }
            if (phase != DownloadPhase.IDLE) {
                val stage = when (phase) { DownloadPhase.ASSEMBLING -> 1; DownloadPhase.VERIFYING -> 2; DownloadPhase.READY -> 3; else -> 0 }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("下载", "合成", "校验", "安装").forEachIndexed { index, label ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(if (index <= stage) AppPalette.PRIMARY else AppPalette.OUTLINE_VARIANT)))
                            Text(label, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp), color = Color(AppPalette.ON_SURFACE_VARIANT))
                        }
                    }
                }
            }
        }
        if (interrupted) InfoNotice("可以重新下载；如果增量更新不可用，可改用完整安装包。", warning = true)
        else InfoNotice(if (state.official == false) "应用内更新仅适用于正式签名版本。" else "下载后会校验安装包，准备完成后再交给系统安装器。")
    }
}
