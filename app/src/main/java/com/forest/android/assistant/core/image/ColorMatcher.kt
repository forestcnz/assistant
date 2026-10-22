package com.forest.android.assistant.core.image

import kotlin.math.abs

/** 区域行缓冲的纯 Kotlin 找色逻辑，便于对照验证扫描顺序和命中坐标。 */
internal object ColorMatcher {

    fun matches(pixel: Int, color: Int, threshold: Int): Boolean =
        matchesRgb(pixel, (color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, threshold)

    private fun matchesRgb(pixel: Int, red: Int, green: Int, blue: Int, threshold: Int): Boolean =
        abs(((pixel shr 16) and 0xFF) - red) <= threshold &&
                abs(((pixel shr 8) and 0xFF) - green) <= threshold &&
                abs((pixel and 0xFF) - blue) <= threshold

    /** 返回首个匹配像素的绝对 X 坐标；[accept] 用于多点找色的后续偏移校验。 */
    inline fun firstMatch(
        row: IntArray,
        left: Int,
        color: Int,
        threshold: Int,
        accept: (x: Int) -> Boolean = { true }
    ): Int? {
        val tr = (color shr 16) and 0xFF
        val tg = (color shr 8) and 0xFF
        val tb = color and 0xFF
        for (index in row.indices) {
            val pixel = row[index]
            if (!matchesRgb(pixel, tr, tg, tb, threshold)) continue
            val x = left + index
            if (accept(x)) return x
        }
        return null
    }
}
