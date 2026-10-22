package com.forest.android.assistant.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 不依赖 Android 的灰度匹配内核。 */
internal object TemplateMatcher {
    data class Match(val x: Int, val y: Int, val score: Double)
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private const val MIN_VARIANCE = 1.0
    private const val PACKED_MAX = 0xFFFF_FFFFL
    private const val PACKED_LOW_MASK = 0xFFFF_FFFFL
    private val coarseBuffers = MatchBufferPool()

    class PreparedTemplate(source: Gray) {
        // 缓存不能捕获某次脚本的取消闭包，否则会持有旧会话。
        val gray = Gray(source.w, source.h, source.px)
        val stats = source.stats()
        // 当前算法只缓存粗层及逐次折半的层，像素总数小于原图的两倍。
        val reservedBytes = source.px.size.toLong() * 8
        private val levels = HashMap<Int, Gray>()

        @Synchronized
        fun level(scale: Int, checkCancelled: () -> Unit): Gray {
            if (scale == 1) return gray
            return levels.getOrPut(scale) { gray.downsample(scale, checkCancelled) }
        }
    }

    /** 输入已裁剪的搜索区，输出回到原图坐标；缓存仅属于模板。 */
    fun find(
        grayScreen: Gray,
        prepared: PreparedTemplate,
        threshold: Double,
        max: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
        debugLogger: ((String) -> Unit)? = null,
        screenLevelProvider: ((Int) -> Gray)? = null
    ): List<Match> {
        if (max <= 0 || prepared.gray.w > grayScreen.w || prepared.gray.h > grayScreen.h) return emptyList()
        val tw = prepared.gray.w
        val th = prepared.gray.h
        val grayTpl = prepared.gray
        val tplStats = prepared.stats
        val localRegion = Rect(0, 0, grayScreen.w, grayScreen.h)

        // 粗搜倍数：让粗图模板边长约 12px，兼顾速度与特征保留
        val scale = max(1, min(tw, th) / 12)
        val cs = screenLevelProvider?.invoke(scale) ?: grayScreen.downsample(scale)
        val ct = prepared.level(scale, grayScreen.checkCancelled)
        val coarseRegion = Rect(0, 0, cs.w, cs.h)
        // 粗筛用平均绝对差而非 NCC：降采样会抹平细笔画特征，NCC 在此阶段会把
        // 大片纯色区域误判为高分（实测真实位置排名可掉到数万名之后），MAD 则稳定居首。
        val coarse = scanCoarseByMad(cs, ct, coarseRegion, limit = max(16, max * 8))
        val candidates = refine(grayScreen, grayTpl, tplStats, coarse, scale,
            localRegion, prepared, screenLevelProvider)
        debugLogger?.invoke(
            "match tpl=${tw}x$th scale=$scale thr=$threshold coarse=${coarse.size} " +
                    "best=" + (candidates.maxByOrNull { it.score }
                ?.let { "(${it.x + offsetX},${it.y + offsetY})=${"%.3f".format(it.score)}" } ?: "-")
        )

        return candidates
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .let { aggregate(it, max(4, min(tw, th) / 2)) }
            .let { hits ->
                // max=1 语义为“最佳匹配”，按分数取；多结果按从左到右、从上到下排序
                if (max == 1) hits.sortedByDescending { it.score }.take(1)
                else hits.sortedWith(compareBy({ it.y }, { it.x })).take(max)
            }.map { Match(it.x + offsetX, it.y + offsetY, it.score) }
    }

