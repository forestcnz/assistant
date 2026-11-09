package com.forest.android.assistant.core.task

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
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
            source.walkTopDown().onEnter { directory ->
                require(!Files.isSymbolicLink(directory.toPath())) { "任务目录包含符号链接，无法复制" }
                true
            }.onFail { _, error -> throw error }.forEach { entry ->
                val relative = entry.relativeTo(source).path.replace('\\', '/')
                if (relative == "history.jsonl" || relative == "flow.draft.json") return@forEach
                require(!Files.isSymbolicLink(entry.toPath())) { "任务文件包含符号链接，无法复制" }
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
