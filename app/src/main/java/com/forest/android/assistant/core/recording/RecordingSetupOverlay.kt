package com.forest.android.assistant.core.recording

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.gesture.GestureClient
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.task.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small system overlay that starts recording without bringing MainActivity to the foreground. */
object RecordingSetupOverlay {
    private const val BG = 0xFF1D2B46.toInt()
    private const val TEXT = Color.WHITE
    private const val SUBTLE = 0xFFBAC9E4.toInt()
    private var window: View? = null
    private var windowManager: WindowManager? = null

    fun show(context: Context) {
        if (!FloatyManager.hasOverlayPermission(context)) {
            Toast.makeText(context, "请先允许显示悬浮窗", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        hide()
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val d = app.resources.displayMetrics.density
        fun dp(value: Int) = (value * d).toInt()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val title = TextView(app).apply {
            text = "录制操作"
            textSize = 18f
            setTextColor(TEXT)
        }
        card.addView(title)
        val name = EditText(app).apply {
            setSingleLine(true)
            setText("录制_" + SimpleDateFormat("MMdd_HHmmss", Locale.ROOT).format(Date()))
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(SUBTLE)
            hint = "任务名称"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        card.addView(name, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        val modes = RadioGroup(app).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val coordinate = RadioButton(app).apply { text = "记录坐标"; setTextColor(TEXT); id = View.generateViewId(); isChecked = true }
        val image = RadioButton(app).apply { text = "记录图片"; setTextColor(TEXT); id = View.generateViewId() }
        modes.addView(coordinate, RadioGroup.LayoutParams(0, -2, 1f))
        modes.addView(image, RadioGroup.LayoutParams(0, -2, 1f))
        card.addView(modes)
        val crop = EditText(app).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("100")
            textSize = 14f
            setTextColor(TEXT)
            hint = "点击截图边长（默认 100）"
        }
        card.addView(crop, LinearLayout.LayoutParams(-1, dp(44)))
        card.addView(TextView(app).apply {
            text = "将显示录制悬浮条；点击开始后在当前应用真实执行操作。每步保留 600×600 页面范围。"
            textSize = 12f
            setTextColor(SUBTLE)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val actions = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = Button(app).apply { text = "取消"; setOnClickListener { scope.cancel(); hide() } }
        val start = Button(app).apply { text = "显示录制悬浮条" }
        actions.addView(cancel)
        actions.addView(start)
        card.addView(actions, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        start.setOnClickListener {
            val taskName = name.text.toString().trim()
            val cropSize = crop.text.toString().toIntOrNull()
            when {
                taskName.isBlank() || taskName.length > 50 || taskName == "." || taskName == ".." || taskName.any { it == '/' || it == '\\' || it.isISOControl() } ->
                    Toast.makeText(app, "任务名称无效（最多 50 个字符，不能包含路径符号）", Toast.LENGTH_LONG).show()
                TaskManager.taskDir(app, taskName).exists() ->
                    Toast.makeText(app, "该任务名称已存在，请换一个名称", Toast.LENGTH_LONG).show()
                cropSize == null || cropSize !in 20..500 ->
                    Toast.makeText(app, "截图边长须为 20～500 像素", Toast.LENGTH_LONG).show()
                RecordingService.active -> Toast.makeText(app, "已有录制正在进行", Toast.LENGTH_LONG).show()
                ScriptRunner.state.value.running -> Toast.makeText(app, "请先停止正在运行的任务", Toast.LENGTH_LONG).show()
                !GestureClient.available() -> Toast.makeText(app, "请先开启无障碍服务，再从悬浮窗开始录制", Toast.LENGTH_LONG).show()
                else -> {
                    start.isEnabled = false
                    start.text = "正在准备…"
                    scope.launch {
                        val ready = withContext(Dispatchers.IO) { ScreenCapture.requestBlocking() }
                        if (ready) {
                            try {
                                RecordingService.start(app, taskName, cropSize, if (modes.checkedRadioButtonId == image.id) RecordingMode.IMAGE else RecordingMode.COORDINATE)
                                hide()
                                scope.cancel()
                            } catch (e: Exception) {
                                start.isEnabled = true
                                start.text = "显示录制悬浮条"
                                Toast.makeText(app, "录制准备失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            start.isEnabled = true
                            start.text = "显示录制悬浮条"
                            Toast.makeText(app, "截屏授权不可用；请先在应用中完成截屏授权后重试", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            minOf(dp(360), app.resources.displayMetrics.widthPixels - dp(32)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        // The overlay must accept keyboard input for task-name and crop-size fields.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager = wm
        window = card
        try {
            wm.addView(card, params)
        } catch (e: Exception) {
            window = null
            windowManager = null
            scope.cancel()
            Toast.makeText(app, "无法显示录制设置悬浮窗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun hide() {
        val old = window
        window = null
        try { if (old != null) windowManager?.removeView(old) } catch (_: Exception) { }
        windowManager = null
    }
}
