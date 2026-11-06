package com.forest.android.assistant.core.flow

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class FlowDocument(
    val nodes: List<FlowNode> = emptyList(),
    val globalCount: Int = 1
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("nodes", nodes.toJsonArray())
        .put("globalCount", globalCount)

    fun source(): String = toJson().toString(2)

    fun find(id: String): FlowNode? {
        nodes.forEach { node ->
            node.find(id)?.let { return it }
        }
        return null
    }

    fun locationOf(id: String): NodeLocation? {
        nodes.forEachIndexed { index, node ->
            if (node.id == id) return NodeLocation(null, SLOT_CHILDREN, index)
            node.locationOf(id)?.let { return it }
        }
        return null
    }

    fun insert(target: InsertTarget, node: FlowNode): FlowDocument =
        copy(nodes = nodes.insert(target, node))

    fun replace(id: String, replacement: FlowNode): FlowDocument =
        copy(nodes = nodes.replace(id, replacement))

    fun remove(id: String): FlowDocument =
        copy(nodes = nodes.remove(id))

    fun move(id: String, target: InsertTarget): FlowDocument {
        val node = find(id) ?: return this
        val location = locationOf(id) ?: return this
        val without = remove(id)
        val adjusted = if (
            location.parentId == target.parentId &&
            location.slot == target.slot &&
            location.index < target.index
        ) target.copy(index = target.index - 1) else target
        return without.insert(adjusted, node)
    }

    fun duplicate(id: String): FlowDocument {
        val node = find(id) ?: return this
        val location = locationOf(id) ?: return this
        val copy = node.deepCopyWithNewIds()
        return insert(InsertTarget(location.parentId, location.slot, location.index + 1), copy)
    }

    companion object {
        const val SLOT_CHILDREN = "children"
        const val SLOT_THEN = "then"
        const val SLOT_ELSE = "else"

        fun parse(source: String): FlowDocument {
            val json = JSONObject(source)
            require(json.optInt("schemaVersion", 1) == 1) { "流程版本不受支持" }
            return FlowDocument(
                nodes = json.optJSONArray("nodes").orEmptyNodes(),
                globalCount = json.optInt("globalCount", 1).coerceIn(1, 999999)
            )
        }
    }
}

data class FlowNode(
    val id: String,
    val type: String,
    val title: String,
    val enabled: Boolean = true,
    val children: List<FlowNode> = emptyList(),
    val thenNodes: List<FlowNode> = emptyList(),
    val elseNodes: List<FlowNode> = emptyList(),
    val props: JSONObject = JSONObject()
) {
    val isContainer: Boolean
        get() = type == "sequence" || type == "repeat" || type == "condition"

    val displayTitle: String
        get() = when {
            type != "repeat" -> title
            props.optString("mode", "count") == "while" -> "当条件成立时"
            props.optString("mode", "count") == "until" -> "重复直到满足"
            props.optString("mode", "count") == "forever" -> "持续循环"
            else -> "重复 ${props.optInt("count", 1)} 次"
        }

    val summary: String
        get() = when (type) {
            "sequence" -> "${countLeaves()} 个动作 · 按序执行"
            "repeat" -> {
                val mode = props.optString("mode", "count")
                if (mode == "count") {
                    "轮间隔 ${props.optInt("interval", 0)} ms · ${children.size} 个步骤"
                } else {
                    "${conditionLabel()} · ${children.size} 个步骤"
                }
            }
            "click" -> "X ${props.optInt("x", 0)} / Y ${props.optInt("y", 0)}"
            "swipe" -> "滑动 ${props.optInt("duration", 300)} ms"
            "wait" -> "等待 ${props.optInt("duration", 1000)} ms"
            "condition" -> "${conditionLabel()} · ${conditionValue()}"
            "image" -> "${props.optString("asset", "未选择素材")} · ${
                if (props.optString("onMatch", "recognize") == "click") "命中后点击" else "仅识别"
            }"
            "text" -> "查找「${props.optString("text", "未填写")}」 · ${
                if (props.optString("onMatch", "recognize") == "click") "命中后点击" else "仅识别"
            }"
            "log" -> props.optString("text", "记录日志")
            "stop" -> "停止当前任务与剩余队列"
            "continue" -> "跳过本轮余下步骤"
            "break" -> "退出最近一层循环"
            "endRound" -> "完成当前整任务轮次"
            else -> type
        }

    fun find(id: String): FlowNode? {
        if (this.id == id) return this
        children.forEach { child -> child.find(id)?.let { return it } }
        thenNodes.forEach { child -> child.find(id)?.let { return it } }
        elseNodes.forEach { child -> child.find(id)?.let { return it } }
        return null
    }

    fun locationOf(id: String): NodeLocation? {
        children.forEachIndexed { index, child ->
            if (child.id == id) return NodeLocation(this.id, FlowDocument.SLOT_CHILDREN, index)
            child.locationOf(id)?.let { return it }
        }
        thenNodes.forEachIndexed { index, child ->
            if (child.id == id) return NodeLocation(this.id, FlowDocument.SLOT_THEN, index)
            child.locationOf(id)?.let { return it }
        }
        elseNodes.forEachIndexed { index, child ->
            if (child.id == id) return NodeLocation(this.id, FlowDocument.SLOT_ELSE, index)
            child.locationOf(id)?.let { return it }
        }
        return null
    }

    fun withProperty(key: String, value: Any?): FlowNode {
        val next = JSONObject(props.toString())
        if (value == null) next.remove(key) else next.put(key, value)
        return copy(props = next)
    }

    fun withTitle(value: String): FlowNode = copy(title = value)

    fun withEnabled(value: Boolean): FlowNode = copy(enabled = value)

    fun toJson(): JSONObject = JSONObject(props.toString()).apply {
        put("id", id)
        put("type", type)
        put("title", title)
        put("enabled", enabled)
        if (children.isNotEmpty() || type == "sequence" || type == "repeat") {
            put("children", children.toJsonArray())
        }
        if (thenNodes.isNotEmpty() || elseNodes.isNotEmpty() || type == "condition") {
            put("then", thenNodes.toJsonArray())
            put("else", elseNodes.toJsonArray())
        }
    }

    fun deepCopyWithNewIds(): FlowNode {
        val serial = idSerial.incrementAndGet().toString(36)
        return copy(
            id = "node-${System.currentTimeMillis().toString(36)}-$serial",
            children = children.map { it.deepCopyWithNewIds() },
            thenNodes = thenNodes.map { it.deepCopyWithNewIds() },
            elseNodes = elseNodes.map { it.deepCopyWithNewIds() },
            props = JSONObject(props.toString())
        )
    }

    fun countLeaves(): Int {
        if (!enabled) return 0
        if (children.isNotEmpty()) return children.sumOf { it.countLeaves() }
        if (thenNodes.isNotEmpty() || elseNodes.isNotEmpty()) {
            return thenNodes.sumOf { it.countLeaves() } + elseNodes.sumOf { it.countLeaves() }
        }
        return 1
    }

    private fun conditionLabel(): String = when (props.optString("conditionKind", "image")) {
        "text" -> "文字存在"
        else -> "图片存在"
    }

    private fun conditionValue(): String =
        props.optString("conditionValue").ifBlank { "未设置" }

    companion object {
        private val idSerial = AtomicLong()

        fun parse(json: JSONObject): FlowNode = FlowNode(
            id = json.getString("id"),
            type = json.getString("type"),
            title = json.optString("title", json.optString("type")),
            enabled = json.optBoolean("enabled", true),
            children = json.optJSONArray("children").orEmptyNodes(),
            thenNodes = json.optJSONArray("then").orEmptyNodes(),
            elseNodes = json.optJSONArray("else").orEmptyNodes(),
            props = JSONObject(json.toString()).apply {
                remove("id")
                remove("type")
                remove("title")
                remove("enabled")
                remove("children")
                remove("then")
                remove("else")
            }
        )
    }
}

