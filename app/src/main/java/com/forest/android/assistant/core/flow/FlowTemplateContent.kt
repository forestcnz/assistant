package com.forest.android.assistant.core.flow

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 模板与任务共用流程格式；图片引用按模块类型处理，避免替换文字条件。 */
object FlowTemplateContent {
    fun parse(source: String): JSONObject {
        require(source.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "模板不能超过 2 MB" }
        val document = JSONObject(source)
        require(document.optInt("schemaVersion", 1) == 1) { "模板流程版本不受支持" }
        var count = 0
        val ids = hashSetOf<String>()
        val types = setOf("click", "swipe", "wait", "log", "sequence", "repeat", "condition", "image", "text", "break", "continue", "endRound", "stop")
        fun visit(nodes: JSONArray, depth: Int) {
            require(depth <= 18) { "模板嵌套层级过深" }
            for (index in 0 until nodes.length()) {
                require(++count <= 999) { "模板步骤过多" }
                val node = nodes.getJSONObject(index)
                val id = node.getString("id")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && ids.add(id)) { "模板步骤 ID 重复或非法" }
                require(node.getString("type") in types) { "模板包含未知模块" }
                listOf("children", "then", "else").forEach { key -> node.optJSONArray(key)?.let { visit(it, depth + 1) } }
            }
        }
        visit(document.getJSONArray("nodes"), 1)
        document.put("schemaVersion", 1).put("globalCount", 1)
        preview(document)
        return document
    }

    /** 单独保存的 break / continue 可在插入循环后使用；用一层循环校验其余参数。 */
    fun preview(document: JSONObject): String {
        if (document.getJSONArray("nodes").length() == 0) return FlowCompiler.compile(document)
        val wrapper = JSONObject().put("id", "validation_${java.util.UUID.randomUUID().toString().replace('-', '_')}")
            .put("type", "repeat").put("props", JSONObject().put("mode", "count").put("count", 1))
            .put("children", document.getJSONArray("nodes"))
        return FlowCompiler.compile(JSONObject().put("schemaVersion", 1).put("nodes", JSONArray().put(wrapper)))
    }

    fun imageNames(document: JSONObject): Set<String> = linkedSetOf<String>().apply {
        visitImages(document) { fields, key ->
            fields.optString(key).takeIf(String::isNotBlank)?.let { add(imageName(it)) }
        }
    }

    fun remapImages(document: JSONObject, names: Map<String, String>): JSONObject {
        val copy = JSONObject(document.toString())
        visitImages(copy) { fields, key ->
            val value = fields.optString(key)
            if (value.isNotBlank()) names[imageName(value)]?.let { replacement ->
                fields.put(key, if (value.replace('\\', '/').startsWith("images/")) "images/$replacement" else replacement)
            }
        }
        return copy
    }

    private fun imageName(raw: String): String {
        val name = raw.replace('\\', '/').removePrefix("images/")
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it.isISOControl() }) { "模板图片路径不合法：$raw" }
        return name
    }

    private fun visitImages(document: JSONObject, action: (JSONObject, String) -> Unit) {
        fun visit(nodes: JSONArray?) {
            if (nodes == null) return
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index)
                val props = node.optJSONObject("props")
                val type = node.optString("type")
                val first = node.optString("conditionKind", props?.optString("conditionKind", "image") ?: "image")
                val second = node.optString("secondKind", props?.optString("secondKind", "text") ?: "text")
                listOfNotNull(node, props).forEach { fields ->
                    if (type == "image") action(fields, "asset")
                    if (type == "condition" || type == "repeat") {
                        if (first == "image") action(fields, "conditionValue")
                        if (second == "image") action(fields, "secondValue")
                    }
                }
                listOf("children", "then", "else").forEach { visit(node.optJSONArray(it)) }
            }
        }
        visit(document.getJSONArray("nodes"))
    }

    /** 同名同内容直接复用，同名不同内容另存，绝不覆盖目标任务已有图片。 */
    fun copyImages(document: JSONObject, source: File, target: File): Map<String, String> {
        val names = imageNames(document)
        val copied = mutableListOf<File>()
        val mapping = linkedMapOf<String, String>()
        try {
            if (names.isNotEmpty()) check(target.mkdirs() || target.isDirectory) { "无法创建模板素材目录" }
            for (name in names) {
                val original = File(source, name)
                require(original.isFile && original.canonicalFile.parentFile == source.canonicalFile) { "模板素材不存在：$name" }
                var destination = File(target, name)
                if (destination.exists() && hash(original).contentEquals(hash(destination))) {
                    mapping[name] = destination.name
                    continue
                }
                var index = 1
                while (destination.exists()) {
                    val extension = original.extension.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
                    destination = File(target, "${original.nameWithoutExtension.take(45)}_template_${index++}$extension")
                }
                copied += destination
                original.copyTo(destination)
                mapping[name] = destination.name
            }
            return mapping
        } catch (error: Exception) {
            copied.forEach { it.delete() }
            throw error
        }
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest()
    }
}
