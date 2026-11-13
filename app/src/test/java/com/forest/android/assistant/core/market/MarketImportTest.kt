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

    @Test fun readsScriptVersionAndAcceptsLegacyCatalogs() {
        assertEquals(1, MarketCatalog.parse(catalog(entry())).single().version)
        assertEquals(3, MarketCatalog.parse(catalog(entry().put("version", 3))).single().version)
        for (invalid in listOf(0, -1, "2", 2.5, Int.MAX_VALUE.toLong() + 1, JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                MarketCatalog.parse(catalog(entry().put("version", invalid)))
            }
        }
    }

    @Test fun importsFlowFromSourceAndOnlyRetainsPublicConfiguration() {
        val release = MarketCatalog.parse(catalog(entry().put("version", 3))).single()
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
        assertEquals(3, config.getJSONObject("marketSource").getInt("version"))
        assertTrue(File(dir, "flow.json").isFile)
        assertTrue(File(dir, "task.js").readText().contains("flow step"))
        assertFalse(File(dir, "task.js").readText().contains("packaged script"))
    }

    @Test fun overwriteReplacesTaskFilesAndKeepsHistory() {
        val original = files.newFolder("Imported")
        File(original, "task.js").writeText("old script")
        File(original, "flow.json").writeText("old flow")
        File(original, "history.jsonl").writeText("old history")
        val incoming = files.newFolder("Incoming")
        File(incoming, "task.js").writeText("new script")
        File(incoming, "config.json").writeText("new config")

        assertThrows(IllegalStateException::class.java) { TaskZip.installTask(incoming, original) }
        assertEquals("old script", File(original, "task.js").readText())
        TaskZip.installTask(incoming, original, overwrite = true)

        assertEquals("new script", File(original, "task.js").readText())
        assertEquals("new config", File(original, "config.json").readText())
        assertEquals("old history", File(original, "history.jsonl").readText())
        assertFalse(File(original, "flow.json").exists())
    }

    @Test fun restoresOriginalTaskAfterAnInterruptedOverwrite() {
        val backup = files.newFolder(".deleted-market-backup-interrupted", "本地任务")
        File(backup, "task.js").writeText("old script")

        TaskZip.recoverMarketImports(files.root)

        assertEquals("old script", File(files.root, "本地任务/task.js").readText())
        assertFalse(File(files.root, ".deleted-market-backup-interrupted").exists())
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
