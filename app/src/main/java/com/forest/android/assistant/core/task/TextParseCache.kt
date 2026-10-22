package com.forest.android.assistant.core.task

/** 单文件文本解析结果缓存；保留原文比对可发现同长度、同时间戳的外部修改。 */
internal class TextParseCache<T>(private val maxChars: Int = 1 shl 20) {
    data class Parsed<T>(val value: T, val error: String? = null)

    private var path: String? = null
    private var text: String? = null
    private var parsed: Parsed<T>? = null

    @Synchronized
    fun get(path: String, text: String): Parsed<T>? =
        parsed?.takeIf { this.path == path && this.text == text }

    @Synchronized
    fun put(path: String, text: String, parsed: Parsed<T>) {
        if (text.length > maxChars) {
            clear()
            return
        }
        this.path = path
        this.text = text
        this.parsed = parsed
    }

    @Synchronized
    fun clear() {
        path = null
        text = null
        parsed = null
    }
}
