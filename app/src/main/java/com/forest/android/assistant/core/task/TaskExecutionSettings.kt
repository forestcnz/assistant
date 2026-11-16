package com.forest.android.assistant.core.task

import android.content.Context

/** 全局任务执行设置：多选任务与分组任务共用，重启应用后保留。 */
object TaskExecutionSettings {
    private const val PREFS = "task_execution"
    private const val KEY_QUEUE_INTERVAL_SEC = "queue_interval_sec"

    /** 默认等待 3 秒；间隔仅作用于队列中两个任务之间。 */
    fun queueIntervalSec(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_QUEUE_INTERVAL_SEC, 3).coerceAtLeast(0)

    fun setQueueIntervalSec(context: Context, seconds: Int) {
        require(seconds >= 0) { "任务间隔必须为非负整数秒" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_QUEUE_INTERVAL_SEC, seconds).apply()
    }
}
