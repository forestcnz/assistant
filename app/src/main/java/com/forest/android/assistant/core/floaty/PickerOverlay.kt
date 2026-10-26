package com.forest.android.assistant.core.floaty

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.File
import android.widget.Toast
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.ScreenshotExportActivity
import com.forest.android.assistant.PickerCapturePermissionActivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * 屏幕拾取工具：拖动准星取点，或拖拽框选区域，生成脚本代码或保存截图。
 * - 坐标模式：生成 `click(x, y);`
 * - 取色模式：从最近一帧截屏读取该点像素，生成 `images.findColor(captureScreen(), "#rrggbb", ...)`
 * - 范围模式：框选屏幕区域，生成 `var region = [x, y, width, height];`，无需截屏授权。
 *
 * 注意：取色准确性依赖“被采样像素未被悬浮层本身覆盖”，因此准星中心留空且不做全屏压暗。
 * 对应设计 docs/README.md 第 4.5 节「坐标拾取器 / 取色器」。
 */
object PickerOverlay {

    private const val TAG = "ScriptJs"

    enum class Mode {
        /** 取坐标，生成 click(x, y) */
        POINT,

        /** 取像素颜色，生成 findColor 颜色串 */
        COLOR,

        /** 拖拽框选屏幕区域，生成 region 数组 */
        REGION,

        /** 拖拽框选屏幕区域并保存截图 */
        SCREENSHOT
    }

    /** 拾取结果回调（宿主写入运行日志等） */
    @Volatile
    var onPicked: ((String) -> Unit)? = null

