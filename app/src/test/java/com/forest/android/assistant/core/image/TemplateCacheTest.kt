package com.forest.android.assistant.core.image

import org.junit.Assert.*
import org.junit.Test
import com.forest.android.assistant.core.image.TemplateMatcher.Gray
import com.forest.android.assistant.core.image.TemplateMatcher.PreparedTemplate

class TemplateCacheTest {
    private fun template() = PreparedTemplate(Gray(12, 12, IntArray(144)))

    @Test fun repeatedHandlesReuseDataAndGenerationChangesInvalidate() {
        val cache = TemplateCache()
        val first = cache.getOrPut(1, 10, ::template)
        assertSame(first, cache.getOrPut(1, 10) { error("must reuse") })
        val level = first.level(2) {}
        assertSame(level, first.level(2) { error("must reuse pyramid") })
        assertNotSame(first, cache.getOrPut(1, 11, ::template))
    }

    @Test fun releaseAndClearDiscardPreprocessing() {
        val cache = TemplateCache()
        val first = cache.getOrPut(1, 0, ::template)
        cache.remove(1)
        val second = cache.getOrPut(1, 0, ::template)
        assertNotSame(first, second)
        cache.clear()
        assertNotSame(second, cache.getOrPut(1, 0, ::template))
    }

    @Test fun lruEvictsOldestAndOversizedEntriesAreNotRetained() {
        val cache = TemplateCache(12 * 12 * 8L * 2)
        val first = cache.getOrPut(1, 0, ::template)
        val second = cache.getOrPut(2, 0, ::template)
        assertSame(first, cache.getOrPut(1, 0, ::template))
        cache.getOrPut(3, 0, ::template)
        assertSame(first, cache.getOrPut(1, 0, ::template))
        assertNotSame(second, cache.getOrPut(2, 0, ::template))
        val tiny = TemplateCache(1)
        assertNotSame(tiny.getOrPut(1, 0, ::template), tiny.getOrPut(1, 0, ::template))
    }

    @Test fun cachedLevelsDoNotRetainAnOldCancellationCallback() {
        var cancelled = false
        val prepared = PreparedTemplate(Gray(24, 24, IntArray(576)) { check(!cancelled) })
        prepared.level(2) {}
        cancelled = true
        prepared.level(2) {}.downsample(2)
        prepared.level(3) {}
    }
}
