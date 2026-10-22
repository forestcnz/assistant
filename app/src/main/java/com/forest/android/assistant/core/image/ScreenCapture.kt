package com.forest.android.assistant.core.image

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 截屏入口：按「通道」选择截屏方式，并处理 MediaProjection 的授权桥接。
 *
 * 通道优先级（AUTO 模式）：**Root → MediaProjection**
 * - Root：`su -c screencap`，静默无授权弹窗；
 * - MediaProjection：需用户授权一次，切屏/息屏后需重新授权。
 *
 * 显式指定 Root 但不可用时自动降级到 MediaProjection 并记录日志。
 * 对应脚本 API：requestScreenCapture / captureScreen（docs/README.md 第 5.4 节）。
 */
object ScreenCapture {

    private const val TAG = "ScriptJs"
    private const val PREFS = "capture"
    private const val KEY_MODE = "mode"

    /** 截屏通道 */
    enum class Channel { MEDIAPROJECTION, ROOT }

    /** 通道模式：AUTO 自动探测，其余为手动指定 */
    enum class Mode { AUTO, MEDIAPROJECTION, ROOT }

    /** 探测结果缓存：-1 未探测 / 0 不可用 / 1 可用 */
    @Volatile
    private var rootProbed = -1

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PermissionHost(
        val activity: WeakReference<Activity>,
        val launch: (Intent) -> Unit
    )

    private val permissionHosts = mutableListOf<PermissionHost>()

    @Volatile
    private var currentPermissionHost: PermissionHost? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var pending: ((Boolean) -> Unit)? = null

    private val requesting = AtomicBoolean(false)

    /** 宿主 Activity 绑定授权启动器（onCreate 时调用） */
    @Synchronized
    fun bind(activity: Activity, launch: (Intent) -> Unit) {
        permissionHosts.removeAll { it.activity.get() == null || it.activity.get() === activity }
        permissionHosts.add(PermissionHost(WeakReference(activity), launch))
        currentPermissionHost = permissionHosts.last()
        appContext = activity.applicationContext
    }

    @Synchronized
    fun unbind(activity: Activity) {
        permissionHosts.removeAll { it.activity.get() == null || it.activity.get() === activity }
        currentPermissionHost = permissionHosts.lastOrNull()
    }

    /** 投影是否就绪（MediaProjection 通道可截屏） */
    fun isReady(): Boolean = ScreenCaptureService.isReady()

    // ==================== 截屏通道 ====================

