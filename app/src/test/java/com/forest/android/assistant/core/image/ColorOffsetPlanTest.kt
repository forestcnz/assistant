package com.forest.android.assistant.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ColorOffsetPlanTest {
    @Test fun compactOffsetsProduceStableBoundingBoxIndexes() {
        val plan = ColorOffsetPlan.create(
            listOf(intArrayOf(2, 1, 0), intArrayOf(-1, 2, 0), intArrayOf(0, 0, 0)),
            searchLeft = 0, searchTop = 0, searchRight = 20, searchBottom = 20,
            imageWidth = 20, imageHeight = 20
        )

        assertNotNull(plan)
        assertEquals(ColorOffsetPlan(-1, 0, 4, 3), plan)
        assertEquals(7, plan!!.index(2, 1))
        assertEquals(8, plan.index(-1, 2))
    }

    @Test fun sparseEmptyAndInvalidOffsetsUseTheOriginalPointPath() {
        assertNull(ColorOffsetPlan.create(
            listOf(intArrayOf(0, 0, 0), intArrayOf(100, 100, 0)),
            0, 0, 10, 10, 200, 200
        ))
        assertNull(ColorOffsetPlan.create(emptyList(), 0, 0, 10, 10, 20, 20))
        assertNull(ColorOffsetPlan.create(
            listOf(intArrayOf(0, 0, 0)), 0, 0, 10, 10, 20, 20
        ))
        assertNull(ColorOffsetPlan.create(
            listOf(intArrayOf(0, 0)), 0, 0, 10, 10, 20, 20
        ))
    }

    @Test fun integerOverflowUsesTheOriginalPointPath() {
        assertNull(ColorOffsetPlan.create(
            listOf(intArrayOf(Int.MAX_VALUE, 0, 0)),
            0, 0, 10, 10, 20, 20
        ))
    }
}
