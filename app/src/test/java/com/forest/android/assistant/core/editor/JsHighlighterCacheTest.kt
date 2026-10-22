package com.forest.android.assistant.core.editor

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class JsHighlighterCacheTest {
    @Test fun reusesAnnotatedOutputForSameImmutableTextAndErrorLine() {
        val source = String("let value = 42;".toCharArray())
        val first = JsHighlighter.highlight(source)
        assertSame(first, JsHighlighter.highlight(source))
        assertNotSame(first, JsHighlighter.highlight(source, errorLine = 1))
        assertNotSame(first, JsHighlighter.highlight(source + "\n"))
    }

    @Test fun doesNotRetainLargeScriptsInTheProcessWideCache() {
        val source = "x".repeat(64 * 1024 + 1)
        val first = JsHighlighter.highlight(source)
        assertNotSame(first, JsHighlighter.highlight(source))
    }
}
