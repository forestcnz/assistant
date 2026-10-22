package com.forest.android.assistant.core.task

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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

    data class Result(val file: File, val sizeBytes: Long)
    data class ImportResult(val imported: List<String>, val skipped: List<String>)

    fun exportDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "zip").apply { mkdirs() }

    /** 保留已有的单任务 ZIP 格式。 */
    fun exportTask(context: Context, taskName: String): Result? {
        val dir = TaskManager.taskDir(context, taskName)
        if (!validName(taskName) || !File(dir, "task.js").isFile) return null
        val out = File(exportDir(context), "$taskName.zip")
        return writeZip(out) { zip -> addDirectory(zip, dir, "") }
    }

    /** 将全部任务写入同一个 ZIP，目录结构为 tasks/<任务名>/...。 */
    fun exportAll(context: Context): Result? {
        val names = TaskManager.listTasks(context).map { it.name }
        if (names.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val out = File(exportDir(context), "全部任务-$stamp.zip")
        return writeZip(out) { zip ->
            names.forEach { name ->
                check(validName(name)) { "任务名不合法：$name" }
                val dir = TaskManager.taskDir(context, name)
                check(File(dir, "task.js").isFile) { "任务已不存在：$name" }
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
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dir).path.replace('\\', '/')
            zip.putNextEntry(ZipEntry(prefix + relative))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** 导入单任务包或合集包；同名任务跳过，其余继续导入。 */
    @Synchronized
    fun importArchive(context: Context, uri: Uri): ImportResult {
        val temp = File(context.cacheDir, "task-import-${UUID.randomUUID()}")
        try {
            check(temp.mkdirs()) { "无法创建导入临时目录" }
            extract(context, uri, temp)
            val bundle = File(temp, BUNDLE_ENTRY)
            val candidates = if (bundle.isFile) {
                // 向后兼容上一版导出的合集包。
                check(bundle.length() <= 1_000_000) { "任务合集清单过大" }
                val json = JSONObject(bundle.readText())
                check(json.optString("format") == BUNDLE_FORMAT && json.optInt("version") == 1) {
                    "不支持的任务合集格式"
                }
                val array = json.optJSONArray("tasks") ?: error("任务合集缺少任务列表")
                check(array.length() > 0) { "任务合集为空" }
                val names = (0 until array.length()).map { array.getString(it) }
                check(names.distinct().size == names.size && names.all(::validName)) { "任务合集包含非法或重复任务名" }
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
            val skipped = mutableListOf<String>()
            candidates.forEach { (name, source) ->
                if (!File(source, "task.js").isFile) {
                    skipped += "任务「$name」缺少 task.js"
                } else {
                    val target = TaskManager.taskDir(context, name)
                    if (target.exists()) {
                        skipped += "任务「$name」已存在"
                    } else {
                        try {
                            installTask(context, source, target)
                            imported += name
                        } catch (e: Exception) {
                            skipped += "任务「$name」导入失败：${e.message}"
                        }
                    }
                }
            }
            Log.i(TAG, "导入任务 ${imported.size} 个，跳过 ${skipped.size} 个")
            return ImportResult(imported, skipped)
        } finally {
            temp.deleteRecursively()
        }
    }

    /** 与旧调用方兼容。 */
    fun importTask(context: Context, uri: Uri, onError: (String) -> Unit = {}): String? = try {
        val result = importArchive(context, uri)
        if (result.imported.isEmpty()) onError(result.skipped.joinToString("；").ifBlank { "没有可导入的任务" })
        result.imported.firstOrNull()
    } catch (e: Exception) {
        onError("导入失败: ${e.message}")
        null
    }

    private fun extract(context: Context, uri: Uri, temp: File) {
        var entries = 0
        var totalBytes = 0L
        val seen = hashSetOf<String>()
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                check(!unsafePath(name, entry.isDirectory)) { "压缩包包含非法路径：$name" }
                entries++
                check(entries <= MAX_ENTRIES) { "压缩包文件过多" }
                if (!entry.isDirectory) {
                    check(seen.add(name)) { "压缩包包含重复路径：$name" }
                    val target = File(temp, name)
                    check(target.canonicalFile.toPath().startsWith(temp.canonicalFile.toPath())) { "压缩包包含非法路径：$name" }
                    check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true) { "无法创建临时目录" }
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            check(totalBytes <= MAX_UNPACKED_BYTES) { "压缩包解压后过大" }
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

    private fun installTask(context: Context, source: File, target: File) {
        val staging = File(TaskManager.tasksRoot(context), ".import-${UUID.randomUUID()}")
        try {
            check(source.copyRecursively(staging, overwrite = false)) { "无法复制任务文件" }
            check(!target.exists()) { "任务已存在" }
            check(staging.renameTo(target)) { "无法保存任务" }
        } finally {
            staging.deleteRecursively()
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
