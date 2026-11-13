package com.forest.android.assistant.core.task

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.forest.android.assistant.core.flow.FlowCompiler
import com.forest.android.assistant.core.market.MarketRelease
import com.forest.android.assistant.core.market.MarketSource
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 任务 ZIP 迁移：兼容单任务包，也支持包含多个任务的合集包。 */
object TaskZip {
    private const val TAG = "ScriptJs"
    private const val BUNDLE_ENTRY = "task-bundle.json"
    private const val BUNDLE_FORMAT = "assistant-task-bundle"
    private const val MAX_ENTRIES = 20_000
    private const val MAX_UNPACKED_BYTES = 2L * 1024 * 1024 * 1024
    private const val IMPORT_BACKUP_PREFIX = ".deleted-market-backup-"

    data class Result(val file: File, val sizeBytes: Long)
    data class ImportResult(
        val imported: List<String>,
        val skipped: List<String>,
        val importedGroups: List<String> = emptyList(),
        val overwritten: List<String> = emptyList(),
        val overwrittenGroups: List<String> = emptyList(),
    )

    fun exportDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "zip").apply { mkdirs() }

    /** 保留已有的单任务 ZIP 格式。 */
    fun exportTask(context: Context, taskName: String): Result? {
        val dir = TaskManager.taskDir(context, taskName)
        if (!validName(taskName) || !File(dir, "task.js").isFile) return null
        com.forest.android.assistant.core.flow.FlowCompiler.prepare(context, taskName)
        val out = File(exportDir(context), "$taskName.zip")
        return writeZip(out) { zip -> addDirectory(zip, dir, "") }
    }

    /** 将全部任务写入同一个 ZIP，目录结构为 tasks/<任务名>/...。 */
    fun exportAll(context: Context): Result? {
        val (names, groups) = synchronized(TaskManager) {
            TaskManager.listTasks(context).map { it.name } to TaskGroups.list(context)
        }
        if (names.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val out = File(exportDir(context), "全部任务-$stamp.zip")
        return writeZip(out) { zip ->
            val manifest = JSONObject().put("format", BUNDLE_FORMAT).put("version", 1)
                .put("tasks", JSONArray(names)).put("groups", JSONArray(groups.map(TaskGroups::toJson)))
            zip.putNextEntry(ZipEntry(BUNDLE_ENTRY))
            zip.write((manifest.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            names.forEach { name ->
                check(validName(name)) { "任务名不合法：$name" }
                val dir = TaskManager.taskDir(context, name)
                check(File(dir, "task.js").isFile) { "任务已不存在：$name" }
                com.forest.android.assistant.core.flow.FlowCompiler.prepare(context, name)
                addDirectory(zip, dir, "tasks/$name/")
            }
        }
    }

    private inline fun writeZip(out: File, content: (ZipOutputStream) -> Unit): Result? = try {
        ZipOutputStream(out.outputStream().buffered()).use(content)
        Log.i(TAG, "任务已导出: ${out.absolutePath} (${out.length()} 字节)")
        Result(out, out.length())
    } catch (e: Exception) {
        Log.w(TAG, "导出失败: ${e.message}")
        out.delete()
        null
    }

    private fun addDirectory(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.walkTopDown().filter { it.isFile && it.name != "flow.draft.json" }.forEach { file ->
            val relative = file.relativeTo(dir).path.replace('\\', '/')
            zip.putNextEntry(ZipEntry(prefix + relative))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** 导入单任务包或合集包，并覆盖同名任务。 */
    @Synchronized
    fun importArchive(context: Context, uri: Uri): ImportResult {
        val temp = File(context.cacheDir, "task-import-${UUID.randomUUID()}")
        try {
            check(temp.mkdirs()) { "无法创建导入临时目录" }
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            extract(input, temp)
            val bundle = File(temp, BUNDLE_ENTRY)
            var archivedGroups = emptyList<TaskGroup>()
            val candidates = if (bundle.isFile) {
                // groups 为可选字段，兼容旧合集包；先校验清单，再安装任务。
                check(bundle.length() <= 1_000_000) { "任务合集清单过大" }
                val json = JSONObject(bundle.readText())
                check(json.optString("format") == BUNDLE_FORMAT && json.optInt("version") == 1) {
                    "不支持的任务合集格式"
                }
                val array = json.optJSONArray("tasks") ?: error("任务合集缺少任务列表")
                check(array.length() > 0) { "任务合集为空" }
                val names = (0 until array.length()).map { array.getString(it) }
                check(names.distinct().size == names.size && names.all(::validName)) { "任务合集包含非法或重复任务名" }
                if (json.has("groups")) archivedGroups = TaskGroups.parseBackup(json.getJSONArray("groups"), names.toSet())
                names.map { name -> name to File(temp, "tasks/$name") }
            } else if (!File(temp, "task.js").isFile && File(temp, "tasks").isDirectory) {
                val tasksDir = File(temp, "tasks")
                val children = tasksDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
                check(children.isNotEmpty()) { "tasks 文件夹中没有任务" }
                check(children.all { validName(it.name) }) { "tasks 文件夹包含非法任务名" }
                children.map { it.name to it }
            } else {
                val root = resolveSingleRoot(temp)
                check(File(root, "task.js").isFile) { "不是有效的任务包（缺少 task.js）" }
                val fallback = displayName(context, uri).removeSuffix(".zip")
                val name = sanitize(readName(root) ?: fallback) ?: error("无法确定任务名")
                listOf(name to root)
            }
            val imported = mutableListOf<String>()
            val overwritten = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            candidates.forEach { (name, source) ->
                if (!File(source, "task.js").isFile) {
                    skipped += "任务「$name」缺少 task.js"
                    return@forEach
                }
                try {
                    synchronized(TaskManager) {
                        check(!name.startsWith(".deleted-")) { "任务名称不合法" }
                        val target = TaskManager.checkedTaskDir(context, name)
                        check(target.name == name) { "任务目录无效" }
                        val overwrite = target.exists()
                        if (overwrite) {
                            check(File(target, "task.js").isFile) { "同名目录不是任务" }
                            TaskManager.requireTasksIdle()
                        }
                        installTask(source, target, overwrite)
                        imported += name
                        if (overwrite) overwritten += name
                    }
                } catch (e: Exception) {
                    skipped += "任务「$name」导入失败：${e.message}"
                }
            }
            val groupPlan = if (archivedGroups.isNotEmpty()) {
                try {
                    TaskGroups.restore(context, archivedGroups).also { skipped.addAll(it.skipped) }
                } catch (error: Exception) {
                    skipped += "分组恢复失败：${error.message}"
                    null
                }
            } else null
            val restoredGroups = groupPlan?.restored.orEmpty()
            Log.i(TAG, "导入任务 ${imported.size} 个，恢复分组 ${restoredGroups.size} 个，跳过 ${skipped.size} 项")
            return ImportResult(imported, skipped, restoredGroups, overwritten, groupPlan?.overwritten.orEmpty())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Synchronized
    internal fun importMarketTask(
        context: Context, file: File, release: MarketRelease, rawName: String, overwrite: Boolean,
    ): String {
        val temp = File(context.cacheDir, "market-import-${UUID.randomUUID()}")
        try {
            check(temp.mkdirs()) { "无法创建导入临时目录" }
            val name = rawName.trim()
            require(validName(name) && !name.startsWith(".deleted-")) { "任务名称不合法" }
            prepareMarketTask(file, temp, release, name)
            File(temp, "images").listFiles()?.forEach { image ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.absolutePath, bounds)
                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216L) {
                    "市场包图片无效或尺寸过大：${image.name}"
                }
            }

            return synchronized(TaskManager) {
                val target = TaskManager.checkedTaskDir(context, name)
                check(target.name == name) { "任务目录无效" }
                if (overwrite) {
                    TaskManager.requireTasksIdle()
                    check(File(target, "task.js").isFile && marketSource(target) == release.source) {
                        "对应的本地任务已变更，请返回市场后重试"
                    }
                } else {
                    val existing = TaskManager.listTasks(context).firstOrNull {
                        marketSource(TaskManager.taskDir(context, it.name)) == release.source
                    }
                    if (existing != null) return@synchronized existing.name
                }
                installTask(temp, target, overwrite)
                name
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    internal fun prepareMarketTask(file: File, temp: File, release: MarketRelease, name: String) {
        extract(file.inputStream(), temp, market = true)
        val script = File(temp, "task.js")
        require(script.isFile && script.length() > 0) { "市场包缺少 task.js" }
        val flow = File(temp, "flow.json")
        if (flow.isFile) script.writeText(FlowCompiler.compile(JSONObject(flow.readText())))

        val configFile = File(temp, "config.json")
        val original = if (configFile.isFile) JSONObject(configFile.readText()) else JSONObject()
        val config = JSONObject()
            .put("name", name)
            .put("desc", release.description)
            .put("marketSource", release.source.toJson().put("version", release.version))
        release.targetPackage?.let { config.put("target", it) }
        original.optJSONArray("designSize")?.let { size ->
            require(size.length() == 2 && size.getInt(0) > 0 && size.getInt(1) > 0) { "任务设计尺寸无效" }
            config.put("designSize", size)
        }
        configFile.writeText(config.toString(2) + "\n")
    }

    internal fun marketSource(directory: File): MarketSource? =
        runCatching {
            val config = File(directory, "config.json")
            if (!config.isFile || config.length() > 64 * 1024) return null
            JSONObject(config.readText()).optJSONObject("marketSource")?.let(MarketSource::parse)
        }.getOrNull()

    private fun extract(input: InputStream, temp: File, market: Boolean = false) {
        var entries = 0
        var totalBytes = 0L
        val seen = hashSetOf<String>()
        val maxEntries = if (market) 200 else MAX_ENTRIES
        val maxBytes = if (market) 40L * 1024 * 1024 else MAX_UNPACKED_BYTES
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                check(!unsafePath(name, entry.isDirectory)) { "压缩包包含非法路径：$name" }
                entries++
                check(entries <= maxEntries) { "压缩包文件过多" }
                if (market) {
                    require(marketEntryAllowed(name, entry.isDirectory)) { "市场包包含不支持的文件：$name" }
                    if (entry.isDirectory) require(zip.read() == -1) { "市场包目录条目包含数据" }
                }
                if (!entry.isDirectory) {
                    check(seen.add(name)) { "压缩包包含重复路径：$name" }
                    val target = File(temp, name)
                    check(target.canonicalFile.toPath().startsWith(temp.canonicalFile.toPath())) { "压缩包包含非法路径：$name" }
                    check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true) { "无法创建临时目录" }
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var fileBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            check(totalBytes <= maxBytes) { "压缩包解压后过大" }
                            fileBytes += count
                            if (market) {
                                val limit = if (name.startsWith("images/")) 8L * 1024 * 1024 else 2_000_000L
                                check(fileBytes <= limit) { "市场包文件过大：$name" }
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        check(entries > 0) { "压缩包为空" }
    }

    private fun marketEntryAllowed(name: String, directory: Boolean): Boolean {
        if (directory) return name == "images/"
        if (name == "task.js" || name == "config.json" || name == "flow.json") return true
        return name.startsWith("images/") && name.count { it == '/' } == 1 &&
            name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp")
    }

    internal fun installTask(source: File, target: File, overwrite: Boolean = false) {
        val root = requireNotNull(target.parentFile)
        val staging = File(root, ".deleted-import-${UUID.randomUUID()}")
        try {
            check(source.copyRecursively(staging, overwrite = false)) { "无法复制任务文件" }
            if (overwrite) {
                val history = File(target, RunHistory.FILE_NAME)
                if (history.isFile) history.copyTo(File(staging, history.name), overwrite = true)
                replaceTask(staging, target)
            } else {
                check(!target.exists()) { "任务已存在" }
                check(staging.renameTo(target)) { "无法保存任务" }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun replaceTask(staging: File, target: File) {
        val backup = File(target.parentFile, "$IMPORT_BACKUP_PREFIX${UUID.randomUUID()}")
        check(backup.mkdir()) { "无法创建任务备份目录" }
        val original = File(backup, target.name)
        if (!target.renameTo(original)) {
            backup.delete()
            error("无法暂存原任务")
        }
        if (staging.renameTo(target)) {
            backup.deleteRecursively()
            return
        }
        if (original.renameTo(target)) backup.delete()
        error("无法保存任务")
    }

    // 应用可能在移走旧任务和保存新任务之间退出。
    internal fun recoverImports(root: File) {
        root.listFiles { file -> file.isDirectory && file.name.startsWith(IMPORT_BACKUP_PREFIX) }
            ?.forEach { backup ->
                val children = backup.listFiles() ?: return@forEach
                if (children.isEmpty()) {
                    backup.delete()
                    return@forEach
                }
                val original = children.singleOrNull()?.takeIf { validName(it.name) && it.isDirectory }
                    ?: return@forEach
                val target = File(root, original.name)
                if (target.exists() || original.renameTo(target)) backup.deleteRecursively()
            }
    }

    private fun resolveSingleRoot(temp: File): File {
        if (File(temp, "task.js").isFile) return temp
        val children = temp.listFiles() ?: return temp
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: temp
    }

    private fun readName(root: File): String? = try {
        File(root, "config.json").takeIf { it.isFile }
            ?.let { JSONObject(it.readText()).optString("name").takeIf(String::isNotBlank) }
    } catch (_: Exception) { null }

    private fun displayName(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } catch (_: Exception) { null }
        return name ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private fun sanitize(raw: String): String? = raw.trim().replace('/', '_').replace('\\', '_')
        .takeIf(::validName)

    private fun validName(name: String): Boolean = name.isNotBlank() && name.length <= 50 &&
        name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun unsafePath(name: String, directory: Boolean): Boolean {
        val path = if (directory) name.removeSuffix("/") else name
        return path.isBlank() || path.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(path) ||
            path.any { it.isISOControl() } ||
            path.split('/').any { it == ".." || it == "." || it.isEmpty() }
    }
}
