package com.forest.android.assistant.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.forest.android.assistant.core.shell.RootShell
import java.io.File
import java.util.concurrent.TimeUnit

/** Root 静默截屏；不可用时由 [ScreenCapture] 降级到 MediaProjection。 */
object SilentCapture {

    private const val TAG = "ScriptJs"
    private const val TIMEOUT_SECONDS = 6L

    /** Root 是否可用（复用 [RootShell] 的探测缓存） */
    fun rootAvailable(): Boolean = RootShell.available()

    /** Root 通道截屏 */
    fun captureViaRoot(context: Context): Bitmap? {
        val file = tempFile(context)
        return screencap(arrayOf("su", "-c", "screencap -p ${file.absolutePath}"), file)
    }

    private fun tempFile(context: Context): File {
        val dir = File(context.cacheDir, "silent").apply { mkdirs() }
        // 文件名唯一：并发/重入时不会互相覆盖同一张截图。
        return File(dir, "cap_${Thread.currentThread().id}_${System.nanoTime()}.png")
    }

    private fun screencap(command: Array<String>, file: File): Bitmap? = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        awaitAndDecode(process, file)
    } catch (e: Exception) {
        Log.w(TAG, "root 截屏失败: ${e.message}")
        file.delete()
        null
    }

    private fun awaitAndDecode(process: Process, file: File): Bitmap? = try {
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            Log.w(TAG, "root 截屏超时")
            null
        } else if (process.exitValue() != 0) {
            Log.w(TAG, "root 截屏退出码 ${process.exitValue()}")
            null
        } else if (!file.isFile || file.length() == 0L) {
            Log.w(TAG, "root 截屏未生成文件")
            null
        } else {
            BitmapFactory.decodeFile(file.absolutePath).also {
                Log.i(TAG, "root 静默截屏成功: ${it?.width}x${it?.height}")
            }
        }
    } finally {
        if (file.exists()) file.delete()
    }
}
