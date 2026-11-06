package com.forest.android.assistant.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 为所有找图接口生成不依赖前景亮度的轮廓图与倍率模板。 */
internal object ShapePixels {
    fun edges(gray: TemplateMatcher.Gray): TemplateMatcher.Gray {
        val out = IntArray(gray.px.size)
        for (y in 0 until gray.h) {
            gray.checkCancelled()
            for (x in 0 until gray.w) {
                val index = y * gray.w + x
                // 分别取两侧差值的较大值，保留细笔画，也避免模板边缘与截图邻域不同而漏检。
                val center = gray.px[index]
                val horizontal = max(
                    abs(center - gray.px[y * gray.w + max(0, x - 1)]),
                    abs(center - gray.px[y * gray.w + min(gray.w - 1, x + 1)])
                )
                val vertical = max(
                    abs(center - gray.px[max(0, y - 1) * gray.w + x]),
                    abs(center - gray.px[min(gray.h - 1, y + 1) * gray.w + x])
                )
                out[index] = min(255, horizontal + vertical)
            }
        }
        return TemplateMatcher.Gray(gray.w, gray.h, out, gray.checkCancelled)
    }

    fun scaled(gray: TemplateMatcher.Gray, scale: Double): TemplateMatcher.Gray {
        val w = max(1, (gray.w * scale).roundToInt())
        val h = max(1, (gray.h * scale).roundToInt())
        if (w == gray.w && h == gray.h) return gray
        val out = IntArray(w * h)
        for (y in 0 until h) {
            gray.checkCancelled()
            val sy = ((y + 0.5) * gray.h / h - 0.5).coerceIn(0.0, (gray.h - 1).toDouble())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, gray.h - 1)
            for (x in 0 until w) {
                val sx = ((x + 0.5) * gray.w / w - 0.5).coerceIn(0.0, (gray.w - 1).toDouble())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, gray.w - 1)
                val top = gray.px[y0 * gray.w + x0] * (1 - (sx - x0)) + gray.px[y0 * gray.w + x1] * (sx - x0)
                val bottom = gray.px[y1 * gray.w + x0] * (1 - (sx - x0)) + gray.px[y1 * gray.w + x1] * (sx - x0)
                out[y * w + x] = (top * (1 - (sy - y0)) + bottom * (sy - y0)).roundToInt()
            }
        }
        return TemplateMatcher.Gray(w, h, out, gray.checkCancelled)
    }
}
