package com.forest.android.assistant.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.forest.android.assistant.core.ocr.OcrApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.forest.android.assistant.core.image.TemplateMatcher.Gray

/**
 * 图像能力：模板匹配/找色（截屏取帧见 [ScreenCapture]）。
 *
 * 匹配算法：灰度化 + 归一化互相关（NCC），由粗到细多级搜索：
 * 1. 粗搜：模板缩到约 12px，滑动打分，用原始数组堆选择邻近去重后的候选；
 * 2. 精搜：逐级缩小定位范围，最终以原分辨率 NCC 判分，再过滤和聚合命中。
 * 纯 Kotlin 实现，避免引入 OpenCV（AAR 117MB，APK 会涨到 80MB+）；后续需要多尺度/复杂找色时可替换实现。
 * 对应脚本 API 见 docs/README.md 第 5.4 节。
 */
object ImageApi {

    data class Point(val x: Int, val y: Int)

    /** 匹配日志钩子：命中为 D（调试）、未命中为 W（警告）；引擎运行期注入 */
    @Volatile
    var debugLogger: ((Char, String) -> Unit)? = null

    @Volatile
    var cancellationCheck: (() -> Unit)? = null

    // —— JS 侧 Image 句柄：自增 id → Bitmap，避免把 Bitmap 暴露给 JS 引擎 ——
    private val images = ConcurrentHashMap<Int, Bitmap>()
    // 图片来源名称（文件名 / "屏幕截图"）：仅在调试日志中标识模板路径，不参与匹配
    private val imageNames = ConcurrentHashMap<Int, String>()
    private val templates = TemplateCache()
    private val screenGrays = ScreenGrayCache()
    private val nextId = AtomicInteger(1)

    /** 存入图片句柄；[name] 用于日志中显示来源（文件名或“屏幕截图”） */
    fun storeImage(bitmap: Bitmap, name: String = "截图"): Int =
        nextId.getAndIncrement().also {
            images[it] = bitmap
            imageNames[it] = name
        }

    fun image(id: Int?): Bitmap? = id?.let { images[it] }

    /** 图片句柄的来源名称（文件名），未登记时返回 null */
    fun imageName(id: Int): String? = imageNames[id]

    fun releaseImage(id: Int) {
        synchronized(templates) {
            images.remove(id)
            imageNames.remove(id)
            templates.remove(id)
            screenGrays.removeImage(id)
        }
    }

    fun clearImages() {
        synchronized(templates) {
            images.clear()
            imageNames.clear()
            templates.clear()
            screenGrays.clear()
        }
    }

    // ==================== 文件读写 ====================

    /** 读图（相对路径基于脚本目录解析）；记录文件名，便于日志定位模板 */
    fun read(path: String, scriptDir: File): Int? {
        val f = resolve(path, scriptDir) ?: return null
        if (!f.isFile) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return storeImage(bmp, f.name)
    }

