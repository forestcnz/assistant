package com.forest.android.assistant.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class MatchBufferPoolTest {
    @Test fun reusesExclusiveBuffersWhenCapacityIsSufficient() {
        val pool = MatchBufferPool(1024)
        val first = pool.acquire(16)
        pool.release(first)
        assertSame(first, pool.acquire(8))
        pool.release(first)
        assertSame(first, pool.acquire(16))
    }

    @Test fun doesNotRetainOversizedOrConcurrentBuffers() {
        val pool = MatchBufferPool(64)
        val large = pool.acquire(16)
        pool.release(large)
        val next = pool.acquire(1)
        assertNotSame(large, next)
        pool.release(next)
        assertSame(next, pool.acquire(1))
        assertEquals(1, next.capacity)
    }

    @Test fun aConcurrentBorrowGetsSeparateArrays() {
        val pool = MatchBufferPool(1024)
        val first = pool.acquire(10)
        val second = pool.acquire(10)
        assertNotSame(first, second)
        pool.release(first)
        pool.release(second)
        val reused = pool.acquire(10)
        assertSame(first, reused)
    }
}
