package com.forest.android.assistant.core.update

import android.content.pm.PackageInfo
import android.os.Build

/** Android 9（API 28）起 versionCode 扩展为 long；低版本使用已废弃的 int 字段。 */
internal val PackageInfo.longVersionCodeCompat: Long
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
