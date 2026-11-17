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
import com.forest.android.assistant.performTaskChange
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*
import android.content.Intent
import com.forest.android.assistant.MainActivity.DurationUnit
import com.forest.android.assistant.RecordingSummaryActivity
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.flow.FlowCompiler
import com.forest.android.assistant.core.recording.RecordingReview
import com.forest.android.assistant.core.recording.RecordingService
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.task.ScheduleManager
import com.forest.android.assistant.core.task.TaskLoop
import com.forest.android.assistant.core.task.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 任务相关弹层：任务详情菜单、循环/定时设置、重命名与新建。 */

/** 任务操作菜单：编辑脚本 / 循环执行 / 定时任务 / 模板截取 / 素材管理 */
@Composable
internal fun MainActivity.TaskMenuDialog(
    taskName: String, onDismiss: () -> Unit, onEditScript: () -> Unit, onRename: () -> Unit,
    onDelete: () -> Unit, onManageAssets: () -> Unit, onLoop: () -> Unit,
    onSchedule: () -> Unit, onExportZip: () -> Unit, onHistory: () -> Unit
) {
    val runState by ScriptRunner.state.collectAsState()
    val busy = runState.running || RecordingService.active || taskMutationBusy || taskTransferBusy
    var showMenu by remember { mutableStateOf(false) }
    var latestRun by remember(taskName) { mutableStateOf<RunHistory.Entry?>(null) }
    LaunchedEffect(taskName, runState.running) {
        latestRun = withContext(Dispatchers.IO) { RunHistory.read(this@TaskMenuDialog, taskName).firstOrNull() }
    }
    val loop = remember(taskName, taskMetadataVersion) { TaskManager.readConfig(this@TaskMenuDialog, taskName).loop.describe() }
    val visual = remember(taskName, taskMetadataVersion) {
        com.forest.android.assistant.core.flow.FlowCompiler.isVisual(this@TaskMenuDialog, taskName)
    }
    val taskType = if (visual) "流程任务" else "脚本任务"
    val config = remember(taskName, taskMetadataVersion) { TaskManager.readConfig(this@TaskMenuDialog, taskName) }
    val scheduleNow = schedules[taskName]
    DetailPage("任务详情", taskType + (latestRun?.let { " · 最近运行" + it.outcome.label } ?: " · 尚未运行"), onDismiss,
        compact = true,
        actions = { IconButton(onClick = { showMenu = true }) { AppIcon(R.drawable.ic_more, "任务操作") } }) {
        // 任务摘要 + 三项关键数据，对应设计稿的详情首屏。
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(AppPalette.PRIMARY_CONTAINER), contentColor = Color(AppPalette.ON_SURFACE_VARIANT),
                shape = RoundedCornerShape(20.dp)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_code, modifier = Modifier.size(24.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 15.dp)) {
                Text(taskName, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(config.desc.ifBlank { if (visual) "可视化流程任务" else "JavaScript 脚本任务" },
                    fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(taskType)
            Spacer(Modifier.weight(1f))
            Text(latestRun?.let { "最近运行" + it.outcome.label } ?: "还没有运行记录", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                (scheduleNow?.describe() ?: "未设置") to "定时运行",
                (when (config.loop.mode) {
                    TaskLoop.Mode.COUNT -> "${config.loop.count} 次"
                    TaskLoop.Mode.DURATION -> "${config.loop.durationSec} 秒"
                    TaskLoop.Mode.UNTIL_STOPPED -> "手动停止"
                }) to "每次执行",
                (latestRun?.durationText() ?: "—") to "最近耗时"
            ).forEachIndexed { index, (value, label) ->
                if (index > 0) Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.weight(1f).then(if (index > 0) Modifier.padding(start = 14.dp) else Modifier)) {
                    Text(value, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(label, fontSize = 9.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("编辑与执行", compact = true)
        if (com.forest.android.assistant.core.recording.RecordingReview.pending(this@TaskMenuDialog, taskName)) {
            SectionCard { DesignRow("完成录制保存", "查看本次录制摘要、改名或放弃", R.drawable.ic_record, compact = true) {
                startActivity(Intent(this@TaskMenuDialog, RecordingSummaryActivity::class.java).putExtra("task_name", taskName))
            } }
        }
        WorkCard(Modifier.fillMaxWidth()) {
            DesignRow(
                "编辑脚本",
                if (visual) "task.js · 保存后转为普通脚本任务" else "task.js · JavaScript",
                R.drawable.ic_code,
                enabled = !runState.running,
                compact = true,
                onClick = onEditScript
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("循环执行", loop, R.drawable.ic_loop, enabled = !runState.running, compact = true, onClick = onLoop)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("定时运行", schedules[taskName]?.describe() ?: "尚未设置运行时间", R.drawable.ic_clock, compact = true, onClick = onSchedule)
        }
        SectionLabel("素材与记录", compact = true)
        WorkCard(Modifier.fillMaxWidth()) {
            DesignRow("素材管理", "预览、裁剪与整理任务图片", R.drawable.ic_tool_shot, compact = true, onClick = onManageAssets)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("运行历史", "查看每次运行结果与耗时", R.drawable.ic_tool_log, compact = true, onClick = onHistory)
        }
        SectionLabel("其他操作", compact = true)
        WorkCard(Modifier.fillMaxWidth()) {
            DesignRow("重命名任务", "保留脚本、素材及定时设置", R.drawable.ic_edit, enabled = !busy, compact = true, onClick = onRename)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("导出任务 ZIP", "打包脚本、配置和素材", R.drawable.ic_export, enabled = !taskTransferBusy, compact = true, onClick = onExportZip)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("删除任务", "删除相关记录与素材", R.drawable.ic_stop, enabled = !busy, compact = true, onClick = onDelete)
        }
    }
    // 任务操作改用底部操作面板，与分组操作、其它详情页的样式保持一致。
    if (showMenu) ActionSheet("任务操作", onDismiss = { showMenu = false }) {
        SectionCard {
            buildList {
                add(Triple(
                    if (visual) "编辑脚本并转为普通任务" else "编辑脚本",
                    R.drawable.ic_code,
                    onEditScript
                ))
                add(Triple("循环执行", R.drawable.ic_loop, onLoop))
                add(Triple("定时运行", R.drawable.ic_clock, onSchedule))
                add(Triple("素材管理", R.drawable.ic_tool_shot, onManageAssets))
                add(Triple("运行历史", R.drawable.ic_tool_log, onHistory))
                add(Triple("重命名任务", R.drawable.ic_edit, onRename))
                add(Triple("导出任务 ZIP", R.drawable.ic_export, onExportZip))
                add(Triple("删除任务", R.drawable.ic_stop, onDelete))
            }.forEachIndexed { index, (label, icon, action) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow(label, "", icon, enabled = !busy, compact = true) { showMenu = false; action() }
            }
        }
    }
}

/** 整个任务循环设置：每轮重新运行 task.js，间隔只发生在轮次之间。 */
@Composable
internal fun MainActivity.LoopDialog(
    taskName: String, mode: TaskLoop.Mode, count: String, duration: String, durationUnit: DurationUnit,
    interval: String, onModeChange: (TaskLoop.Mode) -> Unit, onCountChange: (String) -> Unit,
    onDurationChange: (String) -> Unit, onDurationUnitChange: (DurationUnit) -> Unit,
    onIntervalChange: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit
) {
    // 循环设置整体使用紧凑间距，减少选项卡片、输入框和说明文字的纵向占用。
    DetailPage("循环执行", taskName, onDismiss, compact = true, footer = { PrimaryAction("保存循环设置", onClick = onSave) }) {
        SectionLabel("选择执行方式", compact = true)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceCard("指定次数", "运行固定次数后结束", mode == TaskLoop.Mode.COUNT, compact = true) { onModeChange(TaskLoop.Mode.COUNT) }
            ChoiceCard("指定执行时间", "到时中断当前轮", mode == TaskLoop.Mode.DURATION, compact = true) { onModeChange(TaskLoop.Mode.DURATION) }
            ChoiceCard("直到手动停止", "持续运行，手动结束", mode == TaskLoop.Mode.UNTIL_STOPPED, compact = true) { onModeChange(TaskLoop.Mode.UNTIL_STOPPED) }
        }
        SectionLabel("运行参数", compact = true)
        if (mode == TaskLoop.Mode.COUNT) {
            DesignField("执行次数", count, onCountChange, "", KeyboardType.Number, compact = true)
        }
        if (mode == TaskLoop.Mode.DURATION) {
            DesignField("执行时长", duration, onDurationChange, "到时中断当前轮。", KeyboardType.Number, compact = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DurationUnit.entries.forEach { unit ->
                    FilterChip(durationUnit == unit, { onDurationUnitChange(unit) }, label = { Text(unit.label) })
                }
            }
        }
        if (mode == TaskLoop.Mode.UNTIL_STOPPED) {
            Text("运行将持续到手动停止；任务出错时也会结束。",
                Modifier.fillMaxWidth().padding(vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DesignField("两轮间隔 · 秒", interval, onIntervalChange, "设为 0 秒，上一轮完成后立即开始下一轮。", KeyboardType.Number, compact = true)
        // 说明文字改为普通小字，不再使用浅色提示块。
        Text("每轮从头执行整个任务，与脚本或流程内部的循环分别设置。",
            Modifier.fillMaxWidth().padding(vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 定时任务设置对话框：选类型 + 填参数，可保存或取消定时 */
@Composable
internal fun MainActivity.ScheduleDialog(
    taskName: String, current: ScheduleManager.Schedule?, type: ScheduleManager.Type, value: String,
    onTypeChange: (ScheduleManager.Type) -> Unit, onValueChange: (String) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit, onCancelSchedule: () -> Unit
) {
    // 定时设置同样压缩为紧凑布局，频率选项与参数输入框保持可点、可读。
    DetailPage("定时运行", taskName, onDismiss, compact = true, footer = { PrimaryAction("保存定时设置", onClick = onSave) }) {
        SectionLabel("运行频率", compact = true)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceCard("延时一次", "等待指定时间后，运行一次", type == ScheduleManager.Type.ONCE_AFTER_MINUTES, compact = true) {
                onTypeChange(ScheduleManager.Type.ONCE_AFTER_MINUTES)
            }
            ChoiceCard("固定间隔", "每隔一段时间重复运行", type == ScheduleManager.Type.EVERY_MINUTES, compact = true) {
                onTypeChange(ScheduleManager.Type.EVERY_MINUTES)
            }
            ChoiceCard("每天固定时刻", "每天同一时刻运行", type == ScheduleManager.Type.DAILY_AT, compact = true) {
                onTypeChange(ScheduleManager.Type.DAILY_AT)
            }
        }
        SectionLabel("运行参数", compact = true)
        DesignField(
            when (type) {
                ScheduleManager.Type.ONCE_AFTER_MINUTES -> "延迟时间 · 分钟"
                ScheduleManager.Type.EVERY_MINUTES -> "间隔时间 · 分钟"
                ScheduleManager.Type.DAILY_AT -> "运行时间 · HH:mm"
            },
            value, onValueChange,
            when (type) {
                ScheduleManager.Type.ONCE_AFTER_MINUTES -> "从保存时开始计时。"
                ScheduleManager.Type.EVERY_MINUTES -> "最短间隔为 15 分钟。"
                ScheduleManager.Type.DAILY_AT -> "每天该时刻自动运行。"
            },
            if (type == ScheduleManager.Type.DAILY_AT) KeyboardType.Text else KeyboardType.Number,
            compact = true
        )
        // 系统时间选择器避免移动端输入时刻时误输分隔符；此处改为在运行时间输入框中直接填写。
        if (current != null) TextButton(onClick = onCancelSchedule) {
            Text("取消当前定时", color = Color(AppPalette.ERROR), fontSize = 12.sp)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MainActivity.RenameTaskDialog(oldName: String) {
    var name by remember(oldName) { mutableStateOf(oldName) }
    val trimmed = name.trim()
    val invalid = trimmed.isBlank() || trimmed.length > 50 || trimmed == "." || trimmed == ".." ||
        trimmed.any { it == '/' || it == '\\' || it.isISOControl() } ||
        tasks.any { it.name == trimmed && it.name != oldName }
    ModalBottomSheet(
        onDismissRequest = { if (!taskMutationBusy) renamingTask = null },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            Text("重命名任务", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(oldName, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            DesignField("任务名称", name, { name = it }, "脚本、素材与历史记录会一同保留",
                error = if (invalid) "名称非法或与现有任务重复" else null, compact = true)
            if (schedules.containsKey(oldName)) Text("定时任务将保留原设置，并从改名后重新计时。",
                Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 10.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    SecondaryAction("取消", !taskMutationBusy) { renamingTask = null }
                }
                Box(Modifier.weight(1f)) {
                    PrimaryAction(if (taskMutationBusy) "正在保存…" else "保存", !invalid && !taskMutationBusy) {
                        performTaskChange({ TaskManager.renameTask(this@RenameTaskDialog, oldName, trimmed) }, "已改名为「$trimmed」") {
                            renamingTask = null
                            menuTask = trimmed
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.CreateTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, TaskManager.Scaffold) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var scaffold by rememberSaveable { mutableStateOf(TaskManager.Scaffold.BLANK) }
    // APP 只创建脚本任务；兼容旧版保存过的 VISUAL 选中状态。
    val selectedScaffold = scaffold.takeUnless { it == TaskManager.Scaffold.VISUAL } ?: TaskManager.Scaffold.BLANK
    val trimmed = name.trim()
    val nameError = when {
        trimmed.length > 50 -> "名称最多 50 个字符"
        trimmed.contains('/') || trimmed.contains('\\') || trimmed == "." || trimmed == ".." -> "名称不能包含路径符号"
        tasks.any { it.name == trimmed } -> "该名称已存在，请换一个名称"
        else -> null
    }
    DetailPage("新建任务", "把重复动作，变成一个任务", onDismiss, footer = {
        PrimaryAction("创建任务", enabled = trimmed.isNotBlank() && nameError == null, icon = R.drawable.ic_add) {
            onConfirm(trimmed, selectedScaffold)
        }
    }) {
        Column(Modifier.padding(top = 8.dp)) {
            Text("任务名称", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 42.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, if (nameError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(AppPalette.PRIMARY)),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("例如：每日签到", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                )
            }
            Text(nameError ?: "使用易于辨认的名称，最多 50 个字符。", fontSize = 9.sp, lineHeight = 16.sp,
                color = if (nameError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
        SectionLabel("从一个模板开始")
        TaskManager.Scaffold.entries.filterNot { it == TaskManager.Scaffold.VISUAL }.forEach { option ->
            val icon = when (option) {
                TaskManager.Scaffold.SIGN_IN -> R.drawable.ic_check
                TaskManager.Scaffold.FIND_IMAGE -> R.drawable.ic_tool_shot
                else -> R.drawable.ic_code
            }
            // 设计稿使用更短的模板说明，避免窄屏换行把选项撑高。
            val desc = when (option) {
                TaskManager.Scaffold.BLANK -> "从一段基础脚本自由开始"
                TaskManager.Scaffold.SIGN_IN -> "常用的点击与等待示例"
                TaskManager.Scaffold.FIND_IMAGE -> "学习图片识别和自动点击"
                else -> option.desc
            }
            ChoiceCard(option.label, desc, selectedScaffold == option, icon = icon) { scaffold = option }
        }
        Text("需要拖拽编排流程？在「设置 → 局域网 Web 编辑」中创建和编辑流程任务。",
            Modifier.fillMaxWidth().background(Color(AppPalette.PRIMARY_CONTAINER), RoundedCornerShape(12.dp)).padding(14.dp),
            fontSize = 10.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
