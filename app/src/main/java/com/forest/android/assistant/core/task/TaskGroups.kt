package com.forest.android.assistant.core.task

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** 与任务改名、删除共用 TaskManager 的锁，避免分组成员引用处于迁移中的任务。 */
object TaskGroups {
    private fun file(context: Context) = AtomicFile(File(TaskManager.tasksRoot(context), ".task-groups.json"))

    fun list(context: Context): List<TaskGroup> = synchronized(TaskManager) {
        val available = TaskManager.listTasks(context).map { it.name }.toSet()
        read(context).map { it.copy(taskNames = it.taskNames.filter(available::contains)) }
    }

    fun create(context: Context, name: String, members: List<String>): TaskGroup = synchronized(TaskManager) {
        val groups = read(context)
        val group = TaskGroup(UUID.randomUUID().toString(), "", revision = 0)
            .updated(name, members, TaskManager.listTasks(context).map { it.name }.toSet())
        require(groups.none { it.name == group.name }) { "该分组名称已存在" }
        write(context, groups + group)
        group
    }

    fun update(context: Context, id: String, revision: Long, name: String, members: List<String>): TaskGroup =
        synchronized(TaskManager) {
            val groups = read(context)
            val old = checkedGroup(groups, id, revision)
            val group = old.updated(name, members, TaskManager.listTasks(context).map { it.name }.toSet())
            require(groups.none { it.id != id && it.name == group.name }) { "该分组名称已存在" }
            write(context, groups.map { if (it.id == id) group else it })
            group
        }

    fun delete(context: Context, id: String, revision: Long) = synchronized(TaskManager) {
        val groups = read(context)
        checkedGroup(groups, id, revision)
        write(context, groups.filterNot { it.id == id })
    }

    private fun checkedGroup(groups: List<TaskGroup>, id: String, revision: Long): TaskGroup {
        val group = groups.find { it.id == id } ?: throw NoSuchElementException("分组不存在，请刷新列表")
        if (group.revision != revision) throw ConcurrentModificationException("分组已被修改，请刷新后重试")
        return group
    }

    internal fun parseBackup(entries: JSONArray, taskNames: Set<String>): List<TaskGroup> {
        val groups = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val id = entry.getString("id")
            require(UUID.fromString(id).toString() == id.lowercase()) { "备份中的分组 ID 不合法" }
            val members = entry.getJSONArray("taskNames")
            TaskGroup(id, "", revision = 0).updated(entry.getString("name"),
                (0 until members.length()).map(members::getString), taskNames)
        }
        require(groups.map { it.id }.distinct().size == groups.size &&
            groups.map { it.name }.distinct().size == groups.size) { "备份中存在重复分组" }
        return groups
    }

    internal fun restore(context: Context, incoming: List<TaskGroup>): TaskGroupImport.Plan = synchronized(TaskManager) {
        val before = read(context)
        val plan = TaskGroupImport.plan(before, incoming, TaskManager.listTasks(context).map { it.name }.toSet())
        if (plan.groups != before) write(context, plan.groups)
        plan
    }

    /** 先保存新引用；任务变更失败时回滚。调用方已持有 TaskManager 锁。 */
    internal fun <T> withTaskReferences(context: Context, oldName: String, newName: String?, action: () -> T): T =
        synchronized(TaskManager) {
            if (oldName == newName) return@synchronized action()
            val before = read(context)
            val after = before.map { it.replaceTask(oldName, newName) }
            if (before == after) return@synchronized action()
            write(context, after)
            try {
                action()
            } catch (error: Exception) {
                try { write(context, before) } catch (rollback: Exception) { error.addSuppressed(rollback) }
                throw error
            }
        }

    private fun read(context: Context): List<TaskGroup> {
        val atomic = file(context)
        val source = try {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: FileNotFoundException) {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return emptyList()
            throw error
        }
        val document = JSONObject(source)
        check(document.getInt("version") == 1) { "不支持的任务分组格式" }
        val groups = document.getJSONArray("groups")
        return (0 until groups.length()).map { index ->
            val item = groups.getJSONObject(index)
            val members = item.getJSONArray("taskNames")
            TaskGroup(item.getString("id"), item.getString("name"),
                (0 until members.length()).map(members::getString), item.getLong("revision"))
        }
    }

    private fun write(context: Context, groups: List<TaskGroup>) {
        val entries = JSONArray()
        groups.forEach { entries.put(toJson(it)) }
        val document = JSONObject().put("version", 1).put("groups", entries)
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

    fun toJson(group: TaskGroup): JSONObject = JSONObject()
        .put("id", group.id).put("name", group.name)
        .put("taskNames", JSONArray(group.taskNames)).put("revision", group.revision)
}
