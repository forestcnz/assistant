package com.forest.android.assistant.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.forest.android.assistant.core.task.ImageHistory
import java.io.File

/** 预览按需采样，保存从原文件读取像素；所有入口只允许访问指定任务的素材。 */
object AssetCropper {
    data class Preview(val bitmap: Bitmap, val width: Int, val height: Int)

    private const val MAX_CROP_PIXELS = 16_000_000L

    fun load(context: Context, taskName: String, imageName: String): Preview {
        val file = ImageHistory.source(context, taskName, imageName)
        val bounds = bounds(file)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("无法读取该图片")
        return Preview(bitmap, bounds.outWidth, bounds.outHeight)
    }

    fun save(context: Context, taskName: String, imageName: String, region: CropRegion): File {
        val file = ImageHistory.source(context, taskName, imageName)
        val bounds = bounds(file)
        require(region.isValid(bounds.outWidth, bounds.outHeight)) { "裁剪区域无效，请重新选择" }
        require(region.width.toLong() * region.height <= MAX_CROP_PIXELS) { "选区过大，请缩小到 1600 万像素以内" }
        val cropped = decodeRegion(file, region, bounds)
        try {
            // 在素材所在文件系统内完成编码，原图迁移与替换都使用 rename。
            val temporary = ImageHistory.createPendingFile(context, taskName)
            try {
                temporary.outputStream().use { output ->
                    check(cropped.compress(outputFormat(bounds.outMimeType), 100, output)) { "图片编码失败" }
                }
                return ImageHistory.replace(context, taskName, imageName, temporary)
            } finally {
                temporary.delete()
            }
        } finally {
            cropped.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun outputFormat(mimeType: String?): Bitmap.CompressFormat = when (mimeType) {
        "image/jpeg" -> Bitmap.CompressFormat.JPEG
        "image/webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
        // Android 无 GIF/BMP 等编码器；使用无损 PNG 内容，保留原路径供脚本读取。
        else -> Bitmap.CompressFormat.PNG
    }

    private fun bounds(file: File): BitmapFactory.Options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, this)
        require(outWidth > 0 && outHeight > 0) { "无法读取该图片，请选择有效的图片素材" }
    }

    @Suppress("DEPRECATION")
    private fun decodeRegion(file: File, region: CropRegion, bounds: BitmapFactory.Options): Bitmap {
        val decoder = try { BitmapRegionDecoder.newInstance(file.path, false) } catch (_: Exception) { null }
        if (decoder != null) {
            try {
                return decoder.decodeRegion(Rect(region.left, region.top, region.right, region.bottom),
                    BitmapFactory.Options().apply { inSampleSize = 1 }) ?: error("无法读取选区像素")
            } finally {
                decoder.recycle()
            }
        }
        // GIF 等不支持区域解码的格式，仅在可控内存范围内读取整图。
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_CROP_PIXELS) { "该格式图片过大，无法裁剪" }
        val full = BitmapFactory.decodeFile(file.path) ?: error("无法读取该图片")
        try {
            val crop = Bitmap.createBitmap(full, region.left, region.top, region.width, region.height)
            if (crop === full) return full.copy(Bitmap.Config.ARGB_8888, false) ?: error("无法复制图片")
            return crop
        } finally {
            full.recycle()
        }
    }
}
