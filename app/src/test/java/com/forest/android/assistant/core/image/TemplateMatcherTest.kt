package com.forest.android.assistant.core.image

import com.forest.android.assistant.core.image.TemplateMatcher.Gray
import com.forest.android.assistant.core.image.TemplateMatcher.Match
import com.forest.android.assistant.core.image.TemplateMatcher.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TemplateMatcherTest {
    @Test fun integerDifferenceProducesTheSameDoubleScoreAsLegacyAccumulation() {
        val random = Random(314159)
        repeat(80) {
            val width = random.nextInt(1, 30)
            val height = random.nextInt(1, 25)
            val screen = Gray(width + 3, height + 2,
                IntArray((width + 3) * (height + 2)) { random.nextInt(256) })
            val template = Gray(width, height, IntArray(width * height) { random.nextInt(256) })
            for (y in 0..2) for (x in 0..3) {
                var difference = 0.0
                for (ty in 0 until height) for (tx in 0 until width) {
                    difference += abs(screen.px[(y + ty) * screen.w + x + tx] - template.px[ty * width + tx])
                }
                val expected = (1.0 - (difference / (width * height).toDouble()) / 255.0).coerceIn(0.0, 1.0)
                assertEquals(expected, screen.madSim(x, y, template), 0.0)
            }
        }
    }

    private fun reference(scr: Gray, tpl: Gray, r: Rect, limit: Int): List<Match> {
        val all = ArrayList<Match>()
        for (y in r.top..r.bottom - tpl.h) for (x in r.left..r.right - tpl.w) {
            all.add(Match(x + tpl.w / 2, y + tpl.h / 2, scr.madSim(x, y, tpl)))
        }
        val kept = ArrayList<Match>()
        val distance = max(2, min(tpl.w, tpl.h) / 2)
        for (m in all.sortedByDescending { it.score }) {
            if (kept.size >= limit) break
            if (kept.none { abs(it.x - m.x) < distance && abs(it.y - m.y) < distance }) kept.add(m)
        }
        return kept
    }

    @Test fun heapMatchesOriginalStableSortIncludingTiesAndRegions() {
        val random = Random(42)
        repeat(40) { seed ->
            val screen = Gray(37, 31, IntArray(37 * 31) { if (seed % 3 == 0) 128 else random.nextInt(4) * 60 })
            val template = Gray(3 + seed % 9, 2 + seed % 7,
                IntArray((3 + seed % 9) * (2 + seed % 7)) { if (seed % 3 == 0) 128 else random.nextInt(256) })
            val region = Rect(seed % 4, seed % 5, 36, 30)
            for (limit in listOf(1, 16, 80)) {
                assertEquals(reference(screen, template, region, limit),
                    TemplateMatcher.scanCoarseByMad(screen, template, region, limit))
            }
        }
    }

    private fun fixture(width: Int, height: Int, x: Int, y: Int): Pair<Gray, Gray> {
        val random = Random(width * height)
        val template = Gray(width, height, IntArray(width * height) { index ->
            // 同时包含粗结构、细节和非整倍数边缘，覆盖逐层定位误差。
            ((index % width / 9 * 41 + index / width / 7 * 67 + random.nextInt(25)) % 256)
        })
        val sw = width + 110
        val sh = height + 100
        val pixels = IntArray(sw * sh) { 230 }
        for (row in 0 until height) template.px.copyInto(pixels, (y + row) * sw + x, row * width, (row + 1) * width)
        return Gray(sw, sh, pixels) to template
    }

    private fun find(screen: Gray, template: Gray, region: Rect): List<Match> {
        val scale = max(1, min(template.w, template.h) / 12)
        val cs = screen.downsample(scale)
        val ct = template.downsample(scale)
        val coarse = TemplateMatcher.scanCoarseByMad(cs, ct,
            Rect(region.left / scale, region.top / scale,
                min(cs.w, (region.right + scale - 1) / scale),
                min(cs.h, (region.bottom + scale - 1) / scale)), 16)
        return TemplateMatcher.refine(screen, template, template.stats(), coarse, scale, region)
    }

    @Test fun pyramidFindsExactMatchesAtNonAlignedPositionsAndRegionEdges() {
        for ((w, h) in listOf(12 to 13, 49 to 51, 120 to 127, 241 to 243)) {
            for ((x, y) in listOf(0 to 0, 37 to 29, 110 to 100)) {
                val (screen, template) = fixture(w, h, x, y)
                for (region in listOf(Rect(0, 0, screen.w, screen.h), Rect(x, y, x + w, y + h))) {
                    val best = find(screen, template, region).maxByOrNull { it.score }
                    assertNotNull("$w x $h at $x,$y in $region", best)
                    assertEquals("$w x $h at $x,$y", x + w / 2, best!!.x)
                    assertEquals("$w x $h at $x,$y", y + h / 2, best.y)
                    assertEquals(1.0, best.score, 1e-10)
                }
            }
        }
    }

    @Test fun flatTemplatesKeepMadFallback() {
        val template = Gray(48, 48, IntArray(48 * 48) { 80 })
        val screen = Gray(100, 100, IntArray(100 * 100) { 220 })
        for (y in 21 until 69) for (x in 17 until 65) screen.px[y * 100 + x] = 80
        val best = find(screen, template, Rect(0, 0, 100, 100)).maxByOrNull { it.score }
        assertEquals(Match(41, 45, 1.0), best)
    }

    @Test fun lowVarianceTemplatesKeepTheOriginalNccFallbackScore() {
        val random = Random(707)
        // 包含纯色以及方差小于 1、但像素并不完全相同的模板。
        for (width in listOf(1, 2, 7, 24)) {
            val height = 5
            val screen = Gray(width + 4, height + 3,
                IntArray((width + 4) * (height + 3)) { random.nextInt(256) })
            for (templatePixels in listOf(
                IntArray(width * height) { 80 },
                IntArray(width * height) { if (it == 0) 81 else 80 }
            )) {
                val template = Gray(width, height, templatePixels)
                val stats = template.stats()
                assertTrue(stats.varSum < 1.0)
                for (y in 0..3) for (x in 0..4) {
                    // 原实现最终取相同的 MAD 分数，要求逐位一致。
                    assertEquals(screen.madSim(x, y, template),
                        screen.ncc(x, y, template, stats), 0.0)
                }
            }
        }
    }

    @Test fun cancellationInterruptsCoarseAndFineWork() {
        val failure = IllegalStateException("cancelled")
        var checks = 0
        val screen = Gray(80, 80, IntArray(6400)) { if (++checks == 10) throw failure }
        val template = Gray(12, 12, IntArray(144))
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            TemplateMatcher.scanCoarseByMad(screen, template, Rect(0, 0, 80, 80), 16)
        })
        checks = 0
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            TemplateMatcher.refine(screen, template, template.stats(), listOf(Match(20, 20, 1.0)), 1, Rect(0, 0, 80, 80))
        })
    }

    @Test fun multipleTargetsSurviveSuppressionAndAbsentTemplateStaysBelowThreshold() {
        val template = Gray(7, 9, IntArray(63) { Random(it).nextInt(256) })
        val screen = Gray(70, 50, IntArray(3500) { 230 })
        assertTrue(find(screen, template, Rect(0, 0, 70, 50)).none { it.score >= 0.9 })
        for ((x, y) in listOf(5 to 8, 43 to 31)) {
            for (row in 0 until template.h) template.px.copyInto(screen.px,
                (y + row) * screen.w + x, row * template.w, (row + 1) * template.w)
        }
        val hits = TemplateMatcher.aggregate(
            find(screen, template, Rect(0, 0, 70, 50)).filter { it.score >= 0.99 }
                .sortedByDescending { it.score }, 4)
        assertEquals(setOf(8 to 12, 46 to 35), hits.map { it.x to it.y }.toSet())
    }

    @Test fun largeTemplateRefinementReducesFullResolutionWork() {
        val (original, template) = fixture(240, 240, 37, 29)
        var checks = 0
        val screen = Gray(original.w, original.h, original.px) { checks++ }
        val region = Rect(0, 0, screen.w, screen.h)
        val result = TemplateMatcher.refine(screen, template, template.stats(),
            listOf(Match(7, 7, 1.0)), 20, region).single()
        assertEquals(157, result.x)
        assertEquals(149, result.y)
        assertEquals(1.0, result.score, 1e-10)
        // 旧精搜完整 41x41 邻域，每次 NCC 遍历 240 行（每 32 行一个检查点）。
        // 新路径连同中间层构建、行循环检查的总量也必须小于旧精修的 1/5。
        val oldFineChecks = 41 * 41 * 8
        assertTrue("new checks=$checks, old fine checks=$oldFineChecks", checks < oldFineChecks / 5)
    }

    @Test fun croppedSearchReturnsGlobalCoordinatesWithCachedTemplates() {
        val (screen, template) = fixture(121, 127, 37, 29)
        val prepared = TemplateMatcher.PreparedTemplate(template)
        val left = 31
        val top = 23
        val width = 140
        val height = 145
        val pixels = IntArray(width * height)
        for (row in 0 until height) screen.px.copyInto(pixels, row * width,
            (top + row) * screen.w + left, (top + row) * screen.w + left + width)
        val cropped = Gray(width, height, pixels)
        repeat(2) {
            val match = TemplateMatcher.find(cropped, prepared, 0.99, 1, left, top).single()
            assertEquals(37 + template.w / 2, match.x)
            assertEquals(29 + template.h / 2, match.y)
        }
        val exactRegion = TemplateMatcher.find(template, prepared, 0.99, 1, 37, 29).single()
        assertEquals(97, exactRegion.x)
        assertEquals(92, exactRegion.y)
    }

    /** 日志收集器：记录每次回调的级别与文本 */
    private class LogCollector {
        val lines = ArrayList<Pair<Char, String>>()
        fun collect(level: Char, msg: String) {
            lines.add(level to msg)
        }
    }

    @Test fun successfulMatchLogsDebugWithTemplateName() {
        val (screen, template) = fixture(61, 47, 37, 29)
        val prepared = TemplateMatcher.PreparedTemplate(template)
        val collector = LogCollector()
        val hits = TemplateMatcher.find(screen, prepared, 0.99, 1, logger = collector::collect,
            tplName = "按钮.png")
        assertEquals(1, hits.size)
        val (level, msg) = collector.lines.single()
        assertEquals('D', level)
        assertTrue("日志应包含文件名: $msg", msg.startsWith("按钮.png "))
        assertTrue("日志应包含 match: $msg", msg.contains("match tpl=61x47"))
    }

    @Test fun failedMatchLogsWarningWithBestScore() {
        // 纯色屏幕与带结构模板完全不匹配，应低于阈值并记录 W
        val template = Gray(24, 20, IntArray(24 * 20) { Random(it).nextInt(256) })
        val screen = Gray(120, 90, IntArray(120 * 90) { 200 })
        val prepared = TemplateMatcher.PreparedTemplate(template)
        val collector = LogCollector()
        val hits = TemplateMatcher.find(screen, prepared, 0.99, 1, logger = collector::collect,
            tplName = "缺失.png")
        assertTrue(hits.isEmpty())
        val (level, msg) = collector.lines.single()
        assertEquals('W', level)
        assertTrue("日志应包含文件名: $msg", msg.startsWith("缺失.png "))
        assertTrue("日志应包含失败原因: $msg", msg.contains("找图失败"))
        assertTrue("日志应包含最高分: $msg", msg.contains("best="))
    }

    @Test fun oversizedTemplateLogsWarning() {
        val template = Gray(80, 60, IntArray(80 * 60) { 128 })
        val screen = Gray(40, 30, IntArray(40 * 30) { 128 })
        val prepared = TemplateMatcher.PreparedTemplate(template)
        val collector = LogCollector()
        assertTrue(TemplateMatcher.find(screen, prepared, 0.9, 1, logger = collector::collect).isEmpty())
        val (level, msg) = collector.lines.single()
        assertEquals('W', level)
        assertTrue("应提示模板大于搜索区: $msg", msg.contains("模板大于搜索区"))
    }
}
