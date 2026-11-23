package com.forest.android.assistant.core.task

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 先完整写入隐藏暂存目录，再一次发布；新任务仅继承内容，不继承运行记录。 */
internal object TaskCopy {
    fun create(source: File, target: File) {
        require(source.canonicalFile.parentFile == target.canonicalFile.parentFile) { "任务目录超出允许范围" }
        require(File(source, "task.js").isFile) { "原任务不存在" }
        require(!target.exists()) { "该任务名称已存在" }
        val configFile = File(source, "config.json")
        val config = if (configFile.isFile) JSONObject(configFile.readText()) else JSONObject()
        config.put("name", target.name)
        // .deleted- 前缀与 TaskManager 的临时任务过滤保持一致。
        val staging = File(target.parentFile, ".deleted-copy-${UUID.randomUUID()}")
        try {
            check(staging.mkdirs()) { "无法创建任务暂存目录" }
            // Android 7 没有 java.nio.file.Files：用规范路径判断是否仍位于任务目录内，
            // 符号链接会被解析到真实目标，指向目录外的链接同样会被拒绝。
            val root = source.canonicalFile
            source.walkTopDown().onEnter { directory ->
                require(directory.canonicalFile.isInside(root)) { "任务目录包含符号链接，无法复制" }
                true
            }.onFail { _, error -> throw error }.forEach { entry ->
                val relative = entry.relativeTo(source).path.replace('\\', '/')
                if (relative == "history.jsonl" || relative == "flow.draft.json") return@forEach
                require(entry.canonicalFile.isInside(root)) { "任务文件包含符号链接，无法复制" }
                val destination = File(staging, relative)
                if (entry.isDirectory) check(destination.isDirectory || destination.mkdirs()) { "无法复制任务目录" }
                else entry.copyTo(destination, overwrite = false)
            }
            File(staging, "config.json").writeText(config.toString(2) + "\n")
            check(!target.exists()) { "该任务名称已存在" }
            check(staging.renameTo(target)) { "无法创建任务副本" }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }
}

/** 判断文件是否位于 root 目录内（含 root 本身）；调用前需先取 canonicalFile 解析符号链接。 */
private fun File.isInside(root: File): Boolean =
    path == root.path || path.startsWith(root.path + File.separator)
