package com.forest.android.assistant.core.recording

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.AppPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 录制悬浮窗（紧凑版）：
 * - 蓝白渐变卡片 + 浅蓝描边 + 圆角；
 * - 头部：logo、状态点 + 状态文字、提示行、右侧步数徽标，整条可拖动；
 * - 底部：胶囊按钮「暂停/继续」和蓝色主按钮「完成」；
 * - 所有文字单行 + 固定尺寸，状态切换不会让窗口一跳一跳。
 *
 * 交互约束：
 * - 触摸层始终在悬浮条之上（录制中），悬浮条窗口全程不重建，因此不会闪烁；
 * - 触摸层自己做悬浮条命中转发（按钮/拖动），不需要悬浮条接真实触摸；
 * - 执行手势前用 [WindowManager.removeViewImmediate] 同步移除触摸层，
 *   保证注入的手势落到目标应用（异步改尺寸做不到这一点）。
 */
@SuppressLint("ClickableViewAccessibility")
class RecordingOverlay(
    private val context: Context,
    private val onGesture: (RecordedOperation) -> Unit,
    private val onToggle: () -> Unit,
    private val onFinish: () -> Unit,
    private val onRejected: (String) -> Unit
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()

    // minSdk 29：悬浮窗类型固定为 TYPE_APPLICATION_OVERLAY
    private val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // —— 悬浮条沿用 APP 内部的浅色主题配色 ——
    private val bgTop = AppPalette.SURFACE_CONTAINER_LOW
    private val bgBottom = AppPalette.SURFACE
    private val textColor = AppPalette.ON_SURFACE
    private val subtle = AppPalette.ON_SURFACE_VARIANT
    private val primary = AppPalette.PRIMARY
    private val onPrimary = AppPalette.ON_PRIMARY
    private val red = AppPalette.ERROR

    /** 圆角/胶囊纯色背景 */
    private fun solid(fill: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    /** 竖向渐变背景，用于卡片与主按钮 */
    private fun verticalGradient(top: Int, bottom: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    /** 圆形色块，用于状态点与步骤进度点 */
    private fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    // —— 头部：logo + 状态 + 提示 + 步数徽标 ——

    private val logo = ImageView(context).apply {
        setImageResource(R.drawable.ic_record_logo)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "操作录制"
    }

    private val statusDot = View(context)

    private val stateText = TextView(context).apply {
        setTextColor(textColor)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
    }

    private val stateRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(statusDot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(5) })
        addView(stateText)
    }

    /** 单行提示：状态说明或上一步信息，超出省略，不改变窗口高度 */
    private val hintText = TextView(context).apply {
        setTextColor(subtle)
        textSize = 9f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    /** 步数徽标：浅蓝胶囊 */
    private val stepChip = TextView(context).apply {
        setTextColor(AppPalette.ON_PRIMARY_CONTAINER)
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(3), dp(7), dp(3))
        background = solid(AppPalette.PRIMARY_CONTAINER, 20)
    }

    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), 0)
        addView(logo, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(stateRow)
            addView(hintText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(8)
            rightMargin = dp(6)
        })
        addView(stepChip)
    }

    // —— 底部按钮：胶囊「暂停/继续」+ 蓝色主按钮「完成」 ——

    private val toggleLabel = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        background = solid(AppPalette.SECONDARY_CONTAINER, 20, 1, AppPalette.OUTLINE_VARIANT)
    }

    private val finishLabel = TextView(context).apply {
        text = "完成"
        gravity = Gravity.CENTER
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onPrimary)
        background = solid(primary, 20)
    }

    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(10), 0, dp(10), dp(9))
        addView(toggleLabel, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(6) })
        addView(finishLabel, LinearLayout.LayoutParams(0, dp(34), 1.2f))
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = verticalGradient(bgTop, bgBottom, 14, 1, AppPalette.OUTLINE_VARIANT)
        elevation = dp(10).toFloat()
        addView(header)
        addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private val panelParams = params(dp(200), WindowManager.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        x = dp(8)
        y = dp(12)
    }

    private val touchParams = params(-1, -1)

    /** 屏幕上被触摸时命中的悬浮条区域：按钮或拖动手柄 */
    private enum class PanelHit { NONE, TOGGLE, FINISH, DRAG }

    private var panelHit = PanelHit.NONE
    private var panelDownX = 0f
    private var panelDownY = 0f
    private var panelDragStartX = 0
    private var panelDragStartY = 0
    private var panelDragged = false
    private val touchView = object : View(context) {
        private var points = mutableListOf<RecordedPoint>()
        private var downAt = 0L
        private var invalid = false
        override fun onTouchEvent(event: MotionEvent): Boolean {
            fun point(x: Float = event.rawX, y: Float = event.rawY) = RecordedPoint(x.roundToInt(), y.roundToInt())
            fun add(p: RecordedPoint) {
                if (points.lastOrNull() != p) points.add(p)
                if (points.size > 256) points = points.filterIndexed { i, _ -> i % 2 == 0 || i == points.lastIndex }.toMutableList()
            }
            // 触摸层在悬浮条之上：先做悬浮条命中转发，否则按钮会点不到、还会把按钮点录成操作
            if (panelHit != PanelHit.NONE) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> updatePanelDrag(event)
                    MotionEvent.ACTION_UP -> endPanelTouch(event.rawX, event.rawY)
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelPanelTouch()
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && beginPanelTouch(event.rawX, event.rawY)) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    points.add(point())
                    downAt = event.eventTime
                    invalid = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> invalid = true
                MotionEvent.ACTION_MOVE -> if (!invalid) {
                    // 历史采样使用窗口到屏幕的偏移，避免状态栏造成坐标偏差。
                    for (i in 0 until event.historySize) {
                        add(point(event.getHistoricalX(i) + event.rawX - event.x,
                            event.getHistoricalY(i) + event.rawY - event.y))
                    }
                    add(point())
                }
                MotionEvent.ACTION_CANCEL -> { invalid = true; points.clear() }
                MotionEvent.ACTION_UP -> {
                    if (points.isEmpty()) return true
                    add(point())
                    val duration = (event.eventTime - downAt).coerceAtLeast(1)
                    if (invalid || duration > 60_000) {
                        onRejected(if (invalid) "暂不支持多指操作，本次未执行或记录" else "单次操作不能超过 60 秒")
                    } else {
                        val kind = RecordedOperation.classify(points, duration,
                            ViewConfiguration.get(context).scaledTouchSlop, ViewConfiguration.getLongPressTimeout())
                        onGesture(RecordedOperation(kind,
                            if (kind == RecordedOperation.Kind.SWIPE) points.toList() else listOf(points.first()), duration))
                    }
                    points.clear()
                }
            }
            return true
        }
    }

    init {
        // 按钮点击与按压缩放反馈（暂停时悬浮条可触摸，走真实按钮）
        toggleLabel.setOnClickListener { onToggle() }
        finishLabel.setOnClickListener { onFinish() }
        pressEffect(toggleLabel)
        pressEffect(finishLabel)
        // 整条头部都可以拖动窗口（logo/状态/步数徽标都在里面）
        attachDrag(header)
        render(recording = false, count = 0, message = "")
    }

    // ==================== 悬浮条命中转发（触摸层在悬浮条之上时使用） ====================

    /** 按下时判断是否落在悬浮条上；命中则后续移动/抬起都转给悬浮条处理 */
    private fun beginPanelTouch(x: Float, y: Float): Boolean {
        if (!panel.isAttachedToWindow) return false
        val hit = hitPanel(x, y) ?: return false
        panelHit = hit
        panelDragged = false
        panelDownX = x
        panelDownY = y
        panelDragStartX = panelParams.x
        panelDragStartY = panelParams.y
        when (hit) {
            PanelHit.TOGGLE -> scalePress(toggleLabel, 0.96f)
            PanelHit.FINISH -> scalePress(finishLabel, 0.96f)
            else -> Unit
        }
        return true
    }

    /** 拖动只对头部生效，阈值与原悬浮条拖动一致 */
    private fun updatePanelDrag(event: MotionEvent) {
        if (panelHit != PanelHit.DRAG) return
        val dx = event.rawX - panelDownX
        val dy = event.rawY - panelDownY
        if (!panelDragged && abs(dx) + abs(dy) > dp(3)) panelDragged = true
        if (panelDragged) movePanelBy(dx, dy)
    }

    private fun endPanelTouch(x: Float, y: Float) {
        val hit = panelHit
        panelHit = PanelHit.NONE
        when (hit) {
            PanelHit.TOGGLE -> {
                scalePress(toggleLabel, 1f)
                if (hitPanel(x, y) == PanelHit.TOGGLE) onToggle()
            }
            PanelHit.FINISH -> {
                scalePress(finishLabel, 1f)
                if (hitPanel(x, y) == PanelHit.FINISH) onFinish()
            }
            else -> Unit
        }
    }

    private fun cancelPanelTouch() {
        panelHit = PanelHit.NONE
        scalePress(toggleLabel, 1f)
        scalePress(finishLabel, 1f)
    }

    /** 屏幕坐标命中的悬浮条区域：按钮优先，其次是可拖动头部 */
    private fun hitPanel(x: Float, y: Float): PanelHit? {
        val rect = android.graphics.Rect()
        fun inside(view: View): Boolean {
            if (!view.isAttachedToWindow || view.width <= 0) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            rect.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            return rect.contains(x.roundToInt(), y.roundToInt())
        }
        return when {
            inside(toggleLabel) -> PanelHit.TOGGLE
            inside(finishLabel) -> PanelHit.FINISH
            inside(header) -> PanelHit.DRAG
            else -> null
        }
    }

    private fun scalePress(view: View, scale: Float) {
        view.animate().scaleX(scale).scaleY(scale).setDuration(if (scale == 1f) 90 else 70).start()
    }

    /** 按拖动偏移移动窗口；向上拖动时 y 增大（窗口 gravity 为底部） */
    private fun movePanelBy(dx: Float, dy: Float) {
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        panelParams.x = (panelDragStartX + dx).roundToInt().coerceIn(0, (size.x - panel.width).coerceAtLeast(0))
        panelParams.y = (panelDragStartY - dy).roundToInt().coerceIn(0, (size.y - panel.height).coerceAtLeast(0))
        if (panel.isAttachedToWindow) wm.updateViewLayout(panel, panelParams)
    }

    fun show(recording: Boolean, count: Int, message: String = "") {
        render(recording, count, message)
        // 暂停时悬浮条自己接触摸（触摸层已摧掉）；录制中由触摸层命中转发
        setPanelTouchable(!recording)
        if (!panel.isAttachedToWindow) {
            try {
                wm.addView(panel, panelParams)
            } catch (e: Exception) {
                hide()
                throw e
            }
        }
        setTouchLayerEnabled(recording)
    }

    /**
     * 执行本步手势前调用：**同步摧掉**全屏触摸层，保证注入的手势落到目标应用。
     * 悬浮条窗口不从窗口树摧除，所以不会闪烁。
     */
    fun beginExecuting() {
        setTouchLayerEnabled(false)
        stateText.text = "执行中…"
        statusDot.background = oval(primary)
        hintText.text = "正在回放本步操作，请稍候"
        // 按钮暂时置灰，避免执行中误触
        actions.alpha = 0.55f
    }

    /**
     * 全屏触摸层：录制中挂载，其他时候同步摧除（异步改尺寸会让注入手势被接走）。
     * 触摸层在悬浮条之上，因此悬浮条的点击由 [beginPanelTouch] 命中转发。
     */
    private fun setTouchLayerEnabled(enabled: Boolean) {
        if (enabled) {
            if (!touchView.isAttachedToWindow) runCatching { wm.addView(touchView, touchParams) }
        } else if (touchView.isAttachedToWindow) {
            runCatching { wm.removeViewImmediate(touchView) }
        }
    }

    /** 统一渲染窗口内容；所有文字单行、位置固定，因此尺寸不随状态变化 */
    private fun render(recording: Boolean, count: Int, message: String) {
        val ready = count == 0
        stateText.text = when {
            recording -> "录制中"
            ready -> "准备就绪"
            else -> "已暂停"
        }
        statusDot.background = oval(when {
            recording -> red
            ready -> primary
            else -> subtle
        })
        stepChip.text = "$count 步"
        hintText.text = message.ifBlank {
            when {
                recording -> "松手即执行，点按钮不会记录"
                ready -> "切换到目标应用，点开始录制"
                else -> "点继续接着录，点完成生成任务"
            }
        }
        toggleLabel.text = when {
            recording -> "暂停"
            ready -> "开始"
            else -> "继续"
        }
        actions.alpha = 1f
    }

    /** 按下轻微缩小、松开回弹，给胶囊按钮一点物理感 */
    private fun pressEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }
            false
        }
    }

    /** 头部拖动：沿用原逻辑，把整条头部当作拖动把手 */
    private fun attachDrag(handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = panelParams.x; startY = panelParams.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) + abs(event.rawY - downY) > dp(3)) {
                        val size = android.graphics.Point()
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRealSize(size)
                        panelParams.x = (startX + event.rawX - downX).roundToInt().coerceIn(0, (size.x - panel.width).coerceAtLeast(0))
                        panelParams.y = (startY - event.rawY + downY).roundToInt().coerceIn(0, (size.y - panel.height).coerceAtLeast(0))
                        if (panel.isAttachedToWindow) wm.updateViewLayout(panel, panelParams)
                    }
                }
            }
            true
        }
    }

    /** 切换窗口的可触摸状态：暂停时可点；录制中由触摸层命中转发 */
    private fun setPanelTouchable(touchable: Boolean) {
        if (!panel.isAttachedToWindow) return
        val flags = panelParams.flags
        val updated = if (touchable) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (updated == flags) return
        panelParams.flags = updated
        runCatching { wm.updateViewLayout(panel, panelParams) }
    }

    fun hide() {
        listOf(touchView, panel).forEach {
            if (it.isAttachedToWindow) runCatching { wm.removeViewImmediate(it) }
        }
    }

    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // minSdk 29：刘海屏模式常量自 API 28 起可用，无需版本判断
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
}
