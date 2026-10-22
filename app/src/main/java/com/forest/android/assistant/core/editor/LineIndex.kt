package com.forest.android.assistant.core.editor

/** 文本行首 UTF-16 偏移索引；行号和列号均从 1 开始。 */
internal class LineIndex private constructor(private val starts: IntArray) {

    fun position(offset: Int): Pair<Int, Int> {
        val target = offset.coerceAtLeast(0)
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= target) low = middle + 1 else high = middle - 1
        }
        val lineIndex = high.coerceAtLeast(0)
        return (lineIndex + 1) to (target - starts[lineIndex] + 1)
    }

    companion object {
        fun from(text: String): LineIndex {
            val lineCount = text.count { it == '\n' } + 1
            val starts = IntArray(lineCount)
            var line = 1
            for (index in text.indices) {
                if (text[index] == '\n') starts[line++] = index + 1
            }
            return LineIndex(starts)
        }
    }
}
