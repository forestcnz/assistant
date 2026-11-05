package com.forest.android.assistant.core.recording

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.forest.android.assistant.R
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.gesture.GestureClient
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.AppPalette
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
    // 录制配置弹窗与 APP 页面使用同一套表面色和文字色。
    private const val BG = AppPalette.SURFACE
    private const val TEXT = AppPalette.ON_SURFACE
    private const val SUBTLE = AppPalette.ON_SURFACE_VARIANT
    private const val FIELD_BG = AppPalette.SURFACE_CONTAINER_LOW
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

        /** 输入框统一为圆角浅底，替代默认下划线，便于在深色任务面板之外单独阅读。 */
        fun fieldBackground(fill: Int = FIELD_BG) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), AppPalette.OUTLINE_VARIANT)
        }

        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), AppPalette.OUTLINE_VARIANT)
            }
            elevation = dp(12).toFloat()
            clipToOutline = true
        }

        // 深绿灰标题栏与暖白表单复用悬浮任务面板的层级。
        val header = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(6), dp(3))
            setBackgroundColor(AppPalette.INVERSE_SURFACE)
            addView(ImageView(app).apply {
                setImageResource(R.drawable.ic_record_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(app).apply {
                text = "录制操作"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppPalette.INVERSE_ON_SURFACE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
        }
        header.addView(TextView(app).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(AppPalette.INVERSE_ON_SURFACE)
            contentDescription = "关闭录制设置"
            setOnClickListener { scope.cancel(); hide() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(header)
        // 细分隔线只做层级区分，不引入第二种底色。
        card.addView(View(app).apply { setBackgroundColor(AppPalette.OUTLINE_VARIANT) },
            LinearLayout.LayoutParams(-1, dp(1)))

        val content = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        fun hint(text: String) = TextView(app).apply {
            this.text = text
            textSize = 12f
            setTextColor(SUBTLE)
            setLineSpacing(0f, 1.05f)
        }

        fun formRow(label: String) = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hint(label), LinearLayout.LayoutParams(dp(56), -2).apply { marginEnd = dp(6) })
        }

        val name = EditText(app).apply {
            setSingleLine(true)
            setText("录制_" + SimpleDateFormat("MMdd_HHmmss", Locale.ROOT).format(Date()))
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(SUBTLE)
            hint = "任务名称"
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = fieldBackground()
        }
        content.addView(formRow("任务名称").apply {
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))

        val modes = RadioGroup(app).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        // 两种模式：坐标模式额外导出找图备用素材；图片模式用于找图回放。
        // （纯坐标录制改为悬浮面板上的一键入口，不在本弹窗里选）
        fun modeButton(label: String, checked: Boolean): RadioButton =
            RadioButton(app).apply {
                text = label
                textSize = 14f
                setTextColor(TEXT)
                buttonTintList = ColorStateList.valueOf(AppPalette.PRIMARY)
                id = View.generateViewId()
                isChecked = checked
                minimumHeight = dp(44)
                setPadding(dp(6), dp(5), dp(6), dp(5))
                background = fieldBackground()
            }
        val coordinate = modeButton("记录坐标", checked = true)
        val image = modeButton("记录图片", checked = false)
        modes.addView(coordinate, RadioGroup.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        modes.addView(image, RadioGroup.LayoutParams(0, -2, 1f))
        content.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fun refreshModes() {
            listOf(coordinate, image).forEach { button ->
                button.background = fieldBackground(if (button.isChecked) AppPalette.PRIMARY_CONTAINER else BG).apply {
                    setStroke(dp(1), if (button.isChecked) AppPalette.PRIMARY else AppPalette.OUTLINE_VARIANT)
                }
            }
        }
        modes.setOnCheckedChangeListener { _, _ -> refreshModes() }
        refreshModes()

        fun sizeField(label: String, defaultValue: Int) = EditText(app).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(defaultValue.toString())
            setSelectAllOnFocus(true)
            hint = label
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(SUBTLE)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = fieldBackground()
        }

        fun addSizeRow(label: String, widthField: EditText, heightField: EditText) {
            val row = formRow(label).apply {
                addView(widthField, LinearLayout.LayoutParams(0, -2, 1f))
                addView(hint("×").apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(20), -2))
                addView(heightField, LinearLayout.LayoutParams(0, -2, 1f))
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }

        val cropWidth = sizeField("截图宽度", 100)
        val cropHeight = sizeField("截图高度", 100)
        addSizeRow("截图尺寸", cropWidth, cropHeight)

        val searchRegionWidth = sizeField("范围宽度", DEFAULT_SEARCH_REGION_SIZE)
        val searchRegionHeight = sizeField("范围高度", DEFAULT_SEARCH_REGION_SIZE)
        addSizeRow("页面范围", searchRegionWidth, searchRegionHeight)

        content.addView(hint(
            "宽 × 高（像素）。截图 20～500，页面范围须覆盖截图。" +
                "录制会在当前应用执行操作，无截图请用「纯坐标录制」。"
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val actions = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val cancel = Button(app).apply {
            text = "取消"
            textSize = 14f
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(AppPalette.SECONDARY_CONTAINER)
            setTextColor(TEXT)
            setOnClickListener { scope.cancel(); hide() }
        }
        val start = Button(app).apply {
            text = "显示录制悬浮条"
            textSize = 14f
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(AppPalette.PRIMARY)
            setTextColor(AppPalette.ON_PRIMARY)
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        actions.addView(start, LinearLayout.LayoutParams(0, -2, 1.45f))
        content.addView(actions, LinearLayout.LayoutParams(-1, -2))
        card.addView(content)

        start.setOnClickListener {
            val taskName = name.text.toString().trim()
            val mode = if (modes.checkedRadioButtonId == image.id) RecordingMode.IMAGE else RecordingMode.COORDINATE
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
                                RecordingService.start(app, taskName, width, height, mode, regionWidth, regionHeight)
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
            minOf(dp(288), app.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1),
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

    /**
     * 悬浮面板的「纯坐标录制」快捷入口：不弹配置窗，直接用默认尺寸开始录制。
     * 纯坐标不截图、不需要截屏授权，任务名按时间自动生成，录制中可随时在悬浮条暂停/完成。
     */
    fun startPure(context: Context) {
        val app = context.applicationContext
        if (RecordingService.active) {
            Toast.makeText(app, "已有录制正在进行", Toast.LENGTH_LONG).show()
            return
        }
        if (ScriptRunner.state.value.running) {
            Toast.makeText(app, "请先停止正在运行的任务", Toast.LENGTH_LONG).show()
            return
        }
        if (!GestureClient.available()) {
            Toast.makeText(app, "请先开启无障碍服务，再从悬浮窗开始录制", Toast.LENGTH_LONG).show()
            return
        }
        val name = "纯坐标_" + SimpleDateFormat("MMdd_HHmmss", Locale.ROOT).format(Date())
        if (TaskManager.taskDir(app, name).exists()) {
            Toast.makeText(app, "同一秒内已有同名录制，请稍后重试", Toast.LENGTH_LONG).show()
            return
        }
        try {
            // 纯坐标模式不截图，尺寸参数只作为占位值，不参与录制
            RecordingService.start(app, name, 100, 100, RecordingMode.COORDINATE_ONLY,
                DEFAULT_SEARCH_REGION_SIZE, DEFAULT_SEARCH_REGION_SIZE)
        } catch (e: Exception) {
            Toast.makeText(app, "录制准备失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
