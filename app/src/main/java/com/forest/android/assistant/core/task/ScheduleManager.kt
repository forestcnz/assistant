package com.forest.android.assistant.core.task

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 定时任务：把任务的执行时间表交给 WorkManager，到点由 [ScheduledTaskWorker] 拉起脚本。
 * 表存在任务根目录的 schedules.json，每个任务最多一条（同名唯一 work）。
 * 对应设计 docs/README.md 第 4.6 节「调度与稳定性」。
 */
object ScheduleManager {

    private const val TAG = "ScriptJs"
    private const val FILE_NAME = "schedules.json"
    private const val WORK_PREFIX = "scheduled_task_"
    private val parsedSchedules = TextParseCache<List<Schedule>>()

    /** 触发方式 */
    enum class Type {
        /** N 分钟后执行一次 */
        ONCE_AFTER_MINUTES,

        /** 每 N 分钟重复（WorkManager 周期任务下限 15 分钟） */
        EVERY_MINUTES,

        /** 每天固定时刻 */
        DAILY_AT
    }

    data class Schedule(
        val taskName: String,
        val type: Type,
        val delayMinutes: Int = 0,
        val intervalMinutes: Int = 0,
        val hour: Int = 0,
        val minute: Int = 0,
        val enabled: Boolean = true
    ) {
        fun describe(): String = when (type) {
            Type.ONCE_AFTER_MINUTES -> "$delayMinutes 分钟后执行一次"
            Type.EVERY_MINUTES -> "每 ${intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)} 分钟"
            Type.DAILY_AT -> "每天 %02d:%02d".format(hour, minute)
        }
    }

    /** WorkManager 周期任务的最小间隔 */
    const val MIN_INTERVAL_MINUTES = 15

    private fun file(context: Context): File = File(TaskManager.tasksRoot(context), FILE_NAME)

    @Synchronized
    fun load(context: Context): List<Schedule> {
        val f = file(context)
        if (!f.isFile) {
            parsedSchedules.clear()
            return emptyList()
        }
        return try {
            val source = f.readText()
            val cached = parsedSchedules.get(f.absolutePath, source)
            val result = cached ?: try {
                val arr = JSONArray(source)
                TextParseCache.Parsed((0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) })
            } catch (e: Exception) {
                TextParseCache.Parsed(emptyList(), e.message)
            }.also { parsedSchedules.put(f.absolutePath, source, it) }
            result.error?.let { Log.w(TAG, "读取定时任务失败: $it") }
            result.value.toList()
        } catch (e: Exception) {
            Log.w(TAG, "读取定时任务失败: ${e.message}")
            emptyList()
        }
    }

    fun scheduleOf(context: Context, taskName: String): Schedule? =
        load(context).firstOrNull { it.taskName == taskName }

    /** 保存并注册定时；返回是否成功 */
    fun upsert(context: Context, schedule: Schedule): Boolean {
        val list = load(context).filterNot { it.taskName == schedule.taskName } + schedule
        if (!save(context, list)) return false
        return enqueue(context, schedule)
    }

    /** 取消任务的定时 */
    fun remove(context: Context, taskName: String): Boolean {
        val list = load(context).filterNot { it.taskName == taskName }
        if (!save(context, list)) return false
        cancelWork(context, taskName)
        return true
    }

    /** 改名保留定时参数，使用新任务名重新排程。 */
    @Synchronized
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        val original = load(context)
        val previous = original.firstOrNull { it.taskName == oldName } ?: return true
        val updated = previous.copy(taskName = newName)
        if (!save(context, original.filterNot { it.taskName == oldName } + updated)) return false
        if (updated.enabled && !enqueue(context, updated)) {
            save(context, original)
            cancelWork(context, newName)
            return false
        }
        cancelWork(context, oldName)
        return true
    }

    /** 一次性任务执行完后标记为已完成（保留记录，不再排程） */
    fun markDone(context: Context, taskName: String) {
        val list = load(context).map {
            if (it.taskName == taskName) it.copy(enabled = false) else it
        }
        save(context, list)
    }

    private fun save(context: Context, list: List<Schedule>): Boolean = try {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("task", s.taskName)
                put("type", s.type.name)
                put("delayMinutes", s.delayMinutes)
                put("intervalMinutes", s.intervalMinutes)
                put("hour", s.hour)
                put("minute", s.minute)
                put("enabled", s.enabled)
            })
        }
        val atomic = android.util.AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            stream.write(arr.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "保存定时任务失败: ${e.message}")
        false
    }

    private fun parse(json: JSONObject?): Schedule? {
        if (json == null) return null
        val task = json.optString("task", "")
        if (task.isBlank()) return null
        val type = try {
            Type.valueOf(json.optString("type", Type.ONCE_AFTER_MINUTES.name))
        } catch (e: Exception) {
            Type.ONCE_AFTER_MINUTES
        }
        return Schedule(
            taskName = task,
            type = type,
            delayMinutes = json.optInt("delayMinutes", 0),
            intervalMinutes = json.optInt("intervalMinutes", 0),
            hour = json.optInt("hour", 0),
            minute = json.optInt("minute", 0),
            enabled = json.optBoolean("enabled", true)
        )
    }

    /** 注册到 WorkManager（同名唯一，REPLACE） */
    fun enqueue(context: Context, schedule: Schedule): Boolean = try {
        val data = workDataOf(ScheduledTaskWorker.KEY_TASK to schedule.taskName)
        val wm = WorkManager.getInstance(context)
        val uniqueName = WORK_PREFIX + schedule.taskName
        when (schedule.type) {
            Type.ONCE_AFTER_MINUTES -> {
                val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                    .setInitialDelay(schedule.delayMinutes.coerceAtLeast(1).toLong(), TimeUnit.MINUTES)
                    .setInputData(data)
                    .build()
                wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            }

            Type.EVERY_MINUTES -> {
                val minutes = schedule.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
                // 周期任务入队即会先跑一次；这里加一个间隔的初始延迟，
                // 让“每 N 分钟”的首次执行也发生在 N 分钟之后，符合用户预期
                val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(minutes, TimeUnit.MINUTES)
                    .setInitialDelay(minutes, TimeUnit.MINUTES)
                    .setInputData(data)
                    .build()
                wm.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.REPLACE, request)
            }

            Type.DAILY_AT -> {
                val firstDelay = delayToNext(schedule.hour, schedule.minute)
                val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(firstDelay, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .build()
                wm.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.REPLACE, request)
                Log.i(TAG, "定时任务首次触发：" + (firstDelay / 60000) + " 分钟后")
            }
        }
        Log.i(TAG, "定时任务已注册: ${schedule.taskName} — ${schedule.describe()}")
        true
    } catch (e: Exception) {
        Log.w(TAG, "注册定时任务失败: ${e.message}")
        false
    }

    fun cancelWork(context: Context, taskName: String) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + taskName)
            Log.i(TAG, "已取消定时任务: $taskName")
        } catch (e: Exception) {
            Log.w(TAG, "取消定时任务失败: ${e.message}")
        }
    }

    /** 距离下一个 HH:mm 的毫秒数 */
    private fun delayToNext(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
}
