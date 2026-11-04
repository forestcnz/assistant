package com.forest.android.assistant.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

/** 区域截屏的像素换算：RGBA 通道合成 Bitmap 需要的 ARGB int。 */
class RgbaToArgbTest {

    @Test fun channelsArePackedIntoArgb() {
        // R=0x11, G=0x22, B=0x33, A=0x44 → ARGB 0x44112233
        assertEquals(0x44112233, rgbaToArgb(0x11, 0x22, 0x33, 0x44))
    }

    @Test fun opaqueWhiteAndZeroStayCorrect() {
        assertEquals(0xFFFFFFFF.toInt(), rgbaToArgb(0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals(0, rgbaToArgb(0, 0, 0, 0))
    }
}
