package com.forest.android.assistant.core.floaty

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 悬浮窗管理：统一为一个悬浮控件（依赖 SYSTEM_ALERT_WINDOW 权限）。
 *
 * 控件有状态，就地变形而不是叠加多个窗口：
 * - 空闲态：圆球「JS」，点击展开面板，拖动可移动并自动贴边（位置会记住）；
 * - 运行态：屏幕左下角矩形条显示任务名与「停止」按钮，单击即可停止。
 *
 * 贴边后空闲球半隐藏：约一半探出屏外，点击先弹出完整显示，再次点击才展开面板；
 * 半隐藏位置会持久化，重启后保持贴边形态。
 *
 * 展开的面板按「任务 / 工具」分区：任务列表可滚动，工具区提供取点、取色、选范围、截图、日志和录制，
 * 底部提供「关闭悬浮窗」入口（关闭后可在 App「工具」页重新开启）。
 * 对应设计 docs/README.md 第 4.5 节「悬浮与调试工具」。
 */
object FloatyManager {

    private const val TAG = "ScriptJs"
    private const val PREFS = "floaty"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    /** 用户是否希望显示悬浮球（默认 true = 首次启动自动开启） */
    private const val KEY_ENABLED = "enabled"

    /** 是否已自动引导过悬浮窗授权（只自动跳一次，避免每次启动都抢屏） */
    private const val KEY_ASKED_OVERLAY = "asked_overlay"

    /** 松手贴边后留在屏内的边距（未半隐藏时） */
    private const val SNAP_MARGIN_DP = 6

    /** 空闲球尺寸（dp），与 ballView 保持一致 */
    private const val BALL_SIZE_DP = 35

    /** 运行条尺寸：宽度约为上一版 213dp 的一半。 */
    private const val RUNNING_BAR_WIDTH_DP = 107
    private const val RUNNING_BAR_HEIGHT_DP = 35

    /** 贴边半隐藏：探出屏外的部分占球宽的比例（剩余半圆留在屏内，可点击弹出） */
    private const val PEEK_HIDDEN_RATIO = 0.5f

