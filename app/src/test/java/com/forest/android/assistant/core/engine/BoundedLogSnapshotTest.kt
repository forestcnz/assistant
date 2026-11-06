package com.forest.android.assistant.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedLogSnapshotTest {
    @Test fun appendPreservesEveryPublishedSnapshotOrderAndLimit() {
        val buffer = BoundedLogSnapshot<Int>(3)
        var snapshot = emptyList<Int>()
        val history = mutableListOf<List<Int>>()
        for (value in 1..8) {
            snapshot = buffer.append(snapshot, value)
            history += snapshot
        }
        assertEquals(listOf(1), history[0])
        assertEquals(listOf(1, 2, 3), history[2])
        for (value in 1..8) {
            assertEquals((maxOf(1, value - 2)..value).toList(), history[value - 1])
        }
        assertEquals((6..8).toList(), snapshot)
    }

    @Test fun limitOneKeepsOnlyLatestEntry() {
        val buffer = BoundedLogSnapshot<Int>(1)
        assertEquals(listOf(2), buffer.append(buffer.append(emptyList(), 1), 2))
    }
}
