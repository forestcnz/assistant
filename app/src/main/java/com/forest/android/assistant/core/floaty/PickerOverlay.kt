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
import android.os.Build
import android.os.SystemClock
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.File
import android.widget.Toast
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.ui.AppPalette
import com.forest.android.assistant.ScreenshotExportActivity
import com.forest.android.assistant.PickerCapturePermissionActivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * 屏幕拾取工具：拖动准星取点，或拖拽框选区域，生成脚本代码或保存截图。
 * - 坐标模式：生成 `click(x, y);`
 * - 取色模式：从打开工具时的冻结画面本地读像素（移动时不再截屏），生成
 *   `images.findColor(captureScreen(), "#rrggbb", ...)`
 * - 范围模式：框选屏幕区域，生成 `var region = [x, y, width, height];`，无需截屏授权。
 *
 * 注意：取色与截图共用同一张冻结画面，显示的就是采样源，因此移动时颜色即时跟随。
 * 对应设计 docs/README.md 第 4.5 节「坐标拾取器 / 取色器」。
 */
object PickerOverlay {

    private const val TAG = "ScriptJs"

    /** 隐藏浮层后留给系统合成的时间：确保冻结画面里不再有悬浮球/日志面板 */
    private const val PICKER_OVERLAY_SETTLE_MS = 100L

    /** 等待“隐藏浮层后的新帧”的上限；超时就按截图失败处理，避免长时间卡住 */
    private const val PICKER_CAPTURE_TIMEOUT_MS = 800L