    /**
     * 粗筛：降采样图上按平均绝对差相似度取候选，并按邻近去重。
     * 不做阈值过滤——粗图分数区分度有限，最终由全分辨率 NCC 判定。
     */
    fun scanCoarseByMad(
        scr: Gray,
        tpl: Gray,
        region: Rect,
        limit: Int
    ): List<Match> {
        val maxY = min(region.bottom, scr.h) - tpl.h
        val maxX = min(region.right, scr.w) - tpl.w
        if (maxY < region.top || maxX < region.left) return emptyList()
        // 原始数组最大堆：不创建海量 Match，也不全量排序。
        // 同分按扫描顺序排列，保持原先稳定排序 + 贪心邻近去重的结果。
        val columns = maxX - region.left + 1
        val count = columns * (maxY - region.top + 1)
        // MAD 的整数差值 + 扫描序号可以无损打包；超过 32 位差值时回退原实现。
        if (tpl.px.size.toLong() * 255L <= PACKED_MAX) {
            return scanPackedCandidates(scr, tpl, region, limit, columns, maxY, maxX, count)
        }
        return scanLegacyCandidates(scr, tpl, region, limit, columns, maxY, maxX, count)
    }

    private fun scanPackedCandidates(
        scr: Gray,
        tpl: Gray,
        region: Rect,
        limit: Int,
        columns: Int,
        maxY: Int,
        maxX: Int,
        count: Int
    ): List<Match> {
        val buffer = coarseBuffers.acquire(count)
        try {
            val heap = buffer.heap
            var index = 0
            for (y in region.top..maxY) {
                scr.checkCancelled()
                for (x in region.left..maxX) {
                    heap[index] = packCandidate(scr.madDifference(x, y, tpl), index)
                    index++
                }
            }

            fun better(a: Long, b: Long): Boolean {
                val aScore = a ushr 32
                val bScore = b ushr 32
                if (aScore != bScore) return aScore > bScore
                return (a and PACKED_LOW_MASK) > (b and PACKED_LOW_MASK)
            }
            fun siftDown(start: Int, size: Int) {
                var root = start
                val value = heap[root]
                while (root < size / 2) {
                    var child = root * 2 + 1
                    if (child + 1 < size && better(heap[child + 1], heap[child])) child++
                    if (!better(heap[child], value)) break
                    heap[root] = heap[child]
                    root = child
                }
                heap[root] = value
            }

            for (i in count / 2 - 1 downTo 0) {
                if (i % 4096 == 0) scr.checkCancelled()
                siftDown(i, count)
            }
            val kept = ArrayList<Match>()
            val minDist = max(2, min(tpl.w, tpl.h) / 2)
            var size = count
            while (size > 0 && kept.size < limit) {
                scr.checkCancelled()
                val best = heap[0]
                heap[0] = heap[--size]
                if (size > 0) siftDown(0, size)
                val candidate = unpackCandidateIndex(best)
                val x = region.left + candidate % columns + tpl.w / 2
                val y = region.top + candidate / columns + tpl.h / 2
                if (kept.none { abs(it.x - x) < minDist && abs(it.y - y) < minDist }) {
                    kept.add(Match(x, y, scoreFromDifference(unpackCandidateDifference(best), tpl.px.size)))
                }
            }
            return kept
        } finally {
            coarseBuffers.release(buffer)
        }
    }

    private fun scanLegacyCandidates(
        scr: Gray,
        tpl: Gray,
        region: Rect,
        limit: Int,
        columns: Int,
        maxY: Int,
        maxX: Int,
        count: Int
    ): List<Match> {
        val scores = DoubleArray(count)
        val heap = IntArray(count) { it }
        var index = 0
        for (y in region.top..maxY) {
            scr.checkCancelled()
            for (x in region.left..maxX) scores[index++] = scr.madSim(x, y, tpl)
        }
        fun better(a: Int, b: Int): Boolean =
            scores[a] > scores[b] || (scores[a] == scores[b] && a < b)
        fun siftDown(start: Int, size: Int) {
            var root = start
            val value = heap[root]
            while (root < size / 2) {
                var child = root * 2 + 1
                if (child + 1 < size && better(heap[child + 1], heap[child])) child++
                if (!better(heap[child], value)) break
                heap[root] = heap[child]
                root = child
            }
            heap[root] = value
        }
        for (i in count / 2 - 1 downTo 0) {
            if (i % 4096 == 0) scr.checkCancelled()
            siftDown(i, count)
        }
        val kept = ArrayList<Match>()
        val minDist = max(2, min(tpl.w, tpl.h) / 2)
        var size = count
        while (size > 0 && kept.size < limit) {
            scr.checkCancelled()
            val best = heap[0]
            heap[0] = heap[--size]
            if (size > 0) siftDown(0, size)
            val x = region.left + best % columns + tpl.w / 2
            val y = region.top + best / columns + tpl.h / 2
            if (kept.none { abs(it.x - x) < minDist && abs(it.y - y) < minDist }) {
                kept.add(Match(x, y, scores[best]))
            }
        }
        return kept
    }

