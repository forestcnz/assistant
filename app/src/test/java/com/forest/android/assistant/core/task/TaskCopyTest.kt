package com.forest.android.assistant.core.task

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class TaskCopyTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun source(): File = temporary.newFolder("原任务").apply {
        File(this, "task.js").writeText("click(10, 20);\n")
    }

    @Test fun copiesConfigurationAssetsAndCustomFilesAsIndependentContent() {
        val source = source()
        val originalConfig = """{"name":"原任务","desc":"说明","target":"com.example.app","designSize":[1080,1920],"loop":{"mode":"count","count":3,"interval":2},"custom":{"key":"value"}}"""
        File(source, "config.json").writeText(originalConfig)
        File(source, "images/history").mkdirs()
        File(source, "images/图片.png").writeBytes(byteArrayOf(0, 1, -1, 42))
        File(source, "images/history/旧图.png").writeBytes(byteArrayOf(9, 8, 7))
        File(source, "helper.js").writeText("const helper = 1;")
        File(source, "history.jsonl").writeText("old run history")
        val target = File(temporary.root, "副本")

        TaskCopy.create(source, target)

        val config = JSONObject(File(target, "config.json").readText())
        assertEquals("副本", config.getString("name"))
        assertEquals("说明", config.getString("desc"))
        assertEquals("com.example.app", config.getString("target"))
        assertEquals(3, config.getJSONObject("loop").getInt("count"))
        assertEquals(1080, config.getJSONArray("designSize").getInt(0))
        assertEquals("value", config.getJSONObject("custom").getString("key"))
        assertEquals(originalConfig, File(source, "config.json").readText())
        assertArrayEquals(byteArrayOf(0, 1, -1, 42), File(target, "images/图片.png").readBytes())
        assertArrayEquals(byteArrayOf(9, 8, 7), File(target, "images/history/旧图.png").readBytes())
        assertEquals("const helper = 1;", File(target, "helper.js").readText())
        assertFalse(File(target, "history.jsonl").exists())
        File(target, "task.js").writeText("changed")
        assertEquals("click(10, 20);\n", File(source, "task.js").readText())
    }

    @Test fun copiesSavedVisualFlowWithoutDraft() {
        val source = source()
        val flow = """{"schemaVersion":1,"nodes":[{"id":"step","type":"wait","props":{"duration":100}}]}"""
        File(source, "flow.json").writeText(flow)
        File(source, "flow.draft.json").writeText("invalid draft")
        val target = File(temporary.root, "流程副本")
        TaskCopy.create(source, target)
        assertEquals(flow, File(target, "flow.json").readText())
        assertFalse(File(target, "flow.draft.json").exists())
        assertEquals("click(10, 20);\n", File(target, "task.js").readText())
        assertEquals("流程副本", JSONObject(File(target, "config.json").readText()).getString("name"))
    }

    @Test fun rejectsExistingTargetAndMissingSourceWithoutOverwriting() {
        val source = source()
        val target = temporary.newFolder("已存在")
        File(target, "task.js").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { TaskCopy.create(source, target) }
        assertEquals("keep", File(target, "task.js").readText())
        assertThrows(IllegalArgumentException::class.java) { TaskCopy.create(source, source) }
        assertThrows(IllegalArgumentException::class.java) {
            TaskCopy.create(File(temporary.root, "不存在"), File(temporary.root, "新任务"))
        }
        assertFalse(File(temporary.root, "新任务").exists())
    }

    @Test fun corruptConfigurationLeavesNoPartialTask() {
        val source = source()
        File(source, "config.json").writeText("{invalid")
        val target = File(temporary.root, "副本")
        assertThrows(JSONException::class.java) { TaskCopy.create(source, target) }
        assertFalse(target.exists())
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".deleted-copy-") })
    }

    @Test fun refusesTargetOutsideTaskRoot() {
        val source = source()
        val outside = temporary.newFolder("outside")
        assertThrows(IllegalArgumentException::class.java) { TaskCopy.create(source, File(outside, "副本")) }
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun rejectsLinkedFilesAndCleansStagingOnFailure() {
        val source = source()
        val external = temporary.newFile("private.txt").apply { writeText("private") }
        try {
            Files.createSymbolicLink(File(source, "linked.txt").toPath(), external.toPath())
        } catch (error: Exception) {
            assumeNoException("当前系统不允许创建测试符号链接", error)
        }
        val target = File(temporary.root, "副本")
        assertThrows(IllegalArgumentException::class.java) { TaskCopy.create(source, target) }
        assertFalse(target.exists())
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".deleted-copy-") })
        assertEquals("private", external.readText())
    }
}
