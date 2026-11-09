package com.forest.android.assistant.core.flow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowCompilerTest {
    private fun document(vararg nodes: JSONObject): JSONObject =
        JSONObject().put("schemaVersion", 1).put("nodes", JSONArray(nodes.toList()))

    private fun node(id: String, type: String, props: JSONObject): JSONObject =
        JSONObject().put("id", id).put("type", type).put("props", props)

    @Test
    fun compilesWaitConditionAndImageStateModules() {
        val script = FlowCompiler.compile(document(
            node("wait_condition", "waitCondition", JSONObject()
                .put("conditionKind", "image").put("conditionValue", "condition.png")
                .put("combine", "single").put("timeout", 12000).put("poll", 250)
                .put("onTimeout", "stop")),
            node("wait_appear", "waitImageAppear", JSONObject()
                .put("asset", "appear.png").put("threshold", 0.87)
                .put("timeout", 8000).put("poll", 200).put("onTimeout", "continue")),
            node("wait_gone", "waitImageGone", JSONObject()
                .put("asset", "gone.png").put("threshold", 0.91)
                .put("timeout", 9000).put("poll", 300).put("onTimeout", "stop"))
        ))

        assertTrue(script.contains("__flowWaitCondition(function() { return !!__flowImage(\"images/condition.png\""))
        assertTrue(script.contains("__flowWaitCondition(function() { return"))
        assertTrue(script.contains(", 12000, 250)"))
        assertTrue(script.contains("if (!__flow_wait_condition) throw Error('等待条件超时')"))
        assertTrue(script.contains("__flowImage(\"images/appear.png\", 8000, 200, { threshold: 0.87 })"))
        assertTrue(!script.contains("if (!__flow_wait_appear) throw Error('等待图片出现超时')"))
        assertTrue(script.contains("__flowWaitImageGone(\"images/gone.png\", 9000, 300, { threshold: 0.91 })"))
        assertTrue(script.contains("if (!__flow_wait_gone) throw Error('等待图片消失超时')"))
    }

    @Test
    fun compilesAnyAndAllMultiImageMatching() {
        val images = JSONArray()
            .put(JSONObject().put("asset", "a.png").put("threshold", 0.8))
            .put(JSONObject().put("asset", "images/b.png").put("threshold", 0.95))
        val script = FlowCompiler.compile(document(
            node("multi", "multiImage", JSONObject().put("images", images)
                .put("matchMode", "all").put("timeout", 7000).put("poll", 150)
                .put("region", "[1,2,300,400]").put("onMatch", "click")
                .put("onMiss", "stop").put("failure", "skip"))
        ))

        assertTrue(script.contains(
            "__flowMultiImage([{ path: \"images/a.png\", threshold: 0.8 }, { path: \"images/b.png\", threshold: 0.95 }], \"all\", 7000, 150, { region: [1, 2, 300, 400] })"
        ))
        assertTrue(script.contains("throw Error('多图匹配未命中')"))
        assertTrue(script.contains("click(__flow_multi.x, __flow_multi.y)"))
    }

    @Test
    fun rejectsMissingOrInvalidMultiImageEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(node("multi", "multiImage",
                JSONObject().put("images", JSONArray()).put("matchMode", "any"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(node("multi", "multiImage", JSONObject()
                .put("images", JSONArray().put(JSONObject().put("asset", "a.png").put("threshold", 2.0)))
                .put("matchMode", "any"))))
        }
    }
}
