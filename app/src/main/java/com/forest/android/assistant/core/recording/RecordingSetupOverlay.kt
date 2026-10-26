package com.forest.android.assistant.core.recording

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
        val cropFields = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun addSizeField(row: LinearLayout, label: String, defaultValue: Int): EditText {
            val field = EditText(app).apply {
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(defaultValue.toString())
                setSelectAllOnFocus(true)
                textSize = 14f
                setTextColor(TEXT)
            }
            row.addView(LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(app).apply {
                    text = label
                    textSize = 12f
                    setTextColor(SUBTLE)
                })
                addView(field, LinearLayout.LayoutParams(-1, dp(44)))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            return field
        }
        val cropWidth = addSizeField(cropFields, "截图宽度（20～500）", 100)
        val cropHeight = addSizeField(cropFields, "截图高度（20～500）", 100)
        card.addView(cropFields, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val regionFields = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        val searchRegionWidth = addSizeField(regionFields, "页面范围宽度（像素）", DEFAULT_SEARCH_REGION_SIZE)
        val searchRegionHeight = addSizeField(regionFields, "页面范围高度（像素）", DEFAULT_SEARCH_REGION_SIZE)
        card.addView(regionFields, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        card.addView(TextView(app).apply {
            text = "点击开始后在当前应用真实执行操作。每步按设定的页面范围截图并用于找图；范围须覆盖截图尺寸，超出屏幕时自动调整。"
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
            val width = cropWidth.text.toString().toIntOrNull()
            val height = cropHeight.text.toString().toIntOrNull()
            val regionWidth = searchRegionWidth.text.toString().toIntOrNull()
            val regionHeight = searchRegionHeight.text.toString().toIntOrNull()
            when {
                taskName.isBlank() || taskName.length > 50 || taskName == "." || taskName == ".." || taskName.any { it == '/' || it == '\\' || it.isISOControl() } ->
                    Toast.makeText(app, "任务名称无效（最多 50 个字符，不能包含路径符号）", Toast.LENGTH_LONG).show()
                TaskManager.taskDir(app, taskName).exists() ->
                    Toast.makeText(app, "该任务名称已存在，请换一个名称", Toast.LENGTH_LONG).show()
                width == null || width !in 20..500 || height == null || height !in 20..500 ->
                    Toast.makeText(app, "截图宽度和高度均须为 20～500 像素", Toast.LENGTH_LONG).show()
                regionWidth == null || regionWidth <= 0 || regionHeight == null || regionHeight <= 0 ->
                    Toast.makeText(app, "页面范围宽度和高度均须为正整数像素", Toast.LENGTH_LONG).show()
                regionWidth < width || regionHeight < height ->
                    Toast.makeText(app, "页面范围宽度和高度不能小于对应的截图尺寸", Toast.LENGTH_LONG).show()
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
                                RecordingService.start(app, taskName, width, height,
                                    if (modes.checkedRadioButtonId == image.id) RecordingMode.IMAGE else RecordingMode.COORDINATE,
                                    regionWidth, regionHeight)
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
            // minSdk 29：悬浮窗类型固定为 TYPE_APPLICATION_OVERLAY，TYPE_PHONE 已废弃
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        // Keep all fields reachable on small screens and while the keyboard is open.
        val scroll = ScrollView(app).apply { addView(card) }
        // The overlay must accept keyboard input for task name and recording dimensions.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager = wm
        window = scroll
        try {
            wm.addView(scroll, params)
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
