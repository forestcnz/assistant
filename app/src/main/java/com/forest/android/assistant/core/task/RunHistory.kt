package com.forest.android.assistant.core.task

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行历史：每个任务独立记录每次运行的耗时、退出原因与异常堆栈。
 * 存储为任务目录下的 `history.jsonl`（每行一条记录，便于追加与截断），最多保留 [MAX_ENTRIES] 条。
 * 对应设计 docs/README.md 第 4.2 节「运行历史」。
 */
object RunHistory {

    private const val TAG = "ScriptJs"
    private const val FILE_NAME = "history.jsonl"
    private const val MAX_ENTRIES = 50

    /** 退出原因 */
    enum class Outcome(val label: String) {
        FINISHED("完成"),
        EXITED("退出"),
        STOPPED("已停止"),
        ERROR("出错")
    }

    data class Entry(
        val time: String,
        val source: String,
        val outcome: Outcome,
        val durationMs: Long,
        val message: String? = null,
        val stack: String? = null
    ) {
        /** 耗时展示：毫秒或秒 */
        fun durationText(): String =
            if (durationMs < 1000) "${durationMs}ms" else "%.1fs".format(durationMs / 1000.0)
    }

    private fun file(context: Context, taskName: String): File =
        File(TaskManager.taskDir(context, taskName), FILE_NAME)

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 追加一条运行记录（自动截断到最近 MAX_ENTRIES 条） */
    fun append(context: Context, taskName: String, outcome: Outcome, durationMs: Long,
               source: String = "手动", message: String? = null, stack: String? = null) {
        val entry = Entry(
            time = timeFormat.format(Date()),
            source = source,
            outcome = outcome,
            durationMs = durationMs,
            message = message,
            stack = stack
        )
        try {
            val file = file(context, taskName)
            val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
            lines.add(toJson(entry).toString())
            while (lines.size > MAX_ENTRIES) lines.removeAt(0)
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "写入运行历史失败: ${e.message}")
        }
    }

    /** 读取历史（新的在前） */
    fun read(context: Context, taskName: String, limit: Int = 20): List<Entry> {
        val file = file(context, taskName)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { parse(it) }
                .takeLast(limit)
                .reversed()
        } catch (e: Exception) {
            Log.w(TAG, "读取运行历史失败: ${e.message}")
            emptyList()
        }
    }

    /** 清空历史 */
    fun clear(context: Context, taskName: String): Boolean = try {
        file(context, taskName).delete()
    } catch (e: Exception) {
        false
    }

    private fun toJson(entry: Entry): JSONObject = JSONObject().apply {
        put("time", entry.time)
        put("source", entry.source)
        put("outcome", entry.outcome.name)
        put("durationMs", entry.durationMs)
        put("message", entry.message ?: "")
        put("stack", entry.stack ?: "")
    }

    private fun parse(line: String): Entry? = try {
        val json = JSONObject(line)
        Entry(
            time = json.optString("time", ""),
            source = json.optString("source", "手动"),
            outcome = try {
                Outcome.valueOf(json.optString("outcome", Outcome.FINISHED.name))
            } catch (e: Exception) {
                Outcome.FINISHED
            },
            durationMs = json.optLong("durationMs", 0L),
            message = json.optString("message", "").takeIf { it.isNotBlank() },
            stack = json.optString("stack", "").takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        null
    }
}
