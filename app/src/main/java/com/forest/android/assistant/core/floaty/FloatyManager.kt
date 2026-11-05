package com.forest.android.assistant.core.floaty

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.AppPalette

/**
 * 悬浮窗管理：统一为一个悬浮控件（依赖 SYSTEM_ALERT_WINDOW 权限）。
 *
 * 控件有状态，就地变形而不是叠加多个窗口：
 * - 空闲态：肥宅雕 Logo 悬浮球，点击展开面板，拖动可移动并自动贴边（位置会记住）；
 * - 运行态：原矩形条显示任务名与「停止」按钮，单击即可停止；现在显示红色雕雕、头顶任务名，双击雕雕停止。
 *
 * 贴边后空闲球半隐藏：约一半探出屏外，点击先弹出完整显示，再次点击才展开面板；
 * 半隐藏位置与贴边方向都会持久化，重启后保持贴边形态。
 * 「松手自动贴边」可在 App「设置」页关闭：关闭后松手保持完整显示，只把控件钳在屏内。
 *
 * 贴边坐标全部由 [FloatyGeometry] 计算，并统一使用真实显示尺寸（displayMetrics 在部分 ROM 会虚报）。
 *
 * 展开的面板按「任务 / 工具」分区：任务列表可滚动并支持多选（点选顺序即队列执行顺序），
 * 工具区提供取点、取色、选范围、截图、纯坐标录制和完整录制，
 * 底部固定提供执行入口，悬浮窗总开关由 App「设置」页管理。
 * 对应设计 docs/README.md 第 4.5 节「悬浮与调试工具」。
 */
object FloatyManager {

    private const val TAG = "ScriptJs"
    private const val PREFS = "floaty"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    /** 用户是否希望显示悬浮球（默认 true = 首次启动自动开启） */
    private const val KEY_ENABLED = "enabled"

    /** 持久化的贴边方向（[DockSide.ordinal]）；只在空闲态写入，重启/旋转后据此重算贴边坐标 */
    private const val KEY_DOCK = "dock"

    /** 拖动松手后是否自动贴边（默认 true；关闭后松手保持原位，只钳在屏内） */
    private const val KEY_AUTO_SNAP = "autoSnap"

    /** 松手贴边后留在屏内的边距（未半隐藏时） */
    private const val SNAP_MARGIN_DP = 6

    /** 空闲球尺寸（dp），与 ballView 保持一致 */
    private const val BALL_SIZE_DP = 35

    /** 运行态红色雕雕与空闲球使用相同的显示尺寸；头顶任务名可独立伸展。 */
    private const val RUNNING_BIRD_SIZE_DP = BALL_SIZE_DP
    private const val RUNNING_LABEL_MAX_WIDTH_DP = 170
    private const val RUNNING_LABEL_HEIGHT_DP = 30
    private const val RUNNING_GAP_DP = 2

    // 旧运行条尺寸：宽度约为上一版 213dp 的一半，现仅供布局历史说明。
    // 旧版队列执行时的运行条宽度：适当加宽，保证「进度 + 任务名」可读。

