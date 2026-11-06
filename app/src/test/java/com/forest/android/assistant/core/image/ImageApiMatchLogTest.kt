package com.forest.android.assistant.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配日志文本的纯 JVM 回归：
 * 成功时显示模板名、设定阈值、实际匹配分与坐标；失败时显示模板名、设定阈值、最高分与坐标。
 * 真实 Bitmap 匹配在设备端 ImageApiInstrumentation 中执行。
 */
class ImageApiMatchLogTest {

    @Test fun successLogsTemplateThresholdScoreAndPoint() {
        val text = ImageApi.matchLogText(
            tplName = "按钮.png",
            threshold = 0.9,
            hit = ImageApi.Match(540, 1200, 0.987),
            best = ImageApi.Match(540, 1200, 0.987),
            scale = 1.0,
            success = true
        )
        assertEquals("按钮.png 找图成功 阈值=0.9 匹配分=0.987 坐标=(540,1200)", text)
    }

    @Test fun failureLogsBestScoreAndPoint() {
        val text = ImageApi.matchLogText(
            tplName = "按钮.png",
            threshold = 0.9,
            hit = null,
            best = ImageApi.Match(60, 80, 0.712),
            scale = 1.0,
            success = false
        )
        assertEquals("按钮.png 找图失败 阈值=0.9 最高分=0.712 坐标=(60,80)", text)
    }

    @Test fun failureWithoutCandidateShowsDashes() {
        // 粗搜没有任何候选时，分数与坐标都用 - 占位，避免日志缺字段
        val text = ImageApi.matchLogText(
            tplName = "按钮.png", threshold = 0.85, hit = null, best = null, scale = 1.0, success = false
        )
        assertEquals("按钮.png 找图失败 阈值=0.85 最高分=- 坐标=-", text)
    }

    @Test fun multiScaleSuccessAppendsScale() {
        // 显式多倍率时追加命中倍率，单倍率默认调用不追加
        val text = ImageApi.matchLogText(
            tplName = "按钮.png", threshold = 0.9,
            hit = ImageApi.Match(10, 20, 0.95), best = ImageApi.Match(10, 20, 0.95),
            scale = 1.5, success = true
        )
        assertTrue("应带上命中倍率: $text", text.endsWith(" 倍率=1.5"))
    }

    @Test fun failureWithoutCandidateOmitsScale() {
        // 没有候选时倍率无意义，不应输出
        val text = ImageApi.matchLogText(
            tplName = "按钮.png", threshold = 0.9, hit = null, best = null, scale = 1.5, success = false
        )
        assertTrue("无候选不应输出倍率: $text", !text.contains("倍率="))
    }
}
