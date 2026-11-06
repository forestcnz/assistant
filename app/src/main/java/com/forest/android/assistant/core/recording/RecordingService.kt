package com.forest.android.assistant.core.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.floaty.ConsoleOverlay
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.floaty.PickerOverlay
import com.forest.android.assistant.core.gesture.GestureClient
import com.forest.android.assistant.core.image.ScreenCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: RecordingTask? = null
    private var overlay: RecordingOverlay? = null
    private var recording = false
    private var busy = false
    private var finishRequested = false
    private var pausedAt = SystemClock.uptimeMillis()
    private var pausedTotal = 0L
    private var previousStart = 0L
    private var previousEnd = 0L
    private var rotation = -1
    private var persistenceFailed = false
    private var lastMessage = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FINISH) {
            finishRecording()
            return START_NOT_STICKY
        }
        if (task != null) return START_NOT_STICKY
        try {
            active = true
            startNotification()
            check(!ScriptRunner.state.value.running) { "请先停止正在运行的任务" }
            check(GestureClient.available()) { "请先开启无障碍服务" }
            task = RecordingTask(this, requireNotNull(intent?.getStringExtra("name")),
                intent.getIntExtra("cropWidth", 100), intent.getIntExtra("cropHeight", 100),
                RecordingMode.valueOf(intent.getStringExtra("mode") ?: RecordingMode.COORDINATE.name),
                intent.getIntExtra("searchRegionWidth", DEFAULT_SEARCH_REGION_SIZE),
                intent.getIntExtra("searchRegionHeight", DEFAULT_SEARCH_REGION_SIZE))
            FloatyManager.hideLauncher()
            PickerOverlay.hide()
            ConsoleOverlay.hide()
            overlay = RecordingOverlay(this, ::execute, ::toggle, ::finishRecording, ::pauseWithError)
            overlay?.show(false, 0)
            ScriptRunner.appendLog("操作录制已准备：${task?.name}，切换目标应用后点击开始")
        } catch (e: Exception) {
            notifyError(e.message ?: "无法启动录制")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun toggle() {
        if (busy) return
        val current = task ?: return
        if (recording) {
            pause()
        } else {
            if (!GestureClient.available()) return pauseWithError("无障碍已断开，请重新开启后继续")
            val size = screenSize()
            if (current.steps.isEmpty()) {
                current.width = size.x; current.height = size.y
                rotation = displayRotation()
            } else if (!sameDisplay(current)) {
                return pauseWithError("请恢复录制时的 ${current.width}×${current.height} 屏幕方向")
            }
            if (persistenceFailed) return pauseWithError("保存失败，请点击完成重试保存")
            pausedTotal += SystemClock.uptimeMillis() - pausedAt
            recording = true
            lastMessage = ""
        }
        showOverlay()
    }

    private fun execute(input: RecordedOperation) {
        val current = task ?: return
        if (!recording || busy) return
        if (!sameDisplay(current)) return pauseWithError("屏幕方向或尺寸已变化，请恢复后继续")
        if (input.points.any { it.x !in 0 until current.width || it.y !in 0 until current.height }) {
            return pauseWithError("操作超出屏幕范围，本次未执行")
        }
        busy = true
        scope.launch {
            var imagePath: String? = null
            var committed = false
            try {
                // 悬浮窗保持显示，只让出触点：触摸层缩到 1×1 并等重新布局生效
                overlay?.beginExecuting()
                var crop: RecordedCrop? = null
                var searchRegion: RecordedCrop? = null
                // 页面范围只需要屏幕尺寸，不需要截图；纯坐标模式则连模板也不保存
                if (current.mode != RecordingMode.COORDINATE_ONLY) {
                    searchRegion = RecordedCrop.around(input.points.first(), current.searchRegionWidth,
                        current.searchRegionHeight, current.width, current.height)
                }
                // 纯坐标模式与滑动步骤不截图，直接派发手势
                if (current.mode != RecordingMode.COORDINATE_ONLY &&
                    input.kind != RecordedOperation.Kind.SWIPE) {
                    val region = RecordedCrop.around(input.points.first(), current.cropWidth,
                        current.cropHeight, current.width, current.height)
                    crop = region
                    val target = Rect(region.left, region.top, region.left + region.width, region.top + region.height)
                    withContext(Dispatchers.IO) {
                        // 校验投影帧与屏幕尺寸一致：非全屏共享时坐标与裁切区域都会错位
                        ScreenCapture.projectionFrameSize()?.let { (frameWidth, frameHeight) ->
                            check(frameWidth == current.width && frameHeight == current.height) {
                                "截图尺寸与屏幕不一致，请使用全屏共享并恢复屏幕方向"
                            }
                        }
                        // 悬浮条保持显示，直接取最近一帧（页面静止时不会产生新帧）；只裁模板小图
                        val bitmap = ScreenCapture.captureRegion(
                            target, timeoutMs = RECORD_CAPTURE_TIMEOUT_MS, newerThanUptimeMs = 0
                        ) ?: error("截屏失败，本次未执行；请检查截屏授权")
                        imagePath = try {
                            current.saveImage(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                check(sameDisplay(current)) { "截屏期间屏幕方向发生变化，本次未执行" }
                val started = logicalTime()
                val success = withContext(Dispatchers.IO) {
                    GestureClient.dispatchRecorded(input.points.map { it.x.toFloat() to it.y.toFloat() }, input.durationMs)
                }
                val completed = logicalTime()
                check(success) { "手势未完成或被取消，本次未记录，请检查目标界面后继续" }
                val step = input.copy(
                    delayMs = if (previousEnd == 0L) 0 else (started - previousEnd).coerceAtLeast(0),
                    intervalMs = if (previousStart == 0L) 0 else (started - previousStart).coerceAtLeast(0),
                    image = imagePath, crop = crop, searchRegion = searchRegion
                )
                val stepCount = current.addStep(step)
                committed = true
                previousStart = started
                previousEnd = completed
                // 每步只写轻量快照；task.js 与配置在暂停/完成时生成，避免每步重写三个文件
                persistenceFailed = true
                withContext(Dispatchers.IO) { current.snapshot() }
                persistenceFailed = false
                val point = step.points.first()
                // 悬浮窗空间有限：坐标后紧跟与上一步起点的间隔（秒），省略“间隔”两字
                val gapSeconds = "%.1fs".format(step.intervalMs / 1000.0)
                lastMessage = "${if (step.kind == RecordedOperation.Kind.SWIPE) "滑动" else "点击"} " +
                    "(${point.x},${point.y}) $gapSeconds"
                ScriptRunner.appendLog("录制 $stepCount：${step.kind} ${step.points.first()}，间隔 ${step.intervalMs}ms，已执行")
            } catch (e: Exception) {
                if (!committed) withContext(Dispatchers.IO) { current.removeImage(imagePath) }
                pause()
                notifyError((if (committed) "操作已执行，但保存失败；点击完成重试：" else "") + e.message)
            } finally {
                busy = false
                if (finishRequested) finishRecording() else showOverlay()
            }
        }
    }

    /** 暂停录制：同时把内存中的步骤补成完整任务文件，失败时标记以便完成时重试 */
    private fun pause() {
        if (recording) {
            recording = false
            pausedAt = SystemClock.uptimeMillis()
        }
        // 完成流程会自己写完整文件，避免重复落盘
        if (!finishRequested) persistPending()
    }

    /** 后台生成 task.js / config.json，不在主线程做文件读写 */
    private fun persistPending() {
        val current = task ?: return
        if (current.steps.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { current.persist() }
                persistenceFailed = false
            } catch (e: Exception) {
                persistenceFailed = true
                notifyError("保存失败，请点击完成重试：${e.message}")
            }
        }
    }

    private fun pauseWithError(message: String) {
        pause()
        notifyError(message)
        showOverlay(message)
    }

    private fun showOverlay(message: String = lastMessage) {
        if (busy) return
        try {
            overlay?.show(recording, task?.steps?.size ?: 0, message)
        } catch (e: Exception) {
            notifyError("录制窗口无法恢复，已停止录制：${e.message}")
            stopSelf()
        }
    }

    private fun logicalTime() = SystemClock.uptimeMillis() - pausedTotal

    private fun finishRecording() {
        // 通知栏允许执行期间结束：等待当前操作回调和落盘，避免漏掉最后一步。
        finishRequested = true
        if (busy) return
        pause()
        busy = true
        overlay?.hide()
        scope.launch {
            try {
                val current = task
                withContext(Dispatchers.IO) { current?.persist(); current?.discardIfEmpty() }
                val message = if (current?.steps.isNullOrEmpty()) "录制结束：没有操作，未生成空任务"
                    else "已生成任务「${current?.name}」，共 ${current?.steps?.size} 步"
                Toast.makeText(this@RecordingService, message, Toast.LENGTH_LONG).show()
                ScriptRunner.appendLog(message)
                // 完成后停留在当前应用，不再跳回 App 内；任务可在 App 任务列表查看
                stopSelf()
            } catch (e: Exception) {
                finishRequested = false
                busy = false
                notifyError("保存失败，请释放存储空间后点击完成重试：${e.message}")
                showOverlay("保存失败，点击完成重试")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (recording) pauseWithError("屏幕方向已变化，录制暂停；恢复原方向后继续")
        else showOverlay()
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point = Point().also { getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it) }
    @Suppress("DEPRECATION")
    private fun displayRotation() = getSystemService(WindowManager::class.java).defaultDisplay.rotation
    private fun sameDisplay(current: RecordingTask): Boolean {
        val size = screenSize()
        return size.x == current.width && size.y == current.height && rotation == displayRotation()
    }

    private fun notifyError(message: String) {
        lastMessage = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        ScriptRunner.appendLog("操作录制：$message", 'W')
    }

    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        // minSdk 29：通知渠道必然可用，无需版本判断
        manager.createNotificationChannel(
            NotificationChannel("operation_recording", "操作录制", NotificationManager.IMPORTANCE_LOW))
        val finish = PendingIntent.getService(this, 0,
            Intent(this, RecordingService::class.java).setAction(ACTION_FINISH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "operation_recording")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("操作录制已开启")
            .setContentText("使用悬浮条开始、暂停或完成录制")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_save, "完成并保存", finish).build()
        ServiceCompat.startForeground(this, 1004, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onDestroy() {
        overlay?.hide()
        scope.cancel()
        task?.discardIfEmpty()
        active = false
        ScriptRunner.ensureLauncher(this)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_FINISH = "com.forest.android.assistant.action.FINISH_RECORDING"

        /** 录制每步等待“隐藏悬浮条后的新帧”的上限：超时按截屏失败处理，避免卡满 2 秒 */
        private const val RECORD_CAPTURE_TIMEOUT_MS = 500L

        @Volatile var active = false
            private set

        fun start(
            context: Context,
            name: String,
            cropWidth: Int,
            cropHeight: Int,
            mode: RecordingMode,
            searchRegionWidth: Int = DEFAULT_SEARCH_REGION_SIZE,
            searchRegionHeight: Int = DEFAULT_SEARCH_REGION_SIZE
        ) {
            check(!active) { "已有录制正在进行" }
            active = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)
                    .putExtra("name", name).putExtra("cropWidth", cropWidth)
                    .putExtra("cropHeight", cropHeight).putExtra("mode", mode.name)
                    .putExtra("searchRegionWidth", searchRegionWidth)
                    .putExtra("searchRegionHeight", searchRegionHeight))
            } catch (e: Exception) {
                active = false
                throw e
            }
        }
    }
}
