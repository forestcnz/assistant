package com.forest.android.assistant.core.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * OCR 文字识别：识别截图/任意图片中的文字并返回坐标，供脚本定位点击。
 *
 * 引擎选型：设计文档首选 PaddleOCR（离线模型约 15-20MB）、MLKit 备选；
 * 本实现采用 **MLKit 中文识别（模型内置在 APK，约 10MB）**，无需额外模型文件。
 * 传入 `engine: "paddle"` 时回退到 MLKit 并给出提示。
 * 对应脚本 API 见 docs/README.md 第 5.5 节。
 */
object OcrApi {

    private const val TAG = "ScriptJs"
    private const val TIMEOUT_SECONDS = 20L

    /** 识别出的一段文字（坐标为图片像素坐标） */
    data class Item(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float
    ) {
        fun centerX(): Int = (left + right) / 2

        fun centerY(): Int = (top + bottom) / 2
    }

    @Volatile
    private var ready = false

    @Volatile
    private var recognizer: TextRecognizer? = null

    /** 是否已初始化 */
    fun isReady(): Boolean = ready

    /**
     * 初始化识别引擎；engine 为 "mlkit"（默认）或 "paddle"。
     * 当前仅集成 MLKit，paddle 会回退到 MLKit。
     */
    fun init(engine: String?): Boolean {
        if (engine?.lowercase() == "paddle") {
            Log.w(TAG, "OCR：当前版本未内置 PaddleOCR，已回退到 MLKit")
        }
        return try {
            if (recognizer == null) {
                recognizer = TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                )
            }
            ready = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "OCR 初始化失败: ${e.message}")
            false
        }
    }

    /** 识别图片中的全部文字（按行）；region 为 [x, y, w, h]，可空 */
    fun recognize(bitmap: Bitmap, region: IntArray?): List<Item> {
        if (!ready && !init(null)) return emptyList()
        val client = recognizer ?: return emptyList()

        val cropRect = clampRegion(region, bitmap.width, bitmap.height)
        val target = if (cropRect != null) {
            Bitmap.createBitmap(
                bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height()
            )
        } else {
            bitmap
        }
        val offsetX = cropRect?.left ?: 0
        val offsetY = cropRect?.top ?: 0

        return try {
            val result = Tasks.await(
                client.process(InputImage.fromBitmap(target, 0)),
                TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            val items = ArrayList<Item>()
            result.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    val box = line.boundingBox
                    val text = line.text
                    if (box != null && text.isNotBlank()) {
                        items.add(
                            Item(
                                text = text,
                                left = box.left + offsetX,
                                top = box.top + offsetY,
                                right = box.right + offsetX,
                                bottom = box.bottom + offsetY,
                                confidence = line.confidence.takeIf { it >= 0f } ?: 1f
                            )
                        )
                    }
                }
            }
            items
        } catch (e: Exception) {
            Log.w(TAG, "OCR 识别失败: ${e.message}")
            emptyList()
        } finally {
            if (target !== bitmap) target.recycle()
        }
    }

    /** 仅返回拼接后的文本（按行） */
    fun recognizeText(bitmap: Bitmap, region: IntArray?): String =
        recognize(bitmap, region).joinToString("\n") { it.text }

    /**
     * 按文字查找；findAll=false 返回首个匹配，true 返回全部匹配。
     * 匹配规则：先精确相等，再退化为包含（忽略大小写与首尾空白）。
     */
    fun find(bitmap: Bitmap, text: String, region: IntArray?, findAll: Boolean): List<Item> {
        val query = text.trim()
        if (query.isEmpty()) return emptyList()
        val all = recognize(bitmap, region)
        val exact = all.filter { it.text.trim().equals(query, ignoreCase = true) }
        val matched = if (exact.isNotEmpty()) {
            exact
        } else {
            all.filter { it.text.contains(query, ignoreCase = true) }
        }
        return if (findAll) matched else matched.take(1)
    }

    /**
     * 清理空白、标点和符号后按行做包含匹配（忽略大小写、统一全角字符）。
     * 返回识别顺序中的首个匹配，保留原始文本和坐标；空查询不触发识别。
     */
    fun findContains(bitmap: Bitmap, text: String, region: IntArray?): Item? {
        if (OcrTextMatcher.normalize(text).isEmpty()) return null
        return OcrTextMatcher.findContains(recognize(bitmap, region), text)
    }

    /** 多关键词命中的结果：[item] 为匹配行，[keyword] 为命中的原始关键词 */
    data class KeywordMatch(val item: Item, val keyword: String)

    /**
     * 多关键词宽松包含匹配：只执行一次 OCR，再按行顺序找首个命中任意关键词的行。
     * 全部关键词清理后为空时直接返回 null，不触发识别。
     */
    fun findContainsAny(bitmap: Bitmap, texts: List<String>, region: IntArray?): KeywordMatch? {
        // 先判空，避免无效查询触发一次完整 OCR
        if (texts.none { OcrTextMatcher.normalize(it).isNotEmpty() }) return null
        return OcrTextMatcher.findContainsAny(recognize(bitmap, region), texts)
    }

    private fun clampRegion(region: IntArray?, w: Int, h: Int): Rect? {
        if (region == null || region.size < 4) return null
        val x = region[0].coerceIn(0, w - 1)
        val y = region[1].coerceIn(0, h - 1)
        val rw = if (region[2] > 0) region[2] else w - x
        val rh = if (region[3] > 0) region[3] else h - y
        val right = (x + rw).coerceAtMost(w)
        val bottom = (y + rh).coerceAtMost(h)
        if (right - x <= 0 || bottom - y <= 0) return null
        // 区域过小时按全图处理（裁太小反而影响识别准确率）
        if (right - x < 8 || bottom - y < 8) return null
        return Rect(x, y, right, bottom)
    }
}
