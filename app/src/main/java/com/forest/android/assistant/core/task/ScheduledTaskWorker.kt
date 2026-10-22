package com.forest.android.assistant.core.task

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.forest.android.assistant.core.engine.ScriptRunner

/**
 * 定时任务 Worker：到点后由 WorkManager 拉起，复用 [ScriptRunner] 执行任务脚本。
 * 执行期间同样显示悬浮状态条，可用停止按钮中断（会取消本 Worker）。
 */
class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskName = inputData.getString(KEY_TASK)
        if (taskName.isNullOrBlank()) {
            Log.w(TAG, "定时任务缺少任务名参数")
            return Result.failure()
        }
        ScriptRunner.attach(applicationContext)
        Log.i(TAG, "定时任务触发: $taskName")

        val ok = ScriptRunner.runAndWait(applicationContext, taskName, source = "定时任务")

        // 一次性任务执行完不再排程（保留记录供界面展示）
        ScheduleManager.scheduleOf(applicationContext, taskName)?.let { s ->
            if (s.type == ScheduleManager.Type.ONCE_AFTER_MINUTES) {
                ScheduleManager.markDone(applicationContext, taskName)
            }
        }
        return if (ok) Result.success() else Result.success()   // 失败不重试，避免脚本异常导致反复执行
    }

    companion object {
        private const val TAG = "ScriptJs"

        /** 输入数据键：任务名 */
        const val KEY_TASK = "task_name"
    }
}
