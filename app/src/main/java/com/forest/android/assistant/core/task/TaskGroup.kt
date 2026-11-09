package com.forest.android.assistant.core.task

/** taskNames 同时表示成员和执行顺序；不同分组可引用同一任务。 */
data class TaskGroup(
    val id: String,
    val name: String,
    val taskNames: List<String> = emptyList(),
    val revision: Long = 1
) {
    fun updated(rawName: String, members: List<String>, available: Set<String>): TaskGroup {
        val name = rawName.trim()
        require(name.isNotEmpty() && name.length <= 50 && name.none(Char::isISOControl)) { "分组名称需为 1～50 个字符" }
        require(members.distinct().size == members.size) { "分组内不能重复添加同一任务" }
        require(members.all { it in available }) { "部分任务已不存在，请刷新列表" }
        return copy(name = name, taskNames = members.toList(), revision = revision + 1)
    }

    fun replaceTask(oldName: String, newName: String?): TaskGroup {
        if (oldName !in taskNames || oldName == newName) return this
        val members = taskNames.mapNotNull { if (it == oldName) newName else it }.distinct()
        return copy(taskNames = members, revision = revision + 1)
    }

    /** 分组执行始终遵循组内顺序，勾选先后不影响执行顺序。 */
    fun selectedTasks(selected: Collection<String>): List<String> {
        val names = selected.toSet()
        return taskNames.filter { it in names }
    }
}

object TaskGroupImport {
    data class Plan(val groups: List<TaskGroup>, val restored: List<String>, val skipped: List<String>)

    /** 重复导入保留本机分组；新分组按备份顺序恢复，过滤未成功安装的任务。 */
    fun plan(existing: List<TaskGroup>, incoming: List<TaskGroup>, available: Set<String>): Plan {
        val groups = existing.toMutableList()
        val restored = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (group in incoming) {
            if (groups.any { it.id == group.id || it.name == group.name }) {
                skipped += "分组「${group.name}」已存在"
                continue
            }
            val members = group.taskNames.filter(available::contains)
            if (members.size != group.taskNames.size) skipped += "分组「${group.name}」的部分任务不可用，已跳过这些成员"
            groups += group.copy(taskNames = members, revision = 1)
            restored += group.name
        }
        return Plan(groups, restored, skipped)
    }
}
