package com.forest.android.assistant.core.floaty

import android.os.Build
import android.view.WindowManager

/**
 * 悬浮窗类型：Android 8.0（API 26）起必须使用 TYPE_APPLICATION_OVERLAY；
 * Android 7.x 及以下使用 TYPE_PHONE，配合 SYSTEM_ALERT_WINDOW 权限即可显示。
 */
internal fun overlayWindowType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }
