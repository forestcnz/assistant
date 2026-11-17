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
import com.forest.android.assistant.openFloatingTasks
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.recording.RecordingService
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.core.task.TaskOrder
import com.forest.android.assistant.ui.AppToast as Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 任务空间：任务列表、分组入口与长按拖动排序。 */

/** 任务列表区：标题行（数量 + 导入/导出全部 + 新建） + 列表 */
@Composable
internal fun MainActivity.TaskWorkspace(runState: ScriptRunner.RunState) {
    var groupsSelected by rememberSaveable { mutableStateOf(false) }
    var groupCreateSignal by rememberSaveable { mutableStateOf(0) }
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        PageHeading("任务空间", "雕雕助手 · AUTOMATION COMPANION", compact = true, actions = {
            if (!groupsSelected) {
                Box(Modifier.size(32.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { showTransferDialog = true }, contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_more, "任务工具", Modifier.size(19.dp))
                }
            } else {
                // 分组页标题栏的「＋」用于新建分组，与设计稿一致。
                Box(Modifier.size(32.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { groupCreateSignal++ }, contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_add, "新建分组", Modifier.size(19.dp))
                }
            }
        })
        Box(Modifier.weight(1f)) {
            pages.SaveableStateProvider(false) {
                RetainedManagementPage(visible = !groupsSelected) {
                    TaskListSection(runState, Modifier.fillMaxSize(), onSwitchToGroups = { groupsSelected = true })
                }
            }
            pages.SaveableStateProvider(true) {
                RetainedManagementPage(visible = groupsSelected) {
                    TaskGroupsPage(lifecycle, Modifier.fillMaxSize(), onSwitchToTasks = { groupsSelected = false },
                        createSignal = groupCreateSignal, onOpenTask = { menuTask = it })
                }
            }
        }
    }
}

/** 保留两页的组合、数据和滚动状态；未放置的页面不绘制，也不接收点击。 */
@Composable
private fun MainActivity.RetainedManagementPage(visible: Boolean, content: @Composable () -> Unit) {
    Layout(modifier = Modifier.fillMaxSize(), content = { Box(Modifier.fillMaxSize()) { content() } }) { children, constraints ->
        val page = children.single().measure(constraints)
        layout(page.width, page.height) { if (visible) page.placeRelative(0, 0) }
    }
}

