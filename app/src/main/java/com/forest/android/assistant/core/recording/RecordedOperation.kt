package com.forest.android.assistant.core.recording

import kotlin.math.hypot

data class RecordedPoint(val x: Int, val y: Int)

enum class RecordingMode(val label: String) { COORDINATE("坐标"), IMAGE("图片") }

const val DEFAULT_SEARCH_REGION_SIZE = 600

data class RecordedCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun around(point: RecordedPoint, size: Int, screenWidth: Int, screenHeight: Int): RecordedCrop =
            around(point, size, size, screenWidth, screenHeight)

        fun around(point: RecordedPoint, cropWidth: Int, cropHeight: Int, screenWidth: Int, screenHeight: Int): RecordedCrop {
            require(cropWidth > 0 && cropHeight > 0 && screenWidth > 0 && screenHeight > 0)
            val width = cropWidth.coerceAtMost(screenWidth)
            val height = cropHeight.coerceAtMost(screenHeight)
            return RecordedCrop(
                (point.x - width / 2).coerceIn(0, screenWidth - width),
                (point.y - height / 2).coerceIn(0, screenHeight - height), width, height
            )
        }
    }
}

data class RecordedOperation(
    val kind: Kind,
    val points: List<RecordedPoint>,
    val durationMs: Long,
    /** 上一次真实派发完成至本次派发开始的等待，不包含暂停时间。 */
    val delayMs: Long = 0,
    /** 相邻两次真实派发开始的间隔；首步为 0。 */
    val intervalMs: Long = 0,
    val image: String? = null,
    val crop: RecordedCrop? = null,
    val searchRegion: RecordedCrop? = null,
    val contextImage: String? = null
) {
    enum class Kind { CLICK, LONG_PRESS, SWIPE }

    companion object {
        fun classify(points: List<RecordedPoint>, durationMs: Long, touchSlop: Int, longPressMs: Int): Kind {
            require(points.isNotEmpty())
            val first = points.first()
            if (points.any { hypot((it.x - first.x).toDouble(), (it.y - first.y).toDouble()) > touchSlop }) {
                return Kind.SWIPE
            }
            return if (durationMs >= longPressMs) Kind.LONG_PRESS else Kind.CLICK
        }
    }
}

/** 只使用项目已支持的 API；findImage 返回模板中心，而不是左上角。 */
object RecordingScript {
    fun generate(
        width: Int,
        height: Int,
        steps: List<RecordedOperation>,
        mode: RecordingMode = RecordingMode.COORDINATE,
        searchRegionWidth: Int = DEFAULT_SEARCH_REGION_SIZE,
        searchRegionHeight: Int = DEFAULT_SEARCH_REGION_SIZE
    ): String = buildString {
        appendLine("// 操作录制 · 屏幕 ${width}×${height}，坐标单位为实际像素")
        appendLine("// 回放方式：${mode.label}；点击找图仅搜索各步骤记录的页面范围。")
        appendLine("// 请在相同分辨率、方向及起始界面运行。sleep 为上一步完成后的等待。")
        appendLine("auto.waitFor();")
        appendLine("adapter.reset();")
        steps.forEachIndexed { index, step ->
            val n = index + 1
            val point = step.points.first()
            appendLine()
            appendLine("// 步骤 $n · ${when (step.kind) {
                RecordedOperation.Kind.CLICK -> "点击"
                RecordedOperation.Kind.LONG_PRESS -> "长按"
                RecordedOperation.Kind.SWIPE -> "滑动"
            }} · 与上一步起点间隔 ${step.intervalMs} ms · 持续 ${step.durationMs} ms")
            if (step.delayMs > 0) appendLine("sleep(${step.delayMs});")
            val region = step.searchRegion ?: RecordedCrop.around(point, searchRegionWidth, searchRegionHeight, width, height)
            appendLine("// ${region.width}×${region.height} 页面范围：[左, 上, 宽, 高]，超出屏幕时已向内调整。")
            appendLine("var region$n = [${region.left}, ${region.top}, ${region.width}, ${region.height}];")
            step.contextImage?.let { appendLine("// 页面范围截图：$it") }
            val action = when (step.kind) {
                RecordedOperation.Kind.CLICK -> "click(${point.x}, ${point.y}, ${step.durationMs})"
                RecordedOperation.Kind.LONG_PRESS -> "press(${point.x}, ${point.y}, ${step.durationMs})"
                RecordedOperation.Kind.SWIPE -> if (step.points.size == 2) {
                    val end = step.points.last()
                    "swipe(${point.x}, ${point.y}, ${end.x}, ${end.y}, ${step.durationMs})"
                } else {
                    "gesture(${step.durationMs}, " + step.points.joinToString { "[${it.x}, ${it.y}]" } + ")"
                }
            }
            val useImage = mode == RecordingMode.IMAGE && step.kind != RecordedOperation.Kind.SWIPE
            if (useImage) {
                require(step.image != null && step.crop != null) { "图片模式的点击缺少模板" }
                appendLine("// 坐标替代方案：启用前先注释下方找图代码，避免重复点击。")
            }
            appendLine((if (useImage) "// " else "") + "if (!$action) throw new Error(\"步骤 $n 执行失败\");")
            if (step.image != null && step.crop != null) {
                val dx = point.x - step.crop.left - step.crop.width / 2
                val dy = point.y - step.crop.top - step.crop.height / 2
                if (!useImage) appendLine("/* 找图替代方案：注释上面的坐标操作，再取消本段注释（避免重复点击）。")
                appendLine("if (!requestScreenCapture()) throw new Error(\"截屏授权失败\");")
                appendLine("var template$n = images.read(\"${step.image}\");")
                appendLine("var screen$n = captureScreen();")
                appendLine("try {")
                appendLine("    if (!template$n || !screen$n) throw new Error(\"读取图片失败\");")
                appendLine("    var point$n = images.findImage(screen$n, template$n, { threshold: 0.9, region: region$n });")
                appendLine("    if (!point$n) throw new Error(\"步骤 $n 未找到图片\");")
                appendLine("    // 找图返回模板中心；偏移保留原点击位置，适用于屏幕边缘的截图。")
                val method = if (step.kind == RecordedOperation.Kind.LONG_PRESS) "press" else "click"
                appendLine("    if (!$method(point$n.x + ($dx), point$n.y + ($dy), ${step.durationMs}))")
                appendLine("        throw new Error(\"步骤 $n 点击失败\");")
                appendLine("} finally {")
                appendLine("    images.release(screen$n);")
                appendLine("    images.release(template$n);")
                appendLine("}")
                if (!useImage) appendLine("*/")
            }
        }
    }
}
