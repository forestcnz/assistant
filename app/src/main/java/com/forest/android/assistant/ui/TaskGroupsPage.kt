package com.forest.android.assistant.ui

import com.forest.android.assistant.ui.AppToast as Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.forest.android.assistant.R
import com.forest.android.assistant.core.task.TaskGroup
import com.forest.android.assistant.core.task.TaskGroups
import com.forest.android.assistant.core.task.TaskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** APP、Web 和悬浮窗共用 TaskGroups；每次修改携带 revision，避免覆盖其他端的新数据。 */
@Composable
fun TaskGroupsPage(lifecycle: Lifecycle, modifier: Modifier = Modifier, onSwitchToTasks: () -> Unit,
    createSignal: Int = 0, onOpenTask: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf(emptyList<TaskGroup>()) }
    var taskNames by remember { mutableStateOf(emptyList<String>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf(false) }
    var nameTarget by remember { mutableStateOf<TaskGroup?>(null) }
    var membersTarget by remember { mutableStateOf<TaskGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<TaskGroup?>(null) }
    var actionsExpanded by remember(selectedId) { mutableStateOf(false) }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            synchronized(TaskManager) {
                TaskGroups.list(context) to TaskManager.listTasks(context).map { it.name }
            }
        }
        groups = snapshot.first
        taskNames = snapshot.second
        loaded = true
        error = null
        if (selectedId != null && groups.none { it.id == selectedId }) selectedId = null
    }

    fun mutate(message: String, action: () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val nextId = withContext(Dispatchers.IO + NonCancellable) { action() }
                nameDialog = false
                membersTarget = null
                deleteTarget = null
                selectedId = nextId
                refresh()
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "分组操作失败"
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                // 保留错误说明，刷新后让用户基于最新版本重试。
                val messageText = error
                runCatching { refresh() }
                error = messageText
                nameDialog = false
                membersTarget = null
                deleteTarget = null
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(createSignal) {
        if (createSignal > 0) {
            nameTarget = null
            nameDialog = true
        }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!busy) try {
                    refresh()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "读取分组失败"
                }
                delay(1000)
            }
        }
    }
    val selected = groups.find { it.id == selectedId }
    BackHandler(selected != null && !busy) { selectedId = null }
    val heading: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected == null) {
                SegmentedTabs(listOf("全部任务 ${taskNames.size}", "任务分组"), 1, Modifier.weight(1f)) {
                    if (it == 0) onSwitchToTasks()
                }
            } else {
                AppBackButton(enabled = !busy, onClick = { selectedId = null }, description = "返回分组列表")
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(selected.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("长按任务图标排序 · 左滑移出任务", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(enabled = !busy, onClick = { membersTarget = selected }) { AppIcon(R.drawable.ic_add, "添加任务") }
                IconButton(enabled = !busy, onClick = { actionsExpanded = true }) { AppIcon(R.drawable.ic_more, "分组操作") }
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (selected == null) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 18.dp)) {
                // 与全部任务页共用主视觉，分组切换后仍显示首页卡片。
                item { Box(Modifier.padding(bottom = 16.dp)) { TaskWorkspaceHero() } }
                item { heading() }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) } }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loaded && error == null) item { Text("正在读取分组…") }
                if (loaded && groups.isEmpty()) item {
                    EmptyState("还没有分组", "创建分组后，可添加任务并调整执行顺序。",
                        R.drawable.ic_nav_tasks, "新建分组") { nameTarget = null; nameDialog = true }
                }
                itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
                    // 分组以独立卡片展示，显示成员数量与执行顺序预览。
                    Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(19.dp))
                        .clickable(enabled = !busy) { selectedId = group.id },
                        color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(19.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        // 名称、说明和任务数量共用首行区域，成员预览缩小后将卡片高度压缩约一半。
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(R.drawable.ic_folder, blue = true)
                                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                                    Text(group.name, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (group.taskNames.isEmpty()) "添加任务后按顺序执行"
                                        else "按顺序执行 · 长按任务图标调整顺序",
                                        fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${group.taskNames.size} 个任务", fontSize = 10.sp, lineHeight = 14.sp,
                                    color = Color(AppPalette.ON_SURFACE_VARIANT))
                                AppIcon(R.drawable.ic_arrow, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                            }
                            // 叠放的成员图标预览与保存的组内顺序一致。
                            if (group.taskNames.isNotEmpty()) Row(Modifier.padding(start = 49.dp, top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
                                group.taskNames.take(3).forEach {
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface)) {
                                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_code, modifier = Modifier.size(14.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (groups.isNotEmpty()) item {
                    // 分组说明改为普通小字，不再占用浅色提示块。
                    Text("同一任务可以加入多个分组。分组顺序会同步到 Web 和悬浮任务面板。",
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                        fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // 系统授权必须由 Activity 打开，Compose 提供的是通用 Context。
            // 分组详情已移除悬浮执行入口，执行与授权统一从悬浮面板及权限页进入。
            DetailPage("分组详情", "任务空间 / 分组", { if (!busy) selectedId = null }, scrollable = false,
                compactBackFrame = true,
                actions = {
                    IconButton(enabled = !busy, onClick = { membersTarget = selected }) { AppIcon(R.drawable.ic_add, "添加任务") }
                    IconButton(enabled = !busy, onClick = { actionsExpanded = true }) { AppIcon(R.drawable.ic_more, "分组操作") }
                }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(R.drawable.ic_folder)
                    Column(Modifier.padding(start = 15.dp)) {
                        Text(selected.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("${selected.taskNames.size} 个任务 · 按列表顺序执行", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                    }
                }
                // 与任务列表底部一致，改用普通小字说明拖动和左滑操作。
                Text("长按任务图标排序 · 左滑移出任务", Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                    fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionLabel("执行顺序 · 更改自动保存", compact = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                GroupMembersList(selected, busy, Modifier.weight(1f), onAdd = { if (!busy) membersTarget = selected }, onOpenTask = onOpenTask) { original, members ->
                    mutate("分组已保存") {
                        TaskGroups.update(context, original.id, original.revision, original.name, members).id
                    }
                }
            }
        }
    }
    // 分组操作改用底部操作面板，与任务操作和详情页保持同一套圆角、字号与行高。
    if (actionsExpanded && selected != null) {
        ActionSheet("分组操作", onDismiss = { actionsExpanded = false }) {
            SectionCard {
                DesignRow("重命名分组", "修改分组名称", R.drawable.ic_edit, enabled = !busy, compact = true) {
                    actionsExpanded = false; nameTarget = selected; nameDialog = true
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("删除分组", "任务本身会保留", R.drawable.ic_stop, enabled = !busy, compact = true) {
                    actionsExpanded = false; deleteTarget = selected
                }
            }
        }
    }
    if (nameDialog) {
        val target = nameTarget
        var name by remember(target?.id) { mutableStateOf(target?.name ?: "") }
        val trimmed = name.trim()
        val invalid = trimmed.isBlank() || trimmed.length > 50 || trimmed.any(Char::isISOControl) ||
            groups.any { it.id != target?.id && it.name == trimmed }
        DetailPage(if (target == null) "新建分组" else "重命名分组", "把有关的任务放在一起", { if (!busy) nameDialog = false },
            footer = { PrimaryAction(if (busy) "正在保存…" else if (target == null) "创建分组" else "保存分组", !busy && !invalid) {
                mutate(if (target == null) "分组已创建" else "分组已重命名") {
                    if (target == null) TaskGroups.create(context, name, emptyList()).id
                    else TaskGroups.update(context, target.id, target.revision, name, target.taskNames).id
                }
            } }) {
            DesignField("分组名称", name, { name = it }, "例如：早晨例行、晚间整理。最多 50 个字符。",
                error = if (trimmed.isNotEmpty() && invalid) "名称非法或与现有分组重复" else null)
            InfoNotice(if (target == null) "先给分组起个名字，再添加需要依次执行的任务。" else "组内任务与执行顺序会一同保留。")
        }
    }
    membersTarget?.let { target ->
        val available = taskNames.filterNot { it in target.taskNames }
        GroupTaskPicker(target, available, busy, onDismiss = { if (!busy) membersTarget = null }, onConfirm = { picked ->
                mutate("任务已加入分组") {
                    TaskGroups.update(context, target.id, target.revision, target.name, target.taskNames + picked).id
                }
        })
    }
    deleteTarget?.let { target ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { if (!busy) deleteTarget = null }, title = { Text("删除分组") },
            text = { Text("确定删除「${target.name}」？分组及组内顺序会删除，任务本身会保留。此操作无法撤销。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                mutate("分组已删除") { TaskGroups.delete(context, target.id, target.revision); null }
            }) { Text(if (busy) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleteTarget = null }) { Text("取消") } })
    }
}

@Composable
private fun GroupTaskPicker(group: TaskGroup, available: List<String>, busy: Boolean,
    onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val context = LocalContext.current
    var picked by rememberSaveable(group.id) { mutableStateOf(emptyList<String>()) }
    var descriptions by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(available) {
        descriptions = withContext(Dispatchers.IO) {
            available.associateWith { name ->
                val config = TaskManager.readConfig(context, name)
                config.desc.ifBlank { config.target?.let { "目标：$it" } ?: "" }
            }
        }
    }
    val selected = available.filter { it in picked }
    DetailPage("添加任务", group.name, { if (!busy) onDismiss() },
        actions = {
            if (available.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                picked = if (selected.size == available.size) emptyList() else available
            }) { Text(if (selected.size == available.size) "取消全选" else "全选") }
        },
        footer = {
            // DetailPage 外层已有 18dp 边距；内容区总宽 840dp，对齐其内侧列表。
            Column(Modifier.widthIn(max = 804.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                if (available.isNotEmpty()) PrimaryAction(
                    if (busy) "正在添加…" else if (selected.isEmpty()) "添加所选任务" else "添加所选任务（${selected.size}）",
                    enabled = !busy && selected.isNotEmpty()
                ) { onConfirm(selected) }
                else PrimaryAction("返回分组", enabled = !busy, onClick = onDismiss)
            }
        }) {
        if (available.isEmpty()) {
            EmptyState("所有任务都已在分组中", "你可以返回查看执行顺序。", R.drawable.ic_check)
        } else {
            SectionLabel("选择任务")
            WorkCard(Modifier.fillMaxWidth()) {
                available.forEachIndexed { index, name ->
                    val checked = name in picked
                    DesignRow(name, descriptions[name].orEmpty(), R.drawable.ic_code, enabled = !busy,
                        trailing = { Checkbox(checked, enabled = !busy, onCheckedChange = null) }) {
                        picked = if (checked) picked - name else picked + name
                    }
                    if (index < available.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** 拖动期间仅调整预览，松手后一次保存；取消手势恢复原顺序。 */
@Composable
private fun GroupMembersList(group: TaskGroup, busy: Boolean, modifier: Modifier, onAdd: () -> Unit,
    onOpenTask: (String) -> Unit, onSave: (TaskGroup, List<String>) -> Unit) {
    val listState = rememberLazyListState()
    var draft by remember(group.id) { mutableStateOf<List<String>?>(null) }
    var dragging by remember(group.id) { mutableStateOf<String?>(null) }
    var dragGroup by remember(group.id) { mutableStateOf<TaskGroup?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    val names = draft ?: group.taskNames
    val latestNames by rememberUpdatedState(names)
    val latestSave by rememberUpdatedState(onSave)
    val latestGroup by rememberUpdatedState(group)
    fun reorderAtPointer() {
        val name = dragging ?: return
        val from = latestNames.indexOf(name)
        val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { pointerY >= it.offset && pointerY < it.offset + it.size } ?: return
        val to = latestNames.indexOf(hit.key as? String)
        if (from >= 0 && to >= 0 && from != to) draft = latestNames.toMutableList().apply { add(to, removeAt(from)) }
    }
    LaunchedEffect(dragging) {
        if (dragging != null) while (true) {
            val info = listState.layoutInfo
            val edge = 100f
            val delta = when {
                pointerY < info.viewportStartOffset + edge -> -12f
                pointerY > info.viewportEndOffset - edge -> 12f
                else -> 0f
            }
            if (delta != 0f) { listState.scrollBy(delta); reorderAtPointer() }
            delay(16)
        }
    }
    LaunchedEffect(group.revision, busy) { if (dragging == null) draft = null }
    LazyColumn(modifier.fillMaxWidth().onGloballyPositioned { listTop = it.positionInRoot().y },
        state = listState, contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (names.isEmpty()) item {
            EmptyState("分组暂无任务", "添加已有任务，按你设置的顺序执行。", R.drawable.ic_nav_tasks, "添加任务", onAdd)
        }
        itemsIndexed(names, key = { _, name -> name }) { index, name ->
            var handleTop by remember { mutableFloatStateOf(0f) }
            val shape = RoundedCornerShape(topStart = if (index == 0) 18.dp else 0.dp,
                topEnd = if (index == 0) 18.dp else 0.dp,
                bottomStart = if (index == names.lastIndex) 18.dp else 0.dp,
                bottomEnd = if (index == names.lastIndex) 18.dp else 0.dp)
            SwipeRemoveMember(enabled = !busy && dragging == null, modifier = Modifier.clip(shape),
                onRemove = { latestSave(latestGroup, latestNames - name) }) {
                Surface(Modifier.fillMaxWidth(), color = if (dragging == name) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    shape = shape) {
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 6.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // 长按任务图标即可拖动排序，不再单独显示拖拽符号。
                            Box(Modifier.size(48.dp).semantics { contentDescription = "长按「$name」的图标拖动排序" }
                                .onGloballyPositioned { handleTop = it.positionInRoot().y }
                                .pointerInput(name, busy) {
                                    if (!busy) detectDragGesturesAfterLongPress(
                                        onDragStart = { offset -> dragGroup = latestGroup; draft = latestGroup.taskNames; dragging = name; pointerY = handleTop - listTop + offset.y },
                                        onDrag = { change, amount -> change.consume(); pointerY += amount.y; reorderAtPointer() },
                                        onDragEnd = {
                                            val result = draft
                                            val original = dragGroup
                                            dragging = null
                                            if (result != null && original != null && result != original.taskNames) latestSave(original, result) else draft = null
                                        }, onDragCancel = { dragging = null; draft = null })
                                }, contentAlignment = Alignment.Center) {
                                IconTile(R.drawable.ic_code, blue = true)
                            }
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { onOpenTask(name) }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text("第 ${index + 1} 个执行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (index < names.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRemoveMember(enabled: Boolean, modifier: Modifier = Modifier,
    onRemove: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { width -> minOf(width * .35f, threshold) })
    SwipeToDismissBox(state = state, modifier = modifier.fillMaxWidth().semantics {
        customActions = listOf(CustomAccessibilityAction("移出分组") {
            if (enabled) onRemove()
            enabled
        })
    }, enableDismissFromStartToEnd = false, gesturesEnabled = enabled,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart && enabled) onRemove()
            scope.launch { state.reset() }
        }, backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd) {
                Text("移出分组", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
            }
        }) { content() }
}
