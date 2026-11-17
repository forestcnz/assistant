package com.forest.android.assistant.core.recording

import android.content.Context
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONObject
import java.io.File

/** 摘要标记保留在任务目录，进程退出也不会丢失已完成的快照。 */
object RecordingReview {
    data class Summary(val steps: Int, val durationMs: Long, val mode: String)
    private const val MARKER = TaskManager.PENDING_REVIEW_MARKER

    fun prepare(context: Context, task: RecordingTask) {
        val steps = task.steps
        val json = JSONObject().put("steps", steps.size)
            // 摘要时长与脚本一致，包含最后一次操作后的等待。
            .put("durationMs", steps.sumOf { it.delayMs + it.durationMs } + task.trailingDelayMs).put("mode", task.mode.label)
        File(TaskManager.taskDir(context, task.name), MARKER).writeText(json.toString())
    }

    private fun directory(context: Context, task: String): File {
        val root = TaskManager.tasksRoot(context).canonicalFile
        val directory = File(root, task).canonicalFile
        require(task.isNotBlank() && directory.parentFile == root && File(directory, MARKER).isFile) { "待确认的录制不存在或已经保存" }
        return directory
    }

    fun read(context: Context, task: String): Summary {
        val json = JSONObject(File(directory(context, task), MARKER).readText())
        return Summary(json.getInt("steps"), json.getLong("durationMs"), json.getString("mode"))
    }

    fun finish(context: Context, task: String) {
        check(File(directory(context, task), MARKER).delete()) { "无法完成保存，请重试" }
    }

    fun pending(context: Context, task: String): Boolean = File(TaskManager.taskDir(context, task), MARKER).isFile

    fun discard(context: Context, task: String) {
        directory(context, task)
        TaskManager.deleteTask(context, task).getOrThrow()
    }
}
