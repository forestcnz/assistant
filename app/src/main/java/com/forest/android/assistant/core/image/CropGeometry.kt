package com.forest.android.assistant.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 原图像素坐标，右、下边界不包含在选区中。 */
data class CropRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isValid(imageWidth: Int, imageHeight: Int): Boolean =
        left >= 0 && top >= 0 && right <= imageWidth && bottom <= imageHeight && width > 0 && height > 0

    fun moved(dx: Int, dy: Int, imageWidth: Int, imageHeight: Int): CropRegion {
        val x = (left + dx).coerceIn(0, imageWidth - width)
        val y = (top + dy).coerceIn(0, imageHeight - height)
        return CropRegion(x, y, x + width, y + height)
    }

    companion object {
        fun between(x1: Int, y1: Int, x2: Int, y2: Int): CropRegion =
            CropRegion(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }
}

/** 与居中 ContentScale.Fit 一致，预览采样率不参与原图坐标换算。 */
class CropImageFit(val imageWidth: Int, val imageHeight: Int, viewWidth: Float, viewHeight: Float) {
    val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val left = (viewWidth - imageWidth * scale) / 2f
    val top = (viewHeight - imageHeight * scale) / 2f

    fun imageX(x: Float): Int = ((x - left) / scale).roundToInt().coerceIn(0, imageWidth)
    fun imageY(y: Float): Int = ((y - top) / scale).roundToInt().coerceIn(0, imageHeight)
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + imageWidth * scale && y >= top && y <= top + imageHeight * scale
}
