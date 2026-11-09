package com.forest.android.assistant.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskGroupTest {
    private val available = setOf("签到", "竞技场", "通天塔")

    @Test fun membershipAndOrderAreIndependentAcrossGroups() {
        val first = TaskGroup("a", "日常").updated("日常", listOf("竞技场", "签到"), available)
        val second = TaskGroup("b", "战斗").updated("战斗", listOf("通天塔", "竞技场"), available)
        val reordered = first.updated(first.name, first.taskNames.reversed(), available)
        assertEquals(listOf("签到", "竞技场"), reordered.taskNames)
        assertEquals(listOf("通天塔", "竞技场"), second.taskNames)
        assertEquals(listOf("竞技场", "签到"), first.taskNames)
    }

    @Test fun selectedSubsetUsesGroupOrderAndIgnoresUnknownTasks() {
        val group = TaskGroup("a", "日常", listOf("签到", "竞技场", "通天塔"))
        assertEquals(listOf("签到", "通天塔"), group.selectedTasks(listOf("通天塔", "签到", "已删除")))
        assertEquals(emptyList<String>(), group.selectedTasks(emptyList()))
    }

    @Test fun renameAndDeletePreserveRemainingOrder() {
        val group = TaskGroup("a", "日常", listOf("签到", "竞技场", "通天塔"))
        val renamed = group.replaceTask("竞技场", "新竞技场")
        assertEquals(listOf("签到", "新竞技场", "通天塔"), renamed.taskNames)
        assertEquals(group.revision + 1, renamed.revision)
        assertEquals(listOf("签到", "通天塔"), renamed.replaceTask("新竞技场", null).taskNames)
        assertEquals(group, group.replaceTask("未知任务", null))
    }

    @Test fun rejectsDuplicatesMissingTasksAndInvalidNames() {
        val group = TaskGroup("a", "日常")
        assertThrows(IllegalArgumentException::class.java) { group.updated("日常", listOf("签到", "签到"), available) }
        assertThrows(IllegalArgumentException::class.java) { group.updated("日常", listOf("不存在"), available) }
        for (name in listOf("  ", "x".repeat(51), "a\nb")) {
            assertThrows(IllegalArgumentException::class.java) { group.updated(name, emptyList(), available) }
        }
        assertEquals("空分组", group.updated(" 空分组 ", emptyList(), available).name)
    }

    @Test fun restoresSharedMembershipOrderAndEmptyGroups() {
        val incoming = listOf(TaskGroup("a", "日常", listOf("竞技场", "签到")),
            TaskGroup("b", "战斗", listOf("通天塔", "竞技场")), TaskGroup("c", "空分组"))
        val plan = TaskGroupImport.plan(emptyList(), incoming, available)
        assertEquals(incoming, plan.groups)
        assertEquals(listOf("日常", "战斗", "空分组"), plan.restored)
        assertEquals(emptyList<String>(), plan.skipped)
    }

    @Test fun repeatImportPreservesLocalGroupsAndTheirOrder() {
        val local = TaskGroup("a", "日常", listOf("签到", "竞技场"), revision = 8)
        val incoming = listOf(local.copy(name = "旧名称", taskNames = local.taskNames.reversed()),
            TaskGroup("b", "日常", listOf("通天塔")))
        val plan = TaskGroupImport.plan(listOf(local), incoming, available)
        assertEquals(listOf(local), plan.groups)
        assertEquals(emptyList<String>(), plan.restored)
        assertEquals(2, plan.skipped.size)
    }

    @Test fun failedTaskImportsDoNotLeaveDanglingMembers() {
        val incoming = TaskGroup("a", "日常", listOf("竞技场", "已丢失", "签到"))
        val plan = TaskGroupImport.plan(emptyList(), listOf(incoming), available)
        assertEquals(listOf("竞技场", "签到"), plan.groups.single().taskNames)
        assertEquals(1, plan.skipped.size)
    }
}
