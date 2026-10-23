package com.forest.android.assistant.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OcrTextMatcherTest {
    private fun item(text: String) = OcrApi.Item(text, 10, 20, 210, 80, 0.9f)

    @Test fun ignoresWhitespacePunctuationSymbolsAndInvisibleCharactersOnBothSides() {
        val original = item("优惠：【立\u00a0即\u3000·购\t买】\n¥9.90★❤️\u200b")
        assertSame(original, OcrTextMatcher.findContains(listOf(original), " 立\n即-购_买！ "))
        assertEquals("优惠立即购买990", OcrTextMatcher.normalize(original.text))
    }

    @Test fun normalizesFullWidthCharactersAndIgnoresCase() {
        val original = item("【限时】ＶＩＰ－１２３")
        assertSame(original, OcrTextMatcher.findContains(listOf(original), "vip123"))
        assertSame(original, OcrTextMatcher.findContains(listOf(original), "ＶｉＰ　１２３"))
    }

    @Test fun returnsFirstContainingLineWithoutPreferringLaterExactMatches() {
        val first = item("请立即·购买商品")
        val exact = item("立即购买")
        assertSame(first, OcrTextMatcher.findContains(listOf(first, exact), "立即购买"))
        assertEquals("请立即·购买商品", first.text)
        assertEquals(110, first.centerX())
        assertEquals(50, first.centerY())
        assertEquals(0.9f, first.confidence)
    }

    @Test fun emptyOrSymbolOnlyQueriesNeverMatch() {
        for (query in listOf("", " \t\n\u3000", "【】！—_\u200b", "❤\uFE0F")) {
            assertNull(OcrTextMatcher.findContains(listOf(item("购买")), query))
        }
    }

    @Test fun doesNotJoinSeparateOcrLinesOrMatchMissingText() {
        val lines = listOf(item("立即"), item("购买"))
        assertNull(OcrTextMatcher.findContains(lines, "立即购买"))
        assertNull(OcrTextMatcher.findContains(lines, "提交"))
        assertNull(OcrTextMatcher.findContains(emptyList(), "购买"))
    }

    @Test fun retainsUnicodeLettersAndNumbersIncludingSupplementaryHan() {
        val text = "𠮷-é-中文-１２３"
        assertEquals("𠮷é中文123", OcrTextMatcher.normalize(text))
        val original = item(text)
        assertSame(original, OcrTextMatcher.findContains(listOf(original), "𠮷é中文123"))
    }
}
