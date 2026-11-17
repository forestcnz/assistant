package com.forest.android.assistant.core.recording

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingTaskRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    /** 构造落盘快照，直接验证进程重启后的脚本恢复结果。 */
    private fun snapshot(trailingDelayMs: Long? = null): File {
        val directory = temporary.newFolder()
        val json = JSONObject()
            .put("version", if (trailingDelayMs == null) 4 else 5)
            .put("mode", "coordinate_only")
            .put("screenSize", JSONArray(listOf(900, 1600)))
            .put("operations", JSONArray().put(JSONObject()
                .put("type", "click")
                .put("points", JSONArray().put(JSONArray(listOf(10, 20))))
                .put("durationMs", 100)))
        trailingDelayMs?.let { json.put("trailingDelayMs", it) }
        File(directory, "recording.json").writeText(json.toString())
        return directory
    }

    @Test fun recoveredScriptKeepsWaitAfterLastClick() {
        val directory = snapshot(trailingDelayMs = 3750)

        RecordingTask.recoverOne(directory)

        val script = File(directory, "task.js").readText()
        assertTrue(script.contains("click(10, 20, 100)"))
        assertTrue(script.endsWith("sleep(3750);\n"))
        assertEquals(1, JSONObject(File(directory, "config.json").readText())
            .getJSONObject("loop").getInt("count"))
    }

    @Test fun legacySnapshotStillRecoversWithoutFinalWait() {
        val directory = snapshot()

        RecordingTask.recoverOne(directory)

        val script = File(directory, "task.js").readText()
        assertTrue(script.contains("click(10, 20, 100)"))
        assertEquals(1, Regex("sleep\\(").findAll(script).count())
        assertFalse(script.contains("录制结束前等待"))
    }
}
