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
import com.forest.android.assistant.openCaptureDialog
import com.forest.android.assistant.openFloatingTasks
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*
import android.content.ClipData
import android.content.ClipboardManager
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.task.TaskExecutionSettings
import com.forest.android.assistant.core.update.AppUpdateState
import com.forest.android.assistant.core.web.LanWebServer
import com.forest.android.assistant.core.web.LanWebService
import com.forest.android.assistant.core.web.LanWebState
import com.forest.android.assistant.ui.AppToast as Toast

/** 应用设置页：悬浮球、多任务间隔、截屏通道与更新入口。 */

/** 设置页：悬浮球与截屏通道；屏幕拾取工具和悬浮日志入口统一收在悬浮窗面板中。 */
@Composable
internal fun MainActivity.SettingsTab(launcherEnabled: Boolean, update: AppUpdateState) {
    val webState by LanWebServer.state.collectAsState()
    var showWebEditor by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeading("应用设置", "按你习惯的方式工作", compact = true)
        // 设置页收紧分区和列表间距，减少标题下方及卡片之间的空白。
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("悬浮控制", compact = true)
            WorkCard(Modifier.fillMaxWidth()) {
                val toggle: () -> Unit = {
                    if (!overlayGranted) FloatyManager.requestOverlayPermission(this@SettingsTab)
                    else ScriptRunner.toggleLauncher(this@SettingsTab)
                }
                DesignRow("显示悬浮球", "在其他应用中快速启动任务", R.drawable.ic_floaty,
                    compact = true,
                    trailing = { Switch(checked = launcherEnabled, onCheckedChange = { toggle() },
                        modifier = Modifier.scale(0.78f),
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(AppPalette.PRIMARY), checkedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFE5E9EF), uncheckedThumbColor = Color.White)) }, onClick = toggle)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AutoSnapRow()
            }
            SectionLabel("任务执行", compact = true)
            WorkCard(Modifier.fillMaxWidth()) {
                TaskQueueIntervalRow()
            }
            SectionLabel("截图与识别", compact = true)
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow("截屏通道设置", ScreenCapture.mode(this@SettingsTab).label + " · 点击更换并测试",
                    R.drawable.ic_tool_shot, compact = true) { openCaptureDialog() }
            }
            SectionLabel("快捷入口", compact = true)
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow("打开悬浮任务面板", "查看任务队列及快捷工具", R.drawable.ic_nav_tasks, compact = true) { openFloatingTasks() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow(
                    "局域网 Web 编辑",
                    if (webState.running) "服务运行中 · ${webState.url.orEmpty()}"
                    else "通过其他设备的浏览器编辑脚本和流程",
                    R.drawable.ic_code,
                    compact = true
                ) { showWebEditor = true }
            }
            SectionLabel("版本", compact = true)
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow("应用更新", "当前版本 ${update.currentVersion}", R.drawable.ic_import,
                    compact = true,
                    trailing = if (update.availableUpdate != null) ({ StatusBadge("有新版本", warning = true) }) else null) { showAppUpdate = true }
            }
            Text("雕雕助手 · 把时间留给更喜欢的事", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp))
        }
    }
    if (showWebEditor) {
        LanWebDialog(
            state = webState,
            onStart = { LanWebService.start(this@SettingsTab) },
            onStop = { LanWebService.stop(this@SettingsTab) },
            onCopy = { url ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("局域网编辑地址", url))
                Toast.makeText(this@SettingsTab, "地址已复制", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showWebEditor = false }
        )
    }
}

@Composable
private fun MainActivity.LanWebDialog(
    state: LanWebState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    LanEditorPage(state, onDismiss, onStart, onStop, onCopy)
}

/**
 * 自动贴边开关：拖动松手后是否自动吸到左右边缘（半隐藏）。
 * 状态持久化在 floaty 偏好中，切换后立即作用于当前悬浮球。
 */
@Composable
private fun MainActivity.AutoSnapRow() {
    var autoSnap by remember { mutableStateOf(FloatyManager.isAutoSnapEnabled(this@AutoSnapRow)) }
    val apply: (Boolean) -> Unit = { next ->
        autoSnap = next
        FloatyManager.setAutoSnapEnabled(this@AutoSnapRow, next)
    }
    DesignRow("自动贴边", "拖动后吸附到屏幕左右边缘", R.drawable.ic_tool_point,
        compact = true,
        trailing = { Switch(checked = autoSnap, onCheckedChange = apply,
            modifier = Modifier.scale(0.78f),
            colors = SwitchDefaults.colors(checkedTrackColor = Color(AppPalette.PRIMARY), checkedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFE5E9EF), uncheckedThumbColor = Color.White)) }) { apply(!autoSnap) }
}

/** 多任务间隔以秒保存，未确认的输入在旋转屏幕后继续保留。 */
@Composable
private fun MainActivity.TaskQueueIntervalRow() {
    var intervalSec by remember { mutableStateOf(TaskExecutionSettings.queueIntervalSec(this@TaskQueueIntervalRow)) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var intervalValue by rememberSaveable { mutableStateOf(intervalSec.toString()) }
    DesignRow(
        "多任务执行间隔",
        if (intervalSec == 0) "0 秒 · 完成后立即执行下一个任务" else "$intervalSec 秒 · 两个任务之间等待",
        R.drawable.ic_clock,
        compact = true
    ) {
        intervalValue = intervalSec.toString()
        showDialog = true
    }
    if (showDialog) {
        QueueIntervalPage(intervalValue, { intervalValue = it }, { showDialog = false }) { seconds ->
            TaskExecutionSettings.setQueueIntervalSec(this@TaskQueueIntervalRow, seconds)
            intervalSec = seconds
            showDialog = false
            Toast.makeText(this@TaskQueueIntervalRow, "多任务执行间隔已保存：$seconds 秒", Toast.LENGTH_SHORT).show()
        }
    }
}
