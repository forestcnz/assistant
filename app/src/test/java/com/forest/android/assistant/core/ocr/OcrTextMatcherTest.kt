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

    @Test fun findContainsAnyReturnsFirstMatchingLineWithMatchedKeyword() {
        val first = item("请点击确定按钮")
        val second = item("不同意")
        val match = OcrTextMatcher.findContainsAny(listOf(first, second), listOf("同意", "确定"))
        // 行顺序优先：第 1 行命中「确定」，即使「同意」在关键词数组里更靠前
        assertSame(first, match?.item)
        assertEquals("确定", match?.keyword)
    }

    @Test fun findContainsAnyPrefersEarlierKeywordWithinSameLine() {
        val line = item("同意 或 确定")
        val match = OcrTextMatcher.findContainsAny(listOf(line), listOf("确定", "同意"))
        // 同一行内取数组顺序靠前的关键词
        assertEquals("确定", match?.keyword)
        assertSame(line, match?.item)
    }

    @Test fun findContainsAnyIgnoresWhitespacePunctuationAndFullWidth() {
        val line = item("【确 定】")
        assertEquals("确定", OcrTextMatcher.findContainsAny(listOf(line), listOf("确定"))?.keyword)
        val fullWidth = item("ＶＩＰ－１２３")
        assertEquals("vip123", OcrTextMatcher.findContainsAny(listOf(fullWidth), listOf("vip123"))?.keyword)
    }

    @Test fun findContainsAnySkipsInvalidKeywordsAndReturnsNullWhenNoneValid() {
        val line = item("确定")
        // 无效关键词被忽略，后面的有效关键词仍能命中
        assertEquals("确定", OcrTextMatcher.findContainsAny(listOf(line), listOf(" ", "★", "确定"))?.keyword)
        // 全部清理后为空：返回 null，且不匹配任何行
        assertNull(OcrTextMatcher.findContainsAny(listOf(line), listOf("", "★！", "  ")))
        assertNull(OcrTextMatcher.findContainsAny(listOf(line), emptyList()))
        assertNull(OcrTextMatcher.findContainsAny(emptyList(), listOf("确定")))
    }

    @Test fun findContainsAnyDoesNotJoinSeparateLines() {
        val lines = listOf(item("立即"), item("购买"))
        assertNull(OcrTextMatcher.findContainsAny(lines, listOf("立即购买")))
        assertEquals("购买", OcrTextMatcher.findContainsAny(lines, listOf("购买"))?.keyword)
    }
}
