package com.forest.android.assistant.core.flow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test
    fun compilesWaitClickAndWaitStableModules() {
        val script = FlowCompiler.compile(document(
            node("wc", "waitClick", JSONObject()
                .put("asset", "button.png").put("threshold", 0.88)
                .put("timeout", 0).put("poll", 200)
                .put("offsetX", -12).put("offsetY", 8).put("afterWait", 500).put("stable", true)
                .put("clickRetry", 1).put("retryGap", 400).put("missLimit", 3)
                .put("onMiss", "continue").put("failure", "skip")),
            node("ws", "waitStable", JSONObject()
                .put("precision", "high").put("diff", 0.03).put("frames", 3)
                .put("timeout", 6000).put("poll", 150).put("region", "[10,20,300,400]")
                .put("onTimeout", "stop"))
        ))

        assertTrue(script.contains(
            "__flowWaitClick(\"images/button.png\", 0, 200, { threshold: 0.88, offsetX: -12, offsetY: 8, " +
                "afterWait: 500, stable: true, clickRetry: 1, retryGap: 400, missLimit: 3, failure: \"skip\" })"
        ))
        assertTrue(!script.contains("if (!__flow_wc)"))
        assertTrue(script.contains("__flowWaitStable([10, 20, 300, 400], 4, 0.03, 3, 6000, 150)"))
        assertTrue(script.contains("if (!__flow_ws) throw Error('等待界面稳定超时')"))
        assertTrue(script.contains("function __flowWaitStable("))
    }

    @Test
    fun compilesImageSwitchBranchesWithFallback() {
        val cases = JSONArray()
            .put(JSONObject().put("id", "case1").put("type", "switchCase")
                .put("props", JSONObject().put("asset", "a.png").put("threshold", 0.9).put("click", true)))
            .put(JSONObject().put("id", "case2").put("type", "switchCase")
                .put("props", JSONObject().put("asset", "images/b.png").put("threshold", 0.85)
                    .put("region", "[1,2,30,40]")))
        val script = FlowCompiler.compile(document(
            JSONObject().put("id", "sw").put("type", "imageSwitch")
                .put("props", JSONObject().put("timeout", 4000).put("poll", 250).put("onMiss", "stop"))
                .put("children", cases)
                .put("else", JSONArray().put(node("fallback", "log", JSONObject().put("text", "都没有"))))
        ))

        assertTrue(script.contains(
            "__flowSwitch([{ path: \"images/a.png\", threshold: 0.9, click: true, failure: \"stop\" }, " +
                "{ path: \"images/b.png\", threshold: 0.85, region: [1, 2, 30, 40], click: false, failure: \"stop\" }], 4000, 250)"
        ))
        assertTrue(script.contains("throw Error('图片分支未命中')"))
        assertTrue(script.contains("if (__flow_sw && __flow_sw.index === 1) {"))
        assertTrue(script.contains("else if (__flow_sw && __flow_sw.index === 2) {"))
        assertTrue(script.contains("log(\"都没有\")"))
    }

    @Test
    fun compilesDialogClearAndRepeatClickModules() {
        val script = FlowCompiler.compile(document(
            node("clear", "dialogClear", JSONObject()
                .put("images", JSONArray()
                    .put(JSONObject().put("asset", "ok.png").put("threshold", 0.9))
                    .put(JSONObject().put("asset", "images/ok2.png").put("threshold", 0.8)))
                .put("poll", 250).put("emptyRounds", 2).put("maxDuration", 120000).put("maxClicks", 50)
                .put("afterClick", "stable").put("afterWait", 200).put("clickFailure", "skip")
                .put("onDone", "stop")),
            node("tap", "repeatClick", JSONObject().put("x", 100).put("y", 200).put("count", 4)
                .put("interval", 150).put("jitter", 6).put("duration", 60).put("failure", "skip"))
        ))

        assertTrue(script.contains(
            "__flowDialogClear([{ path: \"images/ok.png\", threshold: 0.9 }, { path: \"images/ok2.png\", threshold: 0.8 }], " +
                "250, 2, 120000, 50, \"stable\", 200, \"skip\")"
        ))
        assertTrue(script.contains("__flowStop();"))
        assertTrue(script.contains("for (var __flow_tap = 0; __flow_tap < 4; __flow_tap++) {"))
        assertTrue(script.contains("if (__flow_tap > 0) sleep(150);"))
        assertTrue(script.contains("var __flow_tap_jx = Math.round((Math.random() * 2 - 1) * 6);"))
        assertTrue(script.contains("click((100 + __flow_tap_jx), (200 + __flow_tap_jy), 60)"))
    }

    @Test
    fun compilesWaitEventModes() {
        val script = FlowCompiler.compile(document(
            node("wait_duration", "waitEvent", JSONObject().put("mode", "duration")
                .put("duration", 300000).put("poll", 1000).put("heartbeat", 30000)),
            node("wait_img", "waitEvent", JSONObject().put("mode", "imageGone")
                .put("asset", "loading.png").put("threshold", 0.88)
                .put("duration", 0).put("poll", 500).put("heartbeat", 0).put("onTimeout", "stop"))
        ))

        assertTrue(script.contains("__flowWaitEvent(\"duration\", null, 300000, 1000, 30000, { threshold: 0.9 })"))
        assertTrue(script.contains(
            "__flowWaitEvent(\"imageGone\", \"images/loading.png\", 0, 500, 0, { threshold: 0.88 })"
        ))
        assertTrue(script.contains("if (!__flow_wait_img) throw Error('等待超时')"))
    }

    @Test
    fun compilesFailureRetryAndAnchorJump() {
        val script = FlowCompiler.compile(document(
            node("tap", "click", JSONObject().put("x", 10).put("y", 20).put("duration", 50)
                .put("retries", 2).put("retryGap", 400).put("failure", "stop")),
            node("jump", "waitImageAppear", JSONObject().put("asset", "a.png")
                .put("timeout", 1000).put("poll", 100).put("onTimeout", "goto:主城")),
            JSONObject().put("id", "home").put("type", "anchor")
                .put("props", JSONObject().put("name", "主城")),
            node("open", "log", JSONObject().put("text", "回主城"))
        ))

        assertTrue(script.contains("var __flow_tap_ok = false;"))
        assertTrue(script.contains("for (var __flow_tap_try = 0; __flow_tap_try <= 2; __flow_tap_try++) {"))
        assertTrue(script.contains("if (__flow_tap_try > 0) sleep(400);"))
        assertTrue(script.contains("__flow_tap_ok = !!__flow_tap;"))
        assertTrue(script.contains("if (!__flow_tap_ok) throw Error('操作失败')"))
        assertTrue(script.contains("if (__flowGate(\"jump\")) {"))
        assertTrue(script.contains("__flowGoto(\"主城\");"))
        assertTrue(script.contains("if (__flowGate(\"anchor:主城\")) {"))
        assertTrue(script.contains("function __flowGate(name)"))
        assertTrue(script.contains("var __flowResume = null;"))
        // var 赋值不会随声明提升，跳转状态必须在 try 之前初始化
        assertTrue(script.indexOf("var __flowResume = null;") < script.indexOf("try {"))
        assertEquals(1, Regex("var __flowResume = null;").findAll(script).count())
    }

    @Test
    fun compilesErrorHandlerRecovery() {
        val handler = JSONObject().put("id", "recover").put("type", "onError")
            .put("props", JSONObject().put("mode", "restart").put("retries", 2).put("delay", 1500))
            .put("children", JSONArray().put(node("back", "log", JSONObject().put("text", "兜底"))))
        val script = FlowCompiler.compile(document(
            node("main", "log", JSONObject().put("text", "主流程")),
            handler
        ))

        assertTrue(script.contains("var __flowRecoveryMode = \"restart\";"))
        assertTrue(script.contains("var __flowRecoveryLimit = 3;"))
        assertTrue(script.contains("var __flowRecoveryDelay = 1500;"))
        assertTrue(script.contains("function __flowRecover() {"))
        assertTrue(script.contains("log(\"兜底\")"))
        assertTrue(script.contains("if (__flowError && __flowError.__flowJump) {"))
        assertTrue(script.contains("if (++__flowJumps > 20)"))
        assertTrue(script.contains("__flowResume = '__start__';"))
        assertTrue(script.contains("throw __flowFatal('截屏不可用')"))
    }

    @Test
    fun rejectsInvalidAnchorsAndDuplicateHandlers() {
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(
                JSONObject().put("id", "a1").put("type", "anchor").put("props", JSONObject().put("name", "重复")),
                JSONObject().put("id", "a2").put("type", "anchor").put("props", JSONObject().put("name", "重复"))
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(
                node("jump", "click", JSONObject().put("x", 1).put("y", 2).put("failure", "goto:不存在"))
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(
                JSONObject().put("id", "seq").put("type", "sequence").put("children", JSONArray().put(
                    JSONObject().put("id", "nested").put("type", "anchor")
                        .put("props", JSONObject().put("name", "内部"))
                ))
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(
                JSONObject().put("id", "e1").put("type", "onError"),
                JSONObject().put("id", "e2").put("type", "onError")
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(
                node("tap", "click", JSONObject().put("x", 1).put("y", 2).put("retries", 9))
            ))
        }
    }

    @Test
    fun rejectsInvalidSettingsOfNewModules() {
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(node("sw", "imageSwitch", JSONObject()).put("children", JSONArray()
                .put(JSONObject().put("id", "case").put("type", "switchCase")
                    .put("props", JSONObject().put("asset", ""))))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(node("wait", "waitEvent", JSONObject().put("mode", "wrong"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(node("clear", "dialogClear", JSONObject()
                .put("images", JSONArray().put(JSONObject().put("asset", "a.png").put("threshold", 0.9)))
                .put("emptyRounds", 0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(node("ws", "waitStable", JSONObject().put("precision", "ultra"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCompiler.compile(document(node("tap", "repeatClick", JSONObject().put("jitter", 300))))
        }
    }
}
