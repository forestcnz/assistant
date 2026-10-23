package com.forest.android.assistant.core.recording

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 每个成功步骤立即落盘，进程意外退出后已录制的任务仍然存在。 */
class RecordingTask(
    context: Context,
    val name: String,
    val cropWidth: Int,
    val cropHeight: Int,
    val mode: RecordingMode,
    val searchRegionWidth: Int = DEFAULT_SEARCH_REGION_SIZE,
    val searchRegionHeight: Int = DEFAULT_SEARCH_REGION_SIZE
) {
    private val directory = TaskManager.taskDir(context, name)
    val steps = mutableListOf<RecordedOperation>()
    var width = 0
    var height = 0

    init {
        require(name.isNotBlank() && name.length <= 50 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "任务名称不合法" }
        require(cropWidth in 20..500 && cropHeight in 20..500) { "截图宽度和高度均需为 20～500 像素" }
        require(searchRegionWidth >= cropWidth && searchRegionHeight >= cropHeight) { "页面范围宽度和高度不能小于对应的截图尺寸" }
        check(!directory.exists() && directory.mkdirs()) { "任务已存在或无法创建目录" }
        check(File(directory, "images").mkdirs()) { "无法创建素材目录" }
    }

    fun saveImage(bitmap: Bitmap, contextImage: Boolean = false): String {
        val folder = if (contextImage) "contexts" else "images"
        check(File(directory, folder).isDirectory || File(directory, folder).mkdirs()) { "无法创建截图目录" }
        val path = "$folder/step_${(steps.size + 1).toString().padStart(3, '0')}.png"
        File(directory, path).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "保存点击截图失败" }
        }
        return path
    }

    fun removeImage(path: String?) {
        if (path != null) File(directory, path).delete()
    }

    fun persist() {
        if (steps.isEmpty()) return
        val operations = JSONArray()
        steps.forEach { step ->
            operations.put(JSONObject().apply {
                put("type", step.kind.name.lowercase())
                put("points", JSONArray().apply {
                    step.points.forEach { put(JSONArray(listOf(it.x, it.y))) }
                })
                put("durationMs", step.durationMs)
                put("delayMs", step.delayMs)
                put("intervalMs", step.intervalMs)
                step.image?.let { put("image", it) }
                step.contextImage?.let { put("contextImage", it) }
                step.searchRegion?.let { put("searchRegion", JSONArray(listOf(it.left, it.top, it.width, it.height))) }
                step.crop?.let { put("crop", JSONObject().apply {
                    put("left", it.left); put("top", it.top)
                    put("width", it.width); put("height", it.height)
                }) }
            })
        }
        atomicWrite("recording.json", JSONObject().apply {
            put("version", 4)
            put("mode", mode.name.lowercase())
            put("screenSize", JSONArray(listOf(width, height)))
            put("cropWidth", cropWidth)
            put("cropHeight", cropHeight)
            put("searchRegionWidth", searchRegionWidth)
            put("searchRegionHeight", searchRegionHeight)
            put("operations", operations)
        }.toString(2))
        atomicWrite("config.json", JSONObject().apply {
            put("name", name)
            put("desc", "${mode.label}录制 · ${steps.size} 步 · ${width}×${height}")
            put("loop", JSONObject().put("count", 1).put("interval", 0))
        }.toString(2))
        // task.js 最后写入：任务列表只展示已经具备配置和素材的可运行任务。
        atomicWrite("task.js", RecordingScript.generate(width, height, steps, mode, searchRegionWidth, searchRegionHeight))
    }

    fun discardIfEmpty() {
        if (steps.isEmpty()) directory.deleteRecursively()
    }

    private fun atomicWrite(name: String, content: String) {
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}