    // 配色（悬浮窗沿用 App 的暖白/深绿灰主题，保证在其他应用之上也可读）
    private const val COLOR_BG = AppPalette.SURFACE
    private const val COLOR_BG_TOP = AppPalette.SURFACE_CONTAINER_LOW
    private const val COLOR_TEXT = AppPalette.ON_SURFACE
    private const val COLOR_TEXT_DIM = AppPalette.ON_SURFACE_VARIANT
    private const val COLOR_PRIMARY = AppPalette.PRIMARY
    private const val COLOR_PRIMARY_CONTAINER = AppPalette.PRIMARY_CONTAINER
    private const val COLOR_PRIMARY_TEXT = AppPalette.ON_PRIMARY_CONTAINER
    private const val COLOR_ON_PRIMARY = AppPalette.ON_PRIMARY
    private const val COLOR_RUNNING = AppPalette.PRIMARY
    private const val COLOR_STOP = AppPalette.ERROR
    private const val COLOR_DIVIDER = AppPalette.OUTLINE_VARIANT

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否启用悬浮球（默认开启） */
    fun isEnabledByUser(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabledByUser(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 拖动松手后是否自动贴边（默认开启）；关闭后松手保持完整显示，只钳在屏内 */
    fun isAutoSnapEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SNAP, true)

    /**
     * 设置松手自动贴边，并立即作用到当前悬浮球：
     * - 开启：空闲球直接贴到最近的边缘（半隐藏）；
     * - 关闭：半隐藏的球弹成完整显示，之后松手也不再自动吸边。
     * 运行条不参与贴边，等回到空闲态再生效。必须在主线程调用。
     */
    fun setAutoSnapEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SNAP, enabled).apply()
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        if (runningTask != null) return
        val screen = realScreenSize(context)
        val viewW = stagedViewW(context)
        val viewH = view.height.takeIf { it > 0 } ?: viewW
        if (enabled) {
            // 开启时立即贴边，避免用户以为开关没生效
            val side = FloatyGeometry.sideByCenter(params.x, viewW, screen.x)
            dockedSide = side
            params.x = FloatyGeometry.peekedX(viewW, screen.x, side)
        } else {
            // 关闭时把半隐藏的球弹出来，保持「完整显示」的一致性
            val side = if (dockedSide == DockSide.NONE) {
                FloatyGeometry.sideByCenter(params.x, viewW, screen.x)
            } else {
                dockedSide
            }
            dockedSide = DockSide.NONE
            params.x = FloatyGeometry.visibleX(viewW, screen.x, side, snapMargin(context))
        }
        params.y = FloatyGeometry.clampY(params.y, viewH, screen.y)
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y, dockedSide)
        Log.i(TAG, "自动贴边已${if (enabled) "开启" else "关闭"} (${params.x}, ${params.y})")
    }

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null

    /** 统一的悬浮控件（胶囊） */
    private var capsuleView: LinearLayout? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    /** 运行时仅雕雕本体响应拖动与双击，头顶名称只负责显示。 */
    private var runningBirdView: ImageView? = null
    private var runningBirdSide = DockSide.NONE
    private var lastRunningTapAt = 0L
    private var lastRunningTapX = 0f
    private var lastRunningTapY = 0f
    private data class ScreenshotVisibility(val view: View, val visibility: Int, val flags: Int)
    private var screenshotVisibility: ScreenshotVisibility? = null

    /** 展开的任务/工具面板 */
    private var panelView: View? = null

    /** 从面板启动任务后，在运行态就绪前暂不恢复空闲球。 */
    private var startingFromPanel = false

    /** 当前贴边形态（LEFT/RIGHT = 半隐藏在屏幕边缘，NONE = 完整显示）；[DockSide] 定义在 FloatyGeometry */
    private var dockedSide = DockSide.NONE

    /** 运行前的空闲球位置与贴边状态；结束后恢复，不让运行条抢走用户的位置。 */
    private var idlePosition: Pair<Int, Int>? = null
    private var idleDockedSide = DockSide.NONE

    /** 当前运行中的任务名（null = 空闲态） */
    @Volatile
    private var runningTask: String? = null

    /** 队列执行进度（如「2/3」）；null = 单任务运行或空闲 */
    @Volatile
    private var runningProgress: String? = null

    /** 点击[停止]时的回调（任务运行中）；运行态双击雕雕也会触发。 */
    @Volatile
    var onStopClicked: (() -> Unit)? = null

    /** 悬浮球面板中选择任务后的回调（多选：按列表顺序依次执行） */
    @Volatile
    var onTasksSelected: ((List<String>) -> Unit)? = null

    /** 任务列表提供者（由宿主注入，避免 core 层循环依赖） */
    @Volatile
    var taskProvider: (() -> List<String>)? = null

    // 原面板「关闭悬浮窗」回调已移除，运行状态与开关 UI 由应用设置统一管理。

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

    /** 应用内的详情与设置入口复用同一任务面板。 */
    fun showTaskPanel(context: Context): Boolean {
        if (!hasOverlayPermission(context)) return false
        if (panelView != null) return true
        val wm = windowManager ?: return false
        toggleTaskPanel(context.applicationContext, wm)
        return panelView != null
    }

    /**
     * 屏幕尺寸/方向变化（旋转、分屏、分辨率切换）后调用：
     * 按新的屏幕边界把悬浮球收回屏内并保存；面板位置依赖悬浮球，直接收起避免错位。
     * 统一使用真实显示尺寸，且按持久化的贴边方向重算贴边坐标（否则转屏后右贴边的球会留在屏幕中间）。
     */
    fun onScreenSizeChanged(context: Context) {
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        if (runningTask != null) {
            hideTaskPanel()
            view.post { positionRunningBird(context) }
            return
        }
        val screen = realScreenSize(context)
        val viewW = stagedViewW(context)
        val h = view.height.takeIf { it > 0 } ?: viewW
        val autoSnap = isAutoSnapEnabled(context)
        // 半隐藏贴边态：按新屏宽重新对齐探出屏外的部分；否则只钳回屏内。
        // 关闭自动贴边时不保持半隐藏，球回到完整显示。
        params.x = FloatyGeometry.restoreX(
            params.x, viewW, screen.x, dockedSide, snapMargin(context), keepDocked = autoSnap
        )
        dockedSide = if (autoSnap) dockedSide else DockSide.NONE
        params.y = FloatyGeometry.clampY(params.y, h, screen.y)
        hideTaskPanel()
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y, dockedSide)
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
        // 面板已移除、任务仍在准备时，保活服务不应提前把空闲球挂回屏幕。
        if (startingFromPanel && capsuleView != null) return true
        // 模拟器/系统可能移除了窗口但没有销毁应用进程；清掉陈旧引用后重新 addView。
        if (capsuleView != null) {
            hideLauncher(stopKeepAlive = false)
            Log.w(TAG, "检测到悬浮球窗口已脱离 WindowManager，正在重建")
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density

        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 背景/内边距按状态在 renderCapsule 中设置。
        }
        val screen = realScreenSize(context)
        val defaultX = (12 * density).toInt()
        // 默认放在中下部并靠左：避开任务列表首屏的主要可点区域
        val defaultY = (screen.y * 0.72f).toInt()
        // 恢复位置：优先用持久化的贴边方向重算，转屏/换分辨率后仍回到同一条边
        val (posX, posY) = restorePosition(
            context,
            loadPosition(context, KEY_X, defaultX),
            loadPosition(context, KEY_Y, defaultY),
            loadDockSide(context)
        )
        if (dockedSide != DockSide.NONE) {
            Log.i(TAG, "恢复半隐藏贴边悬浮球: $dockedSide")
        }
        val params = overlayParams(
            Gravity.TOP or Gravity.START,
            x = posX,
            y = posY
        )

        // 空闲球与运行态雕雕均可拖动；只有雕雕本体接收运行时手势。
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var runningOnDown = false
        var ignoredRunningTouch = false
        // 用系统触摸阈值判定拖动：贴边球只剩半个圆可点，5px 的抖动太容易被当成拖动而重新贴边
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
        val slopSq = touchSlop * touchSlop
        val doubleTapSlopSq = doubleTapSlop * doubleTapSlop
        capsule.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    runningOnDown = runningTask != null
                    ignoredRunningTouch = false
                    if (runningOnDown) {
                        val hit = Rect()
                        runningBirdView?.getHitRect(hit)
                        if (!hit.contains(event.x.toInt(), event.y.toInt())) {
                            // 名称与提示是纯展示区域；消费触摸，避免无障碍 click 监听误接收单击。
                            lastRunningTapAt = 0L
                            ignoredRunningTouch = true
                            return@setOnTouchListener true
                        }
                    }
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ignoredRunningTouch) return@setOnTouchListener true
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > slopSq) {
                        moved = true
                        if (runningOnDown) {
                            // 运行态雕雕与球同为 TOP|START 重力坐标系，拖动时钳在真实屏幕内。
                            lastRunningTapAt = 0L
                            val screenNow = realScreenSize(context)
                            val birdW = runningBirdWidth(context, screenNow.x)
                            val birdH = capsule.height.takeIf { it > 0 }
                                ?: runningBirdHeight(context)
                            params.x = FloatyGeometry.clampX(startX + dx, birdW, screenNow.x)
                            params.y = FloatyGeometry.clampY(startY + dy, birdH, screenNow.y)
                        } else {
                            hideTaskPanel()
                            val screenNow = realScreenSize(context)
                            val viewW = stagedViewW(context)
                            val viewH = capsule.height.takeIf { it > 0 } ?: viewW
                            // 拖动中允许略微探出屏外（保持贴边半隐藏形态），但不允许整颗丢出屏幕
                            params.x = FloatyGeometry.clampDragX(startX + dx, viewW, screenNow.x)
                            params.y = FloatyGeometry.clampY(startY + dy, viewH, screenNow.y)
                        }
                        try {
                            wm.updateViewLayout(capsule, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (ignoredRunningTouch) {
                        ignoredRunningTouch = false
                        runningOnDown = false
                        return@setOnTouchListener true
                    }
                    when {
                        moved -> settleAfterDrag(context, wm, capsule, params)
                        runningOnDown -> {
                            val hit = Rect()
                            runningBirdView?.getHitRect(hit)
                            if (runningTask != null && hit.contains(event.x.toInt(), event.y.toInt())) {
                                val now = SystemClock.uptimeMillis()
                                val dx = event.rawX - lastRunningTapX
                                val dy = event.rawY - lastRunningTapY
                                if (lastRunningTapAt > 0 &&
                                    now - lastRunningTapAt <= ViewConfiguration.getDoubleTapTimeout() &&
                                    dx * dx + dy * dy <= doubleTapSlopSq
                                ) {
                                    lastRunningTapAt = 0L
                                    onStopClicked?.invoke()
                                } else {
                                    lastRunningTapAt = now
                                    lastRunningTapX = event.rawX
                                    lastRunningTapY = event.rawY
                                }
                            }
                        }
                        // 半隐藏贴边第一次点击：先完整弹出，下一次点击才展开面板
                        popOutIfDocked(context, wm, capsule, params) -> Unit
                        else -> toggleTaskPanel(context, wm)
                    }
                    runningOnDown = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 手势被系统打断（来电/通知栏/多指）：收回到合法位置，避免悬浮球留在屏幕外找不回来
                    if (moved) settleAfterDrag(context, wm, capsule, params)
                    lastRunningTapAt = 0L
                    ignoredRunningTouch = false
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
        startingFromPanel = false
        hideTaskPanel(restoreBall = false)
        capsuleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        capsuleView = null
        capsuleParams = null
        runningBirdView = null
        lastRunningTapAt = 0L
        windowManager = null
        dockedSide = DockSide.NONE   // 下次显示时按持久化位置重新推断贴边形态
        if (stopKeepAlive) {
            (context?.applicationContext ?: appContext)?.let { FloatyForegroundService.stop(it) }
        }
        Log.i(TAG, "悬浮控件已隐藏")
    }

    /** 截图时临时隐藏已显示的胶囊，保留窗口和视图对象。必须在主线程调用。 */
    fun hideLauncherForScreenshot(): Boolean {        val view = capsuleView ?: return false
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

    /**
     * 拾取工具（取点/取色/选区/截图）共用：收起面板并隐藏悬浮球（只切可见性），
     * 返回是否需要在拾取结束后恢复悬浮球。必须在主线程调用。
     */
    fun prepareForPicker(): Boolean {
        val panelShowing = panelView != null
        val launcherHidden = hideLauncherForScreenshot()
        // 同步移除任务面板：拾取画面里绝不能残留面板，也不留待处理的移除消息
        hideTaskPanel(restoreBall = false, immediate = true)
        return panelShowing || launcherHidden
    }

    /** 拾取结束后恢复悬浮球：优先只切回可见性，窗口已被移除时重建 */
    fun restoreAfterPicker(context: Context) {
        if (restoreLauncherAfterScreenshot()) return
        if (isEnabledByUser(context)) showLauncher(context)
    }

    /** 切换到运行态：红色雕雕头顶显示任务名，双击雕雕停止任务；沿用空闲球的锚点。
     * @param progress 队列进度（如「2/3」），单任务运行传 null
     */
    fun setRunning(context: Context, taskName: String, progress: String? = null) {
        // 面板触发的任务先保持胶囊脱离窗口，等红色雕雕与坐标就绪再显示。
        startingFromPanel = false
        hideTaskPanel(restoreBall = false)
        if (capsuleView == null || capsuleView?.parent != null && capsuleView?.isAttachedToWindow != true) {
            if (!showLauncher(context)) {
                Log.w(TAG, "运行态悬浮控件显示失败")
                return
            }
        }
        val wasRunning = runningTask != null
        val oldWidth = if (wasRunning) runningBirdWidth(context, realScreenSize(context).x) else 0
        if (!wasRunning) {
            // 记下空闲球的锚点：运行态出现在同一位置，任务结束后再还原
            capsuleParams?.let { idlePosition = it.x to it.y }
            idleDockedSide = dockedSide
            // 已显示的空闲球也先移除，避免换图后才移动到任务名上方。
            detachCapsuleForTransition()
        }
        if (runningTask != taskName || runningProgress != progress) lastRunningTapAt = 0L
        runningTask = taskName
        runningProgress = progress
        dockedSide = DockSide.NONE
        // 队列里切下一个任务时保留雕雕位置；名称宽度变化时避免图像跳动。
        if (wasRunning) {
            val delta = oldWidth - runningBirdWidth(context, realScreenSize(context).x)
            capsuleParams?.let { params ->
                params.x += when (runningBirdSide) {
                    DockSide.RIGHT -> delta
                    DockSide.NONE -> delta / 2
                    DockSide.LEFT -> 0
                }
            }
        }
        renderCapsule(context, reposition = !wasRunning)
        if (!wasRunning) {
            positionRunningBird(context)
            restoreCapsuleIfNeeded()
        }
        Log.i(TAG, "悬浮控件进入运行态: $taskName${progress?.let { "（$it）" } ?: ""}")
    }

    /** 回到空闲态（任务结束/停止后调用） */
    fun clearRunning(context: Context) {
        startingFromPanel = false
        // 先移除运行窗口，避免普通 Logo 在任务名称的旧窗口位置短暂显示。
        detachCapsuleForTransition()
        runningTask = null
        runningProgress = null
        lastRunningTapAt = 0L
        runningBirdSide = DockSide.NONE
        val savedPosition = idlePosition
        val savedSide = idleDockedSide
        val autoSnap = isAutoSnapEnabled(context)
        // 恢复空闲球的贴边形态：关闭自动贴边或原本完整显示时不再半隐藏
        dockedSide = if (autoSnap) savedSide else DockSide.NONE
        capsuleParams?.let { params ->
            savedPosition?.let { (x, y) ->
                val screen = realScreenSize(context)
                val ballW = ballSize(context)
                params.x = FloatyGeometry.restoreX(
                    x, ballW, screen.x, savedSide, snapMargin(context), keepDocked = autoSnap
                )
                params.y = FloatyGeometry.clampY(y, ballW, screen.y)
            }
            params.gravity = Gravity.TOP or Gravity.START
        }
        renderCapsule(context)
        if (panelView == null) restoreCapsuleIfNeeded()
        idlePosition = null
        idleDockedSide = DockSide.NONE
        Log.i(TAG, "悬浮控件回到空闲态")
    }

    /**
     * 按当前状态重建胶囊内容：
     * - 空闲：35dp 圆形悬浮球（品牌图标）；
     * - 运行：原矩形条显示任务名和停止按钮；现为红色雕雕与顶部任务名。
     * @param reposition 内容尺寸变化后是否重新定位（队列切任务时保持用户拖好的位置）
     */
    @SuppressLint("SetTextI18n")
    private fun renderCapsule(context: Context, reposition: Boolean = true) {
        val capsule = capsuleView ?: return
        capsule.removeAllViews()
        runningBirdView = null
        val task = runningTask
        if (task == null) {
            capsule.setPadding(0, 0, 0, 0)
            capsule.background = null
            capsule.alpha = 0.92f
            capsule.contentDescription = "雕雕悬浮球，点击展开任务面板"
            capsule.setOnClickListener(null)
            capsule.addView(ballView(context))
        } else {
            capsule.setPadding(0, 0, 0, 0)
            capsule.background = null
            capsule.alpha = 1f
            capsule.contentDescription = "正在运行 $task，双击雕雕停止任务"
            // 无障碍服务双击激活时发送一次 click；触屏双击仍由下方手势逻辑处理。
            capsule.setOnClickListener { onStopClicked?.invoke() }
            capsule.addView(runningBadge(context, task))
            capsule.addView(runningBird(context))
        }
        capsuleView?.let { view ->
            if (view.isAttachedToWindow) {
                try {
                    capsuleParams?.let { windowManager?.updateViewLayout(view, it) }
                } catch (_: Exception) {
                }
            }
            if (!reposition) {
                view.post { keepInsideScreen(context) }
                return@let
            }
            view.post {
                if (runningTask != null) positionRunningBird(context) else keepInsideScreen(context)
            }
        }
    }

    /** 状态切换后按实际宽高把悬浮窗收回屏幕内（统一使用真实显示尺寸） */
    private fun keepInsideScreen(context: Context) {
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        val h = view.height.takeIf { it > 0 } ?: return
        val screen = realScreenSize(context)
        val w = stagedViewW(context)
        val nx = if (runningTask == null && dockedSide != DockSide.NONE) {
            // 半隐藏贴边态：按当前宽度重新对齐屏外隐藏量（球↔胶囊宽度变化后仍保持贴边形态）
            FloatyGeometry.peekedX(w, screen.x, dockedSide)
        } else {
            FloatyGeometry.clampX(params.x, w, screen.x)
        }
        val ny = FloatyGeometry.clampY(params.y, h, screen.y)
        if (nx != params.x || ny != params.y) {
            params.x = nx
            params.y = ny
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 运行态定位：雕雕沿用空闲球的锚点，名称在头顶，贴边时整体留在屏内。
     * - 球半隐藏在边缘时，红色雕雕靠同侧完整显示，保证双击区域可见；
     * - y 沿用球的中心，并钳在真实屏幕内。
     * 运行态位置不持久化：清空运行态后空闲球回到自己的记忆位置。
     */
    private fun positionRunningBird(context: Context) {
        if (runningTask == null) return
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val screen = realScreenSize(context)
        val width = runningBirdWidth(context, screen.x)
        val height = view.height.takeIf { view.isAttachedToWindow && it > 0 }
            ?: runningBirdHeight(context)
        val anchor = idlePosition ?: (params.x to params.y)
        val ball = ballSize(context)
        runningBirdSide = when {
            idleDockedSide != DockSide.NONE -> idleDockedSide
            anchor.first < width / 2 -> DockSide.LEFT
            anchor.first > screen.x - ball - width / 2 -> DockSide.RIGHT
            else -> DockSide.NONE
        }
        // 横向让雕雕贴近原来的球；靠边时任务名向屏幕内部延伸。
        params.gravity = Gravity.TOP or Gravity.START
        params.x = when (runningBirdSide) {
            DockSide.LEFT -> if (idleDockedSide == DockSide.LEFT) {
                FloatyGeometry.visibleX(width, screen.x, DockSide.LEFT, snapMargin(context))
            } else FloatyGeometry.clampX(anchor.first, width, screen.x)
            DockSide.RIGHT -> if (idleDockedSide == DockSide.RIGHT) {
                FloatyGeometry.visibleX(width, screen.x, DockSide.RIGHT, snapMargin(context))
            } else FloatyGeometry.clampX(anchor.first + ball - width, width, screen.x)
            DockSide.NONE -> FloatyGeometry.clampX(
                anchor.first - (width - ball) / 2, width, screen.x
            )
        }
        val labelHeight = (RUNNING_LABEL_HEIGHT_DP + RUNNING_GAP_DP) * context.resources.displayMetrics.density
        params.y = FloatyGeometry.clampY(
            anchor.second + (ball - runningBirdSize(context)) / 2 - labelHeight.toInt(),
            height, screen.y
        )
        updateRunningBirdAlignment()
        if (view.isAttachedToWindow) {
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    /** 切换图标前先移除旧窗口，避免内容变化和窗口移动分两帧呈现。 */
    private fun detachCapsuleForTransition() {
        val view = capsuleView ?: return
        if (!view.isAttachedToWindow) return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    /** 运行时拖动换边后，让图像始终靠近对应屏幕边缘。 */
    private fun updateRunningBirdAlignment() {
        val image = runningBirdView ?: return
        val lp = image.layoutParams as LinearLayout.LayoutParams
        lp.gravity = birdGravity()
        image.layoutParams = lp
    }

    private fun birdGravity(): Int = when (runningBirdSide) {
        DockSide.LEFT -> Gravity.START
        DockSide.RIGHT -> Gravity.END
        DockSide.NONE -> Gravity.CENTER_HORIZONTAL
    }

    /** 参与贴边计算的悬浮控件宽度：空闲用固定球宽（状态切换瞬间测量值可能是旧胶囊宽度） */
    private fun stagedViewW(context: Context): Int {
        if (runningTask == null) return ballSize(context)
        return runningBirdWidth(context, realScreenSize(context).x)
    }

    /** 顶部任务名按实际文字收缩，长名称与队列进度按屏宽截断。 */
    private fun runningBirdWidth(context: Context, screenW: Int): Int {
        val d = context.resources.displayMetrics.density
        val text = runningLabel(runningTask ?: "")
        val paint = android.graphics.Paint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val max = (RUNNING_LABEL_MAX_WIDTH_DP * d).toInt()
            .coerceAtMost((screenW - (12 * d).toInt()).coerceAtLeast(1))
        return (paint.measureText(text).toInt() + (34 * d).toInt())
            .coerceAtLeast(runningBirdSize(context))
            .coerceAtMost(max)
    }

    private fun runningBirdSize(context: Context): Int =
        (RUNNING_BIRD_SIZE_DP * context.resources.displayMetrics.density).toInt()

    private fun runningBirdHeight(context: Context): Int =
        ((RUNNING_LABEL_HEIGHT_DP + RUNNING_GAP_DP + RUNNING_BIRD_SIZE_DP) *
            context.resources.displayMetrics.density).toInt()

    // 队列执行时在任务名前标注进度（如「2/3」），让用户知道还有几个任务待执行
    private fun runningLabel(task: String): String =
        runningProgress?.let { "[$it] $task" } ?: task

    /** 空闲球边长（px） */
    private fun ballSize(context: Context): Int =
        (BALL_SIZE_DP * context.resources.displayMetrics.density).toInt()

    /** 完整贴边时留在屏内的边距（px） */
    private fun snapMargin(context: Context): Int =
        (SNAP_MARGIN_DP * context.resources.displayMetrics.density).toInt()

    /** 空闲态：圆形角色图片（不带状态点，悬浮球保持干净） */
    private fun ballView(context: Context): View {
        val d = context.resources.displayMetrics.density
        val size = (BALL_SIZE_DP * d).toInt()
        return android.widget.ImageView(context).apply {
            val bitmap = BitmapFactory.decodeResource(
                context.resources, com.forest.android.assistant.R.drawable.ic_brand_mark
            )
            setImageDrawable(RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
                isCircular = true
                setAntiAlias(true)
                setFilterBitmap(true)
            })
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            // 直接圆形裁切图片，无需底板，也不依赖悬浮窗口的硬件加速。
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /** 任务名悬在红色雕雕头顶；队列进度仍显示在名称前，长名字省略尾部。 */
    private fun runningBadge(context: Context, task: String): View {
        val d = context.resources.displayMetrics.density
        val width = runningBirdWidth(context, realScreenSize(context).x)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(AppPalette.SURFACE, 15 * d, 1 * d, AppPalette.OUTLINE_VARIANT)
            setPadding((9 * d).toInt(), 0, (8 * d).toInt(), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                View(context).apply { background = roundedBackground(COLOR_STOP, 4 * d, 0f, 0) },
                LinearLayout.LayoutParams((7 * d).toInt(), (7 * d).toInt()).apply {
                    marginEnd = (6 * d).toInt()
                }
            )
            addView(
                textView(context, runningLabel(task), 12f, COLOR_TEXT).apply {
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            layoutParams = LinearLayout.LayoutParams(width, (RUNNING_LABEL_HEIGHT_DP * d).toInt())
        }
    }

    /** 透明背景的红色雕雕，与空闲球同尺寸；手势命中区域与图像尺寸一致。 */
    private fun runningBird(context: Context): ImageView {
        val d = context.resources.displayMetrics.density
        val size = runningBirdSize(context)
        return ImageView(context).apply {
            setImageResource(com.forest.android.assistant.R.drawable.ic_running_bird)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = birdGravity()
                topMargin = (RUNNING_GAP_DP * d).toInt()
            }
            runningBirdView = this
        }
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

    /** 与录制悬浮窗共用的视觉语言：暖白卡片、深绿灰标题栏、深蓝主按钮。 */
    private fun panelGradient(
        top: Int,
        bottom: Int,
        radiusPx: Float,
        strokePx: Float = 0f,
        strokeColor: Int = 0
    ) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
        cornerRadius = radiusPx
        if (strokePx > 0f) setStroke(strokePx.toInt().coerceAtLeast(1), strokeColor)
    }

    /** 按钮和任务格子使用有界水波纹，让点选反馈与圆角轮廓一致。 */
    private fun panelTouchBackground(background: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x1A1D3557), background, null)

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
     * 拖动松手后的收位（也是手势被系统打断时的兜底）：
     * - 自动贴边开启：贴到最近的左/右边缘（空闲球半隐藏，运行条完整可见）；
     * - 自动贴边关闭：保持松手位置，只钳在屏内。
     * 运行条位置不写入偏好：空闲球位置单独记录，避免「距底边」的坐标污染「距顶边」的持久化值。
     */
    private fun settleAfterDrag(
        context: Context,
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val screen = realScreenSize(context)
        val margin = snapMargin(context)
        val viewW = stagedViewW(context)
        val viewH = view.height.takeIf { it > 0 } ?: viewW
        val autoSnap = isAutoSnapEnabled(context)
        if (runningTask != null) {
            // 运行时雕雕本体与头顶任务名必须保持可见，所以贴边也完整显示。
            val side = FloatyGeometry.sideByCenter(params.x, viewW, screen.x)
            params.x = if (autoSnap) {
                FloatyGeometry.visibleX(viewW, screen.x, side, margin)
            } else {
                FloatyGeometry.clampX(params.x, viewW, screen.x)
            }
            if (autoSnap) {
                runningBirdSide = side
                updateRunningBirdAlignment()
            }
        } else if (autoSnap) {
            val side = FloatyGeometry.sideByCenter(params.x, viewW, screen.x)
            dockedSide = side
            params.x = FloatyGeometry.peekedX(viewW, screen.x, side)
        } else {
            dockedSide = DockSide.NONE
            params.x = FloatyGeometry.clampX(params.x, viewW, screen.x)
        }
        // 垂直钳位：拖动松手后保证整个控件留在真实屏幕内（真实尺寸，displayMetrics 可能虚报）
        params.y = FloatyGeometry.clampY(params.y, viewH, screen.y)
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y, dockedSide)
        Log.i(
            TAG,
            "拖动结束收位: (${params.x}, ${params.y}) 自动贴边=${if (autoSnap) dockedSide else "关"}" +
                if (runningTask != null) "（运行态雕雕）" else ""
        )
    }

    /**
     * 半隐藏态第一次点击：把球从屏幕边缘完整弹出（不展开面板，下一次点击才展开）。
     * 弹出后的位置会持久化，重启保持「完整显示」。
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
        val screen = realScreenSize(context)
        val viewW = stagedViewW(context)
        val viewH = view.height.takeIf { it > 0 } ?: viewW
        params.x = FloatyGeometry.visibleX(viewW, screen.x, side, snapMargin(context))
        params.y = FloatyGeometry.clampY(params.y, viewH, screen.y)
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y, dockedSide)
        Log.i(TAG, "半隐藏悬浮球已弹出完整显示")
        return true
    }

    /** 读取持久化的贴边方向（旧版本没有该键，回退为按 x 推断） */
    private fun loadDockSide(context: Context): DockSide {
        val ordinal = prefs(context).getInt(KEY_DOCK, -1)
        return DockSide.values().firstOrNull { it.ordinal == ordinal } ?: DockSide.NONE
    }

    private fun loadPosition(context: Context, key: String, default: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, default)

    /**
     * 位置恢复：结合持久化的贴边方向与当前真实屏幕尺寸重算坐标。
     * - 有贴边方向 → 按新屏宽重算探出量（旋转/换分辨率后仍贴边，不再跑到屏幕中间）；
     * - 旧版本只有 x/y → 按 x 推断贴边（负 x=左贴边，越过右界=右贴边）；
     * - 关闭「自动贴边」 → 贴边的球回到同侧完整显示。
     * 屏内位置：y 越界时收紧到屏内（早期按 120dp 余量判定「越界」并复位是 bug，已修）。
     */
    private fun restorePosition(
        context: Context,
        x: Int,
        y: Int,
        savedSide: DockSide
    ): Pair<Int, Int> {
        val screen = realScreenSize(context)
        val viewW = ballSize(context)
        val inferred = FloatyGeometry.sideOf(x, viewW, screen.x)
        val side = if (savedSide != DockSide.NONE) savedSide else inferred
        val autoSnap = isAutoSnapEnabled(context)
        dockedSide = if (autoSnap) side else DockSide.NONE
        val nx = FloatyGeometry.restoreX(
            x, viewW, screen.x, side, snapMargin(context), keepDocked = autoSnap
        )
        val ny = FloatyGeometry.clampY(y, viewW, screen.y)
        if (nx != x || ny != y) {
            Log.i(TAG, "悬浮控件位置(x=$x,y=$y) → 贴边=$side 自动贴边=$autoSnap → ($nx,$ny)")
        }
        return nx to ny
    }

    /**
     * 保存空闲球位置与贴边方向。
     * 只持久化空闲态坐标：运行条曾是 BOTTOM 重力（y=距底边），写进同一组键会让下次启动的空闲球跳到屏幕顶部。
     */
    private fun savePosition(context: Context, x: Int, y: Int, side: DockSide = dockedSide) {
        if (runningTask != null) return
        prefs(context).edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .putInt(KEY_DOCK, side.ordinal)
            .apply()
    }

    // ==================== 任务 / 工具面板 ====================

    /**
     * 展开/收起面板（每次展开重新读取任务列表与运行状态）。
     * 布局：深绿灰标题栏 + 暖白内容区，自上而下 = 头部（Logo + 任务列表 + 数量 + 收起） /
     * 单列任务列表 / 固定底栏（执行或停止按钮、快捷工具入口）。
     * 任务格子支持多选：点选顺序即队列执行顺序（再次点选取消），主按钮按该顺序依次执行；
     * 长按某个任务可立即单独运行它。
     * 面板按内容高度在屏幕正中显示；任务较多时列表滚动，工具入口与底栏固定。
     * 面板展开期间悬浮球暂时隐藏（避免遮挡），收起面板时自动恢复。
     * 按住面板标题栏（「任务列表」区域）可整体拖动面板，✕ 按钮与列表滚动不受影响。
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

        // 使用当前显示区域限制面板高度；只有任务列表滚动，工具区和底部执行入口始终留在窗口内。
        @Suppress("DEPRECATION")
        val displaySize = android.graphics.Point().also { wm.defaultDisplay.getSize(it) }
        val maxPanelHeight = minOf((displaySize.y - 32 * d).toInt(), (370 * d).toInt()).coerceAtLeast(1)
        // 窄屏保留两侧各 12dp 的操作边距。
        val panelW = (288 * d).toInt()
            .coerceAtMost((displaySize.x - 24 * d).toInt()).coerceAtLeast(1)
        val contentInset = (8 * d).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelGradient(COLOR_BG_TOP, COLOR_BG, 18 * d, d, AppPalette.OUTLINE_VARIANT)
            elevation = 16 * d
            clipToOutline = true
        }

        // —— 头部：拖动把手 + 标题 + 任务数 + 关闭（按住头部可整体拖动面板） ——
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(contentInset, (3 * d).toInt(), (6 * d).toInt(), (3 * d).toInt())
            // 深绿灰标题栏与暖白内容区形成稳定层级，整条标题栏仍可拖动。
            setBackgroundColor(AppPalette.INVERSE_SURFACE)
        }
        val headerLogo = ImageView(context).apply {
            setImageResource(R.drawable.ic_brand_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val backToTasks = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(AppPalette.INVERSE_ON_SURFACE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "返回任务列表"
            background = panelTouchBackground(roundedBackground(0xFF35464A.toInt(), 14 * d, 0f, 0))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        header.addView(backToTasks, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply {
            marginEnd = (5 * d).toInt()
        })
        header.addView(headerLogo, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply {
            marginEnd = (8 * d).toInt()
        })
        val titleLabel = textView(context, "任务列表", 16f, AppPalette.INVERSE_ON_SURFACE).apply {
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleHint = textView(context, "屏幕拾取与操作录制", 10f, AppPalette.INVERSE_SUBTLE).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        header.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleLabel)
            addView(titleHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (3 * d).toInt() })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 任务数 / 已选数：选中任务后变为「已选 N/M」
        val countLabel = textView(context, "${tasks.size} 个任务", 11f, COLOR_PRIMARY_TEXT).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            background = roundedBackground(COLOR_PRIMARY_CONTAINER, 20 * d, 0f, 0)
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        header.addView(countLabel)
        header.addView(
            textView(context, "✕", 16f, AppPalette.INVERSE_ON_SURFACE).apply {
                gravity = Gravity.CENTER
                contentDescription = "收起任务列表"
                background = panelTouchBackground(roundedBackground(0xFF35464A.toInt(), 14 * d, 0f, 0))
                isClickable = true
                isFocusable = true
                setOnClickListener { hideTaskPanel() }
            },
            LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply {
                marginStart = (6 * d).toInt()
            }
        )
        panel.addView(header)
        panel.addView(divider(context))

        // 多选操作提示由任务格子的序号与选中态承载，不再单独占用提示行。
        // —— 任务列表（单列选择列表，独立滚动） ——
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(contentInset, (5 * d).toInt(), contentInset, (4 * d).toInt())
        }

        // 多选状态：列表顺序 = 队列执行顺序（点选先后决定先后）
        val selected = mutableListOf<String>()
        val cellHolders = LinkedHashMap<String, TaskCellViews>()

        // 主按钮：未选任务时置灰；选中后按点选顺序执行整个队列
        val runSelectedButton = textView(context, "请先选择任务", 14f, COLOR_ON_PRIMARY).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            background = panelTouchBackground(roundedBackground(COLOR_PRIMARY, 14 * d, 0f, 0))
            minimumHeight = (48 * d).toInt()
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            alpha = 0.4f
            isEnabled = false
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selected.isEmpty() || runningTask != null) return@setOnClickListener
                val queue = selected.toList()
                Log.i(TAG, "悬浮球面板执行所选任务: ${queue.joinToString(" → ")}")
                // 启动任务前同步移除面板，避免第一步手势落在尚未退出的悬浮窗上。
                startingFromPanel = true
                hideTaskPanel(restoreBall = false, immediate = true)
                onTasksSelected?.invoke(queue) ?: run {
                    startingFromPanel = false
                    restoreCapsuleIfNeeded()
                }
            }
        }

        // 选中变化后刷新格子高亮 / 序号 / 任务数文案 / 主按钮状态
        val refreshSelection = {
            cellHolders.forEach { (name, views) ->
                val order = selected.indexOf(name).takeIf { it >= 0 }?.plus(1)
                applyTaskCellState(
                    context = context,
                    views = views,
                    order = order,
                    running = runningTask == name,
                    idle = runningTask == null
                )
            }
            countLabel.text = if (selected.isEmpty()) "${tasks.size} 个任务" else "已选 ${selected.size}/${tasks.size}"
            runSelectedButton.isEnabled = selected.isNotEmpty() && runningTask == null
            // 禁用态使用清晰的灰绿文字，不再让整个按钮半透明而降低可读性。
            runSelectedButton.alpha = 1f
            runSelectedButton.setTextColor(if (runSelectedButton.isEnabled) COLOR_ON_PRIMARY else COLOR_TEXT_DIM)
            runSelectedButton.background = panelTouchBackground(roundedBackground(
                if (runSelectedButton.isEnabled) COLOR_PRIMARY else AppPalette.SURFACE_CONTAINER, 14 * d, 0f, 0))
            runSelectedButton.text = when {
                tasks.isEmpty() -> "暂无可执行的任务"
                selected.isEmpty() -> "请先选择任务"
                else -> "执行所选（${selected.size}）"
            }
        }

        if (tasks.isEmpty()) {
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_nav_tasks)
                    setColorFilter(COLOR_PRIMARY)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()))
                addView(menuText(context, "暂无任务") { }.apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 12f
                    setTextColor(COLOR_TEXT)
                    isClickable = false
                    setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
                })
                addView(textView(context, "在应用中创建或导入任务后即可运行", 10f, COLOR_TEXT_DIM).apply {
                    gravity = Gravity.CENTER
                })
            })
        } else {
            tasks.forEachIndexed { index, name ->
                val views = buildTaskCell(
                    context = context,
                    name = name,
                    onToggle = {
                        if (runningTask == null) {
                            if (!selected.remove(name)) selected.add(name)
                            refreshSelection()
                        }
                    },
                    onRunNow = {
                        if (runningTask == null) {
                            Log.i(TAG, "悬浮球面板长按立即运行: $name")
                            // 长按立即运行也要先完成面板移除。
                            startingFromPanel = true
                            hideTaskPanel(restoreBall = false, immediate = true)
                            onTasksSelected?.invoke(listOf(name)) ?: run {
                                startingFromPanel = false
                                restoreCapsuleIfNeeded()
                            }
                        }
                    }
                )
                cellHolders[name] = views
                list.addView(views.root, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = (3 * d).toInt() })
            }
        }
        refreshSelection()

        // 执行或停止按钮与快捷工具共用固定底栏。
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(contentInset, (4 * d).toInt(), contentInset, (5 * d).toInt())
        }
        footer.addView(
            runSelectedButton,
            LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        )

        // —— 底部：工具图标区（两行三列，保留足够的触摸区域）；从单独入口进入 ——
        val toolsArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        toolsArea.addView(textView(context, "选择工具", 12f, COLOR_TEXT_DIM).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        })
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 左右内边距 12dp 对称，按钮间距由每行统一分配。
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (12 * d).toInt())
        }
        val toolButtons = listOf(
            // 四个拾取工具都由 PickerOverlay 统一收起面板/隐藏悬浮球，并在结束后恢复
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_point, "取坐标") {
                PickerOverlay.show(context, PickerOverlay.Mode.POINT)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_color, "取色") {
                PickerOverlay.show(context, PickerOverlay.Mode.COLOR)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_region, "选择范围") {
                PickerOverlay.show(context, PickerOverlay.Mode.REGION)
            },
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_tool_shot, "截图") {
                PickerOverlay.show(context, PickerOverlay.Mode.SCREENSHOT)
            },
            // 纯坐标录制：一键开始，不截图也不需要截屏授权
            toolIconButton(context, com.forest.android.assistant.R.drawable.ic_record_point, "纯坐标录制") {
                hideTaskPanel()
                com.forest.android.assistant.core.recording.RecordingSetupOverlay.startPure(context)
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
                buttons.forEachIndexed { buttonIndex, button ->
                    addView(button, LinearLayout.LayoutParams(0, (72 * d).toInt(), 1f).apply {
                        marginEnd = if (buttonIndex < buttons.lastIndex) (6 * d).toInt() else 0
                    })
                }
            })
        }
        toolsArea.addView(tools)

        val toolsEntry = textView(context, "快捷工具", 13f, COLOR_TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = (48 * d).toInt()
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            background = panelTouchBackground(roundedBackground(AppPalette.SURFACE_CONTAINER_LOW, 14 * d, d, AppPalette.OUTLINE_VARIANT))
            contentDescription = "打开快捷工具"
            isClickable = true
            isFocusable = true
        }

        // 运行中：提供停止入口（与胶囊上的停止键等价）
        if (runningTask != null) {
            runSelectedButton.visibility = View.GONE
            val stopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            stopRow.addView(
                textView(context, "■ 停止当前任务", 14f, COLOR_ON_PRIMARY).apply {
                    background = panelTouchBackground(roundedBackground(COLOR_STOP, 14 * d, 0f, 0))
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    minimumHeight = (48 * d).toInt()
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                    isClickable = true
                    isFocusable = true
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
            footer.addView(stopRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        footer.addView(toolsEntry, LinearLayout.LayoutParams((84 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (6 * d).toInt()
        })
        val footerDivider = divider(context)
        val measureWidth = View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY)
        val measureHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        header.measure(measureWidth, measureHeight)
        footer.measure(measureWidth, measureHeight)
        list.measure(measureWidth, measureHeight)
        val maxListHeight = (maxPanelHeight - header.measuredHeight - footer.measuredHeight -
            2 * (1 * d).toInt().coerceAtLeast(1))
            .coerceAtLeast(1)
        // 内容超高时只压缩任务列表，先为标题、工具入口和执行按钮预留完整高度。
        // 只保留一个 ScrollView，且只包裹任务；工具入口和独立工具页不参与滚动。
        // 先按内容测量任务列表，再固定滚动视口高度，避免 WRAP_CONTENT 的二次测量把列表挤没。
        val listViewportHeight = minOf(list.measuredHeight, maxListHeight)
        val taskScroll = ScrollView(context).apply {
            addView(list)
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        panel.addView(taskScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, listViewportHeight
        ))
        panel.addView(toolsArea)

        // —— 底部：固定执行入口；悬浮窗总开关统一放在 App「设置」页 ——
        panel.addView(footerDivider)
        panel.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 两个页面在同一个窗口内切换，保留任务选择和列表滚动位置。
        toolsEntry.setOnClickListener {
            taskScroll.visibility = View.GONE
            footerDivider.visibility = View.GONE
            footer.visibility = View.GONE
            toolsArea.visibility = View.VISIBLE
            headerLogo.visibility = View.GONE
            backToTasks.visibility = View.VISIBLE
            titleLabel.text = "快捷工具"
            titleHint.visibility = View.VISIBLE
            countLabel.visibility = View.GONE
        }
        backToTasks.setOnClickListener {
            toolsArea.visibility = View.GONE
            taskScroll.visibility = View.VISIBLE
            footerDivider.visibility = View.VISIBLE
            footer.visibility = View.VISIBLE
            backToTasks.visibility = View.GONE
            headerLogo.visibility = View.VISIBLE
            titleLabel.text = "任务列表"
            titleHint.visibility = View.GONE
            countLabel.visibility = View.VISIBLE
        }

        // —— 定位：Gravity.CENTER 系统级居中 ——
        // 手工算坐标依赖 displayMetrics/WindowMetrics，实测 MuMu 上两者都虚报
        // （displayMetrics 高 2064 vs 实际 1920，最大窗口度量 1030x2064 vs 实际 1080x1920），
        // 会导致「看起来不居中」；交由系统按真实显示帧居中最稳，也不再需要布局监听校正。
        // 注意：CENTER 重力下 params.x/y 是「相对屏幕中心的偏移量」，头部拖动直接累加偏移。
        val params = overlayParams(Gravity.CENTER, x = 0, y = 0)
        params.width = panelW
        // 半隐藏悬浮球需要 NO_LIMITS，面板则必须受屏幕边界约束。
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
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
            val headerTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
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
                        if (hdMoved || dx * dx + dy * dy > headerTouchSlop * headerTouchSlop) {
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

    /** 任务格子的子视图集合（多选：勾选序号徽标 + 名称 + 运行中标记） */
    private class TaskCellViews(
        val root: LinearLayout,
        val badge: TextView,
        val label: TextView,
        val runningTag: TextView
    )

    /**
     * 单列任务行包含勾选序号、任务名（最多两行）和运行中标记。
     * 点选 = 加入/移出执行队列（序号 = 执行顺序），长按 = 立即单独运行。
     * 保留 48dp 的最小触摸高度，长名称可撑开行高。
     */
    private fun buildTaskCell(
        context: Context,
        name: String,
        onToggle: () -> Unit,
        onRunNow: () -> Unit
    ): TaskCellViews {
        val d = context.resources.displayMetrics.density
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * d).toInt()
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onToggle() }
            // 长按：快捷运行单个任务（不必先勾选再点主按钮）
            isLongClickable = true
            setOnLongClickListener {
                onRunNow()
                true
            }
        }
        // 勾选徽标：未选为空圈，选中后显示队列序号
        val badge = textView(context, "", 11f, COLOR_ON_PRIMARY).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isSingleLine = true
            setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cell.addView(badge, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
        val label = textView(context, name, 13f, COLOR_TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val runningTag = textView(context, "运行中", 10f, COLOR_RUNNING).apply {
            background = roundedBackground(COLOR_PRIMARY_CONTAINER, 7 * d, 0f, 0)
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // 名称与运行标记纵向排列，避免徽标挤占长任务名的显示空间。
        cell.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(runningTag, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (3 * d).toInt() })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * d).toInt()
        })
        return TaskCellViews(cell, badge, label, runningTag)
    }

    /**
     * 刷新任务格子的多选/运行态：
     * - [order] 非 null = 已选中，显示队列序号；（选中描边高亮）
     * - [running] 当前任务运行中（深蓝色高亮 + 「运行中」标记）；
     * - [idle] 空闲才可点选，运行中其他任务置灰。
     */
    private fun applyTaskCellState(
        context: Context,
        views: TaskCellViews,
        order: Int?,
        running: Boolean,
        idle: Boolean
    ) {
        val d = context.resources.displayMetrics.density
        views.root.background = panelTouchBackground(when {
            running -> roundedBackground(COLOR_PRIMARY_CONTAINER, 14 * d, d, COLOR_RUNNING)
            order != null -> roundedBackground(COLOR_PRIMARY_CONTAINER, 14 * d, 1.5f * d, COLOR_PRIMARY)
            else -> roundedBackground(COLOR_BG, 14 * d, d, AppPalette.OUTLINE_VARIANT)
        })
        views.badge.text = order?.toString() ?: if (running) "▶" else ""
        views.badge.setTextColor(if (running) COLOR_RUNNING else COLOR_ON_PRIMARY)
        views.badge.background = when {
            running -> roundedBackground(COLOR_PRIMARY_CONTAINER, 8 * d, 0f, 0)
            order != null -> roundedBackground(COLOR_PRIMARY, 8 * d, 0f, 0)
            else -> roundedBackground(AppPalette.SURFACE, 5 * d, d, AppPalette.OUTLINE)
        }
        views.label.setTextColor(when {
            running -> COLOR_RUNNING
            order != null -> COLOR_PRIMARY_TEXT
            else -> COLOR_TEXT
        })
        views.runningTag.visibility = if (running) View.VISIBLE else View.GONE
        views.root.isSelected = order != null
        views.root.isEnabled = idle
        views.root.alpha = if (idle || running) 1f else 0.45f
        views.root.contentDescription = when {
            running -> "${views.label.text}，运行中"
            order != null -> "${views.label.text}，第 $order 个执行，点按取消选择"
            idle -> "${views.label.text}，未选择，点按加入队列，长按立即运行"
            else -> "${views.label.text}，请先停止当前任务"
        }
    }

    /** 紧凑工具按钮：图标配文字标签，同时保留无障碍描述。 */
    private fun toolIconButton(context: Context, iconRes: Int, desc: String, onClick: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = (64 * d).toInt()
            background = panelTouchBackground(roundedBackground(COLOR_BG, 14 * d, d, AppPalette.OUTLINE_VARIANT))
            setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
            contentDescription = desc
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(COLOR_PRIMARY)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
            addView(textView(context, desc, 11f, COLOR_TEXT).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * d).toInt() })
        }
    }

    /** 面板底部「关闭悬浮窗」入口已移除，标题栏只负责收起面板并恢复悬浮球。 */
    // 整体隐藏时仍不恢复悬浮球，由 hideLauncher 收起面板并移除悬浮球。
    // 已移除宿主关闭回调及其降级逻辑，用户的开关意愿统一由应用设置记录。

    private fun hideTaskPanel(restoreBall: Boolean = true, immediate: Boolean = false) {
        panelView?.let {
            try {
                if (immediate) windowManager?.removeViewImmediate(it) else windowManager?.removeView(it)
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
            setTextColor(COLOR_TEXT)
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