@Composable
private fun MainActivity.TaskListSection(runState: ScriptRunner.RunState, modifier: Modifier = Modifier, onSwitchToGroups: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // 拖动期间只改预览顺序，松手后一次保存；每秒的列表刷新不会打断拖动。
    var orderDraft by remember { mutableStateOf<List<String>?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragRevision by remember { mutableStateOf(0L) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    val tasksByName = tasks.associateBy { it.name }
    val taskNames = orderDraft ?: tasks.map { it.name }
    val latestNames by rememberUpdatedState(taskNames)
    val latestTasks by rememberUpdatedState(tasks)
    fun reorderAtPointer() {
        val name = dragging ?: return
        val from = latestNames.indexOf(name)
        // 只处理任务行（key 为任务名），标题和工具栏项保持不动。
        val hit = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { pointerY >= it.offset && pointerY < it.offset + it.size } ?: return
        val to = latestNames.indexOf(hit.key as? String)
        if (from >= 0 && to >= 0 && from != to) {
            orderDraft = latestNames.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
    // 拖到列表边缘时自动滚动，便于调整长列表。
    LaunchedEffect(dragging) {
        if (dragging != null) while (true) {
            val info = listState.layoutInfo
            val edge = 100f
            val delta = when {
                pointerY < info.viewportStartOffset + edge -> -12f
                pointerY > info.viewportEndOffset - edge -> 12f
                else -> 0f
            }
            if (delta != 0f) {
                listState.scrollBy(delta)
                reorderAtPointer()
            }
            delay(16)
        }
    }
    LaunchedEffect(tasks) { if (dragging == null) orderDraft = null }
    Column(modifier) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { listTop = it.positionInRoot().y },
            state = listState,
            contentPadding = PaddingValues(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
                Box(Modifier.padding(bottom = 16.dp)) {
                    TaskWorkspaceHero()
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SegmentedTabs(listOf("全部任务 ${tasks.size}", "任务分组"), 0, Modifier.weight(1f)) {
                        if (it == 1) onSwitchToGroups()
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(32.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable { showCreateDialog = true }, contentAlignment = Alignment.Center) {
                        AppIcon(R.drawable.ic_add, "新建任务", Modifier.size(20.dp))
                    }
                }
            }
            if (tasks.isEmpty()) item {
                EmptyState("还没有任务", "用空白、签到或找图模板快速开始，也可以导入已有 ZIP 文件。",
                    R.drawable.ic_nav_tasks, "创建第一个任务") { showCreateDialog = true }
            }
            // 行按需组合，长任务列表不一次性加载所有内容。
            itemsIndexed(taskNames, key = { _, name -> name }) { index, name ->
                val task = tasksByName[name] ?: return@itemsIndexed
                // 长按任务图标即可拖动，跟随手指移动，松手自动保存顺序。
                var iconTop by remember { mutableFloatStateOf(0f) }
                val dragEnabled = !taskMutationBusy && !taskTransferBusy
                // 被拖起的行高亮并浮起；其余行平滑移动，拖动过程更直观。
                val isDragging = dragging == name
                val shape = RoundedCornerShape(topStart = if (index == 0) 18.dp else 0.dp,
                    topEnd = if (index == 0) 18.dp else 0.dp,
                    bottomStart = if (index == taskNames.lastIndex) 18.dp else 0.dp,
                    bottomEnd = if (index == taskNames.lastIndex) 18.dp else 0.dp)
                SwipeDeleteTask(
                    taskName = name,
                    enabled = dragEnabled && dragging == null && !runState.running && !RecordingService.active && deletingTask == null,
                    modifier = Modifier.then(if (isDragging) Modifier else Modifier.animateItem()).clip(shape),
                    onDelete = { deletingTask = name }
                ) {
                    Surface(Modifier.fillMaxWidth(),
                        color = if (isDragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        shadowElevation = if (isDragging) 8.dp else 0.dp,
                        shape = shape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column {
                            TaskRow(task, runState, iconDragModifier = Modifier
                                .semantics { contentDescription = "长按「$name」的图标拖动排序" }
                                .onGloballyPositioned { iconTop = it.positionInRoot().y }
                                .pointerInput(name, dragEnabled) {
                                    if (dragEnabled) detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            // 长按触发时给一次触感反馈，确认已进入拖动状态。
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragRevision = taskOrderRevision
                                            orderDraft = latestTasks.map { it.name }
                                            dragging = name
                                            pointerY = iconTop - listTop + offset.y
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            pointerY += amount.y
                                            reorderAtPointer()
                                        },
                                        onDragEnd = {
                                            val result = orderDraft
                                            val revision = dragRevision
                                            val before = latestTasks.map { it.name }
                                            dragging = null
                                            if (result != null && result != before) {
                                                scope.launch {
                                                    val failure = withContext(Dispatchers.IO) {
                                                        runCatching { TaskOrder.save(this@TaskListSection, revision, result) }
                                                            .exceptionOrNull()
                                                    }
                                                    // 成功或冲突都重新读取，界面始终展示已保存顺序。
                                                    tasks = withContext(Dispatchers.IO) { TaskManager.listTasks(this@TaskListSection) }
                                                    taskOrderRevision = withContext(Dispatchers.IO) { TaskOrder.read(this@TaskListSection).revision }
                                                    orderDraft = null
                                                    Toast.makeText(this@TaskListSection,
                                                        failure?.message ?: "任务顺序已保存",
                                                        if (failure == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                                }
                                            } else orderDraft = null
                                        },
                                        onDragCancel = { dragging = null; orderDraft = null }
                                    )
                                })
                        }
                    }
                }
            }
            if (tasks.isNotEmpty()) item {
                Text("长按图标排序 · 左滑任务删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            }
            item {
                MarketBanner("去脚本市场，发现更多可能", "社区好用的脚本，即选即用", Modifier.padding(top = 16.dp)) {
                    showScriptMarket = true
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickEntry("导入与导出", R.drawable.ic_import, Modifier.weight(1f)) { showTransferDialog = true }
                    QuickEntry("悬浮任务面板", R.drawable.ic_floaty, Modifier.weight(1f)) { openFloatingTasks() }
                }
            }
        }
    }
}

/** 任务行：图标可长按拖动；名称、定时状态与详情入口；执行操作统一进入悬浮面板。 */
@Composable
private fun MainActivity.TaskRow(task: TaskManager.TaskInfo, runState: ScriptRunner.RunState,
    iconDragModifier: Modifier = Modifier) {
    val schedule = schedules[task.name]
    val enabled = !taskMutationBusy && !taskTransferBusy
    val subtitle = schedule?.describe() ?: taskSubtitles[task.name]?.ifBlank { "脚本任务 · 待运行" } ?: "脚本任务 · 待运行"
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { menuTask = task.name }
        .heightIn(min = 60.dp)
        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // 图标本身作为拖动区域，长按图标即可调整顺序。
        Box(Modifier.size(40.dp).then(iconDragModifier), contentAlignment = Alignment.Center) {
            val imageTask = task.name.contains("图") || task.name.contains("截")
            val readingTask = task.name.contains("阅读") || task.name.contains("日志")
            val checkTask = task.name.contains("签到") || task.name.contains("领取")
            IconTile(
                when {
                    schedule != null -> R.drawable.ic_clock
                    imageTask -> R.drawable.ic_tool_shot
                    readingTask -> R.drawable.ic_tool_log
                    checkTask -> R.drawable.ic_check
                    else -> R.drawable.ic_code
                },
                warm = schedule != null || imageTask,
                blue = schedule == null && !imageTask
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(task.name, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f))
            Text(subtitle, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (runState.running && runState.taskName == task.name) StatusBadge("运行中")
        else if (schedule != null) StatusBadge(schedule.describe())
        else AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(15.dp))
    }
}

/** 首页快捷入口：白底描边按钮，与设计稿的 1/2 网格一致。 */
@Composable
private fun MainActivity.QuickEntry(label: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(15.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(15.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides Color(AppPalette.ON_SURFACE_VARIANT)) {
            AppIcon(icon, modifier = Modifier.size(20.dp))
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
    }
}
