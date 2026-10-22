package com.forest.android.assistant.core.image

/** 为紧凑的多点偏移创建可一次读取的像素包围框；不适用时调用方退回逐点读取。 */
internal data class ColorOffsetPlan(
    val leftOffset: Int,
    val topOffset: Int,
    val width: Int,
    val height: Int
) {
    fun index(dx: Int, dy: Int): Int = (dy - topOffset) * width + (dx - leftOffset)

    companion object {
        fun create(
            offsets: List<IntArray>,
            searchLeft: Int,
            searchTop: Int,
            searchRight: Int,
            searchBottom: Int,
            imageWidth: Int,
            imageHeight: Int
        ): ColorOffsetPlan? {
            if (offsets.size < 2 || imageWidth <= 0 || imageHeight <= 0 ||
                searchLeft >= searchRight || searchTop >= searchBottom ||
                offsets.any { it.size < 3 }
            ) return null

            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            for (offset in offsets) {
                minX = minOf(minX, offset[0])
                minY = minOf(minY, offset[1])
                maxX = maxOf(maxX, offset[0])
                maxY = maxOf(maxY, offset[1])
            }

            // Existing point checks use Int addition. If any candidate could overflow,
            // preserve that exact behavior through the original getPixel path.
            val minAbsoluteX = searchLeft.toLong() + minX
            val maxAbsoluteX = (searchRight - 1L) + maxX
            val minAbsoluteY = searchTop.toLong() + minY
            val maxAbsoluteY = (searchBottom - 1L) + maxY
            if (minAbsoluteX < Int.MIN_VALUE || maxAbsoluteX > Int.MAX_VALUE ||
                minAbsoluteY < Int.MIN_VALUE || maxAbsoluteY > Int.MAX_VALUE
            ) return null

            val width = maxX.toLong() - minX + 1L
            val height = maxY.toLong() - minY + 1L
            if (width <= 0 || height <= 0 || width > Long.MAX_VALUE / height) return null
            val area = width * height
            val maxUsefulArea = minOf(4096L, maxOf(16L, offsets.size.toLong() * 4L))
            if (area > Int.MAX_VALUE || area > imageWidth.toLong() * imageHeight ||
                area > maxUsefulArea || width > Int.MAX_VALUE || height > Int.MAX_VALUE
            ) return null
            return ColorOffsetPlan(minX, minY, width.toInt(), height.toInt())
        }
    }
}
