package com.forest.android.assistant

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.forest.android.assistant.MainActivity.DurationUnit
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.recording.RecordingService
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.task.ScheduleManager
import com.forest.android.assistant.core.task.TaskLoop
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.core.task.TaskOrder
import com.forest.android.assistant.core.task.TaskZip
import com.forest.android.assistant.ui.AppToast as Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 主界面的业务动作：任务变更、导入导出、截屏通道与悬浮工具。 */

internal fun MainActivity.openFloatingTasks() {
    if (!FloatyManager.hasOverlayPermission(this)) {
        FloatyManager.requestOverlayPermission(this)
        return
    }
    if (!ScriptRunner.launcherEnabled.value) ScriptRunner.toggleLauncher(this)
    if (!FloatyManager.showTaskPanel(this)) Toast.makeText(this, "无法打开悬浮面板，请重新开启悬浮球", Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.editTask(name: String) {
    val err = ScriptRunner.lastError.value?.takeIf { it.taskName == name }
    startActivity(Intent(this, ScriptEditorActivity::class.java)
        .putExtra(ScriptEditorActivity.EXTRA_TASK, name)
        .putExtra(ScriptEditorActivity.EXTRA_ERROR_LINE, err?.line ?: 0)
        .putExtra(ScriptEditorActivity.EXTRA_ERROR_MSG, err?.message))
}

internal fun MainActivity.editHistoryError(taskName: String, entry: RunHistory.Entry) {
    val source = listOfNotNull(entry.message, entry.stack).joinToString("\n")
    val line = Regex("task\\.js:(\\d+)(?::\\d+)?").find(source)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    startActivity(Intent(this, ScriptEditorActivity::class.java)
        .putExtra(ScriptEditorActivity.EXTRA_TASK, taskName)
        .putExtra(ScriptEditorActivity.EXTRA_ERROR_LINE, line)
        .putExtra(ScriptEditorActivity.EXTRA_ERROR_MSG, entry.message))
}

/** 从任务配置加载循环设置；旧任务没有 mode 时沿用原有执行次数。 */
internal fun MainActivity.openLoopSettings(taskName: String) {
    val loop = TaskManager.readConfig(this, taskName).loop
    loopMode = loop.mode
    loopCountValue = loop.count.toString()
    loopDurationUnit = when {
        loop.durationSec % 3600L == 0L -> DurationUnit.HOUR
        loop.durationSec % 60L == 0L -> DurationUnit.MINUTE
        else -> DurationUnit.SECOND
    }
    loopDurationValue = (loop.durationSec / loopDurationUnit.seconds).toString()
    loopIntervalValue = (loop.intervalSec.takeIf { it > 0 } ?: 3).toString()
    loopEditing = taskName
}

/** 校验界面输入后更新 loop，保留任务的脚本、素材及其他配置字段。 */
internal fun MainActivity.saveLoopSettings(taskName: String) {
    val old = TaskManager.readConfig(this, taskName).loop
    val count = if (loopMode == TaskLoop.Mode.COUNT) loopCountValue.trim().toIntOrNull() else old.count
    val amount = loopDurationValue.trim().toLongOrNull()
    val duration = if (loopMode == TaskLoop.Mode.DURATION)
        amount?.takeIf { it in 1L..(Int.MAX_VALUE.toLong() / loopDurationUnit.seconds) }
            ?.times(loopDurationUnit.seconds)
    else old.durationSec
    val interval = loopIntervalValue.trim().toIntOrNull()
    if (count == null || count < 1 || duration == null || interval == null || interval < 0) {
        Toast.makeText(this, "请输入有效的次数、时长和间隔", Toast.LENGTH_SHORT).show()
        return
    }
    val loop = TaskLoop(loopMode, count, duration, interval)
    TaskManager.updateLoop(this, taskName, loop)
        .onSuccess {
            loopEditing = null
            taskMetadataVersion++
            Toast.makeText(this, "已设置循环：${loop.describe()}", Toast.LENGTH_SHORT).show()
        }
        .onFailure { Toast.makeText(this, it.message ?: "循环设置保存失败", Toast.LENGTH_LONG).show() }
}

/** 把对话框输入解析为定时设置；参数非法返回 null */
internal fun MainActivity.buildSchedule(taskName: String): ScheduleManager.Schedule? = when (scheduleType) {
    ScheduleManager.Type.ONCE_AFTER_MINUTES -> {
        val m = scheduleValue.trim().toIntOrNull() ?: return null
        if (m <= 0) null
        else ScheduleManager.Schedule(taskName, scheduleType, delayMinutes = m)
    }

    ScheduleManager.Type.EVERY_MINUTES -> {
        val m = scheduleValue.trim().toIntOrNull() ?: return null
        if (m < 15) null
        else ScheduleManager.Schedule(taskName, scheduleType, intervalMinutes = m)
    }

    ScheduleManager.Type.DAILY_AT -> {
        val parts = scheduleValue.trim().split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val min = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) null
        else ScheduleManager.Schedule(taskName, scheduleType, hour = h, minute = min)
    }
}

/** 素材预览：显示图片与尺寸/大小信息 */
internal fun MainActivity.loadAssetPreview(info: TaskManager.ImageInfo) {
    previewLoadJob?.cancel()
    previewLoadJob = lifecycleScope.launch {
        val decoded = withContext(Dispatchers.IO) {
            try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(info.file.absolutePath, bounds)
                var scale = 1
                while (bounds.outWidth / scale > 720 || bounds.outHeight / scale > 720) scale *= 2
                android.graphics.BitmapFactory.decodeFile(
                    info.file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                )
            } catch (_: Exception) {
                null
            }
        }
        if (previewLoadJob?.isActive == true && !isFinishing && !isDestroyed) {
            previewBitmap = decoded
            previewAsset = info
        }
    }
}

