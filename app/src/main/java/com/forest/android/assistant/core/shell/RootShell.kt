package com.forest.android.assistant.core.shell

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Root 能力：探测与执行 shell 命令（增强通道，可选）。
 *
 * 被两处复用：
 * - 静默截屏（[com.forest.android.assistant.core.image.SilentCapture]）：`su -c screencap`；
 * - 稳定点击兜底（[com.forest.android.assistant.core.gesture.InputFallback]）：`su -c input tap/swipe`。
 *
 * 无 root 时 [available] 返回 false，调用方保持原有降级行为不变。
 * 对应设计 docs/README.md 第 3 章「增强」权限级别。
 */
object RootShell {

    private const val TAG = "ScriptJs"
    private const val PROBE_TIMEOUT_SECONDS = 2L
    private const val EXEC_TIMEOUT_SECONDS = 6L

    /** 探测结果：-1 未探测 / 0 不可用 / 1 可用 */
    @Volatile
    private var probed = -1

    /** Root 是否可用（`su -c id` 返回 uid=0），结果缓存 */
    fun available(forceRefresh: Boolean = false): Boolean {
        if (forceRefresh || probed < 0) {
            probed = if (probe()) 1 else 0
        }
        return probed == 1
    }

    /** 清空探测缓存（切换 Root 状态后使用） */
    fun resetProbe() {
        probed = -1
    }

    private fun probe(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) process.destroy()
        val ok = finished && output.contains("uid=0")
        Log.i(TAG, "Root 探测: available=$ok")
        ok
    } catch (e: Exception) {
        Log.i(TAG, "Root 探测: 不可用（${e.message}）")
        false
    }

    /** 以 root 执行命令；返回是否成功（退出码 0） */
    fun exec(command: String, timeoutSeconds: Long = EXEC_TIMEOUT_SECONDS): Boolean = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            Log.w(TAG, "root 命令超时: $command")
            false
        } else {
            val code = process.exitValue()
            if (code != 0) Log.w(TAG, "root 命令退出码 $code: $command")
            code == 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "root 命令执行失败: ${e.message}")
        false
    }
}
