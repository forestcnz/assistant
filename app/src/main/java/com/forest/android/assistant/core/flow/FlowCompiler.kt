package com.forest.android.assistant.core.flow

import android.content.Context
import android.util.AtomicFile
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** flow.json is the editable source; task.js is rebuilt before every run. */
object FlowCompiler {
    fun sourceFile(context: Context, taskName: String): File =
        File(TaskManager.taskDir(context, taskName), "flow.json")

    fun draftFile(context: Context, taskName: String): File =
        File(TaskManager.taskDir(context, taskName), "flow.draft.json")

    @Synchronized
    fun saveDraft(context: Context, taskName: String, source: String) {
        require(source.length <= 2_000_000) { "流程草稿过大" }
        JSONObject(source).getJSONArray("nodes")
        writeAtomic(draftFile(context, taskName), source)
    }

    fun isVisual(context: Context, taskName: String): Boolean = sourceFile(context, taskName).isFile

    /** 保存原生 task.js 后，流程任务正式转换为普通脚本任务。 */
    @Synchronized
    fun convertToScript(context: Context, taskName: String) {
        draftFile(context, taskName).delete()
        val source = sourceFile(context, taskName)
        if (source.exists() && !source.delete()) {
            error("无法移除流程源文件，任务仍保持流程模式")
        }
    }

    fun initialDocument(): String = JSONObject()
        .put("schemaVersion", 1)
        .put("nodes", JSONArray())
        .put("globalCount", 1)
        .toString(2)

    @Synchronized
    fun save(context: Context, taskName: String, source: String) {
        val document = JSONObject(source)
        val script = compile(document)
        val directory = TaskManager.taskDir(context, taskName)
        require(File(directory, "task.js").isFile) { "任务不存在" }
        check(!com.forest.android.assistant.core.engine.ScriptRunner.state.value.running) { "请先停止运行" }
        writeAtomic(File(directory, "flow.json"), document.toString(2) + "\n")
        writeAtomic(File(directory, "task.js"), script)
        draftFile(context, taskName).delete()
    }

    @Synchronized
    fun prepare(context: Context, taskName: String) {
        val source = sourceFile(context, taskName)
        if (!source.isFile) return
        val script = compile(JSONObject(source.readText()))
        val target = TaskManager.scriptFile(context, taskName)
        if (!target.isFile || target.readText() != script) writeAtomic(target, script)
    }

    fun compile(document: JSONObject): String {
        require(document.optInt("schemaVersion", 1) == 1) { "流程版本不受支持" }
        val nodes = document.getJSONArray("nodes")
        require(nodes.length() <= 1000) { "步骤过多" }
        val ids = HashSet<String>()
        val templates = linkedSetOf<String>()
        val steps = StringBuilder()
        emitList(nodes, steps, 1, 0, ids, templates)

        val output = StringBuilder("// Generated from flow.json\nsleep(500);\n")
        output.append("var __flowTemplates = Object.create(null);\ntry {\n")
        if (templates.isNotEmpty()) {
            output.append("    for (var __flowPath of [\n")
            templates.forEach { output.append("        ${quoted(it)},\n") }
            output.append("    ]) {\n        __flowTemplates[__flowPath] = images.read(__flowPath);\n    }\n")
        }
        output.append("    __flowMain();\n} finally {\n    images.releaseAll(__flowTemplates);\n}\n\n")
        output.append("""
function __flowImage(path, timeoutMs, intervalMs, options) {
    if (!requestScreenCapture()) throw Error('截屏不可用');
    var template = __flowTemplates[path];
    if (!template) return null;
    return waitImage(template, timeoutMs / 1000, intervalMs / 1000, options);
}
function __flowText(text, options) {
    if (!requestScreenCapture()) throw Error('截屏不可用');
    if (options.match === 'exact') {
        var started = __flowNow();
        var wanted = text.trim().toLowerCase();
        while (true) {
            var shot = captureScreen();
            if (!shot) throw Error('截图失败');
            try {
                var lines = ocr.recognize(shot, options.region ? { region: options.region } : {});
                for (var i = 0; i < lines.length; i++) {
                    if (lines[i].text.trim().toLowerCase() === wanted) return lines[i];
                }
            } finally { images.release(shot); }
            if (__flowNow() - started >= options.timeoutMs) return null;
            sleep(options.intervalMs);
        }
    }
    var result = waitText(text, options);
    if (result.reason === 'capture_error' || result.reason === 'ocr_error' || result.reason === 'invalid_argument')
        throw Error(result.lastError || result.reason);
    return result.value;
}
function __flowMain() {
""".trimStart())
        output.append(steps).append("}\n")
        return output.toString()
    }

