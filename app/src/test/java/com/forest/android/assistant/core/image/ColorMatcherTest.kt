package com.forest.android.assistant.core.image

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ColorMatcherTest {

    @Test
    fun slicedRowMatchesOriginalFullRowScanAcrossRegions() {
        val random = Random(271828)
        repeat(100) {
            val width = random.nextInt(1, 80)
            val row = IntArray(width) { random.nextInt() }
            val left = random.nextInt(width)
            val right = random.nextInt(left + 1, width + 1)
            val target = random.nextInt()
            val threshold = random.nextInt(0, 48)

            // 原实现检查整行，但只接受 region 内的像素。
            val expected = row.indices.firstOrNull { index ->
                index in left until right && matches(row[index], target, threshold)
            }
            val actual = ColorMatcher.firstMatch(
                row.copyOfRange(left, right), left, target, threshold
            )

            assertEquals(expected, actual)
        }
    }

    @Test
    fun multiColorPredicatePreservesCandidateOrderAndAlphaIgnoredComparison() {
        val target = 0xFF406080.toInt()
        val row = intArrayOf(
            0x00406080,
            0x80406080.toInt(),
            0xFF416080.toInt()
        )
        val accepted = mutableListOf<Int>()

        val actual = ColorMatcher.firstMatch(row, 20, target, 1) { x ->
            accepted += x
            x == 21
        }

        assertEquals(21, actual)
        assertEquals(listOf(20, 21), accepted)
    }

    private fun matches(pixel: Int, target: Int, threshold: Int): Boolean =
        kotlin.math.abs(((pixel shr 16) and 0xFF) - ((target shr 16) and 0xFF)) <= threshold &&
            kotlin.math.abs(((pixel shr 8) and 0xFF) - ((target shr 8) and 0xFF)) <= threshold &&
            kotlin.math.abs((pixel and 0xFF) - (target and 0xFF)) <= threshold
}
