package com.forest.android.assistant.core.floaty

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.ui.AppPalette
import kotlinx.coroutines.*

/** 运行条展开为控制面板，持续展示实际轮次、后续队列和任务间等待。 */
internal object RunningControlOverlay {
    private var root: View? = null
    private var manager: WindowManager? = null
    private var scope: CoroutineScope? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var hiddenForCapture = false

    fun isShowing(): Boolean = root?.isAttachedToWindow == true

    fun show(context: Context): Boolean {
        if (root != null) return true
        if (!FloatyManager.hasOverlayPermission(context) || !ScriptRunner.state.value.running) return false
        val app = context.applicationContext
        val d = app.resources.displayMetrics.density
        fun dp(value: Int) = (value * d).toInt()
        fun background(color: Int, radius: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
        fun label(value: String, size: Float = 12f, color: Int = AppPalette.ON_SURFACE_VARIANT) = TextView(app).apply {
            text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        }
        val panel = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL; background = background(AppPalette.BACKGROUND, 25)
            setPadding(dp(20), dp(18), dp(20), dp(18)); elevation = dp(16).toFloat()
        }
        val header = LinearLayout(app).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("正在运行", 22f, AppPalette.ON_SURFACE), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("×", 24f).apply {
            gravity = Gravity.CENTER; contentDescription = "收起运行控制"; setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)
        val status = label("")
        val task = label("", 21f, AppPalette.ON_SURFACE).apply { typeface = Typeface.DEFAULT_BOLD }
        val detail = label("")
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(15) })
        panel.addView(task, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        panel.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(20) })
        panel.addView(label("接下来", 13f, AppPalette.ON_SURFACE))
        val queue = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(app).apply { addView(queue) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(10) })
        panel.addView(label("查看悬浮日志", 13f, AppPalette.PRIMARY).apply {
            gravity = Gravity.CENTER; minimumHeight = dp(48); setOnClickListener { ConsoleOverlay.show(app) }
        })
        panel.addView(label("双击悬浮雕雕，可停止当前任务和剩余队列。", 11f).apply { setPadding(0, dp(8), 0, dp(16)) })
        panel.addView(label("停止任务与队列", 14f, AppPalette.ERROR).apply {
            gravity = Gravity.CENTER; background = background(AppPalette.ERROR_CONTAINER); minimumHeight = dp(50)
            setOnClickListener { ScriptRunner.stop(); hide() }
        })
        // 横屏及小屏只滚动内容，标题和停止入口始终可见。
        val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        while (panel.childCount > 2) {
            val child = panel.getChildAt(1); panel.removeViewAt(1); body.addView(child)
        }
        panel.addView(ScrollView(app).apply { addView(body) }, 1, LinearLayout.LayoutParams(-1, 0, 1f))
        val wm = app.getSystemService(WindowManager::class.java)
        val screen = android.graphics.Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getSize(it) }
        val params = WindowManager.LayoutParams(minOf(dp(360), screen.x - dp(24)), minOf(dp(620), screen.y - dp(48)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
        }
        return try {
            wm.addView(panel, params); root = panel; manager = wm; windowParams = params
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { uiScope ->
                uiScope.launch {
                    var previous: List<String>? = null
                    while (isActive) {
                        val state = ScriptRunner.state.value
                        if (!state.running) { hide(); break }
                        val remaining = ((state.waitingUntilElapsed - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
                        val waiting = state.waitingUntilElapsed > 0
                        status.text = "${if (waiting) "等待${if (state.waitingForRound) "下一轮" else "下一任务"}" else "正在执行"} · ${state.queueIndex}/${state.queueTotal}"
                        task.text = state.taskName.orEmpty()
                        detail.text = if (waiting) "${remaining} 秒后${if (state.waitingForRound) "开始下一轮" else "执行「${state.waitingForTask}」"}"
                            else if (ScriptRunner.runStartedAt == 0L) "正在准备任务…"
                            else "第 ${state.round.coerceAtLeast(1)} 轮${state.roundTotal?.let { " / $it 轮" }.orEmpty()} · 运行 ${(System.currentTimeMillis() - ScriptRunner.runStartedAt).coerceAtLeast(0) / 1000} 秒"
                        if (previous != state.remainingTasks) {
                            previous = state.remainingTasks; queue.removeAllViews()
                            if (state.remainingTasks.isEmpty()) queue.addView(label("当前任务结束后，队列即完成。").apply { setPadding(0, dp(15), 0, dp(15)) })
                            state.remainingTasks.forEach { name -> queue.addView(label("$name\n等待执行", 12f, AppPalette.ON_SURFACE).apply {
                                background = background(AppPalette.SURFACE); setPadding(dp(14), dp(12), dp(14), dp(12))
                            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
                        }
                        delay(500)
                    }
                }
            }
            true
        } catch (_: Exception) { hide(); false }
    }

    fun hide() {
        scope?.cancel(); scope = null
        val old = root; root = null
        if (old != null) runCatching { manager?.removeView(old) }
        manager = null
        windowParams = null; hiddenForCapture = false
    }

    /** 截图时隐藏大面板，保留原位置及停止按钮所在的小运行条。 */
    fun hideForScreenshot(): Boolean {
        val view = root ?: return false
        val params = windowParams ?: return false
        return runCatching {
            view.visibility = View.INVISIBLE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            manager?.updateViewLayout(view, params)
            hiddenForCapture = true
            true
        }.getOrDefault(false)
    }

    fun restoreAfterScreenshot() {
        if (!hiddenForCapture) return
        val view = root ?: return
        val params = windowParams ?: return
        runCatching {
            view.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            manager?.updateViewLayout(view, params)
            hiddenForCapture = false
        }
    }
}