    private fun emitList(
        nodes: JSONArray, out: StringBuilder, depth: Int, loops: Int,
        ids: MutableSet<String>, templates: MutableSet<String>
    ) {
        require(depth <= 20) { "嵌套层级过深" }
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val id = node.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && ids.add(id)) { "步骤 ID 重复或非法：$id" }
            val type = node.getString("type")
            val children = node.optJSONArray("children")
            val thenNodes = node.optJSONArray("then")
            val elseNodes = node.optJSONArray("else")
            val properties = compileProperties(node)
            if (properties.optBoolean("enabled", true).not()) continue
            val pad = "    ".repeat(depth)
            out.append(pad).append("__flowNode(${quoted(id)});\n")
            val variable = "__flow_${id.replace('-', '_')}"
            when (type) {
                "sequence" -> emitList(children ?: error("顺序组缺少步骤列表"), out, depth, loops, ids, templates)
                "repeat" -> {
                    val body = children ?: error("循环缺少步骤列表")
                    require(body.length() > 0) { "循环体不能为空" }
                    val mode = properties.optString("mode", "count")
                    val count = properties.optInt("count", 1)
                    val interval = properties.optInt("interval", 0)
                    require(interval >= 0 && (mode == "count" || interval >= 50)) { "循环间隔不合法" }
                    if (mode == "count") require(count in 1..999999) { "循环次数不合法" }
                    else require(mode in listOf("while", "until", "forever")) { "未知循环方式" }
                    val maxDuration = properties.optInt("maxDuration", 0)
                    if (maxDuration > 0) out.append(pad).append("var ${variable}_started = __flowNow();\n")
                    out.append(pad).append("for (var $variable = 0; ")
                        .append(if (mode == "count") "$variable < $count" else "")
                        .append("; $variable++) {\n")
                    if (interval > 0) out.append(pad).append("    if ($variable > 0) sleep($interval);\n")
                    if (mode == "while" || mode == "until") {
                        val condition = condition(properties, templates)
                        out.append(pad).append("    if (")
                            .append(if (mode == "while") "!($condition)" else condition)
                            .append(") break;\n")
                    }
                    val maxIterations = properties.optInt("maxIterations", 0)
                    if (maxIterations > 0) out.append(pad).append("    if ($variable >= $maxIterations) throw Error('循环次数已达上限');\n")
                    if (maxDuration > 0) out.append(pad).append("    if (__flowNow() - ${variable}_started >= $maxDuration) throw Error('循环时间已达上限');\n")
                    emitList(body, out, depth + 1, loops + 1, ids, templates)
                    out.append(pad).append("}\n")
                }
                "condition" -> {
                    out.append(pad).append("if (").append(condition(properties, templates)).append(") {\n")
                    emitList(thenNodes ?: JSONArray(), out, depth + 1, loops, ids, templates)
                    out.append(pad).append("} else {\n")
                    emitList(elseNodes ?: JSONArray(), out, depth + 1, loops, ids, templates)
                    out.append(pad).append("}\n")
                }
                "click", "swipe" -> {
                    val command = if (type == "click")
                        "click(${number(properties, "x")}, ${number(properties, "y")}, ${number(properties, "duration", 50)})"
                    else "swipe(${number(properties, "x")}, ${number(properties, "y")}, ${number(properties, "endX")}, ${number(properties, "endY")}, ${number(properties, "duration", 300)})"
                    out.append(pad).append("if (!$command) ")
                        .append(if (properties.optString("failure") == "skip") "console.warn('操作失败');" else "throw Error('操作失败');")
                        .append('\n')
                }
                "wait" -> out.append(pad).append("sleep(${number(properties, "duration", 1000)});\n")
                "log" -> out.append(pad).append("log(${quoted(properties.optString("text"))});\n")
                "image", "text" -> {
                    val expression = if (type == "image") imageSearch(properties, templates) else textSearch(properties)
                    out.append(pad).append("var $variable = $expression;\n")
                    if (properties.optString("onMiss", "continue") == "stop")
                        out.append(pad).append("if (!$variable) throw Error('识别未命中');\n")
                    if (properties.optString("onMatch", "recognize") == "click") {
                        val x = if (type == "image") "$variable.x" else "$variable.centerX()"
                        val y = if (type == "image") "$variable.y" else "$variable.centerY()"
                        out.append(pad).append("if ($variable && !click($x, $y)) ")
                            .append(if (properties.optString("failure") == "skip") "console.warn('点击失败');" else "throw Error('点击失败');")
                            .append('\n')
                    }
                }
                "break", "continue" -> {
                    require(loops > 0) { "退出循环和进入下一轮只能放在循环内" }
                    out.append(pad).append(type).append(";\n")
                }
                "endRound" -> out.append(pad).append("return;\n")
                "stop" -> out.append(pad).append("__flowStop();\n")
                else -> error("未知模块：$type")
            }
        }
    }

    /** Web 编辑器使用 props 嵌套结构，原生流程和旧数据使用根字段；编译时兼容两种格式。 */
    private fun compileProperties(node: JSONObject): JSONObject {
        val props = node.optJSONObject("props") ?: return node
        return JSONObject(props.toString()).apply {
            node.keys().forEach { key ->
                if (key != "props") put(key, node.get(key))
            }
        }
    }

    /** Keep both the saved flow and any draft in sync with a renamed task image. */
    fun replaceImageReferences(source: String, oldName: String, newName: String): String? {
        val document = JSONObject(source)
        var changed = false
        fun replace(fields: JSONObject, key: String) {
            val value = fields.optString(key)
            val updated = when (value.replace('\\', '/')) {
                oldName -> newName
                "images/$oldName" -> "images/$newName"
                else -> return
            }
            fields.put(key, updated)
            changed = true
        }
        fun visit(nodes: JSONArray?) {
            if (nodes == null) return
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                val props = node.optJSONObject("props")
                val type = node.optString("type")
                val primary = node.optString("conditionKind", props?.optString("conditionKind", "image") ?: "image")
                val secondary = node.optString("secondKind", props?.optString("secondKind") ?: "")
                listOfNotNull(node, props).forEach { fields ->
                    if (type == "image") replace(fields, "asset")
                    if (type == "condition" || type == "repeat") {
                        if (primary == "image") replace(fields, "conditionValue")
                        if (secondary == "image") replace(fields, "secondValue")
                    }
                }
                listOf("children", "then", "else").forEach { visit(node.optJSONArray(it)) }
            }
        }
        visit(document.optJSONArray("nodes"))
        return if (changed) document.toString(2) + "\n" else null
    }

    private fun condition(node: JSONObject, templates: MutableSet<String>): String {
        val first = conditionPart(node.optString("conditionKind", "image"), node.optString("conditionValue"), templates)
        return when (node.optString("combine", "single")) {
            "single" -> first
            "not" -> "!($first)"
            "all", "any" -> {
                val second = conditionPart(node.optString("secondKind", "text"), node.optString("secondValue"), templates)
                val operator = if (node.optString("combine") == "all") "&&" else "||"
                "($first $operator $second)"
            }
            else -> error("未知条件组合")
        }
    }

    private fun conditionPart(kind: String, value: String, templates: MutableSet<String>): String {
        require(value.isNotBlank()) { "检测内容不能为空" }
        return when (kind) {
            "image" -> "!!__flowImage(${quoted(imagePath(value).also { templates.add(it) })}, 0, 300, {})"
            "text" -> "!!__flowText(${quoted(value)}, { timeoutMs: 100, intervalMs: 50 })"
            else -> error("未知检测方式")
        }
    }

    private fun imageSearch(node: JSONObject, templates: MutableSet<String>): String {
        val path = imagePath(node.getString("asset")).also { templates.add(it) }
        val timeout = number(node, "timeout", 5000)
        val interval = number(node, "poll", 300)
        require(timeout > 0 && interval >= 50) { "找图等待参数不合法" }
        val threshold = node.optDouble("threshold", 0.9)
        require(threshold.isFinite() && threshold in 0.0..1.0) { "找图阈值不合法" }
        return "__flowImage(${quoted(path)}, $timeout, $interval, { threshold: $threshold${region(node)} })"
    }

    private fun textSearch(node: JSONObject): String {
        val query = node.getString("text")
        require(query.isNotBlank()) { "找字内容不能为空" }
        val timeout = number(node, "timeout", 5000)
        val interval = number(node, "poll", 300)
        require(timeout > 0 && interval >= 50) { "找字等待参数不合法" }
        val match = node.optString("match", "contains")
        require(match == "contains" || match == "exact") { "找字匹配方式不合法" }
        return "__flowText(${quoted(query)}, { timeoutMs: $timeout, intervalMs: $interval, match: ${quoted(match)}${region(node)} })"
    }

    private fun region(node: JSONObject): String {
        val text = node.optString("region", "").trim()
        if (text.isEmpty()) return ""
        val parts = text.removePrefix("[").removeSuffix("]").split(',').map { it.trim().toIntOrNull() ?: error("区域格式不正确") }
        require(parts.size == 4 && parts.take(2).all { it >= 0 } && parts.drop(2).all { it > 0 }) { "区域格式不正确" }
        return ", region: [${parts.joinToString(", ")}]"
    }

    private fun number(node: JSONObject, key: String, default: Int = -1): Int {
        val value = node.optInt(key, default)
        require(value >= 0) { "$key 不能小于 0" }
        return value
    }

    private fun imagePath(value: String): String {
        val path = value.replace('\\', '/')
        require(path.isNotBlank() && !path.startsWith('/') && ':' !in path && path.split('/').none { it == ".." || it == "." }) { "图片路径不合法" }
        return if (path.startsWith("images/")) path else "images/$path"
    }

    private fun quoted(value: String): String = JSONObject.quote(value)

    private fun writeAtomic(file: File, text: String) {
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }
}
