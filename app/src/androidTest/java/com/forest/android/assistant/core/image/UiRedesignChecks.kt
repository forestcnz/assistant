package com.forest.android.assistant.core.image

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.forest.android.assistant.MainActivity
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.recording.*
import com.forest.android.assistant.core.task.*
import java.util.UUID

/** 使用独立任务验证真实文件替换、录制确认边界和等待期间停止整个队列。 */
internal object UiRedesignChecks {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val names = (1..4).map { "ui-check-${UUID.randomUUID().toString().take(12)}" }
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            val task = TaskManager.createTask(context, names[0]) ?: error("无法创建测试任务")
            listOf("", "../escape", "a/b", "a\\b", ".hidden", "x\n", "a".repeat(121)).forEach { invalid ->
                check(runCatching { AssetCropper.templateName(invalid) }.isFailure) { "非法模板名未被拒绝：$invalid" }
            }
            val file = AssetCropper.saveTemplate(context, task.name, "button", bitmap, CropRegion(0, 0, 24, 20), false)
            check(file.name == "button.png")
            val original = file.readBytes()
            check(runCatching { AssetCropper.saveTemplate(context, task.name, "button", bitmap, CropRegion(0, 0, 16, 12), false) }.isFailure)
            check(original.contentEquals(file.readBytes())) { "未确认覆盖时旧图发生变化" }
            AssetCropper.saveTemplate(context, task.name, "button", bitmap, CropRegion(0, 0, 16, 12), true)
            val replacement = BitmapFactory.decodeFile(file.path)
            check(replacement.width == 16 && replacement.height == 12); replacement.recycle()
            val history = ImageHistory.list(context, task.name).single()
            check(original.contentEquals(history.file.readBytes())) { "旧图没有完整存入历史" }
            ImageHistory.moveBack(context, task.name, history)
            check(original.contentEquals(file.readBytes())) { "移回历史后内容不一致" }

            val recorded = RecordingTask(context, names[1], 100, 100, RecordingMode.COORDINATE_ONLY)
            recorded.width = 1080; recorded.height = 1920
            recorded.addStep(RecordedOperation(RecordedOperation.Kind.CLICK, listOf(RecordedPoint(100, 120)), 50))
            recorded.persist(); RecordingReview.prepare(context, recorded)
            check(RecordingReview.read(context, names[1]).steps == 1)
            check(RecordingReview.read(context, names[1]).durationMs == 50L)
            RecordingReview.finish(context, names[1])
            check(!RecordingReview.pending(context, names[1]) && TaskManager.scriptFile(context, names[1]).isFile)
            check(runCatching { RecordingReview.discard(context, names[1]) }.isFailure) { "已保存的录制仍可被摘要丢弃" }

            val discarded = RecordingTask(context, names[2], 100, 100, RecordingMode.COORDINATE_ONLY)
            discarded.addStep(RecordedOperation(RecordedOperation.Kind.CLICK, listOf(RecordedPoint(100, 120)), 30))
            discarded.persist(); RecordingReview.prepare(context, discarded)
            RecordingReview.discard(context, names[2])
            check(!TaskManager.taskDir(context, names[2]).exists())

            check(TaskManager.createTask(context, names[3]) != null)
            TaskManager.scriptFile(context, names[0]).writeText("sleep(500);")
            TaskManager.scriptFile(context, names[3]).writeText("log('next task should not run'); sleep(10000);")
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SHOW_TASKS, true))
            }
            Thread.sleep(500)
            val interval = TaskExecutionSettings.queueIntervalSec(context)
            // 不修改用户配置；等待为 0 时改为在第二项运行期间停止。
            ScriptRunner.runQueue(context, listOf(names[0], names[3]))
            waitUntil { ScriptRunner.state.value.running && ScriptRunner.state.value.round == 1L }
            if (interval > 0) {
                waitUntil { ScriptRunner.state.value.waitingForTask == names[3] }
                check(ScriptRunner.state.value.remainingTasks == listOf(names[3]))
            } else waitUntil { ScriptRunner.state.value.taskName == names[3] }
            instrumentation.runOnMainSync { check(FloatyManager.showTaskPanel(context)) }
            ScriptRunner.stop()
            waitUntil { !ScriptRunner.state.value.running }
            check(ScriptRunner.state.value.remainingTasks.isEmpty() && ScriptRunner.state.value.waitingForTask == null)
            if (interval > 0) check(RunHistory.read(context, names[3]).isEmpty()) { "等待时停止后仍运行了下一任务" }
        } finally {
            ScriptRunner.stop()
            waitUntil { !ScriptRunner.state.value.running }
            bitmap.recycle()
            names.forEach { name -> if (TaskManager.taskDir(context, name).exists()) TaskManager.deleteTask(context, name).getOrThrow() }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (!predicate()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "等待 UI 回归状态超时" }
            Thread.sleep(20)
        }
    }
}
