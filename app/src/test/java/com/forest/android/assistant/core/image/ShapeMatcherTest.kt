package com.forest.android.assistant.core.image

import com.forest.android.assistant.core.image.TemplateMatcher.Gray
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ShapeMatcherTest {
    private fun fixture(name: String): Gray {
        val image = ImageIO.read(javaClass.getResource("/shape/$name.png"))
        return Gray(image.width, image.height, IntArray(image.width * image.height) { i ->
            val rgb = image.getRGB(i % image.width, i / image.width)
            (77 * ((rgb shr 16) and 255) + 150 * ((rgb shr 8) and 255) + 29 * (rgb and 255)) shr 8
        })
    }

    private fun find(screen: Gray, template: Gray, threshold: Double = 0.82) =
        ShapeMatcher.Search(ShapeMatcher.edges(screen)).find(template, threshold, ShapeMatcher.defaultScales)

    @Test fun oneSmallCharacterTemplateFindsBothRealButtonSizes() {
        val template = fixture("que")
        for ((screenName, x, y) in listOf(Triple("confirm", 98, 47), Triple("ok", 45, 35))) {
            val hit = find(fixture(screenName), template).firstOrNull()
            assertNotNull(screenName, hit)
            assertTrue("$screenName: $hit", abs(hit!!.x - x) <= 2 && abs(hit.y - y) <= 2)
            println("shape $screenName que: $hit")
        }
    }

    @Test fun bothButtonsKeepTheirSecondCharacterAndRejectOtherCharacters() {
        val confirm = fixture("confirm")
        val ok = fixture("ok")
        assertTrue(find(confirm, fixture("ren")).any { abs(it.x - 165) <= 2 && abs(it.y - 44) <= 2 })
        assertTrue(find(ok, fixture("ding")).any { abs(it.x - 74) <= 2 && abs(it.y - 35) <= 2 })
        for (wrong in listOf("ding", "tong", "yi")) assertTrue(wrong, find(confirm, fixture(wrong)).isEmpty())
        for (wrong in listOf("ren", "tong", "yi")) assertTrue(wrong, find(ok, fixture(wrong)).isEmpty())
    }

    @Test fun reversingContrastKeepsTheSameShape() {
        val screen = fixture("confirm")
        val inverted = Gray(screen.w, screen.h, screen.px.map { 255 - it }.toIntArray())
        assertArrayEquals(ShapeMatcher.edges(screen).px, ShapeMatcher.edges(inverted).px)
        assertEquals(find(screen, fixture("que")), find(inverted, fixture("que")))
    }

    @Test fun findsSmallCharacterInFullHdFrame() {
        val crop = fixture("confirm")
        val pixels = IntArray(1920 * 1080) { 100 }
        val left = 1231
        val top = 601
        for (y in 0 until crop.h) crop.px.copyInto(pixels, (top + y) * 1920 + left, y * crop.w, (y + 1) * crop.w)
        val started = System.nanoTime()
        val hit = find(Gray(1920, 1080, pixels), fixture("que")).firstOrNull()
        assertNotNull(hit)
        assertTrue("$hit", abs(hit!!.x - left - 98) <= 2 && abs(hit.y - top - 47) <= 2)
        println("shape full HD: ${(System.nanoTime() - started) / 1_000_000}ms, $hit")
    }

    @Test fun flatImagesAndOversizedTemplatesDoNotMatch() {
        val flat = Gray(70, 60, IntArray(4200) { 180 })
        val blankTemplate = Gray(20, 20, IntArray(400) { 180 })
        assertTrue(find(flat, fixture("que")).isEmpty())
        assertTrue(find(fixture("confirm"), blankTemplate).isEmpty())
        assertTrue(find(Gray(5, 5, IntArray(25)), fixture("que")).isEmpty())
    }

    @Test fun rejectsInvalidScaleRangesAndThresholds() {
        for (scales in listOf(emptyList(), listOf(Double.NaN), listOf(0.1), listOf(2.1), List(13) { 1.0 })) {
            assertThrows(IllegalArgumentException::class.java) { ShapeMatcher.validateScales(scales) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShapeMatcher.Search(ShapeMatcher.edges(fixture("confirm"))).find(fixture("que"), Double.NaN, listOf(1.0))
        }
    }

    @Test fun processingHonorsCancellation() {
        val stop = IllegalStateException("stopped")
        val image = fixture("confirm")
        assertSame(stop, assertThrows(IllegalStateException::class.java) {
            ShapeMatcher.edges(Gray(image.w, image.h, image.px) { throw stop })
        })
    }
}
