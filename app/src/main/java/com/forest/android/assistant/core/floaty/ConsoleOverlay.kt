package com.forest.android.assistant.core.floaty

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 悬浮控制台：浮在其他应用之上的实时日志面板。
 *
 * 布局（自上而下）：
 * ```
 * ┌──────────────────────────────────────────┐
 * │ 日志 (128)   [全部][信息][警告][错误] 清空 ✕ │ ← 标题栏：整条可拖动，位置会记住
 * ├──────────────────────────────────────────┤
 * │ 09:41:02.113 ▶ 启动任务: 手势演示            │ ← 日志区：等宽、级别着色、贴底自动滚动
 * │ 09:41:02.140 click(400, 300) -> true       │
 * ├──────────────────────────────────────────┤
 * │ ▶ 手势演示 · 00:12              [■ 停止]   │ ← 运行态才显示：任务 + 计时 + 停止
 * └──────────────────────────────────────────┘
 * ```
 * - 级别筛选只影响显示，不丢日志；日志数据由宿主注入（[logProvider]），core 层不依赖引擎；
 * - 只有用户已在底部时才自动滚到底，向上翻阅时不打断；
 * - 拖动标题栏移动并持久化位置（`floaty` 偏好 `console_x/console_y`），旋转后越界自动复位。
 *
 * 对应设计 docs/README.md 第 4.5 节「悬浮控制台」。
 */
object ConsoleOverlay {

    private const val TAG = "ScriptJs"
    private const val PREFS = "floaty"
    private const val KEY_X = "console_x"
    private const val KEY_Y = "console_y"
    private const val REFRESH_MS = 400L
    private const val MAX_RENDER = 200

    /** 控制台展示用的一行日志 */
    data class ConsoleLine(val time: String, val level: Char, val msg: String, val id: Long)

    /** 日志来源（宿主注入，如 ScriptRunner.logs） */
    @Volatile
    var logProvider: (() -> List<ConsoleLine>)? = null

    /** 清空日志回调（宿主注入） */
    @Volatile
    var onClear: (() -> Unit)? = null

    /** 停止当前任务（宿主注入）；返回是否处于运行态用于决定是否显示底部停止条 */
    @Volatile
    var onStop: (() -> Unit)? = null

    /** 运行态信息（任务名 + 已运行毫秒），null = 空闲 */
    @Volatile
    var runningInfo: (() -> Pair<String, Long>?)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var linesContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var titleView: TextView? = null
    private var footerRow: LinearLayout? = null
    private var footerText: TextView? = null
    private var filterRow: LinearLayout? = null
    private data class ScreenshotVisibility(val view: View, val visibility: Int, val flags: Int)
    private var screenshotVisibility: ScreenshotVisibility? = null

    /** 级别筛选：null=全部，否则只显示该级别 */
    private var filterLevel: Char? = null

    private val logWindow = LogWindow<ConsoleLine>(MAX_RENDER) { it.id }
    private val logSnapshotGate = LogSnapshotGate<ConsoleLine>()

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            refreshFooter()
            if (rootView != null) mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    fun isShowing(): Boolean = rootView != null

