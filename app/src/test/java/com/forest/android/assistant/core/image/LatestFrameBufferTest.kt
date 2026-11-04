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

    @Test fun awaitReturnsImmediatelyOrTimesOutWithoutPolling() {
        val buffer = LatestFrameBuffer<Frame, Int> { it.value }
        buffer.replace(Frame(1), 100)
        // 已有更新的帧时立即返回
        assertTrue(buffer.await(99, 0))
        // 过期帧只等到超时，返回 false
        val startedAt = System.nanoTime()
        assertFalse(buffer.await(100, 30))
        assertTrue("应等待到接近超时", (System.nanoTime() - startedAt) / 1_000_000 >= 20)
    }

    @Test fun awaitWakesUpWhenNewFrameArrives() {
        val buffer = LatestFrameBuffer<Frame, Int> { it.value }
        buffer.replace(Frame(1), 100)
        val entered = java.util.concurrent.CountDownLatch(1)
        val woke = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiter = Thread {
            entered.countDown()
            woke.set(buffer.await(100, 2000))
        }
        waiter.start()
        entered.await()
        Thread.sleep(20)
        buffer.replace(Frame(2), 200)
        waiter.join(2000)
        assertTrue("新帧到达后应立即唤醒等待方", woke.get())
    }

    @Test fun useSourceReadsRawFrameOnlyWhenNewerAndUnconverted() {
        val buffer = LatestFrameBuffer<Frame, Int> { it.value }
        val frame = Frame(7)
        buffer.replace(frame, 10)
        // 没有比 10 更新的帧
        assertNull(buffer.useSource(10) { it.value })
        assertEquals(7, buffer.useSource(9) { it.value })
        // 已转换后不再保留原始帧，调用方需回退整屏路径
        assertEquals(7, buffer.read(9))
        assertNull(buffer.useSource(9) { it.value })
        assertTrue(frame.closed)
    }
}
