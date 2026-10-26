package com.forest.android.assistant.core.image

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import com.forest.android.assistant.core.floaty.ConsoleOverlay
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.MainActivity
import com.forest.android.assistant.ScreenshotExportActivity
import com.forest.android.assistant.core.engine.RuntimeStopChecks
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.ocr.OcrScriptChecks
import com.forest.android.assistant.core.task.TaskManager
import java.io.File

/** 无额外测试依赖的设备端回归入口，验证真实 Bitmap、悬浮窗和任务栈行为。 */
class ImageApiInstrumentation : Instrumentation() {
    private var onlyBurst = false
    private var onlyLoop = false
    private var onlyOcr = false
    private var onlyStop = false
    private var onlyNewApi = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        onlyBurst = arguments?.getString("onlyBurst") == "true"
        onlyLoop = arguments?.getString("onlyLoop") == "true"
        onlyOcr = arguments?.getString("onlyOcr") == "true"
        onlyStop = arguments?.getString("onlyStop") == "true"
        onlyNewApi = arguments?.getString("onlyNewApi") == "true"
        Log.i(TAG, "Instrumentation onCreate; starting test thread")
        start()
    }

    override fun onStart() {
        Log.i(TAG, "Instrumentation onStart entered")
        super.onStart()
        Log.i(TAG, "Instrumentation base onStart returned")
        val result = Bundle()
        try {
            if (onlyNewApi) {
                report("New script APIs end-to-end")
                NewApiChecks.run(targetContext)
                result.putString("stream", "新增脚本 API 回归通过")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (onlyStop) {
                report("Script runtime stops busy JavaScript")
                RuntimeStopChecks.run(targetContext)
                result.putString("stream", "脚本运行时停止回归通过")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (onlyOcr) {
                report("OCR normalized contains script API")
                OcrScriptChecks.run(targetContext)
                result.putString("stream", "OCR 宽松包含匹配脚本回归通过")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (onlyLoop) {
                report("Real screenshot loop keeps run bar visible")
                testRealScreenshotLoopKeepsRunBarVisible()
                result.putString("stream", "真实循环截图悬浮窗回归通过")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (onlyBurst) {
                report("Repeated captures keep run bar visible")
                testRepeatedCapturesKeepRunBarVisible()
                result.putString("stream", "连续截图悬浮窗回归通过")
                finish(Activity.RESULT_OK, result)
                return
            }
            report("Bitmap findColor")
            testFindColorPreservesRowMajorOrderAndRegionCoordinates()
            report("Bitmap compact findMultiColors")
            testFindMultiColorsChecksWholeImageOffsetsInCandidateOrder()
            report("Mutable Bitmap cache invalidation")
            testMutableBitmapGenerationInvalidatesScreenGrayCache()
            report("Bitmap sparse fallback")
            testSparseOffsetsRetainPointReadSemantics()
            report("Screenshot overlay hide/restore")
            testScreenshotOverlayHideRestore()
            report("Screenshot export task isolation")
            testScreenshotExportStartsInSeparateTask()
            report("Repeated captures keep run bar visible")
            testRepeatedCapturesKeepRunBarVisible()
            result.putString("stream", "设备回归检查通过")
            Log.i(TAG, "Instrumentation checks passed")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            Log.e(TAG, "Instrumentation checks failed", failure)
            result.putString("stream", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun report(step: String) {
        Log.i(TAG, step)
        sendStatus(0, Bundle().apply { putString("stream", step) })
    }

    private fun testFindColorPreservesRowMajorOrderAndRegionCoordinates() {
        withBitmap(8, 6) { bitmap, imageId ->
            bitmap.setPixel(3, 2, Color.rgb(250, 4, 5))
            bitmap.setPixel(2, 3, Color.RED)
            checkEquals(ImageApi.Point(3, 2), ImageApi.findColor(
                imageId, Color.RED, threshold = 5, region = intArrayOf(2, 1, 3, 3)
            ))
            checkEquals(ImageApi.Point(2, 3), ImageApi.findColor(
                imageId, Color.RED, threshold = 4, region = intArrayOf(2, 1, 3, 3)
            ))
        }
    }

    private fun testFindMultiColorsChecksWholeImageOffsetsInCandidateOrder() {
        withBitmap(8, 7) { bitmap, imageId ->
            bitmap.setPixel(4, 4, Color.RED)
            bitmap.setPixel(5, 4, Color.RED)
            bitmap.setPixel(3, 4, Color.WHITE)
            bitmap.setPixel(5, 3, Color.WHITE)
            bitmap.setPixel(3, 3, Color.BLACK)
            bitmap.setPixel(6, 4, Color.BLACK)
            val offsets = listOf(
                intArrayOf(-2, 0, Color.WHITE),
                intArrayOf(0, -1, Color.WHITE),
                intArrayOf(-2, -1, Color.BLACK),
                intArrayOf(1, 0, Color.BLACK)
            )
            checkEquals(ImageApi.Point(5, 4), ImageApi.findMultiColors(
                imageId, Color.RED, offsets, threshold = 0, region = intArrayOf(4, 4, 2, 2)
            ))
        }
    }

    private fun testSparseOffsetsRetainPointReadSemantics() {
        withBitmap(8, 8) { bitmap, imageId ->
            bitmap.setPixel(1, 1, Color.RED)
            bitmap.setPixel(1, 2, Color.WHITE)
            bitmap.setPixel(6, 6, Color.WHITE)
            checkEquals(ImageApi.Point(1, 1), ImageApi.findMultiColors(
                imageId, Color.RED,
                listOf(intArrayOf(0, 1, Color.WHITE), intArrayOf(5, 5, Color.WHITE)),
                threshold = 0
            ))
        }
    }

    private fun testMutableBitmapGenerationInvalidatesScreenGrayCache() {
        val template = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        val screen = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 12) for (x in 0 until 12) {
            val color = if ((x + y) % 2 == 0) Color.WHITE else Color.BLACK
            template.setPixel(x, y, color)
            screen.setPixel(10 + x, 9 + y, color)
        }
        val screenId = ImageApi.storeImage(screen)
        val templateId = ImageApi.storeImage(template)
        try {
            checkEquals(ImageApi.Point(16, 15), ImageApi.findImage(screenId, templateId, threshold = 0.99))
            screen.eraseColor(Color.RED)
            checkEquals(null, ImageApi.findImage(screenId, templateId, threshold = 0.99))
        } finally {
            ImageApi.releaseImage(screenId)
            ImageApi.releaseImage(templateId)
            screen.recycle()
            template.recycle()
        }
    }

    private fun testScreenshotOverlayHideRestore() {
        val context = targetContext
        check(FloatyManager.hasOverlayPermission(context)) {
            "MuMu 未授予悬浮窗权限，无法验证截图隐藏/恢复"
        }
        val hadLauncher = FloatyManager.isShowing()
        val hadConsole = ConsoleOverlay.isShowing()
        try {
            var launcherShown = hadLauncher
            var consoleShown = hadConsole
            runOnMainSync {
                if (!launcherShown) launcherShown = FloatyManager.showLauncher(context, keepAlive = false)
                if (!consoleShown) consoleShown = ConsoleOverlay.show(context)
            }
            check(launcherShown) { "无法显示悬浮球" }
            check(consoleShown) { "无法显示控制台" }
            runOnMainSync {
                check(FloatyManager.hideLauncherForScreenshot()) { "悬浮球临时隐藏失败" }
                check(ConsoleOverlay.hideForScreenshot()) { "控制台临时隐藏失败" }
                check(FloatyManager.restoreLauncherAfterScreenshot()) { "悬浮球恢复失败" }
                check(ConsoleOverlay.restoreAfterScreenshot()) { "控制台恢复失败" }
            }
        } finally {
            runOnMainSync {
                if (!hadConsole) ConsoleOverlay.hide()
                if (!hadLauncher) FloatyManager.hideLauncher(context, stopKeepAlive = false)
            }
        }
    }

    private fun testRepeatedCapturesKeepRunBarVisible() {
        val context = targetContext
        val name = "burst-${java.util.UUID.randomUUID()}"
        check(!ScriptRunner.state.value.running) { "已有任务在运行" }
        val task = TaskManager.createTask(context, name) ?: error("无法创建截图回归任务")
        val hadConsole = ConsoleOverlay.isShowing()
        try {
            TaskManager.scriptFile(context, task.name).writeText("sleep(15000);")
            ScriptRunner.run(context, task.name)
            waitUntil(10_000) { FloatyManager.currentTask() == name && launcherVisibility() == View.VISIBLE }
            repeat(12) { index ->
                val captured = ScriptRunner.captureScreenForScript(context) { after ->
                    check(after == 0L) { "运行条不应为第 ${index + 1} 次截图而隐藏" }
                    check(launcherVisibility() == View.VISIBLE) { "截图期间运行条不可见" }
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                }
                check(captured != null) { "模拟截图失败" }
                captured.recycle()
                check(launcherVisibility() == View.VISIBLE) { "第 ${index + 1} 次截图后运行条不可见" }
                Thread.sleep(50)
            }
            check(ScriptRunner.state.value.running) { "截图回归任务提前结束" }
            runOnMainSync { check(ConsoleOverlay.show(context)) { "无法显示控制台" } }
            ScriptRunner.captureScreenForScript(context) { after ->
                check(after > 0) { "控制台截图未请求隐藏后的新帧" }
                check(launcherVisibility() == View.VISIBLE) { "隐藏控制台时运行条不可见" }
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            }?.recycle()
            check(launcherVisibility() == View.VISIBLE) { "控制台截图后运行条不可见" }
            ScriptRunner.stop()
            waitUntil(5_000) { !ScriptRunner.state.value.running }
            check(launcherVisibility() != View.INVISIBLE) { "任务停止后悬浮窗仍不可见" }
            waitUntil(2_000) { consoleVisibility() == View.VISIBLE }
        } finally {
            if (ScriptRunner.state.value.running) {
                ScriptRunner.stop()
                waitUntil(5_000) { !ScriptRunner.state.value.running }
            }
            if (!hadConsole) runOnMainSync { ConsoleOverlay.hide() }
            TaskManager.deleteTask(context, name)
        }
    }

    private fun testRealScreenshotLoopKeepsRunBarVisible() {
        val context = targetContext
        val taskName = "ScreenshotLoopTest"
        check(FloatyManager.hasOverlayPermission(context)) { "未授予悬浮窗权限" }
        check(TaskManager.scriptFile(context, taskName).isFile) { "请先安装循环截图脚本任务" }
        check(!ScriptRunner.state.value.running) { "已有任务在运行" }
        startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForIdleSync()
        try {
            ScriptRunner.run(context, taskName)
            waitUntil(10_000) {
                ScriptRunner.state.value.running && FloatyManager.currentTask() == taskName
            }
            val deadline = System.currentTimeMillis() + 120_000
            var visibleSamples = 0
            while (ScriptRunner.state.value.running && System.currentTimeMillis() < deadline) {
                check(launcherVisibility() == View.VISIBLE) {
                    "第 $visibleSamples 次采样发现运行条不可见"
                }
                visibleSamples++
                Thread.sleep(50)
            }
            check(!ScriptRunner.state.value.running) { "循环截图任务超时" }
            check(visibleSamples >= 10) { "截图任务运行时间过短：$visibleSamples 次采样" }
            check(ScriptRunner.logs.value.any { it.msg.contains("连续截图完成：40 次") }) {
                "循环截图未完成：${ScriptRunner.logs.value.takeLast(5).map { it.msg }}"
            }
            val images = TaskManager.imagesDir(context, taskName)
            check(File(images, "loop-1.png").length() > 0L) { "首张截图未保存" }
            check(File(images, "loop-40.png").length() > 0L) { "末张截图未保存" }
        } finally {
            if (ScriptRunner.state.value.running) {
                ScriptRunner.stop()
                waitUntil(5_000) { !ScriptRunner.state.value.running }
            }
        }
    }

    private fun consoleVisibility(): Int {
        var visibility = -1
        runOnMainSync {
            val field = ConsoleOverlay::class.java.getDeclaredField("rootView")
            field.isAccessible = true
            visibility = (field.get(ConsoleOverlay) as? View)?.visibility ?: -1
        }
        return visibility
    }

    private fun launcherVisibility(): Int {
        var visibility = -1
        runOnMainSync {
            val field = FloatyManager::class.java.getDeclaredField("capsuleView")
            field.isAccessible = true
            visibility = (field.get(FloatyManager) as? View)?.visibility ?: -1
        }
        return visibility
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        check(condition()) { "等待截图回归状态超时" }
    }

    private fun testScreenshotExportStartsInSeparateTask() {
        val context = targetContext
        val tasks = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // compileSdk 37 起 appTasks 的元素类型可空，过滤掉空项再匹配主界面
        fun mainTaskId(): Int? = tasks.appTasks.filterNotNull().firstOrNull {
            it.taskInfo?.baseActivity?.className == MainActivity::class.java.name
        }?.taskInfo?.taskId
        if (mainTaskId() == null) {
            runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val appTaskId = mainTaskId() ?: error("主 App 任务不存在")
        val source = File(context.cacheDir, "screenshot-export-task-check.png")
        val screenshot = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        val monitor = addMonitor(ScreenshotExportActivity::class.java.name, null, false)
        var export: Activity? = null
        try {
            runOnMainSync {
                context.startActivity(
                    Intent(context, ScreenshotExportActivity::class.java)
                        .putExtra(ScreenshotExportActivity.EXTRA_SOURCE, source.absolutePath)
                        .putExtra(ScreenshotExportActivity.EXTRA_NAME, source.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            export = waitForMonitorWithTimeout(monitor, 10_000)
                ?: error("截图保存页未启动")
            check(export.taskId != appTaskId) {
                "截图保存页复用了主 App 任务：taskId=$appTaskId"
            }
            check(export.isTaskRoot) { "截图保存页不是独立任务的根 Activity" }
        } finally {
            export?.let { activity -> runOnMainSync { activity.finishAndRemoveTask() } }
            removeMonitor(monitor)
            source.delete()
        }
    }

    private inline fun withBitmap(width: Int, height: Int, action: (Bitmap, Int) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val imageId = ImageApi.storeImage(bitmap)
        try {
            action(bitmap, imageId)
        } finally {
            ImageApi.releaseImage(imageId)
            bitmap.recycle()
        }
    }

    private fun checkEquals(expected: Any?, actual: Any?) {
        check(expected == actual) { "expected=$expected, actual=$actual" }
    }

    private companion object {
        const val TAG = "ImageApiInstrumentation"
    }
}
