package com.forest.android.assistant.core.image

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 截屏前台服务：持有 MediaProjection，通过 VirtualDisplay + ImageReader 持续取帧。
 *
 * Android 14+ 要求先 startForeground(type=mediaProjection) 再获取投影，
 * 且同一次授权令牌只能使用一次（请求新投影需重新弹窗授权）。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // 屏幕方向监听：横竖屏切换时重建虚拟屏，保证截屏方向与屏幕一致
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var rotation: Int = android.view.Surface.ROTATION_0

    private val frames = LatestFrameBuffer<Image, Bitmap> { image -> imageToBitmap(image) }

    private var width = 0
    private var height = 0

    companion object {
        private const val TAG = "ScriptJs"
        private const val CHANNEL_ID = "screen_capture"
        private const val FRESH_DELAY_MS = 150L
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        @Volatile
        private var ready = false

        /** 投影就绪时刻：建立瞬间的首帧可能仍是授权弹窗画面，需按时间过滤 */
        @Volatile
        private var readyAtMillis = 0L

        @Volatile
        private var readyLatch: CountDownLatch? = null

        /** 投影是否已就绪（可截屏） */
        fun isReady(): Boolean = ready

        /** 最近一帧截图；未就绪返回 null */
        fun latestFrame(): Bitmap? = instance?.readFrame()

        /** 已刷新过的帧（跳过投影建立瞬间的初始帧）；脚本侧优先使用 */
        fun freshFrame(): Bitmap? = instance?.readFrame(readyAtMillis + FRESH_DELAY_MS)

        /** 录制隐藏悬浮层后仅接受新帧，禁止返回含录制控件的缓存截图。 */
        fun frameAfter(uptimeMs: Long): Bitmap? =
            instance?.readFrame(maxOf(uptimeMs, readyAtMillis + FRESH_DELAY_MS))

        /** 启动前台服务并用授权结果建立投影，阻塞等待就绪 */
        fun startAndAwait(context: Context, resultCode: Int, data: Intent, timeoutMs: Long): Boolean {
            if (ready) return true
            val latch = CountDownLatch(1)
            readyLatch = latch
            ready = false
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ready
            } catch (e: Exception) {
                Log.w(TAG, "启动截屏服务失败: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun signalReady(ok: Boolean) {
            ready = ok
            readyLatch?.countDown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先进入前台（Android 14+ 获取投影的前置条件）
        startForegroundCompat()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            Log.w(TAG, "截屏服务缺少授权数据")
            signalReady(false)
            stopSelf()
            return START_NOT_STICKY
        }
        setupProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        // minSdk 29：通知渠道必然可用
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "雕雕助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "自动化脚本识别界面时使用屏幕截图" }
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("雕雕助手")
            .setContentText("正在为自动化脚本提供屏幕截图")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        // minSdk 29：Q 起才引入前台服务类型，本分支恒为真
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground 失败: ${e.message}")
        }
    }

    /** 读取当前屏幕尺寸（随方向变化：竖屏 1260x2844，横屏 2844x1260） */
    private fun readScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(android.view.WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }

    private fun setupProjection(resultCode: Int, data: Intent) {
        try {
            val size = readScreenSize()
            width = size.first
            height = size.second

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(resultCode, data)
            if (mp == null) {
                Log.w(TAG, "获取 MediaProjection 失败")
                signalReady(false)
                stopSelf()
                return
            }
            projection = mp

            val ht = HandlerThread("screen-capture").apply { start() }
            thread = ht
            val h = Handler(ht.looper)
            handler = h

            // Android 14+ 要求 createVirtualDisplay 之前注册回调
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 已停止")
                    release()
                    stopSelf()
                }
            }, h)

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            reader.setOnImageAvailableListener({ r -> consumeLatest(r) }, h)

            virtualDisplay = mp.createVirtualDisplay(
                "script-capture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                h
            )
            instance = this
            readyAtMillis = android.os.SystemClock.uptimeMillis()

            // 记录建屏时的屏幕方向，并监听旋转：横屏游戏等场景下方向变化时重建虚拟屏，
            // 否则截屏 buffer 固定为授权时的方向，画面被旋转 90°，找图匹配不上、点击坐标也不对
            rotation = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
                ?: android.view.Surface.ROTATION_0
            registerRotationListener(h)

            signalReady(true)
            Log.i(TAG, "截屏服务就绪: ${width}x$height rotation=$rotation")
        } catch (e: Exception) {
            Log.w(TAG, "建立投影失败: ${e.message}")
            signalReady(false)
            stopSelf()
        }
    }

    /** 注册屏幕方向监听（回调跑在截屏线程，与取帧串行，避免竞争） */
    private fun registerRotationListener(h: Handler) {
        val dm = getSystemService(DisplayManager::class.java)
        displayManager = dm
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                val newRotation = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: return
                if (newRotation != rotation) h.post { rebuildDisplay() }
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, h)
    }

    /**
     * 屏幕旋转后重建虚拟屏：按新方向重新读取宽高（宽高对调），
     * 复用同一个 MediaProjection（授权令牌只能用一次，不能重新获取）。
     * 重建后截屏方向与屏幕实时方向一致，坐标系也与无障碍点击一致。
     */
    private fun rebuildDisplay() {
        val mp = projection ?: return
        val dm = displayManager ?: return
        val newRotation = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: return
        if (newRotation == rotation) return
        rotation = newRotation
        try {
            val size = readScreenSize()
            synchronized(frames) {
                frames.clear()
                imageReader?.close()
                imageReader = null
                readyAtMillis = android.os.SystemClock.uptimeMillis()
            }
            virtualDisplay?.release()
            width = size.first
            height = size.second
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            reader.setOnImageAvailableListener({ r -> consumeLatest(r) }, handler)
            virtualDisplay = mp.createVirtualDisplay(
                "script-capture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            Log.i(TAG, "屏幕方向变更，虚拟屏已重建: ${width}x$height rotation=$rotation")
        } catch (e: Exception) {
            Log.w(TAG, "重建虚拟屏失败: ${e.message}")
        }
    }

    /** 持有最新原始帧，不在屏幕刷新时分配 Bitmap。maxImages=3 给 acquireLatestImage 留两个空位。 */
    private fun consumeLatest(reader: ImageReader) = synchronized(frames) {
        if (reader !== imageReader) return@synchronized
        try {
            val image = reader.acquireLatestImage() ?: return@synchronized
            frames.replace(image, android.os.SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "取帧失败: ${e.message}")
        }
    }

    private fun readFrame(afterExclusive: Long = Long.MIN_VALUE): Bitmap? = try {
        frames.read(afterExclusive)
    } catch (e: Exception) {
        Log.w(TAG, "转换截图失败: ${e.message}")
        null
    }

    /** 只在消费者请求时转换，同一原始帧重复读取复用结果；结果由消费者持有。 */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == width) return padded
            return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } catch (e: Exception) {
            padded.recycle()
            throw e
        }
    }

    private fun release() {
        // 注销屏幕方向监听，避免持有已销毁服务的引用
        try {
            displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        } catch (_: Exception) {
        }
        displayListener = null
        displayManager = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            synchronized(frames) {
                frames.clear()
                imageReader?.close()
                imageReader = null
            }
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        instance = null
        ready = false
        readyAtMillis = 0
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onDestroy() {
        release()
        signalReady(false)
        super.onDestroy()
    }
}
