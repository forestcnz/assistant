package com.forest.android.assistant.core.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 轻量 JS 语法高亮：按 token 着色（关键字/字符串/注释/数字/API 名）。
 *
 * 为什么不直接用 Sora Editor：它需要内置 TextMate 语法包（额外 ~1MB 资源），
 * 且新版以 Kotlin 2.4 编译，与本工程 AGP 内置 Kotlin 2.2 的元数据上限冲突。
 * 自研实现零依赖、包体不变，覆盖脚本编辑器所需的高亮 + 补全场景。
 */
object JsHighlighter {

    private const val MAX_CACHED_TEXT_CHARS = 64 * 1024

    // 与编辑器白底配套，保证细小代码文本的对比度。
    private val COLOR_KEYWORD = Color(0xFF3563D9)
    private val COLOR_STRING = Color(0xFF22756D)
    private val COLOR_COMMENT = Color(0xFF78849A)
    private val COLOR_NUMBER = Color(0xFF97521E)
    private val COLOR_API = Color(0xFF6B49A6)
    private val STYLE_KEYWORD = SpanStyle(color = COLOR_KEYWORD, fontWeight = FontWeight.Medium)
    private val STYLE_STRING = SpanStyle(color = COLOR_STRING)
    private val STYLE_COMMENT = SpanStyle(color = COLOR_COMMENT)
    private val STYLE_NUMBER = SpanStyle(color = COLOR_NUMBER)
    private val STYLE_API = SpanStyle(color = COLOR_API)
    private val STYLE_ERROR_LINE = SpanStyle(background = Color(0x22BA3D4C))

    private val KEYWORDS = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
        "break", "continue", "new", "this", "typeof", "instanceof", "in", "of", "try",
        "catch", "finally", "throw", "delete", "void", "null", "undefined", "true", "false",
        "switch", "case", "default", "class", "extends", "super", "async", "await", "yield"
    )

    /** 内置 API 名（含模块末段），用于高亮与补全 */
    private val API_NAMES: Set<String> by lazy {
        ApiDocs.items.flatMap { listOf(it.name, it.name.substringAfterLast('.')) }.toSet()
    }

    private enum class Kind { COMMENT, STRING, NUMBER, KEYWORD, API, PLAIN }

    private data class Token(val start: Int, val end: Int, val kind: Kind)
    private var cachedText: String? = null
    private var cachedErrorLine: Int? = null
    private var cachedHighlight: AnnotatedString? = null

    /** Compose 文本转换器：只改样式不改字符，offset 映射为恒等 */
    fun transformation(errorLine: Int? = null): VisualTransformation = VisualTransformation { text ->
        TransformedText(highlight(text.text, errorLine), OffsetMapping.Identity)
    }

    /** 出错行的行首/行尾字符下标（用于高亮与光标定位）；行号 1 起 */
    fun lineRange(text: String, line: Int): IntRange? {
        if (line < 1) return null
        var current = 1
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                if (current == line) return start until i
                current++
                start = i + 1
            }
        }
        return if (current == line) start until text.length else null
    }

    /** 生成带高亮样式的文本；errorLine 非空时该行加浅红底 */
    @Synchronized
    fun highlight(text: String, errorLine: Int? = null): AnnotatedString {
        cachedHighlight?.let { cached ->
            if (cachedText === text && cachedErrorLine == errorLine) return cached
        }
        val result = buildAnnotatedString {
            append(text)
            if (errorLine != null) {
                lineRange(text, errorLine)?.let { range ->
                    if (!range.isEmpty()) {
                        addStyle(
                            STYLE_ERROR_LINE,
                            range.first,
                            range.last + 1
                        )
                    }
                }
            }
            tokenize(text).forEach { token ->
                val style = when (token.kind) {
                    Kind.KEYWORD -> STYLE_KEYWORD
                    Kind.STRING -> STYLE_STRING
                    Kind.COMMENT -> STYLE_COMMENT
                    Kind.NUMBER -> STYLE_NUMBER
                    Kind.API -> STYLE_API
                    Kind.PLAIN -> null
                }
                if (style != null && token.start < token.end) {
                    addStyle(style, token.start, token.end.coerceAtMost(text.length))
                }
            }
        }
        if (text.length <= MAX_CACHED_TEXT_CHARS) {
            cachedText = text
            cachedErrorLine = errorLine
            cachedHighlight = result
        } else {
            // Avoid retaining two full copies of a large script after its editor closes.
            cachedText = null
            cachedErrorLine = null
            cachedHighlight = null
        }
        return result
    }

    /**
     * 取光标处的标识符前缀（用于补全）：
     * 返回前缀与其起始下标；若无标识符返回 null。
     */
    fun wordBefore(text: String, cursor: Int): Pair<String, Int>? {
        if (cursor <= 0 || cursor > text.length) return null
        var i = cursor
        while (i > 0) {
            val c = text[i - 1]
            if (c.isLetterOrDigit() || c == '_' || c == '.') i-- else break
        }
        if (i == cursor) return null
        val word = text.substring(i, cursor)
        return if (word.isEmpty()) null else Pair(word, i)
    }

    // ==================== tokenizer ====================

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // 行注释
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val start = i
                    while (i < n && text[i] != '\n') i++
                    tokens.add(Token(start, i, Kind.COMMENT))
                }
                // 块注释
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                    tokens.add(Token(start, i, Kind.COMMENT))
                }
                // 字符串（含模板串）
                c == '"' || c == '\'' || c == '`' -> {
                    val start = i
                    val quote = c
                    i++
                    while (i < n) {
                        if (text[i] == '\\') {
                            i += 2
                            continue
                        }
                        if (text[i] == quote) {
                            i++
                            break
                        }
                        i++
                    }
                    tokens.add(Token(start, i.coerceAtMost(n), Kind.STRING))
                }
                // 数字
                c.isDigit() -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) i++
                    tokens.add(Token(start, i, Kind.NUMBER))
                }
                // 标识符
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                    val word = text.substring(start, i)
                    val kind = when {
                        KEYWORDS.contains(word) -> Kind.KEYWORD
                        API_NAMES.contains(word) -> Kind.API
                        else -> Kind.PLAIN
                    }
                    tokens.add(Token(start, i, kind))
                }
                else -> i++
            }
        }
        return tokens
    }
}
