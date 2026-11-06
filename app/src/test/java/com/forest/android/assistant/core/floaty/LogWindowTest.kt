package com.forest.android.assistant.core.floaty

import org.junit.Assert.*
import org.junit.Test

class LogWindowTest {
    @Test fun snapshotGateSkipsOnlyUnchangedSnapshotAndFilter() {
        val gate = LogSnapshotGate<Long>()
        val first = listOf(1L, 2L)

        assertTrue(gate.shouldRefresh(first, null))
        assertFalse(gate.shouldRefresh(first, null))
        assertTrue(gate.shouldRefresh(first, 'E'))
        assertFalse(gate.shouldRefresh(first, 'E'))
        assertTrue(gate.shouldRefresh(listOf(1L, 2L), 'E'))
        gate.invalidate()
        assertTrue(gate.shouldRefresh(first, 'E'))
    }

    @Test fun rollingStorageKeepsUpdatingBeyond300AndNeverRendersMoreThan200() {
        val window = LogWindow<Long>(200) { it }
        val rendered = ArrayList<Long>()
        for (last in 1L..1000L) {
            val logs = (maxOf(1, last - 299)..last).toList()
            val change = window.update(logs)
            repeat(change.removeCount) { rendered.removeAt(0) }
            rendered.addAll(change.append)
            assertEquals(logs.takeLast(200), rendered)
            assertTrue(rendered.size <= 200)
        }
        val unchanged = window.update((701L..1000L).toList())
        assertEquals(0, unchanged.removeCount)
        assertTrue(unchanged.append.isEmpty())
    }

    @Test fun missedBatchesClearAndSameSizeReplacementDropStaleRows() {
        val window = LogWindow<Long>(200) { it }
        window.update((1L..300L).toList())
        val missed = window.update((601L..900L).toList())
        assertEquals(200, missed.removeCount)
        assertEquals((701L..900L).toList(), missed.append)
        val cleared = window.update(emptyList())
        assertEquals(200, cleared.removeCount)
        assertTrue(cleared.append.isEmpty())
        assertEquals(listOf(901L), window.update(listOf(901L)).append)
        window.clear()
        assertEquals(listOf(901L), window.update(listOf(901L)).append)
    }

    @Test fun filteredLogWindowDropsEvictedRowsEvenWithoutNewMatchingRows() {
        val window = LogWindow<Long>(200) { it }
        window.update(listOf(1, 2, 3))
        val change = window.update(listOf(2, 3))
        assertEquals(1, change.removeCount)
        assertTrue(change.append.isEmpty())
        assertEquals(2, window.update(emptyList()).removeCount)
    }
}
