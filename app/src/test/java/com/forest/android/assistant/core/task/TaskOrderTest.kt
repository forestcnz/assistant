package com.forest.android.assistant.core.task

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskOrderTest {
    @Test fun savedOrderWinsAndNewTasksAreAppendedByName() {
        assertEquals(
            listOf("竞技场", "签到", "新任务", "通天塔"),
            TaskOrder.merge(listOf("竞技场", "签到"), listOf("通天塔", "签到", "新任务", "竞技场"))
        )
    }

    @Test fun deletedTasksAndStaleDuplicatesAreDropped() {
        assertEquals(
            listOf("签到", "通天塔"),
            TaskOrder.merge(listOf("已删除", "签到", "签到", "竞技场"), listOf("通天塔", "签到"))
        )
    }

    @Test fun emptySavedOrderFallsBackToNameOrder() {
        assertEquals(
            listOf("竞技场", "签到", "通天塔"),
            TaskOrder.merge(emptyList(), listOf("签到", "通天塔", "竞技场"))
        )
    }
}