data class InsertTarget(
    val parentId: String?,
    val slot: String,
    val index: Int
) {
    companion object {
        val RootEnd = InsertTarget(null, FlowDocument.SLOT_CHILDREN, Int.MAX_VALUE)
    }
}

data class NodeLocation(
    val parentId: String?,
    val slot: String,
    val index: Int
)

private fun JSONArray?.orEmptyNodes(): List<FlowNode> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) add(FlowNode.parse(getJSONObject(index)))
    }
}

private fun List<FlowNode>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { put(it.toJson()) }
}

private fun List<FlowNode>.insert(target: InsertTarget, node: FlowNode): List<FlowNode> {
    if (target.parentId == null) {
        val index = target.index.coerceIn(0, size)
        return toMutableList().apply { add(index, node) }
    }
    return map { current ->
        when {
            current.id == target.parentId -> current.insert(target, node)
            else -> current.copy(
                children = current.children.insert(target, node),
                thenNodes = current.thenNodes.insert(target, node),
                elseNodes = current.elseNodes.insert(target, node)
            )
        }
    }
}

private fun FlowNode.insert(target: InsertTarget, node: FlowNode): FlowNode {
    fun appendTo(values: List<FlowNode>): List<FlowNode> {
        val index = target.index.coerceIn(0, values.size)
        return values.toMutableList().apply { add(index, node) }
    }
    return when (target.slot) {
        FlowDocument.SLOT_THEN -> copy(thenNodes = appendTo(thenNodes))
        FlowDocument.SLOT_ELSE -> copy(elseNodes = appendTo(elseNodes))
        else -> copy(children = appendTo(children))
    }
}

private fun List<FlowNode>.replace(id: String, replacement: FlowNode): List<FlowNode> =
    map { node ->
        when {
            node.id == id -> replacement
            else -> node.copy(
                children = node.children.replace(id, replacement),
                thenNodes = node.thenNodes.replace(id, replacement),
                elseNodes = node.elseNodes.replace(id, replacement)
            )
        }
    }

private fun List<FlowNode>.remove(id: String): List<FlowNode> =
    mapNotNull { node ->
        when {
            node.id == id -> null
            else -> node.copy(
                children = node.children.remove(id),
                thenNodes = node.thenNodes.remove(id),
                elseNodes = node.elseNodes.remove(id)
            )
        }
    }
