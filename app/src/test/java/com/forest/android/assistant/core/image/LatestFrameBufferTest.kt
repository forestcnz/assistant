package com.forest.android.assistant.core.image

import org.junit.Assert.*
import org.junit.Test

class LatestFrameBufferTest {
    private class Frame(val value: Int) : AutoCloseable {
        var closed = false
        override fun close() { check(!closed); closed = true }
    }

    @Test fun idleFramesAreClosedWithoutConversionAndRepeatedReadsReuseResult() {
        var conversions = 0
        val buffer = LatestFrameBuffer<Frame, List<Int>> { conversions++; listOf(it.value) }
        val first = Frame(1)
        val second = Frame(2)
        buffer.replace(first, 100)
        buffer.replace(second, 200)
        assertTrue(first.closed)
        assertEquals(0, conversions)
        val snapshot = buffer.read()!!
        assertEquals(listOf(2), snapshot)
        assertTrue(second.closed)
        assertSame(snapshot, buffer.read())
        assertEquals(1, conversions)
        buffer.replace(Frame(3), 300)
        assertEquals(listOf(3), buffer.read())
        assertEquals(listOf(2), snapshot)
    }

    @Test fun freshnessAndRotationNeverReturnOldFrames() {
        var conversions = 0
        val buffer = LatestFrameBuffer<Frame, Int> { conversions++; it.value }
        val first = Frame(1)
        buffer.replace(first, 200)
        assertNull(buffer.read(200))
        assertEquals(0, conversions)
        assertEquals(1, buffer.read(199))
        assertNull(buffer.read(200))
        val pending = Frame(2)
        buffer.replace(pending, 300)
        buffer.clear()
        assertTrue(pending.closed)
        assertNull(buffer.read())
        buffer.clear()
    }

    @Test fun failedConversionCanRetryAndStillReleasesSource() {
        var fail = true
        val buffer = LatestFrameBuffer<Frame, Int> {
            if (fail) error("conversion failed")
            it.value
        }
        val frame = Frame(4)
        buffer.replace(frame, 10)
        assertThrows(IllegalStateException::class.java) { buffer.read() }
        assertFalse(frame.closed)
        fail = false
        assertEquals(4, buffer.read())
        assertTrue(frame.closed)
    }
}
