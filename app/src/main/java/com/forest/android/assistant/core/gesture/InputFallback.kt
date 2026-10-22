package com.forest.android.assistant.core.gesture

import android.util.Log
import com.forest.android.assistant.core.shell.RootShell

/**
 * `input` 稳定点击兜底（增强通道）：当无障碍手势派发失败（服务不可用 / 被系统拒绝 / 超时），
 * 且设备有 Root 时，改用 `su -c input tap|swipe` 完成同样的坐标操作。
 *
 * 适用场景：老设备不支持 `dispatchGesture`、或无障碍服务被系统回收。
 * 无 Root 时所有方法直接返回 false，调用方行为保持不变。
 * 对应设计 docs/README.md 第 3 章「增强」权限级别。
 */
object InputFallback {

    private const val TAG = "ScriptJs"

    /** 兜底是否可用（有 Root） */
    fun available(): Boolean = RootShell.available()

    /** 点击兜底（按住 durationMs 后抬起） */
    fun tap(x: Float, y: Float, durationMs: Long = 30): Boolean {
        if (!available()) return false
        val xi = x.toInt()
        val yi = y.toInt()
        return if (durationMs <= 60) {
            // 普通点击：input tap 更贴近系统点击事件
            exec("input tap $xi $yi")
        } else {
            // 长按：input 没有 longpress，用同点滑动的时长模拟
            exec("input swipe $xi $yi $xi $yi $durationMs")
        }
    }

    /** 长按兜底 */
    fun longPress(x: Float, y: Float, durationMs: Long = 600): Boolean {
        if (!available()) return false
        val xi = x.toInt()
        val yi = y.toInt()
        return exec("input swipe $xi $yi $xi $yi $durationMs")
    }

    /** 滑动兜底 */
    fun swipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500
    ): Boolean {
        if (!available()) return false
        val cmd = "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs"
        return exec(cmd)
    }

    /**
     * 折线手势兜底：`input` 只支持直线滑动，
     * 因此退化为「起点 → 终点」的直线滑动（由调用方负责日志说明）。
     */
    fun gestureAsSwipe(
        points: List<Pair<Float, Float>>, durationMs: Long
    ): Boolean {
        if (!available() || points.size < 2) return false
        val first = points.first()
        val last = points.last()
        return swipe(first.first, first.second, last.first, last.second, durationMs)
    }

    private fun exec(command: String): Boolean {
        Log.i(TAG, "input 兜底: $command")
        return RootShell.exec(command)
    }
}
