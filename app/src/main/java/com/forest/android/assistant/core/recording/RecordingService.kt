package com.forest.android.assistant.core.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forest.android.assistant.MainActivity
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
import kotlinx.coroutines.delay
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
        overlay?.hide()
        val hiddenAt = SystemClock.uptimeMillis()
        scope.launch {
            var imagePath: String? = null
            var contextPath: String? = null
            var committed = false
            try {
                // 留出窗口移除的合成时间，并要求投影帧晚于窗口移除时刻。
                delay(100)
                var crop: RecordedCrop? = null
                var searchRegion: RecordedCrop? = null
                withContext(Dispatchers.IO) {
                    val screen = ScreenCapture.capture(newerThanUptimeMs = hiddenAt)
                        ?: error("截屏失败，本次未执行；请检查截屏授权")
                    check(screen.width == current.width && screen.height == current.height) {
                        "截图尺寸与屏幕不一致，请使用全屏共享并恢复屏幕方向"
                    }
                    fun saveRegion(region: RecordedCrop, isContext: Boolean): String {
                        val bitmap = Bitmap.createBitmap(screen, region.left, region.top, region.width, region.height)
                        return try { current.saveImage(bitmap, isContext) } finally {
                            // MediaProjection 返回共享帧，不能回收源图。
                            if (bitmap !== screen) bitmap.recycle()
                        }
                    }
                    val pageRegion = RecordedCrop.around(input.points.first(), current.searchRegionWidth,
                        current.searchRegionHeight, screen.width, screen.height)
                    searchRegion = pageRegion
                    contextPath = saveRegion(pageRegion, true)
                    if (input.kind != RecordedOperation.Kind.SWIPE) {
                        val region = RecordedCrop.around(input.points.first(), current.cropWidth, current.cropHeight, screen.width, screen.height)
                        crop = region
                        imagePath = saveRegion(region, false)
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
                    image = imagePath, crop = crop, searchRegion = searchRegion, contextImage = contextPath
                )
                current.steps.add(step)
                committed = true
                previousStart = started
                previousEnd = completed
                persistenceFailed = true
                withContext(Dispatchers.IO) { current.persist() }
                persistenceFailed = false
                val point = step.points.first()
                lastMessage = "${if (step.kind == RecordedOperation.Kind.SWIPE) "滑动" else "点击"} (${point.x}, ${point.y}) · 间隔 ${step.intervalMs}ms"
                ScriptRunner.appendLog("录制 ${current.steps.size}：${step.kind} ${step.points.first()}，间隔 ${step.intervalMs}ms，已执行")
            } catch (e: Exception) {
                if (!committed) withContext(Dispatchers.IO) { current.removeImage(imagePath); current.removeImage(contextPath) }
                pause()
                notifyError((if (committed) "操作已执行，但保存失败；点击完成重试：" else "") + e.message)
            } finally {
                busy = false
                if (finishRequested) finishRecording() else showOverlay()
            }
        }
    }

    private fun pause() {
        if (recording) {
            recording = false
            pausedAt = SystemClock.uptimeMillis()
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
                startActivity(Intent(this@RecordingService, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SHOW_TASKS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