    /** 授权页关闭后等原任务重新显示的短延迟（原为 250ms） */
    private const val PERMISSION_RETURN_DELAY_MS = 90L

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
        // 只检查用户所选方式，不使用其他通道的就绪状态，也不在主线程探测权限。
        if ((mode == Mode.COLOR || mode == Mode.SCREENSHOT) && !ScreenCapture.captureAvailableCached(context)) {
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
        // 进入拾取：收起任务面板、隐藏悬浮球与日志面板（只切可见性），结束后统一恢复。
        // 必须在隐藏之前记下“原本是否显示”，否则取消后会连悬浮球一起消失。
        restoreLauncherAfterScreenshot = FloatyManager.prepareForPicker()
        restoreConsoleAfterScreenshot = ConsoleOverlay.isShowing() &&
            ConsoleOverlay.hideForScreenshot()
        screenshotContext = context.applicationContext

        // 取色与截图需要一帧冻结画面（之后框选/取色/裁剪都基于它）
        val needsFrame = mode == Mode.SCREENSHOT || mode == Mode.COLOR
        if (!needsFrame) return attachPicker(context, mode, null)

        val hiddenAt = SystemClock.uptimeMillis()
        // 先截取浮层隐藏后的新帧，再显示拾取层；后续框选、取色、裁剪始终使用这张冻结画面。
        screenshotStarting = true
        Thread {
            val frame = try {
                // 浮层隐藏需要一两帧才从合成画面消失：先留出时间，再要求晚于 hiddenAt 的新帧
                Thread.sleep(PICKER_OVERLAY_SETTLE_MS)
                ScreenCapture.capture(PICKER_CAPTURE_TIMEOUT_MS, newerThanUptimeMs = hiddenAt)
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

    /** 独立授权页结束后，待原任务重新显示，再从其画面开始拾取。 */
    fun onCapturePermissionResult(context: Context, mode: Mode, granted: Boolean) {
        permissionStarting = false
        if (granted) {
            // 授权页关闭后原任务需要一两帧才能重新显示，短延迟即可，不再固定等 250ms
            mainHandler.postDelayed({
                if (pickerView == null && !screenshotStarting) show(context, mode)
            }, PERMISSION_RETURN_DELAY_MS)
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
        // 使用读数条的拾取模式由获得焦点的浮层处理系统返回。
        val usesBackToCancel = mode != Mode.SCREENSHOT
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            if (usesBackToCancel) WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        return try {
            wm.addView(view, params)
            if (usesBackToCancel) view.requestFocus()
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
            // 悬浮球优先只切回可见性；窗口已被其他流程摧除时重建
            if (restoreLauncher) FloatyManager.restoreAfterPicker(context)
            if (restoreConsole && !ConsoleOverlay.restoreAfterScreenshot()) ConsoleOverlay.show(context)
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
        private val isScreenshot = mode == Mode.SCREENSHOT
        private val isAreaMode = mode == Mode.REGION || isScreenshot

        private val density = resources.displayMetrics.density

        private var px = 0f
        private var py = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var pressedReadout = false

        /** 框选手势是否已被按钮消费。 */
        private var consumedByButton = false

        private var selectionStartX = 0f
        private var selectionStartY = 0f
        private var selectionEndX = 0f
        private var selectionEndY = 0f
        private var hasSelection = false
        private var isSelecting = false
        private var isCapturing = false

        /** 截图保存按钮区域。 */
        private val saveRect = RectF()

        /** 选区重选按钮区域 */
        private val resetRect = RectF()

        /** 悬浮层在屏幕上的原点：窗口通常从状态栏下方开始，需换算成屏幕坐标 */
        private val location = IntArray(2)

        private fun toScreenX(localX: Float): Int = (localX + location[0]).toInt()

        private fun toScreenY(localY: Float): Int = (localY + location[1]).toInt()

        /** 取消按钮区域（右上角） */
        private val cancelRect = RectF()

        private val readoutRect = RectF()
        private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xA6202E33.toInt()
        }
        private val readoutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            textAlign = Paint.Align.CENTER
        }
        private val swatchBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val backCallback = if (Build.VERSION.SDK_INT >= 33 && !isScreenshot) {
            OnBackInvokedCallback { PickerOverlay.hide() }
        } else null

        // 拾取按钮与信息卡片使用 APP 表面色；准星和暗化遮罩保持屏幕上的对比度。
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
            color = AppPalette.ON_SURFACE
            isFakeBoldText = true
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            color = AppPalette.ON_SURFACE_VARIANT
        }
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AppPalette.SURFACE
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AppPalette.PRIMARY
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
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        init {
            isFocusableInTouchMode = !isScreenshot
            // 初始位置取屏幕中心
            post {
                if (px == 0f && py == 0f) {
                    px = width / 2f
                    py = height / 2f
                    invalidate()
                }
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= 33) backCallback?.let {
                findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }

        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= 33) backCallback?.let {
                findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it)
            }
            super.onDetachedFromWindow()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (isScreenshot || event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) PickerOverlay.hide()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(location)
            if (isAreaMode) {
                drawSelection(canvas)
                return
            }
            // 取色基于冻结画面：把它画出来，保证“看到的像素”就是“取到的颜色”
            if (mode == Mode.COLOR) drawFrozenFrame(canvas)
            val gap = 9f * density
            val arm = 46f * density
            // 准星（中心留空，避免遮挡被采样像素）
            canvas.drawLine(px - arm, py, px - gap, py, crossPaint)
            canvas.drawLine(px + gap, py, px + arm, py, crossPaint)
            canvas.drawLine(px, py - arm, px, py - gap, crossPaint)
            canvas.drawLine(px, py + gap, px, py + arm, crossPaint)
            canvas.drawCircle(px, py, 10f * density, crossPaint)

            val x = toScreenX(px)
            val y = toScreenY(py)
            val sampledColor = if (mode == Mode.COLOR) sampleColor(x, y) else null
            val hex = if (mode == Mode.COLOR) sampledColor?.let { colorHex(it) } ?: "无可用颜色" else null
            drawReadout(canvas, px, py - arm, py + arm, "X $x  Y $y", hex, sampledColor)
        }

        private fun drawReadout(
            canvas: Canvas, anchorX: Float, anchorTop: Float, anchorBottom: Float,
            firstLine: String, secondLine: String? = null, sampledColor: Int? = null
        ) {
            val margin = 6f * density
            val padding = 4f * density
            val rowHeight = 16f * density
            val swatchSize = 12f * density
            val swatchGap = if (sampledColor != null) swatchSize + 5f * density else 0f
            val secondLineWidth = if (secondLine != null) readoutTextPaint.measureText(secondLine) else 0f
            val chipWidth = max(readoutTextPaint.measureText(firstLine), secondLineWidth + swatchGap) + 16f * density
            val chipHeight = (if (secondLine == null) rowHeight else 2 * rowHeight) + 2 * padding
            val left = (anchorX - chipWidth / 2).coerceIn(margin, (width - margin - chipWidth).coerceAtLeast(margin))
            val below = anchorBottom + margin
            val top = if (below + chipHeight <= height - margin) below
                else (anchorTop - margin - chipHeight).coerceAtLeast(margin)
            readoutRect.set(left, top, left + chipWidth, top + chipHeight)
            canvas.drawRoundRect(readoutRect, margin, margin, readoutPaint)

            val firstCenterY = top + padding + rowHeight / 2
            val baselineOffset = -(readoutTextPaint.ascent() + readoutTextPaint.descent()) / 2
            val centerX = readoutRect.centerX()
            canvas.drawText(firstLine, centerX, firstCenterY + baselineOffset, readoutTextPaint)
            if (secondLine != null) {
                val colorCenterY = firstCenterY + rowHeight
                if (sampledColor != null) {
                    val swatchLeft = centerX - (secondLineWidth + swatchGap) / 2
                    val swatchTop = colorCenterY - swatchSize / 2
                    swatchPaint.color = sampledColor or Color.BLACK
                    canvas.drawRect(swatchLeft, swatchTop, swatchLeft + swatchSize, swatchTop + swatchSize, swatchPaint)
                    canvas.drawRect(swatchLeft, swatchTop, swatchLeft + swatchSize, swatchTop + swatchSize, swatchBorderPaint)
                }
                canvas.drawText(secondLine, centerX + swatchGap / 2, colorCenterY + baselineOffset, readoutTextPaint)
            }
        }

        /** 把冻结画面按窗口偏移画到画布上（取色与框选共用） */
        private fun drawFrozenFrame(canvas: Canvas) {
            val frame = frozenFrame ?: return
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

        private fun drawSelection(canvas: Canvas) {
            if (!isScreenshot) {
                if (hasSelection) {
                    drawSelectionFrame(canvas)
                    val region = selectedRegion()
                    drawReadout(
                        canvas, region.exactCenterX() - location[0],
                        (region.top - location[1]).toFloat(), (region.bottom - location[1]).toFloat(),
                        "X ${region.left}  Y ${region.top}", "W ${region.width()}  H ${region.height()}"
                    )
                } else {
                    drawReadout(canvas, width / 2f, height / 2f, height / 2f, "拖动框选范围")
                }
                return
            }

            drawFrozenFrame(canvas)
            val btnW = 76f * density
            val btnH = 44f * density
            val btnGap = 10f * density
            val margin = 18f * density
            cancelRect.set(width - btnW - margin, margin, width - margin, margin + btnH)
            resetRect.set(margin * 2, height - margin - 58f * density, margin * 2 + btnW, height - margin - 14f * density)
            saveRect.set(resetRect.right + btnGap, resetRect.top, width - margin * 2, resetRect.bottom)

            if (hasSelection) drawSelectionFrame(canvas)
            else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            val captureText = if (isCapturing) "截图中" else "保存"
            canvas.drawRoundRect(RectF(margin, height - 142f * density, width - margin, height - margin), 16f * density, 16f * density, panelPaint)
            drawButton(canvas, saveRect, captureText, primary = true)
            drawButton(canvas, resetRect, "重选")
            drawButton(canvas, cancelRect, "取消")

            val hint = when {
                isCapturing -> "正在截取所选区域…"
                hasSelection -> "${selectionWidth()}×${selectionHeight()}，可重新拖动框选"
                else -> "按住并拖动，框选要保存的区域"
            }
            canvas.drawText(android.text.TextUtils.ellipsize(hint, android.text.TextPaint(subPaint),
                (width - margin * 4).coerceAtLeast(1f), android.text.TextUtils.TruncateAt.END).toString(),
                margin * 2, height - 113f * density, subPaint)
        }

        private fun drawSelectionFrame(canvas: Canvas) {
            val left = min(selectionStartX, selectionEndX)
            val top = min(selectionStartY, selectionEndY)
            val right = max(selectionStartX, selectionEndX)
            val bottom = max(selectionStartY, selectionEndY)
            canvas.drawRect(0f, 0f, width.toFloat(), top, dimPaint)
            canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, top, left, bottom, dimPaint)
            canvas.drawRect(right, top, width.toFloat(), bottom, dimPaint)
            canvas.drawRect(left, top, right, bottom, crossPaint)
        }

        private fun drawButton(canvas: Canvas, rect: RectF, label: String, primary: Boolean = false) {
            panelPaint.color = if (primary) AppPalette.PRIMARY else AppPalette.SURFACE
            textPaint.color = if (primary) AppPalette.ON_PRIMARY else AppPalette.ON_SURFACE
            canvas.drawRoundRect(rect, 11f * density, 11f * density, panelPaint)
            val textX = rect.centerX() - textPaint.measureText(label) / 2
            canvas.drawText(label, textX, rect.centerY() + 5f * density, textPaint)
            panelPaint.color = AppPalette.SURFACE
            textPaint.color = AppPalette.ON_SURFACE
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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    pressedReadout = readoutRect.contains(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > 6f * density || abs(event.y - downY) > 6f * density) {
                        moved = true
                    }
                    if (moved) moveCrosshair(event.x, event.y)
                }
                MotionEvent.ACTION_UP -> {
                    if (moved || !pressedReadout) moveCrosshair(event.x, event.y)
                    if (!moved) confirm()
                    pressedReadout = false
                }
                MotionEvent.ACTION_CANCEL -> pressedReadout = false
            }
            return true
        }

        private fun moveCrosshair(x: Float, y: Float) {
            px = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
            py = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
            invalidate()
        }

        private fun startSelection(x: Float, y: Float) {
            isSelecting = true
            hasSelection = false
            selectionStartX = x
            selectionStartY = y
            selectionEndX = x
            selectionEndY = y
        }

        /** 轻点范围读数条复制，拖动则开始重新框选。 */
        private fun onSelectionTouchEvent(event: MotionEvent): Boolean {
            val x = event.x.coerceIn(0f, width.toFloat())
            val y = event.y.coerceIn(0f, height.toFloat())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    consumedByButton = false
                    isSelecting = false
                    pressedReadout = false
                    when {
                        !isScreenshot -> {
                            pressedReadout = hasSelection && readoutRect.contains(x, y)
                            if (pressedReadout) {
                                downX = x
                                downY = y
                            } else startSelection(x, y)
                        }
                        cancelRect.contains(event.x, event.y) -> {
                            consumedByButton = true
                            PickerOverlay.hide()
                        }
                        isCapturing -> consumedByButton = true
                        saveRect.contains(event.x, event.y) -> {
                            consumedByButton = true
                            if (hasSelection) {
                                captureSelection()
                            } else {
                                Toast.makeText(context, "请先拖动选择范围", Toast.LENGTH_SHORT).show()
                            }
                        }
                        resetRect.contains(event.x, event.y) -> {
                            consumedByButton = true
                            hasSelection = false
                        }
                        y >= height - 142f * density -> consumedByButton = true
                        else -> startSelection(x, y)
                    }
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (pressedReadout && (abs(x - downX) > 6f * density || abs(y - downY) > 6f * density)) {
                        pressedReadout = false
                        startSelection(downX, downY)
                    }
                    if (isSelecting && !consumedByButton && !isCapturing) {
                        selectionEndX = x
                        selectionEndY = y
                        val minSize = if (isScreenshot) 2 else 1
                        hasSelection = selectionWidth() >= minSize && selectionHeight() >= minSize
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val copyRange = pressedReadout && hasSelection && readoutRect.contains(x, y)
                        isSelecting = false
                        consumedByButton = false
                        pressedReadout = false
                        if (copyRange) {
                            val region = selectedRegion()
                            onResult("var region = [${region.left}, ${region.top}, ${region.width()}, ${region.height()}];")
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isSelecting) hasSelection = false
                    isSelecting = false
                    consumedByButton = false
                    pressedReadout = false
                }
            }
            invalidate()
            return true
        }

        /** 取冻结画面里准星处的像素：本地读取，无截屏开销，移动时立即更新 */
        private fun sampleColor(x: Int, y: Int): Int? {
            val frame = frozenFrame ?: return null
            if (x !in 0 until frame.width || y !in 0 until frame.height) return null
            return frame.getPixel(x, y)
        }

        private fun colorHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

        /** 点击确认：生成代码（坐标为屏幕坐标，与 click/gesture API 一致） */
        private fun confirm() {
            getLocationOnScreen(location)
            val x = toScreenX(px)
            val y = toScreenY(py)
            when (mode) {
                Mode.POINT -> onResult("click($x, $y);")
                Mode.COLOR -> {
                    // 冻结画面本地取色：点击时立即有结果，无需异步等截屏
                    val color = sampleColor(x, y)
                    if (color == null) {
                        Toast.makeText(context, "取色失败，请重新打开取色工具", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val hex = colorHex(color)
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
