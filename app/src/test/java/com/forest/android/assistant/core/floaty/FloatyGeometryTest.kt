package com.forest.android.assistant.core.floaty

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 贴边几何单元测试：覆盖「半隐藏/完整贴边/旋转恢复/拖动钳位」这些之前靠手点验证的算法，
 * 防止贴边坐标又被改错（历史上出过旋转后悬浮球跑到屏幕中间、运行条写坏空闲位置的问题）。
 */
class FloatyGeometryTest {

    // 常见取值：35dp 悬浮球在 density=3 的 1080×1920 屏幕上约为 105px
    private val ball = 105
    private val screenW = 1080
    private val screenH = 1920
    private val margin = 18

    @Test
    fun peekedXHidesHalfOfTheBallOnBothSides() {
        val leftX = FloatyGeometry.peekedX(ball, screenW, DockSide.LEFT)
        val rightX = FloatyGeometry.peekedX(ball, screenW, DockSide.RIGHT)
        // 探出屏外约一半，屏内仍留约半圆可点
        assertEquals(52, FloatyGeometry.hiddenPx(ball))
        assertEquals(-52, leftX)
        assertEquals(1027, rightX)
        assertEquals(53, ball + leftX)      // 左侧留在屏内的宽度
        assertEquals(53, screenW - rightX)  // 右侧留在屏内的宽度
    }

    @Test
    fun hiddenPxAlwaysHidesAtLeastOnePixel() {
        assertEquals(1, FloatyGeometry.hiddenPx(1))
        assertEquals(50, FloatyGeometry.hiddenPx(100))
    }

    @Test
    fun visibleXKeepsMarginAndNeverLeavesTheScreen() {
        assertEquals(18, FloatyGeometry.visibleX(ball, screenW, DockSide.LEFT, margin))
        assertEquals(957, FloatyGeometry.visibleX(ball, screenW, DockSide.RIGHT, margin))
        // 控件比屏幕还宽时不能出现负坐标
        assertEquals(0, FloatyGeometry.visibleX(1200, screenW, DockSide.LEFT, margin))
        assertEquals(0, FloatyGeometry.visibleX(1200, screenW, DockSide.RIGHT, margin))
    }

    @Test
    fun sideOfTreatsOutOfScreenXAsDocked() {
        assertEquals(DockSide.LEFT, FloatyGeometry.sideOf(-52, ball, screenW))
        assertEquals(DockSide.RIGHT, FloatyGeometry.sideOf(1027, ball, screenW))
        // 恰好贴住右边界属于「完整显示」，不算半隐藏
        assertEquals(DockSide.NONE, FloatyGeometry.sideOf(screenW - ball, ball, screenW))
        assertEquals(DockSide.NONE, FloatyGeometry.sideOf(500, ball, screenW))
    }

    @Test
    fun sideByCenterUsesScreenMiddle() {
        assertEquals(DockSide.LEFT, FloatyGeometry.sideByCenter(0, ball, screenW))
        assertEquals(DockSide.LEFT, FloatyGeometry.sideByCenter(487, ball, screenW))
        assertEquals(DockSide.RIGHT, FloatyGeometry.sideByCenter(489, ball, screenW))
        assertEquals(DockSide.RIGHT, FloatyGeometry.sideByCenter(975, ball, screenW))
    }

    @Test
    fun restoreXKeepsDockAfterRotation() {
        // 竖屏右贴边的 x=1027；转到 1920 宽的横屏后必须重算成贴边位置，而不是钳到屏幕中间
        val portraitDockedRight = 1027
        assertEquals(
            1920 - ball + 52,
            FloatyGeometry.restoreX(portraitDockedRight, ball, 1920, DockSide.RIGHT, margin, true)
        )
        // 即便持久化方向丢失，也不能把 1027 当成屏内位置留在中间：这里验证「有方向就一定贴边」
        assertEquals(
            -52,
            FloatyGeometry.restoreX(portraitDockedRight, ball, screenW, DockSide.LEFT, margin, true)
        )
    }

    @Test
    fun restoreXWithoutDockPopsOutWhenAutoSnapDisabled() {
        // 关闭自动贴边后不保持半隐藏，按同侧完整显示
        assertEquals(
            1920 - ball - margin,
            FloatyGeometry.restoreX(1027, ball, 1920, DockSide.RIGHT, margin, false)
        )
        // 屏内历史位置原样钳位
        assertEquals(500, FloatyGeometry.restoreX(500, ball, screenW, DockSide.NONE, margin, true))
        assertEquals(975, FloatyGeometry.restoreX(2000, ball, screenW, DockSide.NONE, margin, true))
        assertEquals(0, FloatyGeometry.restoreX(-500, ball, screenW, DockSide.NONE, margin, true))
    }

    @Test
    fun clampDragXAllowsPeekButNeverLosesTheBall() {
        assertEquals(-52, FloatyGeometry.clampDragX(-5000, ball, screenW))
        assertEquals(1027, FloatyGeometry.clampDragX(5000, ball, screenW))
        assertEquals(300, FloatyGeometry.clampDragX(300, ball, screenW))
    }

    @Test
    fun clampXAndYKeepTheViewFullyVisible() {
        assertEquals(0, FloatyGeometry.clampX(-5, ball, screenW))
        assertEquals(975, FloatyGeometry.clampX(2000, ball, screenW))
        assertEquals(0, FloatyGeometry.clampY(-5, ball, screenH))
        assertEquals(1815, FloatyGeometry.clampY(5000, ball, screenH))
        // 控件比屏幕还高时退化为 0，不能出现负值
        assertEquals(0, FloatyGeometry.clampY(10, 2000, screenH))
    }
}
