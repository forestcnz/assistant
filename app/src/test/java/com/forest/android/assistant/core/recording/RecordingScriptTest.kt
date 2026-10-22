package com.forest.android.assistant.core.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingScriptTest {
    @Test fun imageModeCommentsCoordinatesAndRestrictsMatchingRegion() {
        val point = RecordedPoint(890, 1590)
        val region = RecordedCrop.around(point, 600, 900, 1600)
        assertEquals(RecordedCrop(300, 1000, 600, 600), region)
        val script = RecordingScript.generate(900, 1600, listOf(RecordedOperation(
            RecordedOperation.Kind.CLICK, listOf(point), 40,
            image = "images/step_001.png", crop = RecordedCrop.around(point, 100, 900, 1600),
            searchRegion = region, contextImage = "contexts/step_001.png"
        )), RecordingMode.IMAGE)
        assertTrue(script.contains("// if (!click(890, 1590, 40))"))
        assertTrue(script.contains("var region1 = [300, 1000, 600, 600];"))
        assertTrue(script.contains("threshold: 0.9, region: region1"))
        assertFalse(script.contains("/*"))
        assertTrue(script.contains("point1.x + (40), point1.y + (40)"))
    }

    @Test fun coordinateModeKeepsImageLookupCommentedAndRegionBounded() {
        val script = RecordingScript.generate(360, 640, listOf(RecordedOperation(
            RecordedOperation.Kind.CLICK, listOf(RecordedPoint(20, 30)), 50,
            image = "images/step_001.png", crop = RecordedCrop(0, 0, 100, 100)
        )))
        assertTrue(script.contains("\nif (!click(20, 30, 50))"))
        assertTrue(script.contains("var region1 = [0, 0, 360, 600];"))
        assertTrue(script.indexOf("var region1") < script.indexOf("/*"))
        assertTrue(script.indexOf("/*") < script.indexOf("images.findImage"))
    }

    @Test fun edgeCropKeepsSizeAndOriginalTapOffset() {
        val point = RecordedPoint(2, 1598)
        val crop = RecordedCrop.around(point, 100, 900, 1600)
        assertEquals(RecordedCrop(0, 1500, 100, 100), crop)
        val script = RecordingScript.generate(900, 1600, listOf(RecordedOperation(
            RecordedOperation.Kind.CLICK, listOf(point), 80,
            image = "images/step_001.png", crop = crop)))
        assertTrue(script.contains("click(2, 1598, 80)"))
        assertTrue(script.contains("point1.x + (-48), point1.y + (48)"))
        assertTrue(script.indexOf("/*") < script.indexOf("images.findImage"))
        assertTrue(script.contains("images.release(template1)"))
    }

    @Test fun cropFitsScreensSmallerThanRequestedSize() {
        assertEquals(RecordedCrop(0, 0, 60, 80), RecordedCrop.around(RecordedPoint(0, 0), 100, 60, 80))
    }

    @Test fun rectangularCropKeepsBothDimensionsAndTapOffsetAtScreenEdge() {
        val point = RecordedPoint(5, 1595)
        val crop = RecordedCrop.around(point, 500, 200, 900, 1600)
        assertEquals(RecordedCrop(0, 1400, 500, 200), crop)
        assertEquals(RecordedCrop(0, 0, 60, 80),
            RecordedCrop.around(RecordedPoint(0, 0), 500, 200, 60, 80))

        val script = RecordingScript.generate(900, 1600, listOf(RecordedOperation(
            RecordedOperation.Kind.CLICK, listOf(point), 80,
            image = "images/step_001.png", crop = crop
        )), RecordingMode.IMAGE)
        assertTrue(script.contains("point1.x + (-245), point1.y + (95)"))
    }

    @Test fun returningSwipeIsNotMistakenForClick() {
        val points = listOf(RecordedPoint(10, 10), RecordedPoint(200, 200), RecordedPoint(10, 10))
        assertEquals(RecordedOperation.Kind.SWIPE, RecordedOperation.classify(points, 400, 8, 500))
        val script = RecordingScript.generate(900, 1600, listOf(RecordedOperation(RecordedOperation.Kind.SWIPE, points, 400)))
        assertTrue(script.contains("gesture(400, [10, 10], [200, 200], [10, 10])"))
        assertTrue(script.contains("var region1 = [0, 0, 600, 600];"))
        assertFalse(script.contains("findImage"))
    }

    @Test fun stationaryLongPressKeepsDuration() {
        assertEquals(RecordedOperation.Kind.LONG_PRESS,
            RecordedOperation.classify(listOf(RecordedPoint(10, 10), RecordedPoint(12, 11)), 900, 8, 500))
        assertEquals(RecordedOperation.Kind.CLICK,
            RecordedOperation.classify(listOf(RecordedPoint(10, 10)), 100, 8, 500))
    }

    @Test fun replayWaitsAfterCompletionWithoutAddingStartIntervalAgain() {
        val steps = listOf(
            RecordedOperation(RecordedOperation.Kind.CLICK, listOf(RecordedPoint(10, 20)), 100),
            RecordedOperation(RecordedOperation.Kind.SWIPE, listOf(RecordedPoint(1, 2), RecordedPoint(3, 4)),
                650, delayMs = 1200, intervalMs = 1300)
        )
        val script = RecordingScript.generate(900, 1600, steps)
        assertEquals(1, Regex("sleep\\(").findAll(script).count())
        assertTrue(script.contains("sleep(1200);"))
        assertFalse(script.contains("sleep(1300)"))
        assertTrue(script.contains("swipe(1, 2, 3, 4, 650)"))
        assertTrue(script.contains("throw new Error"))
    }
}
