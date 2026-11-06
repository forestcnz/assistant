package com.forest.android.assistant.core.image

/** 为同步粗搜复用评分和堆数组；池只保留一组且有总字节上限。 */
internal class MatchBufferPool(private val maxRetainedBytes: Long = 32L * 1024 * 1024) {
    data class Buffer(val heap: LongArray) {
        val capacity: Int get() = heap.size
        val bytes: Long get() = heap.size.toLong() * 8L
    }

    private var available: Buffer? = null

    @Synchronized
    fun acquire(size: Int): Buffer {
        val cached = available
        if (cached != null && cached.capacity >= size) {
            available = null
            return cached
        }
        // 不同时保留一组较小数组和一组较大的数组。
        available = null
        return Buffer(LongArray(size))
    }

    @Synchronized
    fun release(buffers: Buffer) {
        if (buffers.bytes > maxRetainedBytes) return
        val cached = available
        if (cached == null || buffers.capacity > cached.capacity) available = buffers
    }
}