    internal fun packCandidate(difference: Long, index: Int): Long =
        ((PACKED_MAX - difference) shl 32) or (PACKED_MAX - index.toLong())

    internal fun unpackCandidateDifference(packed: Long): Long = PACKED_MAX - (packed ushr 32)

    internal fun unpackCandidateIndex(packed: Long): Int =
        (PACKED_MAX - (packed and PACKED_LOW_MASK)).toInt()

    internal fun scoreFromDifference(difference: Long, pixels: Int): Double =
        1.0 - (difference.toDouble() / pixels.toDouble()) / 255.0

    /** 精搜：在粗搜候选附近以原分辨率重新定位 */
    fun refine(
        scr: Gray,
        tpl: Gray,
        tplStats: Gray.S,
        coarse: List<Match>,
        scale: Int,
        region: Rect,
        prepared: PreparedTemplate? = null,
        screenLevelProvider: ((Int) -> Gray)? = null
    ): List<Match> {
        // 每层从原图降采样，避免逐层舍入累积；最终层仍使用全分辨率 NCC。
        val levels = ArrayList<Int>()
        var level = max(1, scale / 2)
        while (true) {
            levels.add(level)
            if (level == 1) break
            level = max(1, level / 2)
        }
        var positions = coarse.map {
            // 左上角换算，避免奇数模板中心截断产生偏移。
            Match((it.x - max(1, tpl.w / scale) / 2) * scale,
                (it.y - max(1, tpl.h / scale) / 2) * scale, it.score)
        }
        var previousScale = scale
        for (currentScale in levels) {
            scr.checkCancelled()
            val screenLevel = screenLevelProvider?.invoke(currentScale) ?: scr.downsample(currentScale)
            val templateLevel = prepared?.level(currentScale, scr.checkCancelled) ?: tpl.downsample(currentScale)
            val stats = if (currentScale == 1) tplStats else templateLevel.stats()
            val radius = (2 * previousScale + currentScale - 1) / currentScale
            val next = ArrayList<Match>(positions.size)
            for (candidate in positions) {
                val baseX = candidate.x / currentScale
                val baseY = candidate.y / currentScale
                val minX = max(region.left / currentScale, baseX - radius)
                val minY = max(region.top / currentScale, baseY - radius)
                val maxX = minOf(screenLevel.w - templateLevel.w,
                    (region.right - tpl.w + currentScale - 1) / currentScale, baseX + radius)
                val maxY = minOf(screenLevel.h - templateLevel.h,
                    (region.bottom - tpl.h + currentScale - 1) / currentScale, baseY + radius)
                if (minX > maxX || minY > maxY) continue
                var bestX = minX
                var bestY = minY
                var bestScore = -2.0
                for (y in minY..maxY) {
                    scr.checkCancelled()
                    for (x in minX..maxX) {
                        val score = screenLevel.ncc(x, y, templateLevel, stats)
                        if (score > bestScore) {
                            bestScore = score
                            bestX = x
                            bestY = y
                        }
                    }
                }
                next.add(Match(bestX * currentScale, bestY * currentScale, bestScore))
            }
            positions = next
            previousScale = currentScale
        }
        return positions.map { Match(it.x + tpl.w / 2, it.y + tpl.h / 2, it.score) }
    }

