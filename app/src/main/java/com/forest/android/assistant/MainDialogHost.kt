package com.forest.android.assistant

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.recording.RecordingService
import com.forest.android.assistant.core.task.ImageHistory
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.task.ScheduleManager
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.AppUpdatePage
import com.forest.android.assistant.ui.AppToast as Toast
import com.forest.android.assistant.ui.ConfirmActionSheet
import com.forest.android.assistant.ui.ScriptMarket
import com.forest.android.assistant.ui.TransferPage
import com.forest.android.assistant.ui.main.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 主界面的全局弹层：任务菜单、素材、定时/循环、运行历史与截屏设置。 */

@Composable
internal fun MainActivity.MainDialogHost() {
    val runState by ScriptRunner.state.collectAsState()
    val updateState by appUpdate.state.collectAsState()
    if (showScriptMarket) {
        ScriptMarket(tasks.map { it.name }, onBack = { showScriptMarket = false }, onImported = { name ->
            // 刷新本地任务与详情缓存，导入成功后继续留在脚本市场。
            taskMetadataVersion++
            ScriptRunner.appendLog("已从脚本市场导入任务: $name")
            lifecycleScope.launch {
                tasks = withContext(Dispatchers.IO) { TaskManager.listTasks(this@MainDialogHost) }
            }
        }, onOpenTask = { name ->
            showScriptMarket = false
            menuTask = name
            lifecycleScope.launch {
                tasks = withContext(Dispatchers.IO) { TaskManager.listTasks(this@MainDialogHost) }
            }
        }, onUpdate = { showAppUpdate = true })
    }

    if (showTransferDialog) TransferPage(tasks.size, taskTransferBusy,
        tasks.isNotEmpty() && !runState.running && !RecordingService.active && !taskTransferBusy && !taskMutationBusy,
        onBack = { showTransferDialog = false },
        onImport = { importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        onExport = { exportAllTasks() }, onClear = { showClearTasksDialog = true })
    if (showCreateDialog) {
        CreateTaskDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, scaffold ->
                val created = TaskManager.createTask(this, name, scaffold)
                if (created == null) {
                    Toast.makeText(this, "创建失败：名称非法或已存在", Toast.LENGTH_SHORT).show()
                } else {
                    showCreateDialog = false
                    menuTask = created.name
                    Toast.makeText(this, "已创建「" + created.name + "」（" + scaffold.label + "）", Toast.LENGTH_SHORT).show()
                    ScriptRunner.appendLog("已创建任务: " + created.name + "（脚手架：" + scaffold.label + "）")
                    tasks = TaskManager.listTasks(this)
                }
            }
        )
    }

    menuTask?.let { name ->
        TaskMenuDialog(
            taskName = name,
            onDismiss = { menuTask = null },
            onEditScript = {
                editTask(name)
            },
            onRename = { renamingTask = name },
            onDelete = { deletingTask = name },
            onManageAssets = {
                assetsTask = name
                assets = TaskManager.imageInfos(this, name)
            },
            onHistory = {
                historyTask = name
                historyEntries = RunHistory.read(this, name)
            },
            onExportZip = {
                exportZip(name)
            },
            onLoop = {
                openLoopSettings(name)
            },
            onSchedule = {
                scheduleEditing = name
                currentSchedule = ScheduleManager.scheduleOf(this, name)
                scheduleType = currentSchedule?.type ?: ScheduleManager.Type.ONCE_AFTER_MINUTES
                scheduleValue = currentSchedule?.let { s ->
                    when (s.type) {
                        ScheduleManager.Type.ONCE_AFTER_MINUTES -> s.delayMinutes.toString()
                        ScheduleManager.Type.EVERY_MINUTES -> s.intervalMinutes.toString()
                        else -> "%02d:%02d".format(s.hour, s.minute)
                    }
                } ?: "1"
            }
        )
    }

    renamingTask?.let { name -> RenameTaskDialog(name) }
    deletingTask?.let { name ->
        val canDelete = !runState.running && !RecordingService.active && !taskMutationBusy && !taskTransferBusy
        AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { if (!taskMutationBusy) deletingTask = null },
            title = { Text("删除任务") },
            text = { Text("确定删除「$name」？脚本、素材、录制范围图和运行历史都会删除，定时任务也会取消。此操作无法撤销。") },
            confirmButton = {
                TextButton(enabled = canDelete, onClick = {
                    performTaskChange({ TaskManager.deleteTask(this, name) }, "已删除任务「$name」") { deletingTask = null; menuTask = null }
                }) { Text(if (taskMutationBusy) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !taskMutationBusy, onClick = { deletingTask = null }) { Text("取消") } }
        )
    }

    if (showClearTasksDialog) {
        AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { if (!taskMutationBusy) showClearTasksDialog = false },
            title = { Text("清空全部任务") },
            text = { Text("确定删除全部 ${tasks.size} 个任务？脚本、素材、录制范围图和运行历史都会删除，定时任务也会取消。此操作无法撤销。") },
            confirmButton = {
                TextButton(enabled = !taskMutationBusy, onClick = {
                    performTaskChange({ TaskManager.deleteAllTasks(this) }, "已清空全部任务") {
                        showClearTasksDialog = false
                    }
                }) { Text(if (taskMutationBusy) "正在清空…" else "清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !taskMutationBusy, onClick = { showClearTasksDialog = false }) { Text("取消") }
            }
        )
    }

    assetsTask?.let { name ->
        AssetsDialog(
            taskName = name,
            images = assets,
            onDismiss = { assetsTask = null },
            onRefresh = { assets = TaskManager.imageInfos(this, name) },
            onImport = {
                importImageLauncher.launch(arrayOf("image/*"))
            },
            onHistory = { openAssetsHistory(name) },
            onPreview = { info -> loadAssetPreview(info) }
        )
    }

    previewAsset?.let { info ->
        AssetPreviewDialog(
            info = info,
            bitmap = previewBitmap,
            onCrop = { cropAsset(info) },
            onDismiss = {
                previewLoadJob?.cancel()
                previewAsset = null
                previewBitmap = null
            }
        )
    }

    deletingAsset?.let { info ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { deletingAsset = null }, title = { Text("删除素材") },
            text = { Text("确定删除「${info.file.name}」？引用这张图片的脚本可能无法运行。此操作无法撤销。") },
            confirmButton = { TextButton(onClick = {
                if (TaskManager.deleteImage(info.file)) {
                    assetsTask?.let { assets = TaskManager.imageInfos(this@MainDialogHost, it) }
                    previewLoadJob?.cancel()
                    previewAsset = null
                    previewBitmap = null
                    deletingAsset = null
                } else Toast.makeText(this@MainDialogHost, "删除失败，请重试", Toast.LENGTH_SHORT).show()
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingAsset = null }) { Text("取消") } })
    }
    renameAsset?.let { info ->
        var name by remember(info.file) { mutableStateOf(info.file.name) }
        if (renamePreview == null) {
            RenameAssetDialog(
                info = info,
                name = name,
                onNameChange = { name = it },
                onDismiss = { renameAsset = null },
                onConfirm = { newName ->
                    val task = assetsTask ?: return@RenameAssetDialog
                    val preview = TaskManager.previewImageRename(this, task, info.file, newName)
                    if (preview == null) {
                        Toast.makeText(this, "重命名失败（名称非法、已存在或未修改）", Toast.LENGTH_SHORT).show()
                    } else {
                        // 确认前只预览，保留原输入以便返回修改。
                        renamePreview = preview
                    }
                }
            )
        }
    }

    renamePreview?.let { preview ->
        val task = assetsTask
        RenameRefPreviewDialog(
            preview = preview,
            onDismiss = { renamePreview = null },
            onConfirm = {
                renamePreview = null
                renameAsset = null
                if (task == null) return@RenameRefPreviewDialog
                val renamed = TaskManager.renameImageWithScript(preview)
                if (renamed != null) {
                    val synced = preview.plan?.replacementCount ?: 0
                    val tip = if (synced > 0) "已重命名为 ${renamed.name}，同步脚本 $synced 处引用"
                    else "已重命名为 ${renamed.name}"
                    Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
                    ScriptRunner.appendLog(tip)
                    assets = TaskManager.imageInfos(this, task)
                    assets.firstOrNull { it.file.name == renamed.name }?.let { loadAssetPreview(it) }
                } else {
                    Toast.makeText(this, "重命名失败（名称非法或已存在）", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    historyTask?.let { name ->
        RunHistoryDialog(
            taskName = name,
            entries = historyEntries,
            onDismiss = { historyTask = null },
            onClear = {
                RunHistory.clear(this, name)
                historyEntries = emptyList()
                Toast.makeText(this, "已清空运行历史", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (keepAliveDialog) {
        KeepAliveDialog(
            onDismiss = { keepAliveDialog = false },
            onOpenAppSettings = {
                keepAliveDialog = false
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:" + packageName))
                )
            }
        )
    }

    if (captureDialog) {
        CaptureChannelDialog(
            onDismiss = { captureDialog = false },
            onSelect = { mode -> applyCaptureMode(mode) }
        )
    }

    loopEditing?.let { name ->
        LoopDialog(
            taskName = name,
            mode = loopMode,
            count = loopCountValue,
            duration = loopDurationValue,
            durationUnit = loopDurationUnit,
            interval = loopIntervalValue,
            onModeChange = { loopMode = it },
            onCountChange = { loopCountValue = it },
            onDurationChange = { loopDurationValue = it },
            onDurationUnitChange = { loopDurationUnit = it },
            onIntervalChange = { loopIntervalValue = it },
            onDismiss = { loopEditing = null },
            onSave = { saveLoopSettings(name) }
        )
    }

    scheduleEditing?.let { name ->
        ScheduleDialog(
            taskName = name,
            current = currentSchedule,
            type = scheduleType,
            value = scheduleValue,
            onTypeChange = { t ->
                scheduleType = t
                scheduleValue = when (t) {
                    ScheduleManager.Type.DAILY_AT -> "08:00"
                    ScheduleManager.Type.EVERY_MINUTES -> "30"
                    else -> "1"
                }
            },
            onValueChange = { scheduleValue = it },
            onDismiss = { scheduleEditing = null },
            onSave = {
                val schedule = buildSchedule(name)
                if (schedule == null) {
                    Toast.makeText(this, "参数不合法", Toast.LENGTH_SHORT).show()
                } else if (ScheduleManager.upsert(this, schedule)) {
                    Toast.makeText(this, "已设置：${schedule.describe()}", Toast.LENGTH_LONG).show()
                    scheduleEditing = null
                } else {
                    Toast.makeText(this, "设置失败", Toast.LENGTH_SHORT).show()
                }
            },
            onCancelSchedule = {
                ScheduleManager.remove(this, name)
                Toast.makeText(this, "已取消定时", Toast.LENGTH_SHORT).show()
                scheduleEditing = null
            }
        )
    }

    assetsHistoryTask?.let { name ->
        AssetsHistoryDialog(
            entries = assetsHistory,
            busy = assetsHistoryBusy,
            error = assetsHistoryError,
            onDismiss = { assetsHistoryTask = null },
            onMoveBack = { restoringAsset = it }
        )
    }
    if (showAppUpdate) AppUpdatePage(updateState, appUpdate, this::installAppUpdate) { showAppUpdate = false }
    restoringAsset?.let { entry ->
        ConfirmActionSheet("移回历史素材？", "恢复「${entry.originalName}」并覆盖当前同名图片，请确认当前版本不再需要。",
            "覆盖并移回", onDismiss = { restoringAsset = null }, busy = assetsHistoryBusy,
            onConfirm = { restoringAsset = null; assetsHistoryTask?.let { moveHistoryImageBack(it, entry) } })
    }
}
