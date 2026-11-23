package com.forest.android.assistant.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.forest.android.assistant.R
import com.forest.android.assistant.core.floaty.overlayWindowType
import kotlin.math.roundToInt

/** 全局轻提示：直接绘制透明雕雕 Logo，不依赖系统 Toast 自动加载的应用图标。 */
internal object AppToast {
    const val LENGTH_SHORT = android.widget.Toast.LENGTH_SHORT
    const val LENGTH_LONG = android.widget.Toast.LENGTH_LONG

    private val handler = Handler(Looper.getMainLooper())
    private var currentToast: android.widget.Toast? = null
    private var overlayManager: WindowManager? = null
    private var overlayView: View? = null
    private val dismissOverlay = Runnable {
        overlayView?.let { view -> runCatching { overlayManager?.removeViewImmediate(view) } }
        overlayView = null
        overlayManager = null
    }

    fun makeText(context: Context, text: CharSequence?, duration: Int): Message =
        Message(context.applicationContext, text?.toString().orEmpty(), duration)

    class Message internal constructor(private val context: Context, private val text: String, private val duration: Int) {
        fun show() {
            // 调用方可以来自录制、脚本与后台线程，所有提示视图统一在主线程管理。
            handler.post { showMessage(context, text, duration) }
        }
    }

    @Suppress("DEPRECATION")
    private fun showMessage(context: Context, text: String, duration: Int) {
        handler.removeCallbacks(dismissOverlay)
        dismissOverlay.run()
        currentToast?.cancel()
        currentToast = null
        if (text.isBlank()) return

        val content = createContent(context, text)
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val foreground = process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

        // 应用位于其他应用之上时使用已有悬浮权限，避免后台自定义 Toast 被系统拦截。
        if (!foreground && Settings.canDrawOverlays(context) && showOverlay(context, content, duration)) return

        currentToast = if (foreground) {
            android.widget.Toast(context).apply {
                view = content
                this.duration = duration
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(context, 80))
            }
        } else {
            // 无可用应用窗口及悬浮权限时保留系统提示通道，确保授权失败等信息仍能送达。
            android.widget.Toast.makeText(context, text, duration)
        }
        currentToast?.show()
    }

    private fun showOverlay(context: Context, content: View, duration: Int): Boolean {
        val manager = context.getSystemService(WindowManager::class.java) ?: return false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(context, 80)
        }
        return try {
            manager.addView(content, params)
            overlayManager = manager
            overlayView = content
            val baseTimeout = if (duration == LENGTH_LONG) 3_500 else 2_000
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            // 无障碍建议时长从 Android 10（API 29）开始提供，低版本使用默认时长
            val timeout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && accessibility != null) {
                accessibility.getRecommendedTimeoutMillis(
                    baseTimeout,
                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS
                )
            } else {
                baseTimeout
            }
            handler.postDelayed(dismissOverlay, timeout.toLong())
            true
        } catch (_: Exception) {
            runCatching { if (content.isAttachedToWindow) manager.removeViewImmediate(content) }
            false
        }
    }

    private fun createContent(context: Context, text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        background = GradientDrawable().apply {
            // 轻提示与 APP 页面共用暖白底和浅边框，文字使用深蓝灰。
            setColor(AppPalette.BACKGROUND)
            setStroke(dp(context, 1).coerceAtLeast(1), AppPalette.OUTLINE_VARIANT)
            cornerRadius = dp(context, 8).toFloat()
        }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_brand_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)).apply { marginEnd = dp(context, 8) })
        addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(AppPalette.ON_SURFACE)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels - dp(context, 90)).coerceAtLeast(dp(context, 100))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
