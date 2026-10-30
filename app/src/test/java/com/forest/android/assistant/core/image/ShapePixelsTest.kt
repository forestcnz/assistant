package com.forest.android.assistant.core.image

import com.forest.android.assistant.core.image.TemplateMatcher.Gray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapePixelsTest {
    /** 非对称笔画和留白共同构成模板，测试形状匹配是否能定位而非命中纯色背景。 */
    private fun glyph(): Gray {
        val pixels = IntArray(20 * 24) { 245 }
        for (y in 4..19) for (x in 4..6) pixels[y * 20 + x] = 12
        for (x in 4..15) for (y in 5..7) pixels[y * 20 + x] = 12
        for (y in 12..19) for (x in 13..15) pixels[y * 20 + x] = 12
        return Gray(20, 24, pixels)
    }

    @Test fun outlineIgnoresForegroundColorButKeepsStrokeGeometry() {
        val original = glyph()
        val inverted = Gray(original.w, original.h, original.px.map { 255 - it }.toIntArray())
        assertEquals(ShapePixels.edges(original).px.toList(), ShapePixels.edges(inverted).px.toList())
        assertTrue(ShapePixels.edges(Gray(20, 24, IntArray(480) { 200 })).px.all { it == 0 })
    }

    @Test fun detectsScaledGlyphWithCoarseAndFineMatching() {
        val template = glyph()
        val target = ShapePixels.scaled(template, 1.5)
        val screenWidth = 130
        val screenHeight = 95
        val screen = IntArray(screenWidth * screenHeight) { 245 }
        val x = 64
        val y = 32
        for (row in 0 until target.h) target.px.copyInto(screen,
            (y + row) * screenWidth + x, row * target.w, (row + 1) * target.w)
        val edges = ShapePixels.edges(Gray(screenWidth, screenHeight, screen))
        val prepared = TemplateMatcher.PreparedTemplate(ShapePixels.edges(target))
        val hits = TemplateMatcher.find(edges, prepared, 0.8, 1)
        assertFalse("缩放后的笔画应匹配", hits.isEmpty())
        assertEquals(x + target.w / 2, hits[0].x)
        assertEquals(y + target.h / 2, hits[0].y)
    }
}
