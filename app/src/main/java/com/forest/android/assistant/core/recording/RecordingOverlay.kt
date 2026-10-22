package com.forest.android.assistant.core.recording

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** 截获单指输入；派发前真正移除窗口，避免回录自身或 Android 12 遮挡触摸限制。 */
@SuppressLint("ClickableViewAccessibility")
class RecordingOverlay(
    private val context: Context,
    private val onGesture: (RecordedOperation) -> Unit,
    onToggle: () -> Unit,
    onFinish: () -> Unit,
    private val onRejected: (String) -> Unit
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val windowType = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }
    private val toggle = Button(context).apply { setOnClickListener { onToggle() } }
    private val finish = Button(context).apply {
        text = "完成"
        setOnClickListener { onFinish() }
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF0223047.toInt())
            cornerRadius = dp(12).toFloat()
        }
        addView(title)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toggle, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(finish, LinearLayout.LayoutParams(0, dp(44), 1f))
        })
    }
    private val panelParams = params(dp(220), WindowManager.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        x = dp(8)
        y = dp(12)
    }
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
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        title.setOnTouchListener { _, event ->
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

    fun show(recording: Boolean, count: Int, message: String = "") {
        hide()
        title.text = (if (recording) "● 录制中" else "Ⅱ 已暂停") + " · $count 步\n" +
            message.ifBlank { if (recording) "松手后执行，等待录制条恢复再操作" else "切换到目标应用，再点开始/继续" }
        toggle.text = if (recording) "暂停" else if (count == 0) "开始" else "继续"
        if (recording) wm.addView(touchView, params(-1, -1))
        try {
            wm.addView(panel, panelParams)
        } catch (e: Exception) {
            hide()
            throw e
        }
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
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
}