    /** 保存图片；format 支持 png/jpg（默认按扩展名，quality 默认 100） */
    fun save(
        imageId: Int,
        path: String,
        scriptDir: File,
        format: String? = null,
        quality: Int = 100
    ): Boolean {
        val bmp = image(imageId) ?: return false
        val f = resolve(path, scriptDir) ?: return false
        f.parentFile?.mkdirs()
        val fmt = format?.lowercase()
            ?: if (f.extension.equals("jpg", true) || f.extension.equals("jpeg", true)) "jpg" else "png"
        return try {
            FileOutputStream(f).use { out ->
                val compress = if (fmt == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                bmp.compress(compress, quality.coerceIn(1, 100), out)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 裁剪，返回新的 Image 句柄 */
    fun clip(imageId: Int, x: Int, y: Int, w: Int, h: Int): Int? {
        val bmp = image(imageId) ?: return null
        val cx = x.coerceIn(0, max(0, bmp.width - 1))
        val cy = y.coerceIn(0, max(0, bmp.height - 1))
        val cw = w.coerceIn(1, bmp.width - cx)
        val ch = h.coerceIn(1, bmp.height - cy)
        return try {
            storeImage(Bitmap.createBitmap(bmp, cx, cy, cw, ch))
        } catch (e: Exception) {
            null
        }
    }

    /** 取像素色值（ARGB int），越界返回 null */
    fun pixel(imageId: Int, x: Int, y: Int): Int? {
        val bmp = image(imageId) ?: return null
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return null
        return bmp.getPixel(x, y)
    }

    fun width(imageId: Int): Int = image(imageId)?.width ?: 0

    fun height(imageId: Int): Int = image(imageId)?.height ?: 0

    // ==================== 找色 ====================

    /**
     * 区域内找色：逐像素比较，各通道色差 ≤ threshold 视为命中，返回首个命中点。
     * region 为 [x, y, w, h]（w/h ≤ 0 表示延伸到边界）。
     */
    fun findColor(
        imageId: Int,
        color: Int,
        threshold: Int = 16,
        region: IntArray? = null
    ): Point? {
        val bmp = image(imageId) ?: return null
        val r = clampRegion(region, bmp.width, bmp.height) ?: return null
        // 只读取待搜索区域，保持逐行、从左到右的检查顺序和命中坐标。
        val rowWidth = r.width()
        val row = IntArray(rowWidth)
        for (y in r.top until r.bottom) {
            bmp.getPixels(row, 0, rowWidth, r.left, y, rowWidth, 1)
            ColorMatcher.firstMatch(row, r.left, color, threshold)?.let { return Point(it, y) }
        }
        return null
    }

    /** 多点找色：先命中首色，再逐点校验相对偏移颜色（找图的轻量替代） */
    fun findMultiColors(
        imageId: Int,
        firstColor: Int,
        offsets: List<IntArray>,
        threshold: Int = 16,
        region: IntArray? = null
    ): Point? {
        val bmp = image(imageId) ?: return null
        val r = clampRegion(region, bmp.width, bmp.height) ?: return null
        // 与单点找色一致，只复制搜索区域；偏移点仍按原逻辑校验整张图片范围。
        val rowWidth = r.width()
        val row = IntArray(rowWidth)
        val offsetPlan = ColorOffsetPlan.create(
            offsets, r.left, r.top, r.right, r.bottom, bmp.width, bmp.height
        )
        val offsetPixels = offsetPlan?.let { IntArray(it.width * it.height) }
        fun matchAt(x: Int, y: Int, color: Int): Boolean {
            if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return false
            val c = bmp.getPixel(x, y)
            return abs(((c shr 16) and 0xFF) - ((color shr 16) and 0xFF)) <= threshold &&
                    abs(((c shr 8) and 0xFF) - ((color shr 8) and 0xFF)) <= threshold &&
                    abs((c and 0xFF) - (color and 0xFF)) <= threshold
        }
        for (y in r.top until r.bottom) {
            bmp.getPixels(row, 0, rowWidth, r.left, y, rowWidth, 1)
            // 首色按原顺序扫描；只有首色命中后才校验偏移点。
            ColorMatcher.firstMatch(row, r.left, firstColor, threshold) { x ->
                if (offsetPlan == null || offsetPixels == null) {
                    offsets.all { it.size >= 3 && matchAt(x + it[0], y + it[1], it[2]) }
                } else {
                    // 先按原逻辑检查所有偏移点的整图边界，避免批量读取改变越界语义。
                    val inBounds = offsets.all { offset ->
                        val ox = x + offset[0]
                        val oy = y + offset[1]
                        ox >= 0 && oy >= 0 && ox < bmp.width && oy < bmp.height
                    }
                    if (!inBounds) return@firstMatch false
                    val left = x + offsetPlan.leftOffset
                    val top = y + offsetPlan.topOffset
                    bmp.getPixels(offsetPixels, 0, offsetPlan.width, left, top,
                        offsetPlan.width, offsetPlan.height)
                    offsets.all { offset ->
                        ColorMatcher.matches(
                            offsetPixels[offsetPlan.index(offset[0], offset[1])], offset[2], threshold
                        )
                    }
                }
            }?.let { return Point(it, y) }
        }
        return null
    }

    // ==================== 找图（模板匹配） ====================

    /** 命中结果：x/y 为模板中心坐标，score 为相似度 */
    data class Match(val x: Int, val y: Int, val score: Double)

    /** 找图：返回匹配中心点 */
    fun findImage(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null
    ): Point? = findMatches(imageId, tplId, threshold, region, 1).firstOrNull()?.let { Point(it.x, it.y) }

    /** 全部匹配位置（相邻命中已聚合），按从左到右、从上到下排序 */
    fun findAllImages(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null,
        max: Int = 5
    ): List<Point> = findMatches(imageId, tplId, threshold, region, max).map { Point(it.x, it.y) }

    /** 模板是否出现在图中 */
    fun existsImage(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null
    ): Boolean = findMatches(imageId, tplId, threshold, region, 1).isNotEmpty()

    /** 图文联合匹配结果：匹配中心 + 匹配矩形 + 图内识别文字与逐条文本 */
    data class MatchWithText(
        val x: Int,
        val y: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val text: String,
        val items: List<OcrApi.Item>
    )

    /**
     * 找图并对匹配到的区域做 OCR：适合「卡片样式固定 + 图内文字变化」的场景，
     * 例如匹配商品卡片后读取其中的价格文字。
     * @param padding OCR 区域向四周扩展的像素（默认 0），避免边缘文字被裁
     */
    fun findImageWithText(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null,
        padding: Int = 0
    ): MatchWithText? {
        val screen = image(imageId) ?: return null
        val tpl = image(tplId) ?: return null
        val best = findMatches(imageId, tplId, threshold, region, 1).firstOrNull() ?: return null

        // 匹配矩形 = 中心 ± 模板一半，再按 padding 外扩并裁剪到图片范围
        val pad = padding.coerceIn(0, 200)
        val left = (best.x - tpl.width / 2 - pad).coerceAtLeast(0)
        val top = (best.y - tpl.height / 2 - pad).coerceAtLeast(0)
        val right = (best.x + tpl.width / 2 + pad).coerceAtMost(screen.width)
        val bottom = (best.y + tpl.height / 2 + pad).coerceAtMost(screen.height)
        if (right - left <= 0 || bottom - top <= 0) return null

        val items = OcrApi.recognize(screen, intArrayOf(left, top, right - left, bottom - top))
        return MatchWithText(
            x = best.x,
            y = best.y,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            text = items.joinToString("\n") { it.text },
            items = items
        )
    }

    // ==================== 双模板同行校验 ====================

    /** 单个模板的匹配框；[x]/[y] 为中心，四边为模板在画面中的实际范围 */
    data class MatchRect(
        val x: Int,
        val y: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /**
     * 双模板匹配结果：
     * - [sameRow] 为按容差判定的同行结论；
     * - [midX]/[midY] 为两个匹配中心的中点，即“两个模板之间的坐标”；
     * - [ax]/[ay]/[bx]/[by] 等为扁平化字段，便于跨桥接传递。
     */
    data class PairMatch(
        val sameRow: Boolean,
        val rowDelta: Int,
        val tolerance: Int,
        val midX: Int,
        val midY: Int,
        val dx: Int,
        val dy: Int,
        val a: MatchRect,
        val b: MatchRect
    )

    /**
     * 查找两个模板并校验是否在同一行，返回两者中心的中点。
     *
     * 性能：A 先全屏（或指定区域）搜索；B 优先在 A 所在的行带内搜索，
     * 带高为 `hB + 2*tolerance`，找不到时回退一次全屏搜索，
     * 以保证“找到了但不同行”仍能返回 sameRow=false。
     *
     * @param region [x, y, w, h]，两个模板共用
     * @param rowTolerance 同行容差像素；<= 0 时按模板高度自动计算
     */
    fun findImagePair(
        imageId: Int,
        tplAId: Int,
        tplBId: Int,
        thresholdA: Double = 0.9,
        thresholdB: Double = 0.9,
        region: IntArray? = null,
        rowTolerance: Int = 0
    ): PairMatch? {
        val tplA = image(tplAId) ?: return null
        val tplB = image(tplBId) ?: return null

        // 同一模板：取画面中按 X 排序的前两个“互不重叠”的位置（两个外观相同的控件）
        // 注意：调用方即使传同一路径，也可能解析成两个不同句柄，
        // 因此除 id 相等外还要比较图像内容是否同一份来源。
        if (tplAId == tplBId || isSameSource(tplAId, tplBId)) {
            // max 取 4 是为了在聚合后仍有足够候选可筛选；模板较大时相邻命中会被聚合掉
            val hits = findMatches(imageId, tplAId, thresholdA, region, 4)
            val distinct = distinctPositions(hits, tplA.width, tplA.height)
            if (distinct.size < 2) return null
            val sorted = distinct.sortedBy { it.x }
            return buildPair(sorted[0], sorted[1], tplA, tplB, rowTolerance)
        }

        val a = findMatches(imageId, tplAId, thresholdA, region, 1).firstOrNull() ?: return null

        // 自动容差：小控件用更严格的尺子
        val tolerance = resolveTolerance(rowTolerance, tplA.height, tplB.height)
        // B 优先在 A 的行带内搜索；带宽覆盖容差与自身高度
        val band = rowBandOf(region, a, tplB.height, tolerance, imageWidth(imageId), imageHeight(imageId))
        val b = (band?.let {
            findMatches(imageId, tplBId, thresholdB, it, 1).firstOrNull()
        }) ?: findMatches(imageId, tplBId, thresholdB, region, 1).firstOrNull() ?: return null

        return buildPair(a, b, tplA, tplB, tolerance)
    }

    /** 同行容差：显式值优先，否则取较小模板高度的 30%，最少 4px */
    internal fun resolveTolerance(requested: Int, heightA: Int, heightB: Int): Int =
        if (requested > 0) requested else max(4, roundToInt(min(heightA, heightB) * 0.3))

    /** 同行判定：中心 Y 差值不超过容差即为同一行（边界值算同行） */
    internal fun isSameRow(rowDelta: Int, tolerance: Int): Boolean = rowDelta <= tolerance

    /**
     * 两个模板句柄是否来自同一份素材。
     * 同一路径被解析两次会得到不同 id，但 [imageName] 相同；
     * 截图之类没有来源名的句柄不在此判定，避免误判。
     */
    internal fun isSameSource(idA: Int, idB: Int): Boolean {
        if (idA == idB) return true
        val nameA = imageName(idA) ?: return false
        val nameB = imageName(idB) ?: return false
        return nameA == nameB && nameA != "屏幕截图"
    }

    /**
     * 过滤掉互相重叠的命中，只保留彼此间距足够的位置。
     * 模板匹配的相邻聚合对小模板不够严格，同一处可能残留多个候选；
     * 只有中心在水平和垂直方向都相距半个模板以上时，才视为不同位置。
     */
    internal fun distinctPositions(hits: List<Match>, width: Int, height: Int): List<Match> {
        if (hits.isEmpty()) return emptyList()
        val halfW = (width / 2).coerceAtLeast(1)
        val halfH = (height / 2).coerceAtLeast(1)
        val kept = ArrayList<Match>()
        for (hit in hits) {
            val overlaps = kept.any { existing ->
                kotlin.math.abs(existing.x - hit.x) < halfW && kotlin.math.abs(existing.y - hit.y) < halfH
            }
            if (!overlaps) kept.add(hit)
        }
        return kept
    }

    /** 两个匹配中心的中点 */
    internal fun midPoint(ax: Int, ay: Int, bx: Int, by: Int): Pair<Int, Int> =
        Pair((ax + bx) / 2, (ay + by) / 2)

    /** 有符号差值 b - a */
    internal fun delta(a: Int, b: Int): Int = b - a

    private fun roundToInt(value: Double): Int = kotlin.math.round(value).toInt()

    /** A 所在的行带；行带覆盖 A 的中心 ± 容差 ± B 的半高，并裁剪到画面内 */
    private fun rowBandOf(
        region: IntArray?,
        a: Match,
        heightB: Int,
        tolerance: Int,
        screenW: Int,
        screenH: Int
    ): IntArray? {
        val full = clampRegion(region, screenW, screenH) ?: return null
        val half = heightB / 2
        val top = (a.y - tolerance - half).coerceAtLeast(full.top)
        val bottom = (a.y + tolerance + half).coerceAtMost(full.bottom)
        val height = bottom - top
        // 行带过矮时放弃优化，交给调用方回退全屏搜索
        if (height < heightB) return null
        return intArrayOf(full.left, top, full.width(), height)
    }

    private fun imageWidth(imageId: Int): Int = image(imageId)?.width ?: 0
    private fun imageHeight(imageId: Int): Int = image(imageId)?.height ?: 0

    private fun buildPair(
        a: Match,
        b: Match,
        tplA: android.graphics.Bitmap,
        tplB: android.graphics.Bitmap,
        tolerance: Int
    ): PairMatch {
        val toleranceValue = if (tolerance > 0) tolerance else resolveTolerance(0, tplA.height, tplB.height)
        val rectA = toRect(a, tplA)
        val rectB = toRect(b, tplB)
        val rowDelta = kotlin.math.abs(a.y - b.y)
        val mid = midPoint(a.x, a.y, b.x, b.y)
        return PairMatch(
            sameRow = isSameRow(rowDelta, toleranceValue),
            rowDelta = rowDelta,
            tolerance = toleranceValue,
            midX = mid.first,
            midY = mid.second,
            dx = delta(a.x, b.x),
            dy = delta(a.y, b.y),
            a = rectA,
            b = rectB
        )
    }

    private fun toRect(match: Match, tpl: android.graphics.Bitmap): MatchRect = MatchRect(
        x = match.x,
        y = match.y,
        left = match.x - tpl.width / 2,
        top = match.y - tpl.height / 2,
        right = match.x + tpl.width / 2,
        bottom = match.y + tpl.height / 2
    )

    /**
     * 由粗到细模板匹配，返回按位置排序的命中（坐标为模板中心）。
     * region 为 [x, y, w, h]；w/h ≤ 0 表示延伸到图像边界。
     */
    fun findMatches(        imageId: Int,
        tplId: Int,
        threshold: Double,
        region: IntArray?,
        max: Int
    ): List<Match> {
        // 模板来源名（文件名），用于日志定位；未知句柄退回 id
        val tplName = imageName(tplId) ?: "image#$tplId"
        // 无法进入匹配流程时同样按失败记 W，避免只看到“找不到”却不知道原因
        fun fail(reason: String): List<Match> {
            debugLogger?.invoke('W', "${tplName}找图失败 $reason")
            return emptyList()
        }
        if (max <= 0) return emptyList()
        val screen = image(imageId) ?: return fail("原因=截屏句柄无效")
        val tpl = image(tplId) ?: return fail("原因=模板句柄无效")
        val r = clampRegion(region, screen.width, screen.height) ?: return fail("原因=搜索区域无效")
        val tw = tpl.width
        val th = tpl.height
        if (tw <= 0 || th <= 0) return fail("原因=模板尺寸无效")
        if (tw > r.width() || th > r.height()) {
            return fail("tpl=${tw}x$th region=${r.width()}x${r.height()} 原因=模板大于搜索区")
        }

        val currentCheck = cancellationCheck ?: {}
        val bitmapGeneration = screen.generationId
        val baseKey = ScreenGrayCache.Key(
            imageId, r.left, r.top, r.right, r.bottom, 1, bitmapGeneration
        )
        val retainScreenCache = { images[imageId] === screen }
        val basePixels = screenGrays.getOrPut(baseKey, retainScreenCache) { screen.toGray(r).px }
        val grayScreen = Gray(r.width(), r.height(), basePixels, currentCheck)
        fun screenLevel(scale: Int): Gray {
            if (scale == 1) return grayScreen
            val key = ScreenGrayCache.Key(
                imageId, r.left, r.top, r.right, r.bottom, scale, bitmapGeneration
            )
            val pixels = screenGrays.getOrPut(key, retainScreenCache) {
                grayScreen.downsample(scale, currentCheck).px
            }
            val width = max(1, r.width() / scale)
            val height = max(1, r.height() / scale)
            return Gray(width, height, pixels, currentCheck)
        }
        val prepared = synchronized(templates) {
            if (images[tplId] !== tpl) null
            else templates.getOrPut(tplId, tpl.generationId) {
                TemplateMatcher.PreparedTemplate(tpl.toGray())
            }
        } ?: return fail("原因=模板已释放")
        return TemplateMatcher.find(
            grayScreen, prepared, threshold, max, r.left, r.top, debugLogger, ::screenLevel,
            tplName = tplName
        )
            .map { Match(it.x, it.y, it.score) }
    }

    // ==================== 内部工具 ====================

    private fun resolve(path: String, scriptDir: File): File? {
        if (path.isBlank()) return null
        val f = File(path)
        return if (f.isAbsolute) f else File(scriptDir, path)
    }

    /** region 为 [x, y, w, h]；非法/越界自动裁剪 */
    private fun clampRegion(region: IntArray?, w: Int, h: Int): Rect? {
        if (region == null || region.size < 2) return Rect(0, 0, w, h)
        val x = region[0].coerceIn(0, w - 1)
        val y = region[1].coerceIn(0, h - 1)
        val rw = if (region.size >= 3 && region[2] > 0) region[2] else w - x
        val rh = if (region.size >= 4 && region[3] > 0) region[3] else h - y
        val right = (x + rw).coerceAtMost(w)
        val bottom = (y + rh).coerceAtMost(h)
        if (right - x <= 0 || bottom - y <= 0) return null
        return Rect(x, y, right, bottom)
    }

    private fun Bitmap.toGray(region: Rect = Rect(0, 0, width, height)): Gray {
        val w = region.width()
        val h = region.height()
        val buf = IntArray(w * h)
        getPixels(buf, 0, w, region.left, region.top, w, h)
        val check = cancellationCheck
        for (i in buf.indices) {
            if (i % 16384 == 0) check?.invoke()
            val c = buf[i]
            buf[i] = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
        }
        return Gray(w, h, buf, check ?: {})
    }
}
