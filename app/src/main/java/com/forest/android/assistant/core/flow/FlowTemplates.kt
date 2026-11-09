package com.forest.android.assistant.core.flow

import android.content.Context
import android.util.AtomicFile
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 设备上的共享模块模板，不出现在可执行任务列表中。 */
object FlowTemplates {
    private const val ROOT = ".flow-templates"
    data class Info(val id: String, val name: String, val description: String, val revision: Long, val nodeCount: Int)

    fun root(context: Context): File = File(TaskManager.tasksRoot(context), ROOT).apply {
        check(!File(this, "task.js").exists()) { "模板目录与现有任务冲突" }
        check(mkdirs() || isDirectory) { "无法创建模板目录" }
    }

    fun resourceName(id: String): String {
        require(UUID.fromString(id).toString() == id) { "模板 ID 不合法" }
        return "$ROOT/$id"
    }

    fun directory(context: Context, id: String): File = TaskManager.taskDir(context, resourceName(id))

    fun list(context: Context): List<Info> = synchronized(TaskManager) {
        root(context).listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.map { get(context, it.name) }?.sortedBy { it.name }.orEmpty()
    }

    fun get(context: Context, id: String): Info = synchronized(TaskManager) {
        val directory = directory(context, id)
        if (!File(directory, "config.json").isFile) throw NoSuchElementException("模板不存在，请刷新列表")
        val config = JSONObject(AtomicFile(File(directory, "config.json")).openRead().bufferedReader().use { it.readText() })
        val document = JSONObject(AtomicFile(File(directory, "flow.json")).openRead().bufferedReader().use { it.readText() })
        Info(id, config.getString("name"), config.optString("desc"), config.getLong("revision"),
            document.getJSONArray("nodes").length())
    }

    fun create(context: Context, name: String, description: String, source: String,
               sourceTask: String?, sourceTemplate: String?): Info = synchronized(TaskManager) {
        val title = checkedName(context, name)
        require(description.length <= 1000) { "模板说明不能超过 1000 个字符" }
        val document = FlowTemplateContent.parse(source)
        val id = UUID.randomUUID().toString()
        val temporary = File(root(context), ".create-$id")
        try {
            check(temporary.mkdirs()) { "无法创建模板" }
            if (FlowTemplateContent.imageNames(document).isNotEmpty()) {
                val origin = when {
                    sourceTemplate != null -> { get(context, sourceTemplate); resourceName(sourceTemplate) }
                    sourceTask != null && TaskManager.listTasks(context).any { it.name == sourceTask } -> sourceTask
                    else -> error("请先为模板选择素材")
                }
                FlowTemplateContent.copyImages(document, TaskManager.imagesDir(context, origin), File(temporary, "images"))
            }
            write(File(temporary, "flow.json"), document.toString(2))
            write(File(temporary, "task.js"), FlowTemplateContent.preview(document))
            write(File(temporary, "config.json"), JSONObject().put("name", title).put("desc", description.trim())
                .put("revision", 1).toString(2))
            check(temporary.renameTo(directory(context, id))) { "无法保存模板" }
            get(context, id)
        } finally {
            temporary.deleteRecursively()
        }
    }

    fun save(context: Context, id: String, revision: Long, source: String): Info = synchronized(TaskManager) {
        checked(context, id, revision)
        val document = FlowTemplateContent.parse(source)
        val taskName = resourceName(id)
        FlowTemplateContent.imageNames(document).forEach { name ->
            require(File(TaskManager.imagesDir(context, taskName), name).isFile) { "模板素材不存在：$name" }
        }
        val directory = directory(context, id)
        val flow = File(directory, "flow.json")
        val script = File(directory, "task.js")
        val oldFlow = flow.readText()
        val oldScript = script.readText()
        try {
            write(flow, document.toString(2))
            write(script, FlowTemplateContent.preview(document))
            touch(context, id)
        } catch (error: Exception) {
            try { write(flow, oldFlow); write(script, oldScript) } catch (rollback: Exception) { error.addSuppressed(rollback) }
            throw error
        }
    }

    fun update(context: Context, id: String, revision: Long, name: String, description: String): Info = synchronized(TaskManager) {
        checked(context, id, revision)
        val title = checkedName(context, name, id)
        require(description.length <= 1000) { "模板说明不能超过 1000 个字符" }
        val configFile = File(directory(context, id), "config.json")
        val config = JSONObject(configFile.readText()).put("name", title).put("desc", description.trim()).put("revision", revision + 1)
        write(configFile, config.toString(2))
        get(context, id)
    }

    fun touch(context: Context, id: String): Info = synchronized(TaskManager) {
        val info = get(context, id)
        val file = File(directory(context, id), "config.json")
        write(file, JSONObject(file.readText()).put("revision", info.revision + 1).toString(2))
        get(context, id)
    }

    fun checked(context: Context, id: String, revision: Long): Info {
        val info = get(context, id)
        if (info.revision != revision) throw ConcurrentModificationException("模板已被修改，请重新打开后再保存")
        return info
    }

    fun delete(context: Context, id: String, revision: Long) = synchronized(TaskManager) {
        checked(context, id, revision)
        val removed = File(root(context), ".deleted-${UUID.randomUUID()}")
        check(directory(context, id).renameTo(removed)) { "无法删除模板" }
        if (!removed.deleteRecursively()) android.util.Log.w("ScriptJs", "模板已删除，部分暂存文件未清理")
    }

    fun insert(context: Context, id: String, targetTask: String?, targetTemplate: String?): JSONObject = synchronized(TaskManager) {
        get(context, id)
        val target = if (targetTemplate != null) {
            get(context, targetTemplate)
            resourceName(targetTemplate)
        } else {
            require(targetTask != null && TaskManager.listTasks(context).any { it.name == targetTask }) { "目标任务不存在" }
            targetTask
        }
        val document = JSONObject(FlowCompiler.sourceFile(context, resourceName(id)).readText())
        val mapping = FlowTemplateContent.copyImages(document, TaskManager.imagesDir(context, resourceName(id)), TaskManager.imagesDir(context, target))
        FlowTemplateContent.remapImages(document, mapping)
    }

    fun toJson(info: Info): JSONObject = JSONObject().put("id", info.id).put("name", info.name)
        .put("description", info.description).put("revision", info.revision).put("nodeCount", info.nodeCount)

    private fun checkedName(context: Context, rawName: String, exceptId: String? = null): String {
        val name = rawName.trim()
        require(name.isNotEmpty() && name.length <= 50 && name.none(Char::isISOControl)) { "模板名称需为 1～50 个字符" }
        require(list(context).none { it.id != exceptId && it.name == name }) { "该模板名称已存在" }
        return name
    }

    private fun write(file: File, source: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write((source + "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
