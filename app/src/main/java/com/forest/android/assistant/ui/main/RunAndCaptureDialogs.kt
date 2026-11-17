package com.forest.android.assistant.ui.main

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import com.forest.android.assistant.MainActivity
import com.forest.android.assistant.editHistoryError
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*
import com.forest.android.assistant.core.gesture.AccessibilityGuard
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.task.TaskManager

/** 运行结果、保活指引与截屏通道设置等系统能力相关页面。 */

/** 保活指引：降低无障碍服务被系统回收的概率（各厂商后台限制差异较大） */
@Composable
internal fun MainActivity.KeepAliveDialog(onDismiss: () -> Unit, onOpenAppSettings: () -> Unit) {
    KeepAlivePage(onDismiss, onOpenAppSettings) { AccessibilityGuard.openAccessibilitySettings(this@KeepAliveDialog) }
}

/** 运行历史对话框：时间 / 耗时 / 退出原因 / 异常信息与堆栈 */
@Composable
internal fun MainActivity.RunHistoryDialog(taskName: String, entries: List<RunHistory.Entry>, onDismiss: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RunHistory.Entry?>(null) }
    DetailPage("运行历史", taskName, onDismiss,
        actions = { TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("清空") } }) {
        if (entries.isEmpty()) EmptyState("暂无运行记录", "执行任务后，可以在这里查看结果与耗时。", R.drawable.ic_tool_log)
        else {
            SectionLabel("最近 ${entries.size} 次运行")
            entries.forEachIndexed { index, entry ->
                val error = entry.outcome == RunHistory.Outcome.ERROR
                val stopped = entry.outcome == RunHistory.Outcome.STOPPED
                Row(Modifier.fillMaxWidth()) {
                    // 左侧时间线：圆点 + 竖线，出错记录用红色圆点。
                    Column(Modifier.width(18.dp).padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(9.dp).background(
                            if (error) Color(AppPalette.ERROR) else if (stopped) Color(AppPalette.TERTIARY) else Color(AppPalette.SUCCESS),
                            androidx.compose.foundation.shape.CircleShape))
                        if (index < entries.lastIndex) Box(Modifier.width(1.dp).height(110.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp, bottom = 14.dp)) {
                        Text("${entry.time} · ${entry.source}", fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(Modifier.fillMaxWidth().padding(top = 6.dp), color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.clickable { selected = entry }.padding(13.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.outcome.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f))
                                    StatusBadge(entry.durationText(), warning = stopped, error = error)
                                }
                                entry.message?.let {
                                    Text(it, fontSize = 10.sp, lineHeight = 17.sp,
                                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp))
                                }
                                if (error) {
                                    entry.stack?.let {
                                        Text(it.lineSequence().take(4).joinToString("\n"),
                                            Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp, lineHeight = 16.sp)
                                    }
                                    TextButton(onClick = { editHistoryError(taskName, entry) },
                                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                                        Text("打开脚本并定位", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { entry ->
        RunResultPage(taskName, entry, onBack = { selected = null },
            onEdit = { editHistoryError(taskName, entry) }, onAssets = {
                // 先退出现有结果浮层，再打开素材页，保证页面位于可操作的最上层。
                selected = null
                onDismiss()
                assetsTask = taskName; assets = TaskManager.imageInfos(this, taskName)
            })
    }
    if (confirmClear) ConfirmActionSheet("清空运行历史？", "将删除「$taskName」的全部运行记录，脚本和素材会保留。", "清空历史",
        onDismiss = { confirmClear = false }, danger = true, onConfirm = { confirmClear = false; onClear() })
}

/** 必须手动选择并确认，不提供自动模式或自动切换。 */
@Composable
internal fun MainActivity.CaptureChannelDialog(onDismiss: () -> Unit, onSelect: (ScreenCapture.Mode) -> Unit) {
    val result = captureTestResult
    if (captureModeTesting || result != null) {
        DetailPage("截屏测试", selectedCaptureMode.label, { if (!captureModeTesting) captureTestResult = null }, footer = {
            if (!captureModeTesting) {
                if (result?.success == true) PrimaryAction("完成", icon = R.drawable.ic_check, onClick = onDismiss)
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { SecondaryAction("更换方式") { captureTestResult = null; captureModeError = null } }
                    Box(Modifier.weight(1f)) { PrimaryAction("重试测试") { onSelect(selectedCaptureMode) } }
                }
            }
        }) {
            if (captureModeTesting) {
                Column(Modifier.fillMaxWidth().padding(top = 45.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("正在截取测试画面", fontSize = 19.sp, modifier = Modifier.padding(top = 24.dp))
                    Text("系统授权方式可能弹出授权提示，请稍候。", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            } else if (result?.success == true) {
                EmptyState("截屏通道已就绪", "已成功获取测试画面，新设置已保存。", R.drawable.ic_check)
                ContentBlock("测试画面") {
                    KeyValueRow("所选通道", selectedCaptureMode.label)
                    KeyValueRow("画面尺寸", "${result.width} × ${result.height} px")
                }
            } else {
                EmptyState("这次测试未通过", "未获取到有效画面。原有设置仍保留，可以重新授权或选择其他方式。", R.drawable.ic_tool_shot)
                InfoNotice(result?.message ?: "截屏测试失败，请重试。", error = true)
            }
        }
        return
    }
    DetailPage(if (captureModeTesting) "正在测试截屏" else if (captureModeError != null) "截屏测试失败" else "截屏方式",
        "更换截屏能力并测试", { if (!captureModeTesting) onDismiss() },
        footer = { PrimaryAction(if (captureModeTesting) "正在测试…" else if (captureModeError != null) "重试测试" else "确认选择并测试",
            enabled = selectedCaptureMode != ScreenCapture.Mode.UNSELECTED && !captureModeTesting) { onSelect(selectedCaptureMode) } }) {
        // 说明文字改为普通小字，不再使用浅色提示块。
        Text("当前设置：${ScreenCapture.mode(this@CaptureChannelDialog).label}。更换方式将在测试成功后保存。",
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (captureModeTesting) {
            WorkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("正在截取测试画面", style = MaterialTheme.typography.titleMedium)
                    Text("系统授权方式可能会弹出授权提示，请稍候。", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            captureModeError?.let {
                EmptyState("截屏测试未通过", it + "\n原有设置仍然保留，可重试或更换方式。", R.drawable.ic_tool_shot)
            }
            SectionLabel("选择通道")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(ScreenCapture.Mode.MEDIAPROJECTION, ScreenCapture.Mode.ROOT).forEach { mode ->
                    ChoiceCard(mode.label,
                        if (mode == ScreenCapture.Mode.ROOT) "需要设备已授予超级用户权限。" else "通过系统弹窗授权，可用于找图和 OCR。",
                        selectedCaptureMode == mode, icon = if (mode == ScreenCapture.Mode.ROOT) R.drawable.ic_nav_permissions else R.drawable.ic_phone) {
                        selectedCaptureMode = mode; captureModeError = null
                    }
                }
            }
            ContentBlock("为什么需要测试？") {
                Text("确认当前设备能够获取画面后，再用于找图、找色和 OCR。测试失败会保留原设置。", fontSize = 12.sp, lineHeight = 21.sp)
            }
            // 与页面内其它说明保持一致，使用普通小字。
            Text("仅使用你选择的方式，不会自动切换到另一种截屏方式。",
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
