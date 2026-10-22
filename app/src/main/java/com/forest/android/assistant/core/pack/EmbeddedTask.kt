package com.forest.android.assistant.core.pack

import android.content.Context
import android.util.Log
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONObject
import java.io.File

/**
 * 内嵌任务：打包导出的 APK 会把任务放在 `assets/task/` 下，
 * 应用启动时由本类提取到任务目录，使整包自带脚本与素材、无需外部文件。
 *
 * 已存在的任务不覆盖（保留用户修改）；首次提取后由宿主决定是否自动运行。
 */
object EmbeddedTask {

    private const val TAG = "ScriptJs"
    private const val PREF = "embedded"
    private const val KEY_INSTALLED = "installed"

    /** 返回本次提取出的任务名；无内嵌任务返回 null */
    fun installIfPresent(context: Context): String? {
        val assets = context.assets
        val files = try {
            assets.list("task") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        if (files.isEmpty()) return null

        val name = readTaskName(context) ?: "内置任务"
        val target = TaskManager.taskDir(context, name)
        if (target.exists()) {
            Log.i(TAG, "内嵌任务已存在，跳过提取: $name")
            return name
        }
        return try {
            copyAssetDir(context, "task", target)
            Log.i(TAG, "内嵌任务已提取: $name -> ${target.absolutePath}")
            name
        } catch (e: Exception) {
            Log.w(TAG, "内嵌任务提取失败: ${e.message}")
            null
        }
    }

    /** 是否首次启动（用于决定自动运行一次） */
    fun consumeFirstLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val done = prefs.getBoolean(KEY_INSTALLED, false)
        if (!done) prefs.edit().putBoolean(KEY_INSTALLED, true).apply()
        return !done
    }

    private fun readTaskName(context: Context): String? = try {
        context.assets.open("task/config.json").use { input ->
            val json = JSONObject(input.bufferedReader().readText())
            json.optString("name", "").takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val am = context.assets
        val children = am.list(assetPath) ?: return
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir(context, "$assetPath/$child", File(target, child))
        }
    }
}
