package com.forest.android.assistant.core.image

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 截屏入口：按「通道」选择截屏方式，并处理 MediaProjection 的授权桥接。
 *
 * 用户必须先选择系统授权截屏或超级权限截屏，只使用所选方式，不自动切换。
 * 未选择、旧版自动设置和无法识别的设置均视为未选择。
 * 对应脚本 API：requestScreenCapture / captureScreen（docs/README.md 第 5.4 节）。
 */
object ScreenCapture {

    private const val TAG = "ScriptJs"
    private const val PREFS = "capture"
    private const val KEY_MODE = "mode"

    /** 截屏通道 */
    enum class Channel { MEDIAPROJECTION, ROOT }

    /** 未选择不是可用通道；旧版 AUTO 必须由用户重新选择。 */
    enum class Mode(val label: String, val channel: Channel?) {
        UNSELECTED("未选择", null),
        MEDIAPROJECTION("系统授权截屏", Channel.MEDIAPROJECTION),
        ROOT("超级权限截屏", Channel.ROOT);

        fun available(rootAvailable: Boolean, projectionReady: Boolean): Boolean = when (this) {
            UNSELECTED -> false
            MEDIAPROJECTION -> projectionReady
            ROOT -> rootAvailable
        }

        companion object {
            fun fromStored(value: String?): Mode = entries.firstOrNull { it.name == value } ?: UNSELECTED
        }
    }

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

    private data class ProjectionRequest(
        val forModeTest: Boolean,
        val complete: (Boolean) -> Unit
    )

    @Volatile
    private var pending: ProjectionRequest? = null

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