    // 配色（悬浮窗自成体系，不跟随 App 主题，保证在任何应用之上都可读）
    private const val COLOR_BG = 0xFF1D2B46.toInt()
    private const val COLOR_BG_SOLID = 0xFF3563D9.toInt()
    private const val COLOR_ACCENT = 0xFFADC6FF.toInt()
    private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
    private const val COLOR_TEXT_DIM = 0xFFBAC9E4.toInt()
    private const val COLOR_RUNNING = 0xFF78D8C7.toInt()
    private const val COLOR_STOP = 0xFFBA3D4C.toInt()
    private const val COLOR_DIVIDER = 0x33FFFFFF

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否启用悬浮球（默认开启） */
    fun isEnabledByUser(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabledByUser(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun wasOverlayAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_OVERLAY, false)

    fun markOverlayAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_OVERLAY, true).apply()
    }

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null

    /** 统一的悬浮控件（胶囊） */
    private var capsuleView: LinearLayout? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private data class ScreenshotVisibility(val view: View, val visibility: Int, val flags: Int)
    private var screenshotVisibility: ScreenshotVisibility? = null

    /** 展开的任务/工具面板 */
    private var panelView: View? = null

    /** 贴边方向：目前只做左右贴边 */
    private enum class DockSide { NONE, LEFT, RIGHT }

    /** 当前贴边形态（LEFT/RIGHT = 半隐藏在屏幕边缘，NONE = 完整显示） */
    private var dockedSide = DockSide.NONE

    /** 运行前的空闲球位置与贴边状态；结束后恢复，不让顶部运行条覆盖用户的位置。 */
    private var idlePosition: Pair<Int, Int>? = null
    private var idleDockedSide = DockSide.NONE

    /** 当前运行中的任务名（null = 空闲态） */
    @Volatile
    private var runningTask: String? = null

    /** 点击[停止]时的回调（任务运行中） */
    @Volatile
    var onStopClicked: (() -> Unit)? = null

    /** 悬浮球面板中选择任务后的回调 */
    @Volatile
    var onTaskSelected: ((String) -> Unit)? = null

    /** 任务列表提供者（由宿主注入，避免 core 层循环依赖） */
    @Volatile
    var taskProvider: (() -> List<String>)? = null

    /** 面板点击「关闭悬浮窗」的回调（宿主绑定，用于同步运行状态与开关 UI） */
    @Volatile
    var onLauncherClose: (() -> Unit)? = null

    /** 是否已授予悬浮窗权限 */
    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** 跳转系统悬浮窗授权设置页 */
    fun requestOverlayPermission(activity: Activity) {
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            ),
            // 部分模拟器设置应用不支持 package 定向，退回到悬浮窗应用列表。
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            // 再退回应用详情页，让用户从权限入口手动开启。
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            )
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // 尝试下一个系统设置入口。
            }
        }
        Log.w(TAG, "无法打开悬浮窗授权设置页")
    }

    /** 悬浮控件是否显示中 */
    fun isLauncherShowing(): Boolean = capsuleView?.isAttachedToWindow == true

    /**
     * 屏幕尺寸/方向变化（旋转、分屏、分辨率切换）后调用：
     * 按新的屏幕边界把悬浮球收回屏内并保存；面板位置依赖悬浮球，直接收起避免错位。
     */
    fun onScreenSizeChanged(context: Context) {
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        if (runningTask != null) {
            hideTaskPanel()
            view.post { positionRunningBar(context) }
            return
        }
        val m = context.resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: (BALL_SIZE_DP * m.density).toInt()
        val h = view.height.takeIf { it > 0 } ?: (BALL_SIZE_DP * m.density).toInt()
        params.x = if (dockedSide == DockSide.NONE) {
            params.x.coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
        } else {
            // 半隐藏贴边态：按新屏幕宽度重新对齐探出屏外的部分
            peekedX(stagedViewW(context, view), m.widthPixels, dockedSide)
        }
        params.y = params.y.coerceIn(0, (m.heightPixels - h).coerceAtLeast(0))
        hideTaskPanel()
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y)
        Log.i(TAG, "屏幕尺寸变化：悬浮球已收回屏内 (${params.x}, ${params.y})")
    }

    /** 是否处于运行态 */
    fun isShowing(): Boolean = runningTask != null

    /** 当前运行的任务名 */
    fun currentTask(): String? = runningTask

    // ==================== 悬浮控件 ====================

    /**
     * 显示悬浮控件（空闲态）。
     * 未授权返回 false；已显示直接返回 true。
     */
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun showLauncher(context: Context, keepAlive: Boolean = true): Boolean {
        if (com.forest.android.assistant.core.recording.RecordingService.active) return false
        appContext = context.applicationContext
        if (!hasOverlayPermission(context)) {
            if (keepAlive) FloatyForegroundService.stop(context)
            return false
        }
        if (capsuleView?.isAttachedToWindow == true) {
            if (keepAlive) FloatyForegroundService.start(context)
            return true
        }
        // 模拟器/系统可能移除了窗口但没有销毁应用进程；清掉陈旧引用后重新 addView。
        if (capsuleView != null) {
            hideLauncher(stopKeepAlive = false)
            Log.w(TAG, "检测到悬浮球窗口已脱离 WindowManager，正在重建")
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density

        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 背景/内边距按状态在 renderCapsule 中设置。
        }
        val metrics = context.resources.displayMetrics
        val defaultX = (12 * density).toInt()
        // 默认放在中下部并靠左：避开任务列表首屏的主要可点区域
        val defaultY = (metrics.heightPixels * 0.72f).toInt()
        val (posX, posY) = clampToScreen(
            context,
            loadPosition(context, KEY_X, defaultX),
            loadPosition(context, KEY_Y, defaultY)
        )
        // 历史位置若为半隐藏贴边（越过屏幕边界），按原贴边形态恢复
        val ballW = (BALL_SIZE_DP * density).toInt()
        dockedSide = dockSideOf(posX, metrics.widthPixels, ballW)
        if (dockedSide != DockSide.NONE) {
            Log.i(TAG, "恢复半隐藏贴边悬浮球: $dockedSide")
        }
        val params = overlayParams(
            Gravity.TOP or Gravity.START,
            x = posX,
            y = posY
        )

        // 空闲球与运行条均可拖动；运行条内的停止按钮自行处理点击。
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var runningOnDown = false
        capsule.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    runningOnDown = runningTask != null
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > 25) {
                        moved = true
                        if (runningOnDown) {
                            val metrics = context.resources.displayMetrics
                            val barW = capsule.width.takeIf { it > 0 }
                                ?: (RUNNING_BAR_WIDTH_DP * density).toInt()
                            val barH = capsule.height.takeIf { it > 0 }
                                ?: (RUNNING_BAR_HEIGHT_DP * density).toInt()
                            params.x = (startX + dx).coerceIn(0, (metrics.widthPixels - barW).coerceAtLeast(0))
                            // BOTTOM 重力下，y 是距底边的距离，向下拖动要减小 y。
                            params.y = (startY - dy).coerceIn(0, (metrics.heightPixels - barH).coerceAtLeast(0))
                        } else {
                            hideTaskPanel()
                            params.x = startX + dx
                            params.y = startY + dy
                        }
                        try {
                            wm.updateViewLayout(capsule, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!runningOnDown) {
                        when {
                            moved -> snapToEdge(context, wm, capsule, params)
                            // 半隐藏贴边第一次点击：先完整弹出，下一次点击才展开面板
                            popOutIfDocked(context, wm, capsule, params) -> Unit
                            else -> toggleTaskPanel(context, wm)
                        }
                    }
                    runningOnDown = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    runningOnDown = false
                    true
                }
                else -> false
            }
        }

        return try {
            wm.addView(capsule, params)
            capsuleView = capsule
            capsuleParams = params
            windowManager = wm
            renderCapsule(context)
            Log.i(TAG, "悬浮控件已显示")
            if (keepAlive) FloatyForegroundService.start(context)
            true
        } catch (e: Exception) {
            Log.w(TAG, "悬浮控件显示失败 (overlayPermission=${hasOverlayPermission(context)}): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 隐藏悬浮控件（含面板；面板收起时不恢复球，因为整体都要隐藏） */
    fun hideLauncher(context: Context? = null, stopKeepAlive: Boolean = true) {
        screenshotVisibility = null
        hideTaskPanel(restoreBall = false)
        capsuleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        capsuleView = null
        capsuleParams = null
        windowManager = null
        dockedSide = DockSide.NONE   // 下次显示时按持久化位置重新推断贴边形态
        if (stopKeepAlive) {
            (context?.applicationContext ?: appContext)?.let { FloatyForegroundService.stop(it) }
        }
        Log.i(TAG, "悬浮控件已隐藏")
    }

    /** 截图时临时隐藏已显示的胶囊，保留窗口和视图对象。必须在主线程调用。 */
    fun hideLauncherForScreenshot(): Boolean {
        val view = capsuleView ?: return false
        val lp = capsuleParams ?: return false
        val wm = windowManager ?: return false
        if (!view.isAttachedToWindow) return false
        screenshotVisibility?.let { return it.view === view }
        val previous = ScreenshotVisibility(view, view.visibility, lp.flags)
        return try {
            screenshotVisibility = previous
            view.visibility = View.INVISIBLE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(view, lp)
            true
        } catch (_: Exception) {
            view.visibility = previous.visibility
            lp.flags = previous.flags
            screenshotVisibility = null
            false
        }
    }

    /** 恢复截图前的胶囊可见状态；返回 false 表示窗口已被其他流程移除。 */
    fun restoreLauncherAfterScreenshot(): Boolean {
        val previous = screenshotVisibility ?: return false
        screenshotVisibility = null
        if (capsuleView !== previous.view || !previous.view.isAttachedToWindow) return false
        val lp = capsuleParams ?: return false
        return try {
            lp.flags = previous.flags
            previous.view.visibility = previous.visibility
            windowManager?.updateViewLayout(previous.view, lp)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 切换到运行态：左下角矩形条显示任务名和单击停止按钮。 */
    fun setRunning(context: Context, taskName: String) {
        hideTaskPanel()
        if (capsuleView?.isAttachedToWindow != true) {
            if (!showLauncher(context)) {
                Log.w(TAG, "运行态悬浮控件显示失败")
                return
            }
        }
        if (runningTask == null) {
            capsuleParams?.let { idlePosition = it.x to it.y }
            idleDockedSide = dockedSide
        }
        runningTask = taskName
        dockedSide = DockSide.NONE
        renderCapsule(context)
        Log.i(TAG, "悬浮控件进入运行态: $taskName")
    }

    /** 回到空闲态（任务结束/停止后调用） */
    fun clearRunning(context: Context) {
        runningTask = null
        val savedPosition = idlePosition
        dockedSide = idleDockedSide
        capsuleParams?.let { params ->
            savedPosition?.let { (x, y) ->
                params.x = x
                params.y = y
            }
            params.gravity = Gravity.TOP or Gravity.START
        }
        renderCapsule(context)
        idlePosition = null
        idleDockedSide = DockSide.NONE
        Log.i(TAG, "悬浮控件回到空闲态")
    }

    /**
     * 按当前状态重建胶囊内容：
     * - 空闲：35dp 圆球「JS」+ 右下角状态点（绿=就绪）；
     * - 运行：左下角矩形条，显示任务名和停止按钮。
     */
    @SuppressLint("SetTextI18n")
    private fun renderCapsule(context: Context) {
        val capsule = capsuleView ?: return
        capsule.removeAllViews()
        val task = runningTask
        if (task == null) {
            capsule.setPadding(0, 0, 0, 0)
            capsule.background = null
            capsule.alpha = 0.92f
            capsule.addView(ballView(context))
        } else {
            capsule.setPadding(0, 0, 0, 0)
            capsule.background = null
            capsule.alpha = 1f
            capsule.addView(runningBar(context, task))
        }
        capsuleView?.let { view ->
            try {
                capsuleParams?.let { windowManager?.updateViewLayout(view, it) }
            } catch (_: Exception) {
            }
            view.post {
                if (runningTask != null) positionRunningBar(context) else keepInsideScreen(context)
            }
        }
    }

    /** 状态切换后按实际宽高把悬浮窗收回屏幕内 */
    private fun keepInsideScreen(context: Context) {
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        val w = view.width.takeIf { it > 0 } ?: return
        val h = view.height.takeIf { it > 0 } ?: return
        val m = context.resources.displayMetrics
        val nx = when (dockedSide) {
            DockSide.LEFT, DockSide.RIGHT ->
                // 半隐藏贴边态：按当前宽度重新对齐屏外隐藏量（球↔胶囊宽度变化后仍保持贴边形态）
                peekedX(stagedViewW(context, view), m.widthPixels, dockedSide)
            DockSide.NONE ->
                params.x.coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
        }
        val ny = params.y.coerceIn(0, (m.heightPixels - h).coerceAtLeast(0))
        if (nx != params.x || ny != params.y) {
            params.x = nx
            params.y = ny
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    /** 由系统从左下角定位，避开 ROM 报告的屏幕高度与实际显示帧不一致。 */
    private fun positionRunningBar(context: Context) {
        if (runningTask == null) return
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.x = (8 * context.resources.displayMetrics.density).toInt()
        params.y = (12 * context.resources.displayMetrics.density).toInt()
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    /** 参与贴边计算的悬浮控件宽度：空闲用固定球宽（状态切换瞬间测量值可能是旧胶囊宽度） */
    private fun stagedViewW(context: Context, view: View): Int {
        val density = context.resources.displayMetrics.density
        if (runningTask == null) return (BALL_SIZE_DP * density).toInt()
        return view.width.takeIf { it > 0 } ?: (60 * density).toInt()
    }

    /** 空闲态：圆形球 + 状态点 */
    private fun ballView(context: Context): View {
        val d = context.resources.displayMetrics.density
        val size = (BALL_SIZE_DP * d).toInt()
        val frame = android.widget.FrameLayout(context)
        frame.addView(
            View(context).apply {
                background = roundedBackground(COLOR_BG_SOLID, BALL_SIZE_DP * d / 2, 1.5f * d, 0x33FFFFFF)
            },
            android.widget.FrameLayout.LayoutParams(size, size)
        )
        frame.addView(
            android.widget.ImageView(context).apply {
                setImageResource(com.forest.android.assistant.R.drawable.ic_brand_mark)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding((1 * d).toInt(), (1 * d).toInt(), (1 * d).toInt(), (1 * d).toInt())
            },
            android.widget.FrameLayout.LayoutParams(size, size)
        )
        // 右下角状态点：空闲=绿（就绪）
        frame.addView(
            statusDot(context),
            android.widget.FrameLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (1 * d).toInt()
                bottomMargin = (1 * d).toInt()
            }
        )
        return frame
    }

    /** 状态点。 */
    private fun statusDot(context: Context): View {
        val d = context.resources.displayMetrics.density
        return View(context).apply {
            background = roundedBackground(COLOR_RUNNING, 5 * d, 0f, 0)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt())
        }
    }

    /** 左下角任务条：任务名占剩余空间，停止按钮可单击。 */
    private fun runningBar(context: Context, task: String): View {
        val d = context.resources.displayMetrics.density
        val width = (RUNNING_BAR_WIDTH_DP * d).toInt()
            .coerceAtMost((context.resources.displayMetrics.widthPixels - 24 * d).toInt())
            .coerceAtLeast(1)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_BG, 7 * d, 1 * d, 0x33FFFFFF)
            setPadding((5 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        bar.addView(
            textView(context, task, 11f, COLOR_TEXT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding((2 * d).toInt(), 0, (2 * d).toInt(), 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(
            textView(context, "停止", 12f, COLOR_TEXT).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(COLOR_STOP, 5 * d, 0f, 0)
                isClickable = true
                contentDescription = "停止任务 $task"
                setOnClickListener { onStopClicked?.invoke() }
            },
            LinearLayout.LayoutParams((42 * d).toInt(), (27 * d).toInt())
        )
        bar.layoutParams = LinearLayout.LayoutParams(width, (RUNNING_BAR_HEIGHT_DP * d).toInt())
        return bar
    }

    // ==================== 视图小工具 ====================

    private fun textView(context: Context, text: String, sp: Float, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
        }

    /** 圆角背景（radius=高度一半即胶囊/圆形） */
    private fun roundedBackground(
        color: Int,
        radiusPx: Float,
        strokePx: Float,
        strokeColor: Int
    ) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0f) setStroke(strokePx.toInt().coerceAtLeast(1), strokeColor)
    }

    /** 悬浮球上过长任务名截断（保证胶囊不占满屏幕） */
    private fun ellipsize(name: String, max: Int): String =
        if (name.length <= max) name else name.take(max) + "…"

    /**
     * 真实屏幕尺寸（宽、高）。
     * displayMetrics.heightPixels 在部分 ROM/模拟器（如实测 MuMu 报 2064，实际 1920）会虚报，
     * 涉及居中/贴边/钳位的计算统一用真实显示尺寸，避免面板「看起来不居中」。
     */
    private fun realScreenSize(context: Context): android.graphics.Point {
        val wmSvc = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wmSvc.maximumWindowMetrics.bounds
            return android.graphics.Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        wmSvc.defaultDisplay.getRealSize(point)
        return point
    }


    /**
     * 松手后贴到最近的左/右边缘：
     * - 空闲球：半隐藏，约一半探出屏外（peek），点击可完整弹出；
     * - 运行态胶囊：完整贴边并留边距（停止键必须保持可见）。
     */
    private fun snapToEdge(
        context: Context,
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val m = context.resources.displayMetrics
        val screenW = m.widthPixels
        val viewW = stagedViewW(context, view)
        val side = if (params.x + viewW / 2 < screenW / 2) DockSide.LEFT else DockSide.RIGHT
        params.x = if (runningTask == null) {
            peekedX(viewW, screenW, side)
        } else {
            val margin = (SNAP_MARGIN_DP * m.density).toInt()
            if (side == DockSide.LEFT) margin else screenW - viewW - margin
        }
        // 垂直钳位：拖动松手后保证整个控件留在真实屏幕内（用真实高度，displayMetrics 可能虚报）
        val viewH = view.height.takeIf { it > 0 } ?: (BALL_SIZE_DP * m.density).toInt()
        params.y = params.y.coerceIn(0, (realScreenSize(context).y - viewH).coerceAtLeast(0))
        dockedSide = if (runningTask == null) side else DockSide.NONE
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y)
        Log.i(TAG, "悬浮控件贴边: $side${if (runningTask == null) "（半隐藏）" else "（完整）"}")
    }

    /** 半隐藏贴边的 x：屏内只留一条半圆弧，方向为 [side] */
    private fun peekedX(viewW: Int, screenW: Int, side: DockSide): Int {
        val hidden = (viewW * PEEK_HIDDEN_RATIO).toInt().coerceAtLeast(1)
        return when (side) {
            DockSide.LEFT -> -hidden
            DockSide.RIGHT -> screenW - viewW + hidden
            DockSide.NONE -> 0
        }
    }

    /**
     * 半隐藏态第一次点击：把球从屏幕边缘完整弹出（不展开面板，下一次点击才展开）。
     * @return 是否执行了弹出（false = 本就完整可见）
     */
    private fun popOutIfDocked(
        context: Context,
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams
    ): Boolean {
        val side = dockedSide
        if (side == DockSide.NONE) return false
        dockedSide = DockSide.NONE
        val m = context.resources.displayMetrics
        val margin = (SNAP_MARGIN_DP * m.density).toInt()
        val viewW = view.width.takeIf { it > 0 } ?: (BALL_SIZE_DP * m.density).toInt()
        params.x = if (side == DockSide.LEFT) margin else m.widthPixels - viewW - margin
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y)
        Log.i(TAG, "半隐藏悬浮球已弹出完整显示")
        return true
    }

    /** 根据持久化坐标推断贴边状态（负 x = 左贴边，越过右边界 = 右贴边） */
    private fun dockSideOf(x: Int, screenW: Int, viewW: Int): DockSide = when {
        x < 0 -> DockSide.LEFT
        x > screenW - viewW -> DockSide.RIGHT
        else -> DockSide.NONE
    }

    private fun loadPosition(context: Context, key: String, default: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, default)

    /**
     * 位置钳位：旋转屏幕/切换分辨率后历史绝对坐标可能落到屏幕外。
     * - 屏内位置：y 越界时收紧到屏内（早期按 120dp 余量判定「越界」并复位是 bug，已修）；
     * - 越过左右边界的 x 属于用户留下的半隐藏贴边位置（负 x=左贴边，越过右界=右贴边），
     *   归一到标准探出量而不是收回屏内，重启后仍保持贴边半隐藏形态。
     */
    private fun clampToScreen(
        context: Context,
        x: Int,
        y: Int
    ): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        val viewW = (BALL_SIZE_DP * m.density).toInt()
        val maxX = (m.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (m.heightPixels - viewW).coerceAtLeast(0)
        if (x < 0) {
            val nx = peekedX(viewW, m.widthPixels, DockSide.LEFT)
            Log.i(TAG, "悬浮控件位置(x=$x,y=$y)：恢复左贴边半隐藏($nx,$y)")
            return nx to y.coerceIn(0, maxY)
        }
        if (x > maxX) {
            val nx = peekedX(viewW, m.widthPixels, DockSide.RIGHT)
            Log.i(TAG, "悬浮控件位置(x=$x,y=$y)：恢复右贴边半隐藏($nx,$y)")
            return nx to y.coerceIn(0, maxY)
        }
        if (y < 0 || y > maxY) {
            Log.i(TAG, "悬浮控件位置越界(x=$x,y=$y)，y 收紧为(${y.coerceIn(0, maxY)})")
            return x to y.coerceIn(0, maxY)
        }
        return x to y
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    // ==================== 任务 / 工具面板 ====================

    /**
     * 展开/收起面板（每次展开重新读取任务列表与运行状态）。
     * 布局：卡片式，自上而下 = 头部（选择任务 + 关闭） / 任务列表（双列紧凑格子，可滚动） /
     * 工具图标区（两行，每行 3 个按钮） / 运行中任务显示「■ 停止当前任务」 / 底部「✕ 关闭悬浮窗」。
     * 宽度不超过 284dp，按内容高度在屏幕正中显示。
     * 面板展开期间悬浮球暂时隐藏（避免遮挡），收起面板时自动恢复。
     * 按住面板标题栏（「≡ 选择任务」区域）可整体拖动面板，✕ 按钮与列表滚动不受影响。
     */
    private fun toggleTaskPanel(
        context: Context,
        wm: WindowManager
    ) {
        if (panelView != null) {
            hideTaskPanel()
            return
        }
        val tasks = taskProvider?.invoke() ?: emptyList()
        val d = context.resources.displayMetrics.density
        Log.i(TAG, "展开面板, 任务数=${tasks.size}, 运行中=${runningTask ?: "无"}")

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_BG, 16 * d, 1 * d, 0x22FFFFFF)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }

        // —— 头部：拖动把手 + 标题 + 任务数 + 关闭（按住头部可整体拖动面板） ——
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        header.addView(
            textView(context, "选择任务", 15f, COLOR_TEXT).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(textView(context, "${tasks.size} 个", 11f, COLOR_TEXT_DIM))
        header.addView(
            textView(context, "✕", 14f, COLOR_TEXT_DIM).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { hideTaskPanel() }
            },
            LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt())
        )
        panel.addView(header)
        panel.addView(divider(context))

        // —— 任务列表（双列紧凑格子，可滚动，最长 42% 屏高） ——
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        }
        if (tasks.isEmpty()) {
            list.addView(menuText(context, "（暂无任务，先在 App 里新建）") { })
        } else {
            // 每行两个任务格子（左右分布），任务多时高度减半、面板更紧凑
            tasks.chunked(2).forEach { pair ->
                val gridRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { name ->
                    val isRunning = runningTask == name
                    gridRow.addView(
                        taskCell(context, name, isRunning) {
                            if (runningTask == null) {
                                Log.i(TAG, "悬浮球选择任务: $name")
                                hideTaskPanel()
                                onTaskSelected?.invoke(name)
                            }
                        },
                        LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply {
                            marginEnd = (4 * d).toInt()
                            bottomMargin = (4 * d).toInt()
                        }
                    )
                }
                // 单数任务补空占位，保证最后一行格子仍然左对齐等宽
                if (pair.size == 1) {
                    gridRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                }
                list.addView(gridRow)
            }
        }
        val maxListHeight = (realScreenSize(context).y * 0.42f).toInt().coerceAtLeast((48 * d).toInt())
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val parentLimit = View.MeasureSpec.getSize(heightMeasureSpec)
                val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    maxListHeight else maxListHeight.coerceAtMost(parentLimit)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(list, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            isFillViewport = false
        }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // —— 底部：工具图标区（两行三列，保留足够的触摸区域） ——
        panel.addView(divider(context))
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 左右内边距 8dp 对称（每个按钮 marginEnd 6dp + 行末 padding 2dp）
            setPadding((8 * d).toInt(), (8 * d).toInt(), (2 * d).toInt(), (6 * d).toInt())
        }
        val toolButtons = listOf(
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_point, "取坐标") {
                hideTaskPanel()
                PickerOverlay.show(context, PickerOverlay.Mode.POINT)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_color, "取色") {
                hideTaskPanel()
                PickerOverlay.show(context, PickerOverlay.Mode.COLOR)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_region, "选择范围") {
                hideTaskPanel()
                PickerOverlay.show(context, PickerOverlay.Mode.REGION)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_shot, "截图") {
                hideTaskPanel()
                PickerOverlay.show(context, PickerOverlay.Mode.SCREENSHOT)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_log, "日志") {
                hideTaskPanel()
                ConsoleOverlay.toggle(context)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_record, "录制操作") {
                hideTaskPanel()
                com.forest.android.assistant.core.recording.RecordingSetupOverlay.show(context)
            }
        )
        toolButtons.chunked(3).forEachIndexed { index, buttons ->
            tools.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (index > 0) setPadding(0, (6 * d).toInt(), 0, 0)
                buttons.forEach { addView(it) }
            })
        }
        panel.addView(tools)

        // 运行中：提供停止入口（与胶囊上的停止键等价）
        if (runningTask != null) {
            val stopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            }
            stopRow.addView(
                textView(context, "■ 停止当前任务", 13f, 0xFFFFFFFF.toInt()).apply {
                    background = roundedBackground(COLOR_STOP, 10 * d, 0f, 0)
                    gravity = Gravity.CENTER
                    setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                    isClickable = true
                    setOnClickListener {
                        Log.i(TAG, "面板停止按钮点击")
                        hideTaskPanel()
                        onStopClicked?.invoke()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            panel.addView(stopRow)
        }

        // —— 底部：关闭悬浮窗（关闭后需回 App「工具」页重新开启） ——
        panel.addView(divider(context))
        panel.addView(
            closeLauncherRow(context),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // —— 定位：Gravity.CENTER 系统级居中 ——
        // 手工算坐标依赖 displayMetrics/WindowMetrics，实测 MuMu 上两者都虚报
        // （displayMetrics 高 2064 vs 实际 1920，最大窗口度量 1030x2064 vs 实际 1080x1920），
        // 会导致「看起来不居中」；交由系统按真实显示帧居中最稳，也不再需要布局监听校正。
        // 注意：CENTER 重力下 params.x/y 是「相对屏幕中心的偏移量」，头部拖动直接累加偏移。
        val panelW = (284 * d).toInt().coerceAtMost((context.resources.displayMetrics.widthPixels - 32 * d).toInt()).coerceAtLeast(1)
        val params = overlayParams(Gravity.CENTER, x = 0, y = 0)
        params.width = panelW
        try {
            wm.addView(panel, params)
            panelView = panel
            windowManager = wm
            // 面板展开期间隐藏悬浮球（避免遮挡居中的面板，收起面板时自动恢复）
            hideCapsuleForPanel()
            // —— 头部拖动：按住标题栏可整体移动面板（✕ 按钮点击不受影响，列表滚动也不冲突） ——
            var hdDownX = 0f
            var hdDownY = 0f
            var hdStartX = 0
            var hdStartY = 0
            var hdMoved = false
            header.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        hdDownX = event.rawX
                        hdDownY = event.rawY
                        hdStartX = params.x
                        hdStartY = params.y
                        hdMoved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - hdDownX).toInt()
                        val dy = (event.rawY - hdDownY).toInt()
                        if (hdMoved || dx * dx + dy * dy > 25) {
                            hdMoved = true
                            params.x = hdStartX + dx
                            params.y = hdStartY + dy
                            try {
                                wm.updateViewLayout(panel, params)
                            } catch (_: Exception) {
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (hdMoved) {
                            // 拖动结束：限制「相对中心」的偏移量，保证标题栏不被拖出屏幕（否则面板收不回、球也隐藏着）
                            // realScreenSize 在个别 ROM 上有偏差，这里只做宽松钳位，不影响居中本身
                            val real = realScreenSize(context)
                            val pw = panel.width.takeIf { it > 0 } ?: panelW
                            val ph = panel.height.takeIf { it > 0 } ?: 0
                            val maxOffX = ((real.x - pw) / 2).coerceAtLeast(0)
                            val maxOffY = ((real.y - ph) / 2).coerceAtLeast(0)
                            params.x = params.x.coerceIn(-maxOffX, maxOffX)
                            params.y = params.y.coerceIn(-maxOffY, maxOffY)
                            try {
                                wm.updateViewLayout(panel, params)
                            } catch (_: Exception) {
                            }
                        }
                        hdMoved
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        hdMoved = false
                        true
                    }
                    else -> false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "面板显示失败: ${e.message}")
        }
    }

    private fun divider(context: Context): View {
        val d = context.resources.displayMetrics.density
        return View(context).apply {
            setBackgroundColor(COLOR_DIVIDER)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)
            )
        }
    }

    /**
     * 任务格子（双列紧凑布局）：左 ▶ 强调色 + 任务名（单行截断）+（运行中）标记。
     * 单元高度与间距由添加到行时的 LayoutParams 统一设置。
     */
    private fun taskCell(
        context: Context,
        name: String,
        running: Boolean,
        onClick: () -> Unit
    ): View {
        val d = context.resources.displayMetrics.density
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(0x1FFFFFFF, 10 * d, 1 * d, 0x22FFFFFF)
            setPadding((10 * d).toInt(), 0, (6 * d).toInt(), 0)
            isClickable = true
            setOnClickListener { onClick() }
            if (!running && runningTask != null) alpha = 0.45f
        }
        cell.addView(textView(context, "▶", 10f, COLOR_ACCENT))
        cell.addView(
            textView(context, name, 13f, if (running) COLOR_RUNNING else COLOR_TEXT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding((7 * d).toInt(), 0, (4 * d).toInt(), 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (running) {
            cell.addView(
                textView(context, "运行中", 9f, COLOR_RUNNING).apply {
                    background = roundedBackground(0x22FFFFFF, 7 * d, 1 * d, 0x55FFFFFF)
                    setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
                }
            )
        }
        return cell
    }

    /** 紧凑工具按钮：仅显示图标，保留无障碍描述。 */
    private fun toolIconButton(context: Context, iconRes: Int, desc: String, onClick: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        return android.widget.ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(COLOR_TEXT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            background = roundedBackground(0x1FFFFFFF, 8 * d, 1 * d, 0x22FFFFFF)
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, (48 * d).toInt(), 1f).apply {
                marginEnd = (6 * d).toInt()
            }
        }
    }

    /** 面板底部「✕ 关闭悬浮窗」整行按钮 */
    private fun closeLauncherRow(context: Context): View {
        val d = context.resources.displayMetrics.density
        return textView(context, "✕ 关闭悬浮窗", 13f, COLOR_TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            isClickable = true
            setOnClickListener {
                Log.i(TAG, "面板点击关闭悬浮窗，回调已绑定=${onLauncherClose != null}")
                // 不恢复悬浮球：接下来整体隐藏（hideLauncher 内部也会再收面板）
                hideTaskPanel(restoreBall = false)
                onLauncherClose?.invoke() ?: run {
                    // 宿主未绑定回调（如仅保活服务自启）时退化为直接隐藏并记录用户意愿
                    appContext?.let { ctx ->
                        setEnabledByUser(ctx, false)
                        hideLauncher(ctx)
                    }
                }
            }
        }
    }

    private fun hideTaskPanel(restoreBall: Boolean = true) {
        panelView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
        if (restoreBall) restoreCapsuleIfNeeded()
    }

    /** 面板展开期间把悬浮球窗口暂时移除（收起面板时由 [restoreCapsuleIfNeeded] 恢复） */
    private fun hideCapsuleForPanel() {
        capsuleView?.let { cap ->
            if (cap.isAttachedToWindow) {
                try {
                    windowManager?.removeView(cap)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 面板收起后恢复悬浮球（沿用隐藏前的位置参数，内容保持原状态） */
    private fun restoreCapsuleIfNeeded() {
        val cap = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        if (cap.isAttachedToWindow || cap.parent != null) return
        try {
            wm.addView(cap, params)
        } catch (_: Exception) {
        }
    }

    /** 面板中的一行菜单项（空列表提示等） */
    private fun menuText(context: Context, label: String, onClick: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 叠加窗口通用参数：置顶、不抢焦点、可触摸 */
    private fun overlayParams(gravity: Int, x: Int, y: Int): WindowManager.LayoutParams {
        // minSdk 29：悬浮窗类型固定为 TYPE_APPLICATION_OVERLAY
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            // NO_LIMITS 允许窗口探出屏幕外：贴边半隐藏的「埋进边缘」部分需要延伸出屏幕边界
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }
}
