package com.forest.android.assistant.core.market

import com.forest.android.assistant.core.image.ImageApi
import com.forest.android.assistant.core.task.TaskZip
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MarketImportTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun parsesScriptsAndUsesSnapshotUrls() {
        val release = MarketCatalog.parse(catalog(entry(), entry("arena", "2026-11-13")))
        assertEquals(listOf("arena", "flow-demo"), release.map { it.source.scriptId })
        assertEquals(
            "$MARKET_RELEASES/download/market-${"a".repeat(40)}/arena.zip",
            release.first().downloadUrl
        )
        assertThrows(IllegalArgumentException::class.java) {
            MarketCatalog.parse(catalog(entry(), entry()))
        }
    }

    @Test fun linksToChineseSourceDirectories() {
        val entry = entry("arena")
            .put("targetApp", "神雕侠侣")
            .put("title", "竞技场")
        val release = MarketCatalog.parse(catalog(entry)).single()
        assertEquals(
            "/forestcnz/assistant-scripts/tree/${"a".repeat(40)}/scripts/神雕侠侣/竞技场",
            java.net.URI(release.sourceUrl).path
        )
        assertThrows(IllegalArgumentException::class.java) {
            MarketCatalog.parse(catalog(entry.put("title", "..")))
        }
    }

    @Test fun importsFlowFromSourceAndOnlyRetainsPublicConfiguration() {
        val release = MarketCatalog.parse(catalog(entry())).single()
        val archive = archive(
            "task.js" to "log('packaged script');",
            "flow.json" to """{"schemaVersion":1,"nodes":[{"id":"start","type":"log","props":{"text":"flow step"}}]}""",
            "config.json" to """{"name":"Old","designSize":[1080,2400],"loop":{"mode":"forever"},"target":"wrong.app"}"""
        )
        val dir = files.newFolder()
        TaskZip.prepareMarketTask(archive, dir, release, "Imported")
        val config = JSONObject(File(dir, "config.json").readText())
        assertEquals("Imported", config.getString("name"))
        assertEquals(1080, config.getJSONArray("designSize").getInt(0))
        assertFalse(config.has("loop"))
        assertFalse(config.has("target"))
        assertEquals(release.source, TaskZip.marketSource(dir))
        assertTrue(File(dir, "flow.json").isFile)
        assertTrue(File(dir, "task.js").readText().contains("flow step"))
        assertFalse(File(dir, "task.js").readText().contains("packaged script"))
    }

    @Test fun rejectsTraversalAndPrivateFilesInMarketPackages() {
        val release = MarketCatalog.parse(catalog(entry())).single()
        assertThrows(IllegalStateException::class.java) {
            TaskZip.prepareMarketTask(archive("../outside.js" to "data"), files.newFolder(), release, "Imported")
        }
        listOf("history.jsonl", "flow.draft.json", "images/template.js").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                TaskZip.prepareMarketTask(archive("task.js" to "log(1);", path to "data"), files.newFolder(), release, "Imported")
            }
        }
    }

    @Test fun marketImagePathsStayInsideTaskDirectory() {
        val taskDir = files.newFolder("task")
        val other = files.newFile("other.png")
        assertEquals(File(taskDir, "images/template.png").canonicalFile,
            ImageApi.resolve("images/template.png", taskDir, true))
        assertNull(ImageApi.resolve("../other.png", taskDir, true))
        assertNull(ImageApi.resolve(other.absolutePath, taskDir, true))
        assertEquals(other, ImageApi.resolve(other.absolutePath, taskDir, false))
    }

    private fun archive(vararg entries: Pair<String, String>): File = files.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun catalog(vararg entries: JSONObject): String =
        JSONObject().put("schemaVersion", 1).put("releases", JSONArray(entries.toList())).toString()

    private fun entry(id: String = "flow-demo", date: String = "2026-11-12") = JSONObject()
        .put("scriptId", id)
        .put("title", "流程演示").put("description", "输出日志").put("author", "雕雕助手")
        .put("usage", "在任意页面运行").put("environment", "通用").put("capabilities", JSONArray())
        .put("changes", "更新说明").put("updatedAt", date).put("minAppVersionCode", 14)
        .put("sizeBytes", 200).put("sha256", "b".repeat(64)).put("revision", "a".repeat(40))
}
