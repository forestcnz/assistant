package com.forest.android.assistant.core.image

/** 持有一帧原始资源，按需转换；已返回的结果不回收、不改写。 */
internal class LatestFrameBuffer<T : AutoCloseable, R>(private val convert: (T) -> R) {
    private var source: T? = null
    private var result: R? = null
    private var timestamp = Long.MIN_VALUE

    @Synchronized
    fun replace(frame: T, uptimeMs: Long) {
        source?.close()
        source = frame
        result = null
        timestamp = uptimeMs
    }

    @Synchronized
    fun read(afterExclusive: Long = Long.MIN_VALUE): R? {
        if (timestamp <= afterExclusive) return null
        result?.let { return it }
        val frame = source ?: return null
        return convert(frame).also {
            result = it
            source = null
            frame.close()
        }
    }

    @Synchronized
    fun clear() {
        source?.close()
        source = null
        result = null
        timestamp = Long.MIN_VALUE
    }
}
