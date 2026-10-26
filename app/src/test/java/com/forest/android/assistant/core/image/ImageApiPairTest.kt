package com.forest.android.assistant.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双模板同行校验的纯 JVM 回归：容差计算、同行判定边界、中点与有符号差值。
 * 真实 Bitmap 匹配在设备端 ImageApiInstrumentation 中执行。
 */
class ImageApiPairTest {

    @Test fun returnsNullWhenEitherTemplateHandleIsInvalid() {
        // 无效句柄必须在进入匹配前返回 null，不能抛异常
        assertNull(ImageApi.findImagePair(0, 0, 0))
        assertNull(ImageApi.findImagePair(1, 0, 0))
        assertNull(ImageApi.findImagePair(1, 1, 0))
    }

    @Test fun returnsNullWhenImageHandleIsInvalid() {
        assertNull(ImageApi.findImagePair(-1, 1, 2))
    }

    @Test fun toleranceIsNeverBelowFourPixels() {
        // 自动容差下限：极小图标也不会因 1~2px 抖动被判成不同行
        assertEquals(4, ImageApi.resolveTolerance(0, 2, 2))
        assertEquals(4, ImageApi.resolveTolerance(0, 10, 10))
    }

    @Test fun autoToleranceUsesSmallerTemplateHeight() {
        // 按较小模板高度计算，避免大控件把小控件带偏
        assertEquals(9, ImageApi.resolveTolerance(0, 30, 60))
        assertEquals(9, ImageApi.resolveTolerance(0, 60, 30))
        assertEquals(18, ImageApi.resolveTolerance(0, 60, 60))
        assertEquals(5, ImageApi.resolveTolerance(0, 30, 16))
    }

    @Test fun explicitToleranceOverridesAutoValue() {
        assertEquals(6, ImageApi.resolveTolerance(6, 60, 60))
        assertEquals(120, ImageApi.resolveTolerance(120, 10, 10))
        // 非正数回退到自动值
        assertEquals(18, ImageApi.resolveTolerance(0, 60, 60))
        assertEquals(18, ImageApi.resolveTolerance(-5, 60, 60))
    }

    @Test fun sameRowBoundaryIsInclusive() {
        // rowDelta 恰好等于容差时算同行
        assertTrue(ImageApi.isSameRow(9, 9))
        assertTrue(ImageApi.isSameRow(0, 9))
        assertFalse(ImageApi.isSameRow(10, 9))
        assertFalse(ImageApi.isSameRow(30, 9))
    }

    @Test fun midPointUsesBothCentersRegardlessOfOrder() {
        // 中点与参数顺序无关
        val left = ImageApi.midPoint(100, 200, 300, 200)
        val right = ImageApi.midPoint(300, 200, 100, 200)
        assertEquals(200, left.first)
        assertEquals(200, left.second)
        assertEquals(left, right)
    }

    @Test fun midPointHandlesDifferentHeights() {
        // 两框高度不同导致中心 Y 不同时，中点仍取两者中心的中点
        assertEquals(205, ImageApi.midPoint(100, 200, 100, 210).second)
    }

    @Test fun signedDeltaReflectsTemplateOrder() {
        // dx/dy 为 b - a 的有符号差值，A 在左时为正
        assertEquals(200, ImageApi.delta(100, 300))
        assertEquals(-200, ImageApi.delta(300, 100))
    }
}
