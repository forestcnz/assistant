package com.forest.android.assistant.core.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class LineIndexTest {
    @Test fun matchesOriginalPositionCalculationAtEveryUtf16Offset() {
        val random = Random(8675309)
        repeat(100) {
            val text = buildString {
                repeat(random.nextInt(0, 250)) {
                    append(when (random.nextInt(12)) {
                        0 -> '\n'
                        1 -> '\r'
                        2 -> '中'
                        else -> ('a'.code + random.nextInt(26)).toChar()
                    })
                }
            }
            val index = LineIndex.from(text)
            for (offset in 0..text.length) {
                val before = text.substring(0, offset)
                val expected = (before.count { it == '\n' } + 1) to
                    (before.length - before.lastIndexOf('\n'))
                assertEquals("offset=$offset text=$text", expected, index.position(offset))
            }
        }
    }

    @Test fun positionClampsNegativeOffsetsAndTracksTrailingEmptyLine() {
        val index = LineIndex.from("a\nb\n")
        assertEquals(1 to 1, index.position(-1))
        assertEquals(3 to 1, index.position(4))
    }
}
