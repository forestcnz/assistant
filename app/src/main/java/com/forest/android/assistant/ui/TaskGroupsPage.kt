package com.forest.android.assistant.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
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
fun TaskGroupsPage(lifecycle: Lifecycle, modifier: Modifier = Modifier, onSwitchToTasks: () -> Unit) {
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
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) IconButton(enabled = !busy, onClick = { selectedId = null }) { AppIcon(R.drawable.ic_back, "返回分组列表") }
            Column(Modifier.weight(1f)) {
                Text(selected?.name ?: "全部分组", style = MaterialTheme.typography.titleMedium)
                Text(if (selected == null) "同一任务可加入多个分组" else "长按任务图标排序 · 左滑移出任务",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected == null) IconButton(enabled = !busy, onClick = { nameTarget = null; nameDialog = true }) { AppIcon(R.drawable.ic_add, "新建分组") }
            else {
                IconButton(enabled = !busy, onClick = { membersTarget = selected }) { AppIcon(R.drawable.ic_add, "添加任务") }
                IconButton(enabled = !busy, onClick = { actionsExpanded = true }) { AppIcon(R.drawable.ic_more, "分组操作") }
            }
            if (selected == null) IconButton(enabled = !busy, onClick = onSwitchToTasks) { AppIcon(R.drawable.ic_switch_lists, "切换到任务列表") }
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (selected == null) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(18.dp)) {
                item { heading() }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) } }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loaded && error == null) item { Text("正在读取分组…") }
                if (loaded && groups.isEmpty()) item {
                    EmptyState("还没有分组", "创建分组后，可添加任务并调整执行顺序。",
                        R.drawable.ic_nav_tasks, "新建分组") { nameTarget = null; nameDialog = true }
                }
                itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = if (index == 0) 18.dp else 0.dp,
                            topEnd = if (index == 0) 18.dp else 0.dp,
                            bottomStart = if (index == groups.lastIndex) 18.dp else 0.dp,
                            bottomEnd = if (index == groups.lastIndex) 18.dp else 0.dp)) {
                        Column {
                            DesignRow(group.name, "${group.taskNames.size} 个任务", R.drawable.ic_code, enabled = !busy) { selectedId = group.id }
                            if (index < groups.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 18.dp)) {
                heading()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                GroupMembersList(selected, busy, Modifier.weight(1f), onAdd = { if (!busy) membersTarget = selected }) { original, members ->
                    mutate("分组已保存") {
                        TaskGroups.update(context, original.id, original.revision, original.name, members).id
                    }
                }
            }
        }
    }
    if (actionsExpanded && selected != null) {
        AlertDialog(containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { actionsExpanded = false }, title = { Text("分组操作") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DesignRow("重命名分组", "", R.drawable.ic_code, enabled = !busy) {
                        actionsExpanded = false; nameTarget = selected; nameDialog = true
                    }
                    DesignRow("删除分组", "", R.drawable.ic_stop, enabled = !busy) {
                        actionsExpanded = false; deleteTarget = selected
                    }
                }
            }, confirmButton = { TextButton(onClick = { actionsExpanded = false }) { Text("关闭") } })
    }
    if (nameDialog) {
        val target = nameTarget
        var name by remember(target?.id) { mutableStateOf(target?.name ?: "") }
        val trimmed = name.trim()
        val invalid = trimmed.isBlank() || trimmed.length > 50 || trimmed.any(Char::isISOControl) ||
            groups.any { it.id != target?.id && it.name == trimmed }
        AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { if (!busy) nameDialog = false },
            title = { Text(if (target == null) "新建分组" else "重命名分组") },
            text = { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text("分组名称") }, isError = invalid,
                supportingText = { Text(if (invalid) "名称非法或与现有分组重复" else if (target == null) "使用易于辨认的名称，如：每日任务" else "组内任务与执行顺序会一同保留") }) },
            confirmButton = { TextButton(enabled = !busy && !invalid, onClick = {
                mutate(if (target == null) "分组已创建" else "分组已重命名") {
                    if (target == null) TaskGroups.create(context, name, emptyList()).id
                    else TaskGroups.update(context, target.id, target.revision, name, target.taskNames).id
                }
            }) { Text(if (busy) "正在保存…" else if (target == null) "创建" else "保存") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { nameDialog = false }) { Text("取消") } })
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
            Text("所有任务均已在当前分组中。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onSave: (TaskGroup, List<String>) -> Unit) {
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
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
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
