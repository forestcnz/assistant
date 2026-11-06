package com.forest.android.assistant.core.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLoopTest {
    @Test fun oldSettingsDefaultToOneExecution() {
        assertEquals(TaskLoop.Mode.COUNT, TaskLoop.Mode.fromKey(""))
        assertEquals(TaskLoop.Mode.COUNT, TaskLoop.Mode.fromKey("unknown"))
        assertTrue(TaskLoop().shouldStartNext(0, 0))
        assertFalse(TaskLoop().shouldStartNext(1, 0))
    }

    @Test fun countIgnoresElapsedTimeAndStopsAtLimit() {
        val loop = TaskLoop(mode = TaskLoop.Mode.COUNT, count = 3)
        assertTrue(loop.shouldStartNext(2, Long.MAX_VALUE))
        assertFalse(loop.shouldStartNext(3, 0))
    }

    @Test fun durationStopsAtBoundaryEvenDuringLongInterval() {
        val loop = TaskLoop(mode = TaskLoop.Mode.DURATION, durationSec = 60, intervalSec = 120)
        assertTrue(loop.shouldStartNext(10, 59_999))
        assertEquals(1L, loop.nextDelayMs(59_999))
        assertFalse(loop.shouldStartNext(10, 60_000))
        assertEquals(0L, loop.nextDelayMs(60_000))
    }

    @Test fun unlimitedModeContinuesAfterAnyNumberOfRounds() {
        val loop = TaskLoop(mode = TaskLoop.Mode.UNTIL_STOPPED)
        assertTrue(loop.shouldStartNext(1_000_000, Long.MAX_VALUE))
    }
}
