package com.forest.android.assistant.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.Log
import com.forest.android.assistant.core.shell.RootShell
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Root 静默截屏；仅在用户选择该方式后使用，失败不自动切换其他方式。 */
object SilentCapture {

    private const val TAG = "ScriptJs"
    private const val TIMEOUT_SECONDS = 6L

    /** screencap raw 输出头：小端 宽、高、PixelFormat 各 4 字节 */
    private const val RAW_HEADER_BYTES = 12

    /** 单边像素上限，防止异常头把内存撑爆 */
    private const val MAX_SIDE = 8192

    /** Root 是否可用（复用 [RootShell] 的探测缓存） */
    fun rootAvailable(forceRefresh: Boolean = false): Boolean = RootShell.available(forceRefresh)

    /**
     * Root 通道截屏：
     * 优先用无压缩的 raw 输出——省掉 PNG 编码与解码（实测约快 80~150ms）；
     * raw 头格式不被识别（非 RGBA_8888）时回退到 PNG，保证兼容性。
     */
    fun captureViaRoot(context: Context): Bitmap? {
        val rawFile = tempFile(context, "raw")
        screencap("screencap ${rawFile.absolutePath}", rawFile) { decodeRaw(it) }?.let { return it }
        val pngFile = tempFile(context, "png")
        return screencap("screencap -p ${pngFile.absolutePath}", pngFile) {
            BitmapFactory.decodeFile(it.absolutePath)
        }
    }

    private fun tempFile(context: Context, extension: String): File {
        val dir = File(context.cacheDir, "silent").apply { mkdirs() }
        // 文件名唯一（纳秒级）：并发/重入时不会互相覆盖同一张截图。
        return File(dir, "cap_${System.nanoTime()}.$extension")
    }

    private fun screencap(command: String, file: File, decode: (File) -> Bitmap?): Bitmap? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        awaitAndDecode(process, file, decode)
    } catch (e: Exception) {
        Log.w(TAG, "root 截屏失败: ${e.message}")
        file.delete()
        null
    }

    private fun awaitAndDecode(process: Process, file: File, decode: (File) -> Bitmap?): Bitmap? = try {
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
            decode(file).also {
                Log.i(TAG, "root 静默截屏成功: ${it?.width}x${it?.height}")
            }
        }
    } finally {
        if (file.exists()) file.delete()
    }

    /**
     * 解析 screencap 的 raw 输出：小端 12 字节头（宽、高、PixelFormat）+ RGBA_8888 像素。
     * 只接受 RGBA_8888；其他格式或尺寸异常返回 null，由 PNG 路径兜底。
     * 像素内存布局与 ImageReader 一致，可直接 copyPixelsFromBuffer 拷进 ARGB_8888 位图。
     */
    private fun decodeRaw(file: File): Bitmap? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < RAW_HEADER_BYTES) return null
        val header = ByteBuffer.wrap(bytes, 0, RAW_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        val format = header.int
        if (format != PixelFormat.RGBA_8888) return null
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        val pixelBytes = width.toLong() * height.toLong() * 4L
        if (bytes.size.toLong() < RAW_HEADER_BYTES + pixelBytes) return null
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.copyPixelsFromBuffer(
                    ByteBuffer.wrap(bytes, RAW_HEADER_BYTES, pixelBytes.toInt())
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "raw 截屏解码失败: ${e.message}")
            null
        }
    }
}