    /**
     * 屏幕尺寸/方向变化（旋转、分屏、分辨率切换）后调用：
     * 按新屏幕边界把控制台收回屏内并保存位置，避免旋转后半出屏（右侧按钮点不到）。
     */
    fun onScreenSizeChanged(context: Context) {
        val view = rootView ?: return
        val lp = params ?: return
        val wm = windowManager ?: return
        val m = context.resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: (520 * m.density).toInt()
        val h = view.height.takeIf { it > 0 } ?: (200 * m.density).toInt()
        lp.x = lp.x.coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (m.heightPixels - h).coerceAtLeast(0))
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
        savePos(context, lp.x, lp.y)
        Log.i(TAG, "屏幕尺寸变化：控制台已收回屏内 (${lp.x}, ${lp.y})")
    }

    /** 开关控制台；返回操作后是否处于显示状态 */
    fun toggle(context: Context): Boolean {
        if (isShowing()) {
            hide()
            return false
        }
        return show(context)
    }

    @SuppressLint("SetTextI18n")
    fun show(context: Context): Boolean {
        if (rootView != null) return true
        if (!FloatyManager.hasOverlayPermission(context)) {
            Log.w(TAG, "悬浮控制台需要悬浮窗权限")
            return false
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val width = (screenW - 20 * d).toInt().coerceAtMost((520 * d).toInt())

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF1D2B46.toInt(), 16 * d, 1 * d, 0x26FFFFFF)
            clipToOutline = true
        }

        // —— 标题栏（可拖动） ——
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }
        val title = TextView(context).apply {
            text = "日志"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionText(context, "清空") {
            onClear?.invoke()
            rebuild()
        })
        header.addView(actionText(context, "✕") { hide() })
        root.addView(header)
        titleView = title
        attachDrag(context, header)

        // —— 级别筛选行 ——
        val filters = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((14 * d).toInt(), 0, (10 * d).toInt(), (6 * d).toInt())
        }
        listOf<Pair<String, Char?>>(
            "全部" to null, "信息" to 'I', "警告" to 'W', "错误" to 'E'
        ).forEach { (label, level) ->
            filters.addView(filterChip(context, label, level))
        }
        root.addView(filters)
        filterRow = filters
        root.addView(divider(context))

        // —— 日志区 ——
        val lines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(lines)
            isFillViewport = true
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenH * 0.32f).toInt()
            )
        )
        linesContainer = lines
        scrollView = scroll

        // —— 底部运行态停止条（空闲时隐藏） ——
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            visibility = View.GONE
        }
        footerText = TextView(context).apply {
            setTextColor(0xFFBAC9E4.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        footer.addView(footerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(
            TextView(context).apply {
                text = "■ 停止"
                setTextColor(Color.WHITE)
                textSize = 12f
                background = rounded(0xFFBA3D4C.toInt(), 10 * d, 0f, 0)
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                isClickable = true
                setOnClickListener {
                    Log.i(TAG, "控制台底部停止按钮点击")
                    onStop?.invoke()
                }
            }
        )
        root.addView(footer)
        footerRow = footer

        // —— 窗口参数：默认底部居中，位置可拖动并记忆 ——
        val defaultX = ((screenW - width) / 2).coerceAtLeast(0)
        val defaultY = (screenH - (screenH * 0.42f)).toInt().coerceAtLeast(0)
        var px = loadPos(context, KEY_X, defaultX)
        var py = loadPos(context, KEY_Y, defaultY)
        if (px !in 0..(screenW - width).coerceAtLeast(0) ||
            py !in 0..(screenH - (120 * d).toInt()).coerceAtLeast(0)
        ) {
            px = defaultX
            py = defaultY
        }
        val lp = overlayParams(Gravity.TOP or Gravity.START, px, py).apply { this.width = width }

        return try {
            wm.addView(root, lp)
            windowManager = wm
            params = lp
            rootView = root
            logWindow.clear()
            rebuild()
            refreshFooter()
            mainHandler.postDelayed(refresher, REFRESH_MS)
            Log.i(TAG, "悬浮控制台已显示")
            true
        } catch (e: Exception) {
            Log.w(TAG, "悬浮控制台显示失败: ${e.message}")
            rootView = null
            false
        }
    }

    fun hide() {
        screenshotVisibility = null
        mainHandler.removeCallbacks(refresher)
        rootView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        params = null
        linesContainer = null
        scrollView = null
        titleView = null
        footerRow = null
        footerText = null
        filterRow = null
        logWindow.clear()
        logSnapshotGate.invalidate()
        Log.i(TAG, "悬浮控制台已隐藏")
    }

    /** 临时隐藏控制台窗口并暂停轮询，截图时保留整棵视图树。必须在主线程调用。 */
    fun hideForScreenshot(): Boolean {
        val view = rootView ?: return false
        val lp = params ?: return false
        val wm = windowManager ?: return false
        if (!view.isAttachedToWindow) return false
        screenshotVisibility?.let { return it.view === view }
        val previous = ScreenshotVisibility(view, view.visibility, lp.flags)
        return try {
            screenshotVisibility = previous
            mainHandler.removeCallbacks(refresher)
            view.visibility = View.INVISIBLE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(view, lp)
            true
        } catch (_: Exception) {
            view.visibility = previous.visibility
            lp.flags = previous.flags
            screenshotVisibility = null
            if (rootView === view) mainHandler.postDelayed(refresher, REFRESH_MS)
            false
        }
    }

    /** 恢复原控制台视图和日志状态；窗口已被用户关闭时返回 false。 */
    fun restoreAfterScreenshot(): Boolean {
        val previous = screenshotVisibility ?: return false
        screenshotVisibility = null
        if (rootView !== previous.view || !previous.view.isAttachedToWindow) return false
        val lp = params ?: return false
        return try {
            lp.flags = previous.flags
            previous.view.visibility = previous.visibility
            windowManager?.updateViewLayout(previous.view, lp)
            refresh()
            refreshFooter()
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
            mainHandler.removeCallbacks(refresher)
            mainHandler.postDelayed(refresher, REFRESH_MS)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 标题栏拖动：整条标题栏是拖动把手 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(context: Context, handle: View) {
        val d = context.resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    // 必须返回 true 接管手势，否则 MOVE/UP 不会送到本视图（拖动会失效）
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 钳位：卡片必须完整留在屏幕内，否则右侧按钮（清空/关闭/停止）会点不到
                    val m = context.resources.displayMetrics
                    val w = rootView?.width ?: 0
                    val h = rootView?.height ?: 0
                    lp.x = (startX + (event.rawX - downX).toInt())
                        .coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
                    lp.y = (startY + (event.rawY - downY).toInt())
                        .coerceIn(0, (m.heightPixels - h).coerceAtLeast(0))
                    try {
                        rootView?.let { windowManager?.updateViewLayout(it, lp) }
                    } catch (_: Exception) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    savePos(context, lp.x, lp.y)
                    Log.i(TAG, "悬浮控制台位置已保存: (${lp.x}, ${lp.y})")
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    savePos(context, lp.x, lp.y)
                    true
                }

                else -> false
            }
        }
    }

    /** 筛选胶囊：选中态高亮 */
    private fun filterChip(context: Context, label: String, level: Char?): View {
        val d = context.resources.displayMetrics.density
        val selected = filterLevel == level
        return TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(if (selected) 0xFF2346A0.toInt() else 0xFFBAC9E4.toInt())
            background = rounded(
                if (selected) 0xFFE7EEFF.toInt() else 0x1FFFFFFF,
                12 * d,
                1 * d,
                if (selected) 0xFFE7EEFF.toInt() else 0x22FFFFFF
            )
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            isClickable = true
            setOnClickListener {
                filterLevel = level
                rebuildFilters(context)
                rebuild()
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * d).toInt() }
        }
    }

    private fun rebuildFilters(context: Context) {
        val row = filterRow ?: return
        row.removeAllViews()
        listOf<Pair<String, Char?>>(
            "全部" to null, "信息" to 'I', "警告" to 'W', "错误" to 'E'
        ).forEach { (label, level) -> row.addView(filterChip(context, label, level)) }
    }

    /** 追加新日志并（在已贴底时）自动滚到底部 */
    private fun refresh() {
        val container = linesContainer ?: return
        val all = logProvider?.invoke() ?: return
        if (!logSnapshotGate.shouldRefresh(all, filterLevel)) return
        val displayed = if (filterLevel == null) all else all.filter { passesFilter(it) }
        val change = logWindow.update(displayed)
        updateTitle(all)
        if (change.removeCount == 0 && change.append.isEmpty()) return
        val context = container.context
        val stick = isAtBottom()
        val oldScrollY = scrollView?.scrollY ?: 0
        var removedHeight = 0
        repeat(change.removeCount) {
            removedHeight += container.getChildAt(0).height
            container.removeViewAt(0)
        }
        change.append.forEach { container.addView(lineView(context, it)) }
        scrollView?.post {
            if (stick) scrollView?.fullScroll(View.FOCUS_DOWN)
            else scrollView?.scrollTo(0, (oldScrollY - removedHeight).coerceAtLeast(0))
        }
    }

    /** 全量重建（清空 / 切换筛选 / 日志被截断时） */
    private fun rebuild() {
        val container = linesContainer ?: return
        container.removeAllViews()
        logWindow.clear()
        logSnapshotGate.invalidate()
        refresh()
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    /** 底部运行态条：任务名 + 计时（空闲时隐藏） */
    @SuppressLint("SetTextI18n")
    private fun refreshFooter() {
        val footer = footerRow ?: return
        val text = footerText ?: return
        val info = runningInfo?.invoke()
        if (info == null) {
            footer.visibility = View.GONE
            return
        }
        val (task, startedAt) = info
        val s = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        text.text = "▶ $task · %02d:%02d".format(s / 60, s % 60)
        footer.visibility = View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    private fun updateTitle(all: List<ConsoleLine>) {
        val total = all.size
        val filtered = if (filterLevel == null) total
        else all.count { it.level == filterLevel }
        titleView?.text = if (filterLevel == null) "日志 ($total)" else "日志 ($filtered/$total)"
    }

    private fun passesFilter(line: ConsoleLine): Boolean =
        filterLevel == null || line.level == filterLevel

    /** 用户是否已在底部（向上翻阅时不打断） */
    private fun isAtBottom(): Boolean {
        val sv = scrollView ?: return true
        val child = sv.getChildAt(0) ?: return true
        return sv.scrollY + sv.height >= child.height - 16
    }

    @SuppressLint("SetTextI18n")
    private fun lineView(context: Context, line: ConsoleLine): TextView = TextView(context).apply {
        text = "${line.time} ${line.msg}"
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setTextColor(colorFor(line.level))
        setPadding(0, 1, 0, 1)
    }

    /** 级别着色：E=红、W=琥珀、D=灰、其余=白 */
    private fun colorFor(level: Char): Int = when (level) {
        'E' -> 0xFFFFA8B1.toInt()
        'W' -> 0xFFFFD399.toInt()
        'D' -> 0xFFBAC9E4.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    private fun actionText(context: Context, label: String, onClick: () -> Unit): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun divider(context: Context): View {
        val d = context.resources.displayMetrics.density
        return View(context).apply { setBackgroundColor(0x33FFFFFF) }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)
            )
        }
    }

    private fun rounded(
        color: Int,
        radiusPx: Float,
        strokePx: Float,
        strokeColor: Int
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0f) setStroke(strokePx.toInt().coerceAtLeast(1), strokeColor)
    }

    private fun overlayParams(gravity: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    private fun loadPos(context: Context, key: String, default: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, default)

    private fun savePos(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}
