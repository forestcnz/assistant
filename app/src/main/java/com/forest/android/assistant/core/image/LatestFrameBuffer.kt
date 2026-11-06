package com.forest.android.assistant.core.image

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 持有一帧原始资源，按需转换；已返回的结果不回收、不改写。
 * 新增 [await] / [useSource]：前者等到新帧到达即可继续（避免固定间隔轮询的额外延迟），
 * 后者允许调用方直接从原始帧裁切需要的区域，避免整屏 Bitmap 分配。
 */
internal class LatestFrameBuffer<T : AutoCloseable, R>(private val convert: (T) -> R) {
    /** 显式锁 + 条件变量：等待新帧时挂起线程，帧到达立即唤醒，不做轮询 */
    private val lock = ReentrantLock()
    private val frameArrived = lock.newCondition()
    private var source: T? = null
    private var result: R? = null
    private var timestamp = Long.MIN_VALUE

    fun replace(frame: T, uptimeMs: Long) = lock.withLock {
        source?.close()
        source = frame
        result = null
        timestamp = uptimeMs
        // 唤醒等待新帧的调用方（录制截屏），省去轮询间隔
        frameArrived.signalAll()
    }

    fun read(afterExclusive: Long = Long.MIN_VALUE): R? = lock.withLock {
        if (timestamp <= afterExclusive) return null
        result?.let { return it }
        val frame = source ?: return null
        return convert(frame).also {
            result = it
            source = null
            frame.close()
        }
    }

    /**
     * 等到时间戳晚于 [afterExclusive] 的帧到达；超时或有中断时返回 false。
     * 只负责等待，不转换帧，调用方随后用 [read] 取结果。
     */
    fun await(afterExclusive: Long, timeoutMs: Long): Boolean = lock.withLock {
        if (timestamp > afterExclusive) return true
        if (timeoutMs <= 0) return false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (timestamp <= afterExclusive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            try {
                frameArrived.awaitNanos(remaining)
            } catch (e: InterruptedException) {
                // 保留中断标记，交给调用方按超时处理
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    /**
     * 在持锁状态下直接访问未转换的原始帧，块内不要保存引用。
     * 返回 null 表示没有更新的帧，或该帧已被 [read] 转换（此时已没有原始资源）。
     */
    fun <X> useSource(afterExclusive: Long = Long.MIN_VALUE, block: (T) -> X): X? = lock.withLock {
        if (timestamp <= afterExclusive) return null
        val frame = source ?: return null
        return block(frame)
    }

    fun clear() = lock.withLock {
        source?.close()
        source = null
        result = null
        timestamp = Long.MIN_VALUE
    }
}
