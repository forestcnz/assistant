package com.forest.android.assistant.core.task

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 当前素材与历史版本之间只做同一文件系统内的移动，失败时保留或恢复原位置。 */
object ImageHistory {
    data class Entry(val file: File, val originalName: String, val archivedAt: String)

    private val versionPrefix = Regex("""^(\d{8}_\d{9})_[a-f0-9]{8}__""")

    fun imagesDirectory(context: Context, taskName: String): File {
        val root = TaskManager.tasksRoot(context).canonicalFile
        val task = File(root, taskName).canonicalFile
        require(taskName.isNotBlank() && task.parentFile == root && File(task, "task.js").isFile) { "任务不存在" }
        val images = File(task, "images").canonicalFile
        require(images.parentFile == task) { "素材目录无效" }
        return images
    }

    fun source(context: Context, taskName: String, imageName: String): File {
        val directory = imagesDirectory(context, taskName)
        val file = File(directory, imageName).canonicalFile
        require(imageName.isNotBlank() && file.parentFile == directory && file.isFile) { "图片不存在或已被移除" }
        return file
    }

    private fun historyDirectory(images: File): File {
        val history = File(images, "history").canonicalFile
        require(history.parentFile == images) { "历史素材目录无效" }
        return history
    }

    fun createPendingFile(context: Context, taskName: String): File {
        val history = historyDirectory(imagesDirectory(context, taskName))
        check(history.isDirectory || history.mkdirs()) { "无法创建历史素材目录" }
        return File.createTempFile(".crop-", ".tmp", history)
    }

    @Synchronized
    fun list(context: Context, taskName: String): List<Entry> {
        val history = historyDirectory(imagesDirectory(context, taskName))
        return history.listFiles { file -> file.isFile && !file.name.startsWith('.') }
            ?.map { file ->
                val prefix = versionPrefix.find(file.name)
                val stamp = prefix?.groupValues?.get(1)
                val date = if (stamp != null) {
                    "${stamp.substring(0, 4)}-${stamp.substring(4, 6)}-${stamp.substring(6, 8)} " +
                        "${stamp.substring(9, 11)}:${stamp.substring(11, 13)}:${stamp.substring(13, 15)}.${stamp.substring(15)}"
                } else {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
                }
                Entry(file, prefix?.let { file.name.substring(it.value.length) } ?: file.name, date)
            }
            ?.sortedByDescending { it.archivedAt } ?: emptyList()
    }

    /** 裁剪结果已完整编码到 pending，再迁移原图并替换，脚本路径保持不变。 */
    @Synchronized
    fun replace(context: Context, taskName: String, imageName: String, pending: File): File {
        val current = source(context, taskName, imageName)
        val history = historyDirectory(current.parentFile!!)
        require(pending.canonicalFile.parentFile == history && pending.isFile && pending.length() > 0) { "裁剪结果无效" }
        val archived = archive(current, history)
        if (!pending.renameTo(current)) {
            check(archived.renameTo(current)) { "保存失败，原图已保留在历史素材中，可移回" }
            error("保存失败，原图已恢复")
        }
        return current
    }

    /** 移回历史版本，直接覆盖当前同名素材。 */
    @Synchronized
    fun moveBack(context: Context, taskName: String, entry: Entry): File {
        val images = imagesDirectory(context, taskName)
        val history = historyDirectory(images)
        val archived = entry.file.canonicalFile
        require(archived.parentFile == history && archived.isFile && !archived.name.startsWith('.')) { "历史图片不存在" }
        val prefix = versionPrefix.find(archived.name)
        val originalName = prefix?.let { archived.name.substring(it.value.length) } ?: archived.name
        val target = File(images, originalName).canonicalFile
        require(originalName.isNotBlank() && target.parentFile == images && (!target.exists() || target.isFile)) { "无法移回该图片" }
        // Android 同一文件系统的 rename 会原子替换目标，失败时两个文件保持原状。
        check(archived.renameTo(target)) { "移回失败，请重试" }
        return target
    }

    private fun archive(current: File, history: File): File {
        check(history.isDirectory || history.mkdirs()) { "无法创建历史素材目录" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        var target: File
        do {
            val token = UUID.randomUUID().toString().substring(0, 8)
            target = File(history, "${stamp}_${token}__${current.name}")
        } while (target.exists())
        check(current.renameTo(target)) { "无法迁移原图到历史素材，操作已取消" }
        return target
    }
}
