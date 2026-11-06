package com.forest.android.assistant.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TextParseCacheTest {
    @Test fun sameContentSkipsParsingButPathOrAnyContentChangeInvalidates() {
        val cache = TextParseCache<List<String>>()
        val parsed = TextParseCache.Parsed(listOf("one"))
        cache.put("/tasks/schedules.json", "{}", parsed)
        assertSame(parsed, cache.get("/tasks/schedules.json", "{}"))
        assertEquals(null, cache.get("/other/schedules.json", "{}"))
        assertEquals(null, cache.get("/tasks/schedules.json", "{ }"))
        cache.put("/tasks/schedules.json", "{ }", TextParseCache.Parsed(listOf("two")))
        assertEquals(listOf("two"), cache.get("/tasks/schedules.json", "{ }")?.value)
    }

    @Test fun oversizedInputIsNeverRetainedAndErrorsCanBeCached() {
        val cache = TextParseCache<List<String>>(maxChars = 2)
        val invalid = TextParseCache.Parsed(emptyList<String>(), "invalid JSON")
        cache.put("path", "{}", invalid)
        assertSame(invalid, cache.get("path", "{}"))
        cache.put("path", "long", TextParseCache.Parsed(listOf("ignored")))
        assertEquals(null, cache.get("path", "long"))
        assertNotSame(invalid, cache.get("path", "{}"))
    }
}
