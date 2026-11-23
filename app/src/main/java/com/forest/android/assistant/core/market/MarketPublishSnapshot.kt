package com.forest.android.assistant.core.market

import android.content.Context
import android.graphics.BitmapFactory
import com.forest.android.assistant.core.flow.FlowCompiler
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class MarketPublishConflict(message: String) : IllegalStateException(message)

internal data class MarketPublishSnapshot(
    val id: String,
    val task: String,
    val directory: String,
    val head: MarketGitHubHead,
    val metadata: JSONObject,
    val folder: File,
    val files: Map<String, File>,
    val zipBytes: Long,
) {
    val scriptId: String get() = metadata.getString("scriptId")
    val version: Int get() = metadata.getInt("version")
    val title: String get() = metadata.getString("title")

    fun summary(): JSONObject = JSONObject().put("id", id).put("task", task).put("directory", directory)
        .put("route", head.route.wireName).put("release", metadata).put("zipBytes", zipBytes)
        .put("files", JSONArray(files.map { (path, file) ->
            JSONObject().put("path", path).put("bytes", file.length())
        }))

    fun discard() { folder.deleteRecursively() }

    companion object {
        private const val MAX_TEXT_BYTES = 2_000_000L
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_UNPACKED_BYTES = 40L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 200
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun preview(context: Context, cache: File, request: JSONObject, account: MarketGitHubUser,
                    github: MarketGitHub): MarketPublishSnapshot {
            val id = UUID.randomUUID().toString().replace("-", "")
            val name = request.getString("task")
            val title = request.required("title", 50)
            val targetApp = request.optional("targetApp", 80)
            val targetPackage = request.optional("targetPackage", 160)
            require(safeName(title) && (targetApp == null || safeName(targetApp))) { "市场名称或目标应用名称含非法字符" }
            require(targetPackage == null || (targetApp != null && packagePattern.matches(targetPackage))) { "目标包名需要有效的目标应用名称" }
            val directory = "scripts/${targetApp ?: "通用"}/$title"
            val head = github.head()
            val requestedId = request.optional("updateId", 48)
            val old = if (requestedId != null) {
                github.releaseMetadata(head.sha, directory)?.takeIf { it.optString("scriptId") == requestedId }
                    ?: throw MarketPublishConflict("市场条目已变更，请刷新后重新选择")
            } else {
                if (github.directoryExists(head.sha, directory)) {
                    throw MarketPublishConflict("市场已有同应用、同名称的任务，请选择更新该条目或使用其他名称")
                }
                null
            }
            val version = old?.getInt("version")?.let {
                require(it in 1 until Int.MAX_VALUE) { "当前市场版本无法递增" }
                it + 1
            } ?: 1
            val minimum = request.getInt("minAppVersionCode")
            require(minimum > 0) { "最低助手版本号必须是正整数" }
            val capabilities = request.getJSONArray("capabilities").strings().map(String::trim)
            require(capabilities.size <= 8 && capabilities.all { it.length in 1..80 }) { "最多可填 8 项能力，每项最多 80 字" }
            val metadata = JSONObject().put("title", title).put("scriptId", requestedId ?: "dd-$id")
                .put("version", version).put("author", old?.getString("author") ?: account.login)
                .put("description", request.required("description", 200)).put("usage", request.required("usage", 2000))
                .put("environment", request.required("environment", 500)).put("changes", request.required("changes", 500))
                .put("capabilities", JSONArray(capabilities)).put("minAppVersionCode", minimum)
                .put("updatedAt", beijingDate())
            targetApp?.let { metadata.put("targetApp", it) }
            targetPackage?.let { metadata.put("targetPackage", it) }
            val images = request.getJSONArray("images").strings()
            require(images.size <= MAX_ZIP_ENTRIES && images.distinct().size == images.size) { "素材过多或重复" }
            val folder = File(cache, id)
            check(folder.mkdirs()) { "无法创建发布快照" }
            try {
                val files = synchronized(TaskManager) { copySavedTask(context, name, folder, images) }
                val bytes = measureZip(files)
                File(folder, "release.json").also {
                    it.writeText(metadata.toString(2) + "\n")
                    files["release.json"] = it
                }
                return MarketPublishSnapshot(id, name, directory, head, metadata, folder, files, bytes)
            } catch (error: Exception) {
                folder.deleteRecursively()
                throw error
            }
        }

        private fun copySavedTask(context: Context, name: String, folder: File, images: List<String>): LinkedHashMap<String, File> {
            TaskManager.requireTasksIdle()
            require(TaskManager.listTasks(context).any { it.name == name }) { "任务不存在" }
            val directory = TaskManager.checkedTaskDir(context, name)
            val source = File(directory, "task.js")
            val flow = File(directory, "flow.json")
            val files = linkedMapOf("task.js" to File(folder, "task.js"))
            val selected = images.toSet()
            if (flow.isFile) {
                require(!File(directory, "flow.draft.json").isFile) { "请先在流程编辑器保存草稿，再发布" }
                val copy = copyFile(flow, folder, MAX_TEXT_BYTES)
                val references = linkedSetOf<String>()
                val script = FlowCompiler.compile(JSONObject(copy.readText()), references)
                val missing = references.map { it.removePrefix("images/") }.filterNot(selected::contains)
                require(missing.isEmpty()) { "请选择流程依赖的素材：${missing.take(8).joinToString("、")}" }
                files.getValue("task.js").writeText(script)
                require(files.getValue("task.js").length() in 1..MAX_TEXT_BYTES) { "生成的脚本超出市场文件大小限制" }
                files["flow.json"] = copy
            } else {
                copyFile(source, folder, MAX_TEXT_BYTES)
            }
            val config = TaskManager.readConfig(context, name)
            if (config.hasDesignSize()) {
                val file = File(folder, "config.json")
                file.writeText(JSONObject().put("designSize", JSONArray(listOf(config.designWidth, config.designHeight))).toString(2) + "\n")
                files["config.json"] = file
            }
            require(files.size + images.size <= MAX_ZIP_ENTRIES) { "市场包最多包含 $MAX_ZIP_ENTRIES 个文件" }
            if (images.isEmpty()) return files
            val targetImages = File(folder, "images").apply { check(mkdir()) }
            val localImages = File(directory, "images")
            require(localImages.canonicalFile == localImages) { "任务素材目录无效" }
            var totalBytes = files.values.sumOf(File::length)
            for (image in images.sorted()) {
                require(safeName(image) && imageMime(image) != null) { "市场不支持该素材：$image" }
                val local = File(localImages, image)
                require(local.length() <= MAX_UNPACKED_BYTES - totalBytes) { "市场包解压大小超过 40 MiB" }
                val copy = copyFile(local, targetImages, MAX_IMAGE_BYTES)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(copy.absolutePath, bounds)
                require(bounds.outMimeType == imageMime(image) && bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216L) { "素材格式或尺寸不受市场支持：$image" }
                files["images/$image"] = copy
                totalBytes += copy.length()
            }
            return files
        }

        private fun copyFile(source: File, target: File, maxBytes: Long): File {
            require(source.canonicalFile == source && source.isFile && source.length() in 1..maxBytes) { "文件缺失或大小不符合市场要求：${source.name}" }
            return source.copyTo(File(target, source.name)).also {
                require(it.length() in 1..maxBytes) { "文件超出市场大小限制：${source.name}" }
            }
        }

        // 用与市场相同的 ZIP 压缩方式计算大小，快照只保存源码文件。
        private fun measureZip(files: Map<String, File>): Long {
            require(files.values.sumOf(File::length) <= MAX_UNPACKED_BYTES) { "市场包解压大小超过 40 MiB" }
            var size = 0L
            val counter = object : OutputStream() {
                override fun write(b: Int) { size++ }
                override fun write(b: ByteArray, off: Int, len: Int) { size += len }
            }
            ZipOutputStream(counter).use { zip ->
                files.forEach { (path, file) ->
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            require(size <= MAX_MARKET_ZIP_BYTES) { "市场 ZIP 不能超过 20 MiB" }
            return size
        }

        /** 市场目录统一使用北京时间日期（yyyy-MM-dd）。 */
        private fun beijingDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }.format(Date())

        fun imageMime(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> null
        }

        private fun safeName(name: String) = name.isNotBlank() && name !in setOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

        private fun JSONObject.optional(key: String, limit: Int): String? =
            optString(key, "").trim().takeIf(String::isNotEmpty)?.also { require(it.length <= limit) { "$key 最多 $limit 字" } }

        private fun JSONObject.required(key: String, limit: Int): String = requireNotNull(optional(key, limit)) { "缺少字段：$key" }

        private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    }
}