    /** 相邻命中聚合：距离小于 minDist 的只保留分数最高者 */
    fun aggregate(sorted: List<Match>, minDist: Int): List<Match> {
        val kept = ArrayList<Match>()
        for (m in sorted) {
            if (kept.none { abs(it.x - m.x) < minDist && abs(it.y - m.y) < minDist }) kept.add(m)
        }
        return kept
    }

    /** 灰度图（亮度 = (77R + 150G + 29B) >> 8） */
    class Gray(val w: Int, val h: Int, val px: IntArray, val checkCancelled: () -> Unit = {}) {

        /** 模板统计量：均值 + 去均值平方和 */
        data class S(val mean: Double, val varSum: Double)

        private var cached: S? = null

        fun stats(): S = cached ?: run {
            var sum = 0L
            for (v in px) sum += v
            val mean = sum.toDouble() / px.size
            var varSum = 0.0
            for (v in px) {
                val d = v - mean
                varSum += d * d
            }
            S(mean, varSum).also { cached = it }
        }

        /**
         * 归一化互相关，值域 [-1, 1]。
         * 模板或窗口近似平坦（方差过小）时 NCC 数值不稳定、分数会失真，
         * 此时退化为平均绝对差相似度，避免大面积为纯色背景的模板漏检。
         */
        fun ncc(x0: Int, y0: Int, tpl: Gray, ts: S): Double {
            val n = (tpl.w * tpl.h).toDouble()
            var sumI = 0.0
            var sumI2 = 0.0
            var dot = 0.0
            for (ty in 0 until tpl.h) {
                if (ty % 32 == 0) checkCancelled()
                var si = (y0 + ty) * w + x0
                var ti = ty * tpl.w
                for (tx in 0 until tpl.w) {
                    val i = px[si++].toDouble()
                    val t = tpl.px[ti++].toDouble()
                    sumI += i
                    sumI2 += i * i
                    dot += i * t
                }
            }
            val meanI = sumI / n
            val varI = sumI2 - sumI * meanI
            // 模板近似平坦：退化为平均绝对差；窗口平坦但模板有结构：判为不相似
            if (ts.varSum < MIN_VARIANCE) return madSim(x0, y0, tpl)
            if (varI < MIN_VARIANCE) return 0.0
            val den = sqrt(varI * ts.varSum)
            return ((dot - sumI * ts.mean) / den).coerceIn(-1.0, 1.0)
        }

        /** 平均绝对差相似度：1 - 平均灰度差/255，值域 [0,1]；不依赖方差，适合粗筛 */
        fun madDifference(x0: Int, y0: Int, tpl: Gray): Long {
            var diff = 0L
            for (ty in 0 until tpl.h) {
                if (ty % 32 == 0) checkCancelled()
                var si = (y0 + ty) * w + x0
                var ti = ty * tpl.w
                for (tx in 0 until tpl.w) diff += abs(px[si++] - tpl.px[ti++]).toLong()
            }
            return diff
        }

        fun madSim(x0: Int, y0: Int, tpl: Gray): Double =
            scoreFromDifference(madDifference(x0, y0, tpl), tpl.px.size).coerceIn(0.0, 1.0)

        /** 整数倍盒式降采样 */
        fun downsample(scale: Int, check: () -> Unit = checkCancelled): Gray {
            if (scale == 1) return this
            val nw = max(1, w / scale)
            val nh = max(1, h / scale)
            val out = IntArray(nw * nh)
            for (y in 0 until nh) {
                check()
                for (x in 0 until nw) {
                    var sum = 0
                    var cnt = 0
                    for (dy in 0 until scale) {
                        val sy = y * scale + dy
                        if (sy >= h) break
                        val rowOff = sy * w
                        for (dx in 0 until scale) {
                            val sx = x * scale + dx
                            if (sx >= w) break
                            sum += px[rowOff + sx]
                            cnt++
                        }
                    }
                    out[y * nw + x] = if (cnt > 0) sum / cnt else 0
                }
            }
            return Gray(nw, nh, out, checkCancelled)
        }
    }



}
