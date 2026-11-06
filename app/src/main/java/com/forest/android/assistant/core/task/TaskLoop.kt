package com.forest.android.assistant.core.task

/** 整个任务的循环条件；旧配置没有 mode 时按固定次数执行。 */
data class TaskLoop(
    val mode: Mode = Mode.COUNT,
    val count: Int = 1,
    val durationSec: Long = 60,
    val intervalSec: Int = 0
) {
    enum class Mode(val key: String) {
        COUNT("count"), DURATION("duration"), UNTIL_STOPPED("untilStopped");

        companion object {
            fun fromKey(key: String): Mode = entries.firstOrNull { it.key == key } ?: COUNT
        }
    }

    /** elapsedMs 使用单调时钟计算，避免系统时间调整影响运行时长。 */
    fun shouldStartNext(completed: Long, elapsedMs: Long): Boolean = when (mode) {
        Mode.COUNT -> completed < count
        Mode.DURATION -> elapsedMs < durationSec * 1000L
        Mode.UNTIL_STOPPED -> true
    }

    /** 轮次间隔不超过剩余运行时间，时间到达后立即检查退出条件。 */
    fun nextDelayMs(elapsedMs: Long): Long = when (mode) {
        Mode.DURATION -> minOf(intervalSec * 1000L, (durationSec * 1000L - elapsedMs).coerceAtLeast(0L))
        else -> intervalSec * 1000L
    }

    fun describe(): String = when (mode) {
        Mode.COUNT -> "执行 $count 次"
        Mode.DURATION -> "运行 ${durationSec} 秒"
        Mode.UNTIL_STOPPED -> "直到手动停止"
    }
}
