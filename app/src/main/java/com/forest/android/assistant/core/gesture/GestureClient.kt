package com.forest.android.assistant.core.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手势派发客户端：把脚本层的坐标操作（click/swipe 等）转为无障碍手势。
 * 所有方法阻塞直到手势完成，与脚本顺序执行的心智模型一致。
 * 对应脚本 API：click / longClick / press / swipe / gesture（见 docs/README.md 第 5.3 节）。
 */
object GestureClient {

    private const val TAG = "ScriptJs"

    private const val TIMEOUT_SECONDS = 10L

    /** 降级到 input 兜底时的回调（宿主写运行日志；null 表示不记录） */
    @Volatile
    var onFallback: ((String) -> Unit)? = null

    /** 坐标点击：短暂按下后抬起 */
    fun click(x: Float, y: Float, durationMs: Long = 30): Boolean {
        val svc = service()
        if (svc != null) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            if (dispatch(svc, path, durationMs)) {
                return true
            }
            notifyFallback("手势派发失败（服务异常或超时）")
        } else {
            notifyFallback("无障碍服务不可用")
        }
        // 增强通道兜底：有 Root 时改用 input
        return InputFallback.tap(x, y, durationMs).also { ok ->
            if (ok) {
                notifyFallback("已改用 Root input tap 完成点击")
            }
        }
    }

    /** 长按：默认 600ms，与脚本 API 默认值一致 */
    fun longClick(x: Float, y: Float, durationMs: Long = 600): Boolean =
        click(x, y, durationMs)

    /** 滑动 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500): Boolean {
        val svc = service()
        if (svc != null) {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            if (dispatch(svc, path, durationMs)) return true
            notifyFallback("手势派发失败（服务异常或超时）")
        } else {
            notifyFallback("无障碍服务不可用")
        }
        return InputFallback.swipe(x1, y1, x2, y2, durationMs).also { ok ->
            if (ok) notifyFallback("已改用 Root input swipe 完成滑动")
        }
    }

    /** 任意折线轨迹手势（gesture API 的底层实现），支持延迟起手 */
    fun gesture(
        durationMs: Long,
        points: List<Pair<Float, Float>>,
        startDelayMs: Long = 0
    ): Boolean {
        if (points.size < 2) return false
        val svc = service()
        if (svc != null) {
            val path = Path().apply {
                moveTo(points[0].first, points[0].second)
                points.drop(1).forEach { lineTo(it.first, it.second) }
            }
            if (dispatch(svc, path, durationMs, startDelayMs)) return true
            notifyFallback("手势派发失败（服务异常或超时）")
        } else {
            notifyFallback("无障碍服务不可用")
        }
        return InputFallback.gestureAsSwipe(points, durationMs).also { ok ->
            if (ok) notifyFallback("已改用 Root input swipe 完成（折线退化为直线）")
        }
    }

    /** 手势能力是否可用（服务已连接） */
    fun available(): Boolean = service() != null

    /**
     * 录制专用：只派发一次，等待系统完成回调，不显示特效、不自动补点。
     * 已受理后取消/超时可能已有部分触摸生效，不能再用 Root 重试造成重复操作。
     */
    fun dispatchRecorded(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        val svc = service() ?: return false
        if (points.isEmpty() || durationMs !in 1..60_000) return false
        val latch = CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            if (expired.get()) { latch.countDown(); return@post }
            try {
                val path = Path().apply {
                    moveTo(points.first().first, points.first().second)
                    points.drop(1).forEach { lineTo(it.first, it.second) }
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
                val accepted = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
                }, handler)
                if (!accepted) latch.countDown()
            } catch (e: Exception) {
                Log.w(TAG, "录制手势派发失败: ${e.message}")
                latch.countDown()
            }
        }
        return try {
            latch.await(durationMs + 3000, TimeUnit.MILLISECONDS) && completed.get()
        } finally {
            expired.set(true)
        }
    }

    /** 单笔手势描述（多指 gestures API 用） */
    data class Stroke(
        val durationMs: Long,
        val startDelayMs: Long,
        val points: List<Pair<Float, Float>>
    )

    /**
     * 多指并动手势：每根手指一条轨迹，同时起手。
     * 注意 Android 要求多笔的 startTime 相同，调用方需保证 startDelayMs 一致
     * （引擎侧对不一致的延迟已做统一处理）。单笔时退化为普通 gesture。
     */
    fun gestures(strokes: List<Stroke>): Boolean {
        val svc = service() ?: return false
        val valid = strokes.filter { it.points.size >= 2 && it.durationMs > 0 }
        if (valid.isEmpty()) return false
        if (valid.size == 1) {
            return gesture(valid[0].durationMs, valid[0].points, valid[0].startDelayMs)
        }
        val builder = GestureDescription.Builder()
        for (s in valid) {
            val path = Path().apply {
                moveTo(s.points[0].first, s.points[0].second)
                s.points.drop(1).forEach { lineTo(it.first, it.second) }
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(path, s.startDelayMs, s.durationMs)
            )
        }
        val gesture = try {
            builder.build()
        } catch (e: Exception) {
            // 多笔 startTime 不一致等非法组合
            Log.w(TAG, "多指手势构建失败: ${e.message}")
            return false
        }
        return dispatch(svc, gesture)
    }

    /** 取已连接的服务实例；未开启时返回 null */
    private fun service(): ScriptAccessibilityService? =
        ScriptAccessibilityService.instance

    /** 派发单笔手势并阻塞等待完成 */
    private fun dispatch(
        svc: ScriptAccessibilityService,
        path: Path,
        durationMs: Long,
        startDelayMs: Long = 0
    ): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startDelayMs, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatch(svc, gesture)
    }

    /** 记录降级原因（有回调时写运行日志，否则写 logcat） */
    private fun notifyFallback(reason: String) {
        val msg = "[增强通道] $reason"
        Log.i(TAG, msg)
        onFallback?.invoke(msg)
    }

    /** 派发已构建的手势并阻塞等待完成 */
    private fun dispatch(svc: ScriptAccessibilityService, gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val accepted = svc.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    completed = false
                    latch.countDown()
                }
            },
            null
        )
        // dispatchGesture 返回 false 表示未受理（服务状态异常）
        if (!accepted) return false
        val durationMs = (0 until gesture.strokeCount).maxOfOrNull {
            val stroke = gesture.getStroke(it)
            stroke.startTime + stroke.duration
        } ?: 0L
        latch.await(durationMs + TIMEOUT_SECONDS * 1000, TimeUnit.MILLISECONDS)
        return completed
    }
}