internal fun MainActivity.installAppUpdate() {
    fun taskBusy(): Boolean {
        if (!ScriptRunner.state.value.running && !RecordingService.active) return false
        appUpdate.showNotice("请在任务或录制结束后安装")
        return true
    }
    if (taskBusy()) return
    try {
        if (!packageManager.canRequestPackageInstalls()) {
            appUpdate.showNotice("允许来自此来源的应用后，返回并点击立即安装")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
            return
        }
    } catch (_: ActivityNotFoundException) {
        appUpdate.showNotice("系统无法打开安装来源设置")
        return
    } catch (_: SecurityException) {
        appUpdate.showNotice("系统限制了应用安装")
        return
    }
    lifecycleScope.launch {
        val installer = appUpdate.prepareInstaller() ?: return@launch
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || taskBusy()) return@launch
        try {
            startActivity(installer)
        } catch (_: ActivityNotFoundException) {
            appUpdate.showNotice("系统无法打开 APK 安装器")
        } catch (_: SecurityException) {
            appUpdate.showNotice("系统限制了应用安装")
        }
    }
}

/** 导出任务 ZIP，并让用户选择保存位置。 */
internal fun MainActivity.exportZip(taskName: String) {
    if (taskTransferBusy) return
    taskTransferBusy = true
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { TaskZip.exportTask(this@exportZip, taskName) }
        if (result == null) {
            taskTransferBusy = false
            Toast.makeText(this@exportZip, "导出失败", Toast.LENGTH_SHORT).show()
            return@launch
        }
        requestZipSaveLocation(result.file)
    }
}

/** 一键将当前全部任务导出为一个 ZIP，并让用户选择保存位置。 */
internal fun MainActivity.exportAllTasks() {
    if (taskTransferBusy) return
    taskTransferBusy = true
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { TaskZip.exportAll(this@exportAllTasks) }
        if (result == null) {
            taskTransferBusy = false
            Toast.makeText(this@exportAllTasks, "导出全部任务失败", Toast.LENGTH_LONG).show()
            return@launch
        }
        requestZipSaveLocation(result.file)
    }
}

private fun MainActivity.requestZipSaveLocation(file: File) {
    pendingExportFile = file
    try {
        exportZipLauncher.launch(file.name)
    } catch (e: Exception) {
        file.delete()
        pendingExportFile = null
        taskTransferBusy = false
        Toast.makeText(this, "无法打开保存位置：${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** 只展示选择，不探测权限或替用户选择通道。 */
internal fun MainActivity.openCaptureDialog() {
    if (captureModeTesting) return
    selectedCaptureMode = ScreenCapture.mode(this)
    captureModeError = null
    captureTestResult = null
    captureDialog = true
}

/** 确认时先测试真实截图；成功才保存并关闭，失败允许重新选择。 */
internal fun MainActivity.applyCaptureMode(mode: ScreenCapture.Mode) {
    if (mode == ScreenCapture.Mode.UNSELECTED || captureModeTesting) return
    captureModeTesting = true
    captureModeError = null
    captureTestResult = null
    lifecycleScope.launch {
        try {
            val result = ScreenCapture.testAndSelectMode(applicationContext, mode)
            captureReady = ScreenCapture.captureAvailableCached(this@applyCaptureMode)
            // 成功后停留结果页供用户核对通道和真实画面尺寸，再手动完成。
            captureTestResult = result
            if (!result.success) captureModeError = result.message
        } finally {
            captureModeTesting = false
        }
    }
}

internal fun MainActivity.performTaskChange(operation: () -> Result<*>, successMessage: String, onSuccess: () -> Unit) {
    if (taskMutationBusy) return
    taskMutationBusy = true
    lifecycleScope.launch {
        try {
            val result = withContext(Dispatchers.IO) { operation() }
            val snapshot = withContext(Dispatchers.IO) {
                Triple(
                    TaskManager.listTasks(this@performTaskChange),
                    ScheduleManager.load(this@performTaskChange)
                        .filter { it.enabled }.associateBy { it.taskName },
                    TaskOrder.read(this@performTaskChange).revision
                )
            }
            tasks = snapshot.first
            schedules = snapshot.second
            // 改名/删除会同步更新顺序文件，这里立即同步版本，随后的拖拽保存不会误判冲突
            taskOrderRevision = snapshot.third
            taskMetadataVersion++
            if (result.isSuccess) {
                ScriptRunner.appendLog(successMessage)
                Toast.makeText(this@performTaskChange, successMessage, Toast.LENGTH_SHORT).show()
                onSuccess()
            } else Toast.makeText(this@performTaskChange, result.exceptionOrNull()?.message ?: "操作失败", Toast.LENGTH_LONG).show()
        } finally {
            taskMutationBusy = false
        }
    }
}
