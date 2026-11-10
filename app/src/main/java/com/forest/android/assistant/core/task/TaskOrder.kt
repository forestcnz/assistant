package com.forest.android.assistant.core.task

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * 任务列表的自定义顺序（tasks/.task-order.json）。
 * APP、Web 与悬浮窗共用同一份顺序；写入时携带 revision，避免直接覆盖其他端刚保存的结果。
 * 未记录的新任务按名称追加在末尾，已删除的任务在读取时自动忽略。
 */
object TaskOrder {
    /** 当前顺序与版本号；文件不存在时版本为 0，表示还没手动排序过。 */
    data class Snapshot(val taskNames: List<String> = emptyList(), val revision: Long = 0)

    fun read(context: Context): Snapshot = synchronized(TaskManager) { readSafely(context) }

    /** 顺序文件损坏时退回默认顺序，避免影响任务列表等核心功能。 */
    private fun readSafely(context: Context): Snapshot = try {
        readFile(context)
    } catch (error: Exception) {
        android.util.Log.w("ScriptJs", "任务顺序读取失败，按名称顺序显示", error)
        Snapshot()
    }

    /** 按已保存顺序排列现有任务，其余任务按名称追加到末尾。 */
    fun ordered(context: Context, names: Collection<String>): List<String> =
        merge(read(context).taskNames, names)

    /**
     * 合并已保存顺序与现有任务：保存过的任务保持记录顺序，
     * 未记录的新任务按名称排序追加，已不存在的任务自动丢弃。
     */
    internal fun merge(saved: List<String>, available: Collection<String>): List<String> {
        val remaining = available.toMutableSet()
        val ordered = saved.filter { remaining.remove(it) }
        return ordered + remaining.sorted()
    }

    /** 保存手动顺序；版本不一致表示其他端已修改，抛并发修改异常由调用方提示刷新。 */
    fun save(context: Context, expectedRevision: Long, taskNames: List<String>): Snapshot =
        synchronized(TaskManager) {
            val current = readSafely(context)
            if (expectedRevision >= 0 && current.revision != expectedRevision) {
                throw ConcurrentModificationException("任务顺序已被修改，请刷新后重试")
            }
            val available = TaskManager.availableTaskNames(context)
            require(taskNames.distinct().size == taskNames.size) { "任务顺序中存在重复任务" }
            require(taskNames.all { it in available }) { "部分任务已不存在，请刷新列表" }
            // 保存时补齐调用方列表中缺失的任务，避免并发新建的任务从顺序中丢失。
            val next = Snapshot(merge(taskNames, available), current.revision + 1)
            writeFile(context, next)
            next
        }

    /** 任务改名/删除时同步维护顺序，保持其余任务的位置；失败时回滚。调用方已持有 TaskManager 锁。 */
    internal fun <T> withTaskOrderReference(
        context: Context,
        oldName: String,
        newName: String?,
        action: () -> T
    ): T = synchronized(TaskManager) {
        if (oldName == newName) return@synchronized action()
        val before = readSafely(context)
        val names = before.taskNames.mapNotNull { if (it == oldName) newName else it }.distinct()
        if (names == before.taskNames) return@synchronized action()
        val after = Snapshot(names, before.revision + 1)
        writeFile(context, after)
        try {
            action()
        } catch (error: Exception) {
            try {
                writeFile(context, before)
            } catch (rollback: Exception) {
                error.addSuppressed(rollback)
            }
            throw error
        }
    }

    private fun file(context: Context) = AtomicFile(File(TaskManager.tasksRoot(context), ".task-order.json"))

    private fun readFile(context: Context): Snapshot {
        val atomic = file(context)
        val source = try {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: FileNotFoundException) {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return Snapshot()
            throw error
        }
        val document = JSONObject(source)
        check(document.getInt("version") == 1) { "不支持的任务顺序格式" }
        val names = document.getJSONArray("taskNames")
        return Snapshot((0 until names.length()).map(names::getString), document.getLong("revision"))
    }

    private fun writeFile(context: Context, snapshot: Snapshot) {
        val document = JSONObject().put("version", 1)
            .put("revision", snapshot.revision)
            .put("taskNames", JSONArray(snapshot.taskNames))
        val atomic = file(context)
        val stream = atomic.startWrite()
        try {
            stream.write((document.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
