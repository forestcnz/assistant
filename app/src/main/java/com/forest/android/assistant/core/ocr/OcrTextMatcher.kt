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
}
