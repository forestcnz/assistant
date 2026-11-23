package com.forest.android.assistant.core.shell

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * 兼容 Android 7 的进程等待：Process.waitFor(timeout, unit) 需要 API 26，
 * 低版本通过轮询 exitValue() 模拟超时等待。
 */
internal fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return waitFor(timeout, unit)
    }
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    while (true) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(20)
        }
    }
}
