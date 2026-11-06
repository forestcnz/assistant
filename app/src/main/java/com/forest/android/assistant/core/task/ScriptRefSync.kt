package com.forest.android.assistant.core.task

/**
 * 素材重命名后同步脚本引用：只做文本级替换与预览，不碰文件系统，便于单测。
 *
 * 匹配规则（按优先级，同一行只替换最长匹配）：
 * 1. `images/旧名`（含 `images\旧名`），大小写敏感，避免误伤其他路径；
 * 2. 独立的旧文件名（前后不是文件名字符），如 `read("旧名")`。
 *
 * 只输出「会被修改的行」用于预览；用户确认后才把替换结果写回 task.js。
 */
object ScriptRefSync {

    /** 预览行：行号从 1 开始，before/after 为该行替换前后的完整文本 */
    data class LineChange(
        val lineNumber: Int,
        val before: String,
        val after: String,
        val replacements: Int
    )

    /** 替换结果 */
    data class Plan(
        val oldName: String,
        val newName: String,
        val original: String,
        val updated: String,
        val changes: List<LineChange>
    ) {
        val changedLineCount: Int get() = changes.size
        val replacementCount: Int get() = changes.sumOf { it.replacements }
        val hasChanges: Boolean get() = changes.isNotEmpty()
    }

    /** 判断字符是否属于文件名（用于确认旧名是独立 token，避免 tpl.png 命中 tpl.png.bak） */
    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    /**
     * 计算重命名后的脚本同步方案。
     * @param script 原脚本全文
     * @param oldName 旧素材文件名（不含目录）
     * @param newName 新素材文件名（不含目录）
     */
    fun plan(script: String, oldName: String, newName: String): Plan {
        val empty = Plan(oldName, newName, script, script, emptyList())
        if (oldName.isBlank() || newName.isBlank() || oldName == newName) return empty
        if (script.isEmpty()) return empty

        val changes = ArrayList<LineChange>()
        val out = StringBuilder(script.length + 16)
        var replacedTotal = 0

        // split 保留空行，行号与编辑器一致（1 起）
        script.split('\n').forEachIndexed { index, line ->
            val result = replaceLine(line, oldName, newName)
            if (result.count > 0) {
                changes += LineChange(index + 1, line, result.text, result.count)
                replacedTotal += result.count
            }
            if (index > 0) out.append('\n')
            out.append(result.text)
        }

        if (replacedTotal == 0) return empty
        return Plan(oldName, newName, script, out.toString(), changes)
    }

    private data class LineResult(val text: String, val count: Int)

    private fun replaceLine(line: String, oldName: String, newName: String): LineResult {
        if (oldName.isEmpty() || !line.contains(oldName)) return LineResult(line, 0)

        val builder = StringBuilder(line.length)
        var count = 0
        var i = 0
        while (i < line.length) {
            // 优先匹配 images/旧名 或 images\旧名（整段替换，避免出现 images/新名 时再被裸名规则二次替换）
            val dirMatch = matchDirRef(line, i, oldName, newName)
            if (dirMatch != null) {
                builder.append(dirMatch.second)
                count += dirMatch.first
                i += dirMatch.third
                continue
            }
            // 裸名：前后必须是分隔符/空白/引号等非文件名字符
            if (line.startsWith(oldName, i) && isStandalone(line, i, oldName.length)) {
                builder.append(newName)
                count++
                i += oldName.length
                continue
            }
            builder.append(line[i])
            i++
        }
        return LineResult(builder.toString(), count)
    }

    /** 返回 (替换处数=1, 替换后的文本, 原文本消耗长度)，不匹配返回 null */
    private fun matchDirRef(
        line: String,
        start: Int,
        oldName: String,
        newName: String
    ): Triple<Int, String, Int>? {
        val prefixes = arrayOf("images/", "images\\")
        for (prefix in prefixes) {
            if (!line.startsWith(prefix, start)) continue
            val nameStart = start + prefix.length
            if (!line.startsWith(oldName, nameStart)) continue
            val end = nameStart + oldName.length
            // 后面不能紧跟文件名字符，避免 images/a.png 命中 images/a.png2
            if (end < line.length && isNameChar(line[end])) continue
            return Triple(1, prefix + newName, end - start)
        }
        return null
    }

    /** 旧名是否作为独立 token 出现（前后不是文件名字符） */
    private fun isStandalone(line: String, start: Int, length: Int): Boolean {
        val before = if (start > 0) line[start - 1] else null
        val afterIndex = start + length
        val after = if (afterIndex < line.length) line[afterIndex] else null
        if (before != null && isNameChar(before)) return false
        if (after != null && isNameChar(after)) return false
        return true
    }
}