    /** 保留用户已明确选择的通道，旧版自动设置不迁移为任何具体通道。 */
    fun mode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        return Mode.fromStored(raw)
    }

    /** 仅在确认测试通过后保存通道。 */
    private fun setMode(context: Context, newMode: Mode) {
        require(newMode != Mode.UNSELECTED) { "请选择截屏方式" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, newMode.name).apply()
        rootProbed = -1
        if (newMode != Mode.MEDIAPROJECTION) release(context)
        Log.i(TAG, "截屏方式已设为：" + newMode.label)
    }

    data class ModeTestResult(val success: Boolean, val message: String)

    /** 测试候选通道的真实截图，成功后才保存选择；失败保留原设置。 */
    suspend fun testAndSelectMode(context: Context, candidate: Mode): ModeTestResult = withContext(Dispatchers.IO) {
        if (candidate == Mode.UNSELECTED) {
            return@withContext ModeTestResult(false, "请先选择截屏方式")
        }
        val startedAt = SystemClock.uptimeMillis()
        var confirmed = false
        try {
            when (candidate) {
                Mode.ROOT -> Unit
                Mode.MEDIAPROJECTION -> if (!requestProjectionBlocking(context, 60, forModeTest = true)) {
                    return@withContext ModeTestResult(false, "系统截屏授权未完成，请授权后重试")
                }
                Mode.UNSELECTED -> Unit
            }
            val frame = when (candidate) {
                Mode.ROOT -> captureSilently(context, Channel.ROOT)
                Mode.MEDIAPROJECTION -> captureFromProjection(timeoutMs = 3000, newerThanUptimeMs = startedAt)
                Mode.UNSELECTED -> null
            }
            if (frame == null) {
                if (candidate == Mode.ROOT) rootProbed = 0
                return@withContext ModeTestResult(false, "未能取得截图，请检查所选方式的权限，或重新选择")
            }
            try {
                if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) {
                    return@withContext ModeTestResult(false, "截图无效，请重试或重新选择截屏方式")
                }
                ensureActive()
                setMode(context, candidate)
                if (candidate == Mode.ROOT) rootProbed = 1
                confirmed = true
                ModeTestResult(true, "截图测试通过，已选择：" + candidate.label)
            } finally {
                // 超级权限截图由本次测试独占；系统投影帧由服务管理，不能回收。
                if (candidate == Mode.ROOT && !frame.isRecycled) frame.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "截屏方式测试失败", e)
            ModeTestResult(false, "截图测试失败，请检查权限后重试")
        } finally {
            if (!confirmed && candidate == Mode.MEDIAPROJECTION && mode(context) != Mode.MEDIAPROJECTION) {
                release(context)
            }
        }
    }

    /** Root 是否可用（结果缓存） */
    fun rootAvailable(forceRefresh: Boolean = false): Boolean {
        if (forceRefresh || rootProbed < 0) {
            rootProbed = if (SilentCapture.rootAvailable(forceRefresh)) 1 else 0
        }
        return rootProbed == 1
    }

    /**
     * Root 静默截屏是否可用：**只用缓存结果**，不触发探测。
     * 供每秒刷新的界面轮询使用，避免在 UI 线程里跑 su 探测。
     */
    fun silentAvailableCached(): Boolean = rootProbed == 1

    /** 后台预热静默通道探测结果（供界面 chip 正确显示「截屏可用」） */
    fun probeSilentAsync(context: Context) {
        if (mode(context) != Mode.ROOT) return
        Thread {
            rootAvailable()
        }.start()
    }

    /** 只返回用户选择的通道；未选择返回 null，不探测或回退到其他通道。 */
    fun activeChannel(context: Context): Channel? = mode(context).channel

    /**
     * 所选通道是否具备截屏能力，不允许用另一个通道的就绪状态代替。
     * 注意不要用 [isReady] 判断「能否截屏」——它只表示 MediaProjection 是否就绪，
     * 走静默通道时恒为 false，会导致取色器等入口误判为「未授权」而反复请求
     * （历史缺陷：取色器在静默通道下陷入「申请→打开」无限递归，实际打不开）。
     */
    fun captureAvailable(context: Context): Boolean = when (mode(context)) {
        Mode.UNSELECTED -> false
        Mode.MEDIAPROJECTION -> isReady()
        Mode.ROOT -> rootAvailable()
    }

    /** 界面和通知使用缓存检查，避免在主线程探测超级用户权限。 */
    fun captureAvailableCached(context: Context): Boolean =
        mode(context).available(rootAvailable = silentAvailableCached(), projectionReady = isReady())

    private fun captureSilently(context: Context, channel: Channel): Bitmap? =
        if (channel == Channel.ROOT) SilentCapture.captureViaRoot(context) else null

    /**
     * 截取当前屏幕：
     * - Root 通道：直接静默截屏，失败返回 null，不触发系统授权；
     * - MediaProjection 通道：取投影帧（等待刷新帧，避免拿到授权弹窗残留画面）。
     */
    fun capture(timeoutMs: Long = 2000, newerThanUptimeMs: Long = 0): Bitmap? {
        val context = appContext ?: return null
        return when (activeChannel(context)) {
            null -> {
                Log.w(TAG, "请先在工具页选择截屏方式")
                null
            }
            Channel.ROOT -> captureSilently(context, Channel.ROOT).also {
                if (it == null) Log.w(TAG, "超级权限截屏失败，请检查权限或手动更换截屏方式")
            }
            Channel.MEDIAPROJECTION -> captureFromProjection(timeoutMs, newerThanUptimeMs)
        }
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
        val context = appContext ?: return false
        when (mode(context)) {
            Mode.UNSELECTED -> {
                val message = "请先在工具页的截屏通道设置中选择截屏方式"
                Log.w(TAG, message)
                mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                return false
            }
            Mode.ROOT -> {
                if (rootAvailable(forceRefresh = true)) {
                    captureSilently(context, Channel.ROOT)?.let {
                        it.recycle()
                        Log.i(TAG, "超级权限截屏可用，无需系统授权")
                        return true
                    }
                }
                rootProbed = 0
                val message = "超级权限截屏不可用，请检查权限或在工具页手动更换截屏方式"
                Log.w(TAG, message)
                mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                return false
            }
            Mode.MEDIAPROJECTION -> Unit
        }
        return requestProjectionBlocking(context, timeoutSeconds)
    }

    /** 候选方式测试可先申请投影，验证成功前不修改已保存的通道。 */
    private fun requestProjectionBlocking(
        context: Context,
        timeoutSeconds: Long,
        forModeTest: Boolean = false
    ): Boolean {
        if (isReady()) return forModeTest || mode(context) == Mode.MEDIAPROJECTION
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
            return (forModeTest || mode(context) == Mode.MEDIAPROJECTION) && isReady()
        }

        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val request = ProjectionRequest(forModeTest) { ok ->
            granted.set(ok)
            latch.countDown()
        }
        pending = request
        mainHandler.post {
            try {
                if (!forModeTest && mode(context) != Mode.MEDIAPROJECTION) {
                    request.complete(false)
                    return@post
                }
                val mpm = act.getSystemService(MediaProjectionManager::class.java)
                launch(mpm.createScreenCaptureIntent())
            } catch (e: Exception) {
                Log.w(TAG, "拉起截屏授权失败: ${e.message}")
                request.complete(false)
            }
        }
        return try {
            val ok = latch.await(timeoutSeconds, TimeUnit.SECONDS) && granted.get() &&
                (forModeTest || mode(context) == Mode.MEDIAPROJECTION)
            if (!ok) Log.w(TAG, "截屏授权未完成（用户取消或超时）")
            ok
        } catch (e: InterruptedException) {
            false
        } finally {
            if (pending === request) pending = null
            requesting.set(false)
        }
    }

    /** 宿主 Activity 收到授权结果后回调（onActivityResult / launcher 回调） */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val request = pending ?: return
        val app = appContext
        if (resultCode == Activity.RESULT_OK && data != null && app != null &&
            (request.forModeTest || mode(app) == Mode.MEDIAPROJECTION)
        ) {
            // 启动前台服务并等待投影就绪（勿在主线程阻塞）
            Thread {
                if (pending !== request) return@Thread
                val ok = ScreenCaptureService.startAndAwait(app, resultCode, data, 10000)
                val stillRequested = pending === request &&
                    (request.forModeTest || mode(app) == Mode.MEDIAPROJECTION)
                if (!stillRequested && mode(app) != Mode.MEDIAPROJECTION) release(app)
                request.complete(ok && stillRequested)
            }.start()
        } else {
            request.complete(false)
        }
    }
}