    /** 当前通道模式（默认 AUTO） */
    fun mode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.AUTO.name) ?: Mode.AUTO.name
        return try {
            Mode.valueOf(raw)
        } catch (e: Exception) {
            Mode.AUTO
        }
    }

    /** 设置通道模式并清空探测缓存 */
    fun setMode(context: Context, newMode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, newMode.name).apply()
        rootProbed = -1
        Log.i(TAG, "截屏通道模式已设为: " + newMode.name)
    }

    /** Root 是否可用（结果缓存） */
    fun rootAvailable(forceRefresh: Boolean = false): Boolean {
        if (forceRefresh || rootProbed < 0) {
            rootProbed = if (SilentCapture.rootAvailable()) 1 else 0
        }
        return rootProbed == 1
    }

    /**
     * Root 静默截屏是否可用：**只用缓存结果**，不触发探测。
     * 供每秒刷新的界面轮询使用，避免在 UI 线程里跑 su 探测。
     */
    fun silentAvailableCached(): Boolean = rootProbed == 1

    /** 后台预热静默通道探测结果（供界面 chip 正确显示「截屏可用」） */
    fun probeSilentAsync() {
        Thread {
            rootAvailable(forceRefresh = true)
        }.start()
    }

    /** 解析实际使用的通道；显式指定但不可用时降级到 MediaProjection */
    fun activeChannel(context: Context): Channel = when (mode(context)) {
        Mode.ROOT -> if (rootAvailable()) {
            Channel.ROOT
        } else {
            Log.w(TAG, "已指定 Root 截屏但不可用，降级到 MediaProjection")
            Channel.MEDIAPROJECTION
        }

        Mode.MEDIAPROJECTION -> Channel.MEDIAPROJECTION

        Mode.AUTO -> if (rootAvailable()) Channel.ROOT else Channel.MEDIAPROJECTION
    }

    /** 当前通道是否需要用户授权弹窗（Root 为静默截屏） */
    fun needsAuthorization(context: Context): Boolean =
        activeChannel(context) == Channel.MEDIAPROJECTION

    /**
     * 是否**具备截屏能力**（任意通道）：静默通道可用，或 MediaProjection 已就绪。
     * 注意不要用 [isReady] 判断「能否截屏」——它只表示 MediaProjection 是否就绪，
     * 走静默通道时恒为 false，会导致取色器等入口误判为「未授权」而反复请求
     * （历史缺陷：取色器在静默通道下陷入「申请→打开」无限递归，实际打不开）。
     */
    fun captureAvailable(context: Context): Boolean =
        isReady() || activeChannel(context) != Channel.MEDIAPROJECTION

    private fun captureSilently(context: Context, channel: Channel): Bitmap? =
        if (channel == Channel.ROOT) SilentCapture.captureViaRoot(context) else null

    /**
     * 截取当前屏幕：
     * - Root 通道：直接静默截屏（失败则尝试 MediaProjection 兜底）；
     * - MediaProjection 通道：取投影帧（等待刷新帧，避免拿到授权弹窗残留画面）。
     */
    fun capture(timeoutMs: Long = 2000, newerThanUptimeMs: Long = 0): Bitmap? {
        val context = appContext
        if (context != null) {
            val channel = activeChannel(context)
            if (channel != Channel.MEDIAPROJECTION) {
                captureSilently(context, channel)?.let { return it }
                Log.w(TAG, "静默截屏失败，尝试 MediaProjection 兜底")
            }
        }
        return captureFromProjection(timeoutMs, newerThanUptimeMs)
    }

    /** MediaProjection 通道取帧 */
    private fun captureFromProjection(timeoutMs: Long, newerThanUptimeMs: Long): Bitmap? {
        fun frame() = if (newerThanUptimeMs > 0) ScreenCaptureService.frameAfter(newerThanUptimeMs)
            else ScreenCaptureService.freshFrame()
        frame()?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            frame()?.let { return it }
        }
        return if (newerThanUptimeMs > 0) null else ScreenCaptureService.latestFrame()
    }

    /** 释放投影（停止前台服务） */
    fun release(context: Context) {
        ScreenCaptureService.stop(context)
    }

    /**
     * 同步请求截屏能力（脚本线程调用）：
     * - Root 通道：静默可用，直接返回 true（不弹窗）；
     * - MediaProjection 通道：弹系统授权窗 → 启动前台服务 → 等待投影就绪。
     */
    fun requestBlocking(timeoutSeconds: Long = 60): Boolean {
        val context = appContext
        if (context != null) {
            val channel = activeChannel(context)
            if (channel != Channel.MEDIAPROJECTION) {
                // 静默通道需实地截一帧确认真的可用：否则会出现「声称无需授权但截不到图」的假成功
                if (captureSilently(context, channel) != null) {
                    Log.i(TAG, "静默截屏通道（" + channel.name + "）：无需授权")
                    return true
                }
                Log.w(TAG, "静默通道 " + channel.name + " 实测失败，降级 MediaProjection")
                // 注意：这里**不**把通道探测缓存置为不可用——实测失败可能是瞬时问题
                // （例如系统繁忙），置为不可用会让静默通道
                // 直到下次重启应用都不再被采用，代价过大。本次调用按降级处理即可，
                // 下次仍会重新尝试静默通道。
            }
        }
        if (isReady()) return true
        val host = currentPermissionHost
        val act = host?.activity?.get()
        val launch = host?.launch
        if (act == null || launch == null) {
            Log.w(TAG, "请求截屏失败：宿主界面未绑定（请先打开 App）")
            return false
        }
        // 已有请求在途：等待其结束，避免并发弹窗
        if (!requesting.compareAndSet(false, true)) {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (requesting.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            return isReady()
        }

        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        pending = { ok ->
            granted.set(ok)
            latch.countDown()
        }
        mainHandler.post {
            try {
                val mpm = act.getSystemService(MediaProjectionManager::class.java)
                launch(mpm.createScreenCaptureIntent())
            } catch (e: Exception) {
                Log.w(TAG, "拉起截屏授权失败: ${e.message}")
                pending?.invoke(false)
            }
        }
        return try {
            val ok = latch.await(timeoutSeconds, TimeUnit.SECONDS) && granted.get()
            if (!ok) Log.w(TAG, "截屏授权未完成（用户取消或超时）")
            ok
        } catch (e: InterruptedException) {
            false
        } finally {
            pending = null
            requesting.set(false)
        }
    }

    /** 宿主 Activity 收到授权结果后回调（onActivityResult / launcher 回调） */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val callback = pending
        val app = appContext
        if (resultCode == Activity.RESULT_OK && data != null && app != null) {
            // 启动前台服务并等待投影就绪（勿在主线程阻塞）
            Thread {
                val ok = ScreenCaptureService.startAndAwait(app, resultCode, data, 10000)
                callback?.invoke(ok)
            }.start()
        } else {
            callback?.invoke(false)
        }
    }
}
