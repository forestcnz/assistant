package com.forest.android.assistant.core.image

/** 只缓存不可变灰度像素数组，不保存 Bitmap 或调用方的取消回调。 */
internal class ScreenGrayCache(private val maxBytes: Long = 16L * 1024 * 1024) {
    data class Key(
        val imageId: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val scale: Int,
        val generationId: Int = 0
    )

    private val entries = LinkedHashMap<Key, IntArray>(16, 0.75f, true)
    private var bytes = 0L

    fun getOrPut(key: Key, create: () -> IntArray): IntArray =
        getOrPut(key, shouldRetain = { true }, create = create)

    fun getOrPut(key: Key, shouldRetain: () -> Boolean, create: () -> IntArray): IntArray {
        synchronized(this) { entries[key]?.let { return it } }
        val pixels = create()
        val size = pixels.size.toLong() * 4L
        if (size > maxBytes) return pixels
        synchronized(this) {
            entries[key]?.let { return it }
            // ImageApi checks that the image handle still points at the same Bitmap.
            // If release raced with preprocessing, return this call's pixels without caching them.
            if (!shouldRetain()) return pixels
            while (bytes + size > maxBytes && entries.isNotEmpty()) {
                val eldest = entries.entries.iterator().next()
                bytes -= eldest.value.size.toLong() * 4L
                entries.remove(eldest.key)
            }
            entries[key] = pixels
            bytes += size
        }
        return pixels
    }

    @Synchronized
    fun removeImage(imageId: Int) {
        val keys = entries.keys.filter { it.imageId == imageId }
        keys.forEach { key ->
            entries.remove(key)?.let { bytes -= it.size.toLong() * 4L }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
    }
}
