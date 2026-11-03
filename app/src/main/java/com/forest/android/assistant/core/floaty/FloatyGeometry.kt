package com.forest.android.assistant.core.floaty

/**
 * 悬浮窗贴边几何计算（纯函数，不依赖 Android 框架，便于单元测试）。
 *
 * 坐标语义统一为「重力 TOP|START」的窗口参数：x/y = 窗口左上角相对屏幕左上角的像素。
 * 运行条不再使用 BOTTOM 重力，避免「距底边」的 y 被写进「距顶边」的持久化位置。
 */

/** 贴边方向：目前只做左右贴边 */
internal enum class DockSide { NONE, LEFT, RIGHT }

internal object FloatyGeometry {

    /** 贴边半隐藏：探出屏外的部分占球宽的比例（剩余半圆留在屏内，可点击弹出） */
    const val PEEK_HIDDEN_RATIO = 0.5f

    /** 探出屏外的像素数，至少 1px（比例为 0 时也要有「探出」效果） */
    fun hiddenPx(viewW: Int, ratio: Float = PEEK_HIDDEN_RATIO): Int =
        (viewW * ratio).toInt().coerceAtLeast(1)

    /** 半隐藏贴边的 x：屏内只留一条半圆弧，方向为 [side] */
    fun peekedX(viewW: Int, screenW: Int, side: DockSide, ratio: Float = PEEK_HIDDEN_RATIO): Int {
        val hidden = hiddenPx(viewW, ratio)
        return when (side) {
            DockSide.LEFT -> -hidden
            DockSide.RIGHT -> screenW - viewW + hidden
            // 无贴边方向时退化为屏内左上角，调用方不应依赖该分支
            DockSide.NONE -> clampX(0, viewW, screenW)
        }
    }

    /** 完整可见的贴边 x（留 [margin] 边距）；side 为 NONE 时按左侧处理 */
    fun visibleX(viewW: Int, screenW: Int, side: DockSide, margin: Int): Int = when (side) {
        DockSide.LEFT -> clampX(margin, viewW, screenW)
        DockSide.RIGHT -> clampX(screenW - viewW - margin, viewW, screenW)
        DockSide.NONE -> clampX(margin, viewW, screenW)
    }

    /** 按控件中心点判断应该贴哪一边（中心在屏幕左半 → 左边） */
    fun sideByCenter(x: Int, viewW: Int, screenW: Int): DockSide =
        if (x + viewW / 2 < screenW / 2) DockSide.LEFT else DockSide.RIGHT

    /** 由持久化 x 推断贴边状态（负 x = 左贴边，越过右边界 = 右贴边） */
    fun sideOf(x: Int, viewW: Int, screenW: Int): DockSide = when {
        x < 0 -> DockSide.LEFT
        x > screenW - viewW -> DockSide.RIGHT
        else -> DockSide.NONE
    }

    /** x 钳位到完整可见区间 */
    fun clampX(x: Int, viewW: Int, screenW: Int): Int =
        x.coerceIn(0, (screenW - viewW).coerceAtLeast(0))

    /** y 钳位到完整可见区间 */
    fun clampY(y: Int, viewH: Int, screenH: Int): Int =
        y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))

    /**
     * 拖动期间的 x 范围：允许探出屏外形成半隐藏形态，但不允许整颗丢出屏幕。
     * 手势被系统打断（来电/通知栏/多指）时也不会把悬浮球留在屏幕外找不回来。
     */
    fun clampDragX(x: Int, viewW: Int, screenW: Int): Int {
        val hidden = hiddenPx(viewW)
        val maxX = (screenW - viewW + hidden).coerceAtLeast(-hidden)
        return x.coerceIn(-hidden, maxX)
    }

    /**
     * 恢复/屏幕尺寸变化后的 x：
     * - 有贴边方向且保持半隐藏 → 按新屏宽重算探出量（旋转/换分辨率后仍贴边）；
     * - 有贴边方向但不半隐藏（关闭自动贴边）→ 完整显示贴到同侧；
     * - 无贴边方向 → 屏内钳位。
     */
    fun restoreX(
        x: Int,
        viewW: Int,
        screenW: Int,
        side: DockSide,
        margin: Int,
        keepDocked: Boolean
    ): Int = when {
        side == DockSide.NONE -> clampX(x, viewW, screenW)
        keepDocked -> peekedX(viewW, screenW, side)
        else -> visibleX(viewW, screenW, side, margin)
    }
}
