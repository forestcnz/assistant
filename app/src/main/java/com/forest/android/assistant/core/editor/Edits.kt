package com.forest.android.assistant.core.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 编辑器输入增强：
 * - **括号/引号自动配对**：输入 `(` `[` `{` `"` `'` `` ` `` 自动补全闭合符，光标置于中间；
 * - **跳过闭合符**：光标后已是同名闭合符时，再输入一次直接越过（不重复插入）；
 * - **自动缩进**：回车时沿用上一行缩进；若上一行以 `{` `(` `[` 结尾则再多缩进一级。
 *
 * 只处理“单字符插入/回车”这类可确定的编辑，粘贴等多字符变更原样放行，避免误改内容。
 * 对应设计 docs/README.md 第 4.4 节「自动缩进、括号匹配」。
 */
object Edits {

    private const val INDENT = "    "

    private val PAIRS = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '"' to '"',
        '\'' to '\'',
        '`' to '`'
    )

    private val CLOSERS = PAIRS.values.toSet()

    /** 对一次输入变更做增强处理，返回应写入编辑器的值 */
    fun apply(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        // 仅处理纯插入（长度 +1）
        if (new.text.length != old.text.length + 1) return new
        val insertAt = new.selection.start - 1
        if (insertAt < 0 || insertAt >= new.text.length) return new
        val typed = new.text[insertAt]

        // 回车：自动缩进
        if (typed == '\n') return newline(old, new, insertAt)

        // 已存在同名闭合符 → 跳过（不发生实际插入）
        if (typed in CLOSERS && insertAt < old.text.length && old.text[insertAt] == typed) {
            return TextFieldValue(old.text, TextRange(insertAt + 1))
        }

        // 自动配对
        val close = PAIRS[typed] ?: return new
        // 紧接着是标识符/空白时不补引号，避免在已有单词旁插入多余符号
        if ((typed == '"' || typed == '\'' || typed == '`')) {
            val next = old.text.getOrNull(insertAt)
            if (next != null && (next.isLetterOrDigit() || next == '_')) return new
        }
        val text = new.text.substring(0, insertAt + 1) + close + new.text.substring(insertAt + 1)
        return TextFieldValue(text, TextRange(insertAt + 1))
    }

    private fun newline(old: TextFieldValue, new: TextFieldValue, insertAt: Int): TextFieldValue {
        // 上一行（光标所在行的上文）
        val before = old.text.substring(0, insertAt)
        val lineStart = before.lastIndexOf('\n') + 1
        val currentLine = before.substring(lineStart)
        val indent = currentLine.takeWhile { it == ' ' || it == '\t' }
        val extra = if (currentLine.trimEnd().endsWith("{") ||
            currentLine.trimEnd().endsWith("(") ||
            currentLine.trimEnd().endsWith("[")
        ) INDENT else ""
        // 光标后紧邻闭合符时，闭合符单独占一行（常见代码风格）
        val after = old.text.substring(insertAt)
        val nextNonSpace = after.takeWhile { it == ' ' || it == '\t' }
        val nextChar = after.getOrNull(nextNonSpace.length)
        val isCloser = nextChar != null && (nextChar in CLOSERS)
        val body = if (isCloser) {
            "\n$indent$extra\n$indent" + nextNonSpace
        } else {
            "\n$indent$extra"
        }
        val text = old.text.substring(0, insertAt) + body + after.substring(nextNonSpace.length)
        return TextFieldValue(text, TextRange(insertAt + 1 + indent.length + extra.length))
    }
}
