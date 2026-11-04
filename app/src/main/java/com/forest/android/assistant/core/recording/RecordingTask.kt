package com.forest.android.assistant.core.recording

import android.content.Context
import android.graphics.Bitmap
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 录制任务落盘：
 * - 每成功一步只更新 recording.json（单文件轻量快照，不加 fsync），不再重写配置与脚本；
 * - 暂停和完成时调用 [persist] 生成 config.json 与 task.js；
 * - 进程意外退出后由 [recoverPending] 用 recording.json 补全缺失的脚本与配置，
 *   已录制的任务仍然存在。
 */
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

    /** 步骤写入（主线程）与落盘读取（IO 线程）可能并发，列表本身只在锁内访问 */
    private val stepList = mutableListOf<RecordedOperation>()

    /** 已录制步骤的不可变快照，外部只读 */
    val steps: List<RecordedOperation> get() = synchronized(stepList) { stepList.toList() }

    /** 追加一步，返回追加后的步骤数 */
    fun addStep(step: RecordedOperation): Int = synchronized(stepList) {
        stepList.add(step)
        stepList.size
    }

    var width = 0
    var height = 0

    init {
        require(name.isNotBlank() && name.length <= 50 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "任务名称不合法" }
        // 纯坐标模式不截图、不生成素材，尺寸参数不参与录制
        if (mode != RecordingMode.COORDINATE_ONLY) {
            require(cropWidth in 20..500 && cropHeight in 20..500) { "截图宽度和高度均需为 20～500 像素" }
            require(searchRegionWidth >= cropWidth && searchRegionHeight >= cropHeight) { "页面范围宽度和高度不能小于对应的截图尺寸" }
        }
        check(!directory.exists() && directory.mkdirs()) { "任务已存在或无法创建目录" }
        check(File(directory, "images").mkdirs()) { "无法创建素材目录" }
    }

    /** 保存模板小图（只有坐标/图片模式会调用）；返回任务内相对路径 */
    fun saveImage(bitmap: Bitmap): String {
        val folder = "images"
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

    /**
     * 每步录制后只更新 recording.json：单文件、无 fsync，
     * 进程被杀后仍可用它恢复；脚本与配置留到暂停/完成时生成。
     */
    @Synchronized
    fun snapshot() {
        val current = steps
        if (current.isEmpty()) return
        writeAtomic(File(directory, "recording.json"), recordingJson(current).toString(2))
    }

    /** 生成完整任务文件（recording.json + config.json + task.js），暂停和完成时调用 */
    @Synchronized
    fun persist() {
        // 先取一份快照：写文件期间新步骤可能继续追加
        val current = steps
        if (current.isEmpty()) return
        writeAtomic(File(directory, "recording.json"), recordingJson(current).toString(2))
        writeAtomic(File(directory, "config.json"), configJson(current).toString(2))
        // task.js 最后写入：任务列表只展示已经具备配置和素材的可运行任务。
        writeAtomic(File(directory, "task.js"),
            RecordingScript.generate(width, height, current, mode, searchRegionWidth, searchRegionHeight))
    }

    fun discardIfEmpty() {
        if (steps.isEmpty()) directory.deleteRecursively()
    }

    private fun recordingJson(current: List<RecordedOperation>): JSONObject = JSONObject().apply {
        put("version", 4)
        put("mode", mode.name.lowercase())
        put("screenSize", JSONArray(listOf(width, height)))
        put("cropWidth", cropWidth)
        put("cropHeight", cropHeight)
        put("searchRegionWidth", searchRegionWidth)
        put("searchRegionHeight", searchRegionHeight)
        put("operations", JSONArray().apply { current.forEach { put(stepJson(it)) } })
    }

    private fun configJson(current: List<RecordedOperation>): JSONObject = JSONObject().apply {
        put("name", name)
        put("desc", "${mode.label}录制 · ${current.size} 步 · ${width}×${height}")
        put("loop", JSONObject().put("count", 1).put("interval", 0))
    }

    private fun stepJson(step: RecordedOperation): JSONObject = JSONObject().apply {
        put("type", step.kind.name.lowercase())
        put("points", JSONArray().apply {
            step.points.forEach { put(JSONArray(listOf(it.x, it.y))) }
        })
        put("durationMs", step.durationMs)
        put("delayMs", step.delayMs)
        put("intervalMs", step.intervalMs)
        step.image?.let { put("image", it) }
        step.searchRegion?.let { put("searchRegion", JSONArray(listOf(it.left, it.top, it.width, it.height))) }
        step.crop?.let { put("crop", JSONObject().apply {
            put("left", it.left); put("top", it.top)
            put("width", it.width); put("height", it.height)
        }) }
    }

    companion object {
        /**
         * 进程重启后补全未完成落盘的录制任务：
         * recording.json 比 task.js 新（或脚本缺失）时，用快照重建脚本与配置。
         * 普通任务没有 recording.json，不受影响。
         */
        fun recoverPending(context: Context) {
            TaskManager.tasksRoot(context).listFiles { file -> file.isDirectory }?.forEach { dir ->
                runCatching { recoverOne(dir) }
            }
        }

        private data class Snapshot(
            val mode: RecordingMode,
            val width: Int,
            val height: Int,
            val searchRegionWidth: Int,
            val searchRegionHeight: Int,
            val steps: List<RecordedOperation>
        )

        private fun recoverOne(dir: File) {
            val snapshotFile = File(dir, "recording.json")
            if (!snapshotFile.isFile) return
            val script = File(dir, "task.js")
            // 脚本已是最新（暂停/完成时写过）就无需恢复
            if (script.isFile && script.lastModified() >= snapshotFile.lastModified()) return
            val snapshot = parseRecording(snapshotFile) ?: return
            File(dir, "config.json").let { config ->
                writeAtomic(config, JSONObject().apply {
                    put("name", dir.name)
                    put("desc", "${snapshot.mode.label}录制 · ${snapshot.steps.size} 步 · " +
                        "${snapshot.width}×${snapshot.height}")
                    put("loop", JSONObject().put("count", 1).put("interval", 0))
                }.toString(2))
            }
            writeAtomic(script, RecordingScript.generate(snapshot.width, snapshot.height,
                snapshot.steps, snapshot.mode, snapshot.searchRegionWidth, snapshot.searchRegionHeight))
        }

        private fun parseRecording(file: File): Snapshot? = runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val storedMode = json.optString("mode")
            val mode = RecordingMode.values().firstOrNull { it.name.equals(storedMode, true) }
                ?: RecordingMode.COORDINATE
            val size = json.optJSONArray("screenSize")
            val operations = json.optJSONArray("operations") ?: JSONArray()
            val steps = (0 until operations.length()).mapNotNull { index ->
                operations.optJSONObject(index)?.let { parseStep(it) }
            }
            if (steps.isEmpty()) return@runCatching null
            Snapshot(
                mode = mode,
                width = size?.optInt(0) ?: 0,
                height = size?.optInt(1) ?: 0,
                searchRegionWidth = json.optInt("searchRegionWidth", DEFAULT_SEARCH_REGION_SIZE),
                searchRegionHeight = json.optInt("searchRegionHeight", DEFAULT_SEARCH_REGION_SIZE),
                steps = steps
            )
        }.getOrNull()

        private fun parseStep(json: JSONObject): RecordedOperation? = runCatching {
            val kind = when (json.optString("type")) {
                "click" -> RecordedOperation.Kind.CLICK
                "long_press" -> RecordedOperation.Kind.LONG_PRESS
                "swipe" -> RecordedOperation.Kind.SWIPE
                else -> return null
            }
            val array = json.optJSONArray("points") ?: return null
            val points = (0 until array.length()).mapNotNull { index ->
                array.optJSONArray(index)?.let { RecordedPoint(it.optInt(0), it.optInt(1)) }
            }
            if (points.isEmpty()) return null
            RecordedOperation(
                kind = kind,
                points = points,
                durationMs = json.optLong("durationMs"),
                delayMs = json.optLong("delayMs"),
                intervalMs = json.optLong("intervalMs"),
                image = json.optString("image").takeIf { it.isNotEmpty() },
                crop = json.optJSONObject("crop")?.let {
                    RecordedCrop(it.optInt("left"), it.optInt("top"), it.optInt("width"), it.optInt("height"))
                },
                searchRegion = json.optJSONArray("searchRegion")?.let {
                    RecordedCrop(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3))
                }
            )
        }.getOrNull()
    }
}

/**
 * 先写临时文件再改名：改名是原子操作，崩溃时不会留下半个文件。
 * 不加 fsync，让每步快照保持毫秒级开销；进程被杀不会丢数据，只有突然断电可能丢失最后几笔。
 */
private fun writeAtomic(target: File, content: String) {
    val dir = target.parentFile ?: return
    val temp = File.createTempFile(target.name, ".tmp", dir)
    try {
        temp.writeText(content, Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}
