package com.forest.android.assistant.core.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenGrayCacheTest {
    @Test fun sameImageRegionAndScaleReusePixelsButDifferentKeysDoNot() {
        val cache = ScreenGrayCache()
        val key = ScreenGrayCache.Key(4, 10, 20, 30, 40, 1)
        val first = cache.getOrPut(key) { intArrayOf(1, 2, 3) }
        assertSame(first, cache.getOrPut(key) { error("cache miss") })
        assertNotSame(first, cache.getOrPut(key.copy(scale = 2)) { intArrayOf(1, 2, 3) })
        assertNotSame(first, cache.getOrPut(key.copy(generationId = 1)) { intArrayOf(1, 2, 3) })
        assertNotSame(first, cache.getOrPut(key.copy(left = 11)) { intArrayOf(1, 2, 3) })
    }

    @Test fun releaseAndClearDiscardEntriesAndLruRespectsByteLimit() {
        val cache = ScreenGrayCache(maxBytes = 16)
        val firstKey = ScreenGrayCache.Key(1, 0, 0, 2, 2, 1)
        val secondKey = firstKey.copy(imageId = 2)
        val first = cache.getOrPut(firstKey) { intArrayOf(1, 2, 3, 4) }
        cache.getOrPut(secondKey) { intArrayOf(5, 6, 7, 8) }
        assertNotSame(first, cache.getOrPut(firstKey) { intArrayOf(9, 9, 9, 9) })
        cache.removeImage(2)
        val replacement = cache.getOrPut(secondKey) { intArrayOf(5, 6, 7, 8) }
        cache.clear()
        assertArrayEquals(intArrayOf(5, 6, 7, 8), replacement)
        assertNotSame(replacement, cache.getOrPut(secondKey) { intArrayOf(5, 6, 7, 8) })
    }

    @Test fun oversizedPixelsAreReturnedButNotRetained() {
        val cache = ScreenGrayCache(maxBytes = 4)
        val key = ScreenGrayCache.Key(1, 0, 0, 2, 2, 1)
        val first = cache.getOrPut(key) { intArrayOf(1, 2) }
        val second = cache.getOrPut(key) { intArrayOf(1, 2) }
        assertNotSame(first, second)
    }

    @Test fun pixelsComputedAfterImageReleaseAreNotRetained() {
        val cache = ScreenGrayCache()
        val key = ScreenGrayCache.Key(9, 0, 0, 2, 2, 1)
        val releasedPixels = cache.getOrPut(key,
            create = { intArrayOf(1, 2, 3, 4) }, shouldRetain = { false })
        val currentPixels = cache.getOrPut(key) { intArrayOf(5, 6, 7, 8) }
        assertNotSame(releasedPixels, currentPixels)
        assertSame(currentPixels, cache.getOrPut(key) { error("cache miss") })
    }
}
