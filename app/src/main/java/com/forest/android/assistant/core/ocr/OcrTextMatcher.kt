package com.forest.android.assistant.core.ocr

import java.text.Normalizer

/** 宽松包含匹配仅用于新 API，不改变原有精确优先查找的行为。 */
internal object OcrTextMatcher {
    private val nonText = Regex("[^\\p{L}\\p{N}]")

    /** 统一全角/兼容字符，只保留 Unicode 字母（含汉字）和数字。 */
    fun normalize(text: String): String =
        nonText.replace(Normalizer.normalize(text, Normalizer.Form.NFKC), "")

    fun findContains(items: List<OcrApi.Item>, text: String): OcrApi.Item? {
        val query = normalize(text)
        if (query.isEmpty()) return null
        return items.firstOrNull { normalize(it.text).contains(query, ignoreCase = true) }
    }

    /**
     * 多关键词宽松包含匹配：只做一次识别结果的扫描。
     * 行顺序优先（取识别顺序中的首个命中行），同一行内取 [queries] 中靠前的关键词。
     * 清理后为空的关键词会被忽略；全部无效时返回 null。
     */
    fun findContainsAny(items: List<OcrApi.Item>, queries: List<String>): OcrApi.KeywordMatch? {
        // 预先清理查询词并保留原始文本，命中时返回原始关键词而非清理后的形式
        val prepared = queries.mapNotNull { raw ->
            val normalized = normalize(raw)
            if (normalized.isEmpty()) null else normalized to raw
        }
        if (prepared.isEmpty()) return null
        for (item in items) {
            val text = normalize(item.text)
            // 数组顺序即优先级，同一行内不再比较长短或位置
            for ((normalized, raw) in prepared) {
                if (text.contains(normalized, ignoreCase = true)) {
                    return OcrApi.KeywordMatch(item, raw)
                }
            }
        }
        return null
    }
}