    /** 截图保存成功回调（宿主写入运行日志等） */
    @Volatile
    var onScreenshotSaved: ((String) -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var pickerView: PickerView? = null
    @Volatile
    private var screenshotStarting = false
    private var permissionStarting = false
    private var restoreLauncherAfterScreenshot = false
    private var restoreConsoleAfterScreenshot = false
    private var screenshotContext: Context? = null

    fun isShowing(): Boolean = pickerView != null || screenshotStarting || permissionStarting

    /** 显示拾取层；重复调用先关闭再打开 */
    fun show(context: Context, mode: Mode): Boolean {
        if (screenshotStarting || permissionStarting) return true
        if (pickerView != null) {
            hide()
            return true
        }
        if (!FloatyManager.hasOverlayPermission(context)) {
            Log.w(TAG, "拾取器需要悬浮窗权限")
            return false
        }
        // 取色/截图依赖截屏：**任意通道**都不可用时才先申请授权（静默通道下无需授权，直接打开）
        if ((mode == Mode.COLOR || mode == Mode.SCREENSHOT) && !ScreenCapture.captureAvailable(context)) {
            permissionStarting = true
            return try {
                PickerCapturePermissionActivity.launch(context, mode)
                true
            } catch (e: Exception) {
                permissionStarting = false
                Log.w(TAG, "无法打开截屏授权页: ${e.message}")
                false
            }
        }
        if (mode == Mode.SCREENSHOT) {
            // 先隐藏应用自己的控制浮层，截图关键帧不应包含悬浮球/日志面板。
            restoreLauncherAfterScreenshot = FloatyManager.isLauncherShowing()
            restoreConsoleAfterScreenshot = ConsoleOverlay.isShowing()
            screenshotContext = context.applicationContext
            if (restoreLauncherAfterScreenshot) FloatyManager.hideLauncher(stopKeepAlive = false)
            if (restoreConsoleAfterScreenshot) ConsoleOverlay.hide()
            val hiddenAt = SystemClock.uptimeMillis()

            // 先截取浮层移除后的新帧，再显示截图层；后续框选和裁剪始终使用这张冻结画面。
            screenshotStarting = true
            Thread {
                val frame = try {
                    Thread.sleep(100)
                    ScreenCapture.capture(newerThanUptimeMs = hiddenAt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                }
                mainHandler.post {
                    screenshotStarting = false
                    if (frame == null) {
                        restoreScreenshotOverlays()
                        Toast.makeText(context, "截图失败：无法获取屏幕画面", Toast.LENGTH_LONG).show()
                    } else if (!attachPicker(context, mode, frame)) {
                        restoreScreenshotOverlays()
                    }
                }
            }.start()
            return true
        }
        return attachPicker(context, mode, null)
    }

    /** 独立授权页结束后，待原任务重新显示，再从其画面开始拾取。 */
    fun onCapturePermissionResult(context: Context, mode: Mode, granted: Boolean) {
        permissionStarting = false
        if (granted) {
            mainHandler.postDelayed({
                if (pickerView == null && !screenshotStarting) show(context, mode)
            }, 250)
        } else {
            Toast.makeText(
                context,
                if (mode == Mode.COLOR) "取色需要截屏授权" else "截图需要截屏授权",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun attachPicker(context: Context, mode: Mode, frozenFrame: Bitmap?): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = PickerView(context, mode, frozenFrame) { code -> deliver(context, code) }
        // minSdk 29：悬浮窗类型固定为 TYPE_APPLICATION_OVERLAY
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        return try {
            wm.addView(view, params)
            windowManager = wm
            pickerView = view
            Log.i(TAG, "拾取器已开启: $mode")
            true
        } catch (e: Exception) {
            Log.w(TAG, "拾取器显示失败: ${e.message}")
            false
        }
    }

    fun hide() {
        pickerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        pickerView = null
        windowManager = null
        restoreScreenshotOverlays()
    }

    private fun restoreScreenshotOverlays() {
        val context = screenshotContext
        val restoreLauncher = restoreLauncherAfterScreenshot
        val restoreConsole = restoreConsoleAfterScreenshot
        screenshotContext = null
        restoreLauncherAfterScreenshot = false
        restoreConsoleAfterScreenshot = false
        if (context == null || (!restoreLauncher && !restoreConsole)) return
        mainHandler.post {
            if (restoreLauncher &&
                (FloatyManager.isShowing() || FloatyManager.isEnabledByUser(context))) {
                FloatyManager.showLauncher(context)
            }
            if (restoreConsole) ConsoleOverlay.show(context)
        }
    }

    /** 直接打开系统文件选择器，由系统界面一次性选择文件名和保存位置。 */
    private fun exportScreenshot(context: Context, bitmap: Bitmap) {
        Thread {
            val saved = saveScreenshotTemp(context, bitmap)
            mainHandler.post {
                if (saved == null) {
                    Toast.makeText(context, "截图保存失败", Toast.LENGTH_LONG).show()
                    return@post
                }
                try {
                    // 保存页无任务亲和性，会在独立的临时任务中打开；结束后露出截图前的应用。
                    // 保持冻结截图层覆盖在页面上，直到系统文件选择器即将打开。
                    hide()
                    context.startActivity(
                        Intent(context, ScreenshotExportActivity::class.java)
                            .putExtra(ScreenshotExportActivity.EXTRA_SOURCE, saved.absolutePath)
                            .putExtra(ScreenshotExportActivity.EXTRA_NAME, defaultScreenshotName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    saved.delete()
                    Toast.makeText(context, "无法打开保存位置：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun defaultScreenshotName(): String =
        "screenshot_${android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis())}.png"

    private fun saveScreenshotTemp(context: Context, bitmap: Bitmap): File? {
        val target = File(context.cacheDir, "screenshot-export-${java.util.UUID.randomUUID()}.png")
        return try {
            val compressed = target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (compressed) target else null.also { target.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "截图保存失败: ${e.message}")
            target.delete()
            null
        }
    }

    /** 生成结果：复制到剪贴板 + 提示 + 回调宿主 */
    private fun deliver(context: Context, code: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("script-js", code))
        } catch (_: Exception) {
        }
        Toast.makeText(context, "已复制: $code", Toast.LENGTH_LONG).show()
        Log.i(TAG, "拾取结果: $code")
        onPicked?.invoke(code)
        hide()
    }

    @SuppressLint("ViewConstructor")
    private class PickerView(
        context: Context,
        initialMode: Mode,
        private val frozenFrame: Bitmap?,
        private val onResult: (String) -> Unit
    ) : View(context) {

        /** 当前拾取模式由工具入口确定 */
        private val mode: Mode = initialMode
        private val isAreaMode = mode == Mode.REGION || mode == Mode.SCREENSHOT

        private val density = resources.displayMetrics.density

        private var px = 0f
        private var py = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        /** 本次手势是否被右上角按钮消费（避免 UP 又当成取点确认） */
        private var consumedByButton = false

        private var color = 0
        private var hasColor = false
        private var sampling = false

        /** 用户已点击但取色尚未返回：取样完成后自动确认 */
        private var pendingConfirm = false

        /** 截屏不可用（未授权）时提示，而不是一直显示“取色中” */
        private var captureMissing = false

        private var selectionStartX = 0f
        private var selectionStartY = 0f
        private var selectionEndX = 0f
        private var selectionEndY = 0f
        private var hasSelection = false
        private var isSelecting = false
        private var isCapturing = false

        /** 选区确认按钮区域（生成代码 / 保存截图） */
        private val saveRect = RectF()

        /** 选区重选按钮区域 */
        private val resetRect = RectF()

        /** 悬浮层在屏幕上的原点：窗口通常从状态栏下方开始，需换算成屏幕坐标 */
        private val location = IntArray(2)

        private fun toScreenX(localX: Float): Int = (localX + location[0]).toInt()

        private fun toScreenY(localY: Float): Int = (localY + location[1]).toInt()

        /** 取消按钮区域（右上角） */
        private val cancelRect = RectF()

        /** 当前模式标签区域（右上角，取消左侧） */
        private val modeRect = RectF()

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
            color = Color.WHITE
            isFakeBoldText = true
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            color = 0xFFBAC9E4.toInt()
        }
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF21D2B46.toInt()
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt()
            style = Paint.Style.FILL
        }
        private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x00FFFFFF
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        init {
            // 初始位置取屏幕中心
            post {
                if (px == 0f && py == 0f) {
                    px = width / 2f
                    py = height / 2f
                    if (mode == Mode.COLOR) sample()
                    invalidate()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(location)
            if (isAreaMode) {
                drawSelection(canvas)
                return
            }
            val gap = 9f * density
            val arm = 46f * density
            // 准星（中心留空，避免遮挡被采样像素）
            canvas.drawLine(px - arm, py, px - gap, py, crossPaint)
            canvas.drawLine(px + gap, py, px + arm, py, crossPaint)
            canvas.drawLine(px, py - arm, px, py - gap, crossPaint)
            canvas.drawLine(px, py + gap, px, py + arm, crossPaint)
            canvas.drawCircle(px, py, 10f * density, crossPaint)

            // 右上角显示当前模式和取消按钮
            val btnW = 64f * density
            val btnH = 34f * density
            val btnGap = 8f * density
            cancelRect.set(width - btnW - 16f * density, 16f * density, width - 16f * density, 16f * density + btnH)
            modeRect.set(cancelRect.left - btnW - btnGap, 16f * density, cancelRect.left - btnGap, 16f * density + btnH)
            val modeLabel = if (mode == Mode.POINT) "取坐标" else "取色"
            canvas.drawRoundRect(modeRect, 8f * density, 8f * density, panelPaint)
            canvas.drawText(
                modeLabel,
                modeRect.centerX() - textPaint.measureText(modeLabel) / 2,
                modeRect.centerY() + 5f * density,
                textPaint
            )
            canvas.drawRoundRect(cancelRect, 8f * density, 8f * density, panelPaint)
            canvas.drawText(
                "取消",
                cancelRect.centerX() - textPaint.measureText("取消") / 2,
                cancelRect.centerY() + 5f * density,
                textPaint
            )

            // 信息面板（避开准星中心，右下偏移）
            val lines = ArrayList<String>()
            lines.add("x=${toScreenX(px)}  y=${toScreenY(py)}")
            if (mode == Mode.COLOR) {
                lines.add(
                    when {
                        hasColor -> "#%06x".format(color and 0xFFFFFF)
                        captureMissing -> "需截屏授权"
                        else -> "取色中…"
                    }
                )
            }
            val lineH = 18f * density
            val pad = 10f * density
            var panelW = 0f
            lines.forEach { panelW = maxOf(panelW, maxOf(textPaint.measureText(it), subPaint.measureText(it))) }
            panelW += pad * 2 + if (mode == Mode.COLOR) 26f * density else 0f
            val panelH = pad * 2 + lineH * lines.size + 16f * density
            var left = px + 22f * density
            var top = py + 22f * density
            if (left + panelW > width) left = px - 22f * density - panelW
            if (top + panelH > height) top = py - 22f * density - panelH
            left = left.coerceIn(0f, maxOf(0f, width - panelW))
            top = top.coerceIn(0f, maxOf(0f, height - panelH))
            canvas.drawRoundRect(
                RectF(left, top, left + panelW, top + panelH),
                8f * density, 8f * density, panelPaint
            )
            var ty = top + pad + 14f * density
            canvas.drawText(lines[0], left + pad, ty, textPaint)
            if (mode == Mode.COLOR) {
                ty += lineH
                canvas.drawText(lines[1], left + pad + 26f * density, ty, textPaint)
                swatchPaint.color = if (hasColor) color else 0xFF444444.toInt()
                canvas.drawRect(
                    left + pad, ty - 12f * density, left + pad + 18f * density, ty + 2f * density,
                    swatchPaint
                )
            }
            ty += lineH + 2f * density
            canvas.drawText(
                if (mode == Mode.POINT) "点击=生成 click(x,y)，拖动=移动准星"
                else "点击=生成颜色串，拖动=移动准星",
                left + pad, ty, subPaint
            )
        }

        private fun drawSelection(canvas: Canvas) {
            frozenFrame?.let { frame ->
                canvas.drawBitmap(
                    frame,
                    null,
                    RectF(
                        -location[0].toFloat(),
                        -location[1].toFloat(),
                        frame.width - location[0].toFloat(),
                        frame.height - location[1].toFloat()
                    ),
                    bitmapPaint
                )
            }
            val btnW = 76f * density
            val btnH = 38f * density
            val btnGap = 10f * density
            val margin = 18f * density
            cancelRect.set(width - btnW - margin, margin, width - margin, margin + btnH)
            resetRect.set(cancelRect.left - btnW - btnGap, margin, cancelRect.left - btnGap, margin + btnH)
            saveRect.set(resetRect.left - btnW - btnGap, margin, resetRect.left - btnGap, margin + btnH)

            if (hasSelection) {
                val left = min(selectionStartX, selectionEndX)
                val top = min(selectionStartY, selectionEndY)
                val right = max(selectionStartX, selectionEndX)
                val bottom = max(selectionStartY, selectionEndY)
                canvas.drawRect(0f, 0f, width.toFloat(), top, dimPaint)
                canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), dimPaint)
                canvas.drawRect(0f, top, left, bottom, dimPaint)
                canvas.drawRect(right, top, width.toFloat(), bottom, dimPaint)
                canvas.drawRect(left, top, right, bottom, selectionFillPaint)
                canvas.drawRect(left, top, right, bottom, crossPaint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            }

            val captureText = when {
                mode == Mode.REGION -> "生成代码"
                isCapturing -> "截图中"
                else -> "保存"
            }
            drawButton(canvas, saveRect, captureText)
            drawButton(canvas, resetRect, "重选")
            drawButton(canvas, cancelRect, "取消")

            val hint = when {
                isCapturing -> "正在截取所选区域…"
                hasSelection && mode == Mode.REGION -> selectedRegion().let {
                    "范围 [${it.left}, ${it.top}, ${it.width()}, ${it.height()}]"
                }
                hasSelection -> "${selectionWidth()}×${selectionHeight()}，可重新拖动框选"
                mode == Mode.REGION -> "按住并拖动选择范围，生成 [x, y, 宽度, 高度]"
                else -> "按住并拖动，框选要保存的区域"
            }
            val textX = margin
            val textY = if (saveRect.bottom + 34f * density < height) {
                saveRect.bottom + 26f * density
            } else {
                height - margin
            }
            canvas.drawText(hint, textX, textY, subPaint)
        }

        private fun drawButton(canvas: Canvas, rect: RectF, label: String) {
            canvas.drawRoundRect(rect, 9f * density, 9f * density, panelPaint)
            val textX = rect.centerX() - textPaint.measureText(label) / 2
            canvas.drawText(label, textX, rect.centerY() + 5f * density, textPaint)
        }

        private fun selectionWidth(): Int = abs(selectionEndX.toInt() - selectionStartX.toInt())

        private fun selectionHeight(): Int = abs(selectionEndY.toInt() - selectionStartY.toInt())

        /** 使用屏幕原点换算，保证状态栏/窗口偏移不会混入宽高。 */
        private fun selectedRegion(): Rect {
            getLocationOnScreen(location)
            return Rect(
                toScreenX(min(selectionStartX, selectionEndX)),
                toScreenY(min(selectionStartY, selectionEndY)),
                toScreenX(max(selectionStartX, selectionEndX)),
                toScreenY(max(selectionStartY, selectionEndY))
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isAreaMode) return onSelectionTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    consumedByButton = false
                    if (cancelRect.contains(event.x, event.y)) {
                        consumedByButton = true
                        PickerOverlay.hide()
                        return true
                    }
                    if (modeRect.contains(event.x, event.y)) {
                        consumedByButton = true
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > 6f * density || abs(event.y - downY) > 6f * density) {
                        moved = true
                    }
                    px = event.x
                    py = event.y
                    if (mode == Mode.COLOR) sample()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !consumedByButton) {
                        px = event.x
                        py = event.y
                        confirm()
                    }
                    consumedByButton = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /** 框选与按钮手势分开处理，重选/确认按钮的拖动不会意外创建新选区。 */
        private fun onSelectionTouchEvent(event: MotionEvent): Boolean {
            val x = event.x.coerceIn(0f, width.toFloat())
            val y = event.y.coerceIn(0f, height.toFloat())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    consumedByButton = false
                    isSelecting = false
                    when {
                        cancelRect.contains(event.x, event.y) -> {
                            consumedByButton = true
                            PickerOverlay.hide()
                        }
                        isCapturing -> consumedByButton = true
                        saveRect.contains(event.x, event.y) -> {
                            consumedByButton = true
                            if (hasSelection) {
                                if (mode == Mode.REGION) {
                                    val region = selectedRegion()
                                    onResult("var region = [${region.left}, ${region.top}, ${region.width()}, ${region.height()}];")
                                } else {
                                    captureSelection()
                                }
                            } else {
                                Toast.makeText(context, "请先拖动选择范围", Toast.LENGTH_SHORT).show()
                            }
                        }
                        resetRect.contains(event.x, event.y) -> {
                            consumedByButton = true
                            hasSelection = false
                        }
                        else -> {
                            isSelecting = true
                            hasSelection = false
                            selectionStartX = x
                            selectionStartY = y
                            selectionEndX = x
                            selectionEndY = y
                        }
                    }
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (isSelecting && !consumedByButton && !isCapturing) {
                        selectionEndX = x
                        selectionEndY = y
                        val minSize = if (mode == Mode.SCREENSHOT) 2 else 1
                        hasSelection = selectionWidth() >= minSize && selectionHeight() >= minSize
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        isSelecting = false
                        consumedByButton = false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isSelecting) hasSelection = false
                    isSelecting = false
                    consumedByButton = false
                }
            }
            invalidate()
            return true
        }

        /** 取最近一帧截屏在准星处的像素（不阻塞 UI 线程；坐标为屏幕坐标） */
        private fun sample() {
            if (sampling) return
            sampling = true
            val x = toScreenX(px)
            val y = toScreenY(py)
            Thread {
                // 取最近一帧即可（timeout=0 不等待），移动过程中会持续有新帧
                val frame = ScreenCapture.capture(0)
                val c = if (frame != null && x >= 0 && y >= 0 && x < frame.width && y < frame.height) {
                    frame.getPixel(x, y)
                } else {
                    null
                }
                post {
                    sampling = false
                    if (c != null) {
                        color = c
                        hasColor = true
                        captureMissing = false
                        // 用户点了一下但当时还没取到颜色：这次取样完成后自动完成确认，
                        // 让取色和取坐标一样「单击一次即出结果」
                        if (pendingConfirm) {
                            pendingConfirm = false
                            confirm()
                        }
                    } else {
                        captureMissing = !ScreenCapture.captureAvailable(context)
                    }
                    invalidate()
                }
            }.start()
        }

        /** 点击确认：生成代码（坐标为屏幕坐标，与 click/gesture API 一致） */
        private fun confirm() {
            if (isAreaMode) return
            val x = toScreenX(px)
            val y = toScreenY(py)
            when (mode) {
                Mode.POINT -> onResult("click($x, $y);")
                Mode.COLOR -> {
                    if (!hasColor) {
                        // 首次点击时异步取样可能还没回来：记下待确认，取样完成后自动确认
                        pendingConfirm = true
                        Toast.makeText(
                            context,
                            if (captureMissing) "取色需要截屏授权，请先在主界面「截屏」去授权"
                            else "正在取色…",
                            Toast.LENGTH_SHORT
                        ).show()
                        sample()
                        return
                    }
                    val hex = "#%06x".format(color and 0xFFFFFF)
                    onResult("images.findColor(captureScreen(), \"$hex\", { threshold: 16 });   // ($x, $y)")
                }

                Mode.REGION, Mode.SCREENSHOT -> Unit
            }
        }

        private fun captureSelection() {
            val region = selectedRegion()
            val left = region.left
            val top = region.top
            val width = region.width().coerceAtLeast(1)
            val height = region.height().coerceAtLeast(1)
            isCapturing = true
            invalidate()
            Thread {
                val frame = frozenFrame
                post {
                    isCapturing = false
                    if (frame == null) {
                        Toast.makeText(context, "截图失败：无法获取屏幕画面", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val cropX = left.coerceIn(0, frame.width - 1)
                    val cropY = top.coerceIn(0, frame.height - 1)
                    val cropW = min(width, frame.width - cropX)
                    val cropH = min(height, frame.height - cropY)
                    if (cropW < 2 || cropH < 2) {
                        Toast.makeText(context, "选区超出屏幕范围", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    val bitmap = try {
                        Bitmap.createBitmap(frame, cropX, cropY, cropW, cropH)
                    } catch (_: Exception) {
                        null
                    }
                    if (bitmap == null) {
                        Toast.makeText(context, "截图失败：选区裁剪异常", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    PickerOverlay.exportScreenshot(context, bitmap)
                }
            }.start()
        }
    }
}
