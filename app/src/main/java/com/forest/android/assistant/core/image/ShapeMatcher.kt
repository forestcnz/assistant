package com.forest.android.assistant.core.image

import com.forest.android.assistant.core.image.TemplateMatcher.Gray
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sobel 轮廓 + 多尺度 NCC。保留笔画边缘，弱化颜色与平滑背景；不做文字语义识别。 */
internal object ShapeMatcher {
    val defaultScales: List<Double> = listOf(0.75, 1.0, 1.25, 1.5)

    data class Match(val x: Int, val y: Int, val width: Int, val height: Int, val score: Double) {
        val left get() = x - width / 2
        val top get() = y - height / 2
        val right get() = left + width
        val bottom get() = top + height
    }

    fun validateScales(scales: List<Double>) {
        require(scales.size in 1..12 && scales.all { it.isFinite() && it in 0.5..2.0 }) {
            "shape scales must contain 1..12 finite numbers between 0.5 and 2.0"
        }
    }

    /** 同一帧可复用边缘图和粗搜层，不持有全局截图或取消回调。 */
    class Search(private val screenEdges: Gray) {
        private val levels = HashMap<Int, Gray>()

        fun find(template: Gray, threshold: Double, scales: List<Double>, maxHits: Int = 4): List<Match> {
            validateScales(scales)
            require(threshold.isFinite() && threshold in 0.0..1.0) { "shape threshold must be in [0, 1]" }
            require(maxHits in 1..16)
            val hits = ArrayList<Match>()
            val sizes = HashSet<Pair<Int, Int>>()
            for (scale in scales) {
                screenEdges.checkCancelled()
                val w = (template.w * scale).roundToInt().coerceAtLeast(1)
                val h = (template.h * scale).roundToInt().coerceAtLeast(1)
                if (w < 3 || h < 3 || w > screenEdges.w || h > screenEdges.h || !sizes.add(w to h)) continue
                val resized = resize(template, w, h, screenEdges.checkCancelled)
                val contour = edges(resized)
                // 空白素材不能通过低方差 MAD 回退而变成任意平坦区域的“形状”。
                if (contour.px.count { it > 32 } < 8 || contour.stats().varSum < 1.0) continue
                hits += TemplateMatcher.find(
                    screenEdges, TemplateMatcher.PreparedTemplate(contour), threshold, maxHits,
                    screenLevelProvider = { level -> levels.getOrPut(level) { screenEdges.downsample(level) } },
                    coarseTargetSize = 6 // 轮廓粗搜降低采样密度，最终仍在原分辨率判分。
                ).map { Match(it.x, it.y, w, h, it.score) }
            }
            // 同一笔画在相邻尺度下只保留最佳候选。
            val kept = ArrayList<Match>()
            for (hit in hits.sortedByDescending { it.score }) {
                if (kept.none {
                    abs(it.x - hit.x) < max(4, min(it.width, hit.width) / 2) &&
                        abs(it.y - hit.y) < max(4, min(it.height, hit.height) / 2)
                }) kept.add(hit)
                if (kept.size >= maxHits) break
            }
            return kept
        }
    }

    fun edges(source: Gray): Gray {
        val out = IntArray(source.w * source.h)
        fun at(x: Int, y: Int) = source.px[y.coerceIn(0, source.h - 1) * source.w + x.coerceIn(0, source.w - 1)]
        for (y in 0 until source.h) {
            source.checkCancelled()
            for (x in 0 until source.w) {
                val gx = -at(x - 1, y - 1) + at(x + 1, y - 1) - 2 * at(x - 1, y) +
                    2 * at(x + 1, y) - at(x - 1, y + 1) + at(x + 1, y + 1)
                val gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) +
                    at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
                out[y * source.w + x] = min(255, abs(gx) + abs(gy))
            }
        }
        return Gray(source.w, source.h, out, source.checkCancelled)
    }

    /** 双线性缩放仅作用于匹配副本，不修改素材。 */
    internal fun resize(source: Gray, w: Int, h: Int, check: () -> Unit = source.checkCancelled): Gray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            check()
            val fy = ((y + 0.5) * source.h / h - 0.5).coerceIn(0.0, (source.h - 1).toDouble())
            val y0 = floor(fy).toInt()
            val y1 = min(y0 + 1, source.h - 1)
            for (x in 0 until w) {
                val fx = ((x + 0.5) * source.w / w - 0.5).coerceIn(0.0, (source.w - 1).toDouble())
                val x0 = floor(fx).toInt()
                val x1 = min(x0 + 1, source.w - 1)
                val top = source.px[y0 * source.w + x0] * (1 - (fx - x0)) + source.px[y0 * source.w + x1] * (fx - x0)
                val bottom = source.px[y1 * source.w + x0] * (1 - (fx - x0)) + source.px[y1 * source.w + x1] * (fx - x0)
                out[y * w + x] = (top * (1 - (fy - y0)) + bottom * (fy - y0)).roundToInt()
            }
        }
        return Gray(w, h, out, check)
    }
}
