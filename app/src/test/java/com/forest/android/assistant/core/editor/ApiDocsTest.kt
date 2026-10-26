package com.forest.android.assistant.core.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDocsTest {
    @Test fun containsLookupIsDiscoverableFromModuleAndMethodPrefixes() {
        for (prefix in listOf("ocr.", "ocr.find", "findTextC")) {
            assertTrue(ApiDocs.suggestions(prefix).any { it.name == "ocr.findTextContains" })
        }
    }

    @Test fun newApisAreDiscoverableFromTheirPrefixes() {
        // 新增 API 应能从模块名、方法名前缀或片段名补全出来
        val cases = mapOf(
            "waitImageC" to "waitImageClick",
            "waitImageA" to "waitImageAny",
            "waitText" to "waitTextClick",
            "clickR" to "clickRetry",
            "images.findImageA" to "images.findImageAny",
            "findImageAny" to "images.findImageAny",
            "findImageP" to "images.findImagePair",
            "readAl" to "images.readAll",
            "releaseAl" to "images.releaseAll",
            "ocr.findTextA" to "ocr.findTextAny",
            "findTextAny" to "ocr.findTextAny"
        )
        for ((prefix, name) in cases) {
            assertTrue(
                "缺少补全项 $name（前缀 $prefix）",
                ApiDocs.suggestions(prefix, limit = 20).any { it.name == name }
            )
        }
    }

    @Test fun declarationsUseObjectMembersAndDescribeOcrResults() {
        val dts = ApiDocs.buildDts()
        assertFalse(Regex("declare function \\w+\\.").containsMatchIn(dts))
        assertTrue(dts.contains("declare function sleep(ms: any): any;"))
        assertTrue(dts.contains("findTextContains(image: any, text: string, options?: OcrOptions): OcrItem | null;"))
        assertTrue(dts.contains("region?: [number, number, number, number]"))
        assertTrue(dts.contains("interface OcrItem extends Rect"))
        assertTrue(dts.contains("centerX(): number; centerY(): number;"))
    }

    @Test fun declarationsCoverNewApisAndTypes() {
        val dts = ApiDocs.buildDts()
        // 全局函数
        assertTrue("缺少 waitImageClick 声明", dts.contains("declare function waitImageClick("))
        assertTrue("缺少 waitImageAny 声明", dts.contains("declare function waitImageAny("))
        assertTrue("缺少 waitTextClick 声明", dts.contains("declare function waitTextClick("))
        assertTrue("缺少 clickRetry 声明", dts.contains("declare function clickRetry("))
        // 对象成员
        assertTrue("缺少 images.findImageAny 声明", dts.contains("findImageAny: (...args: any[]) => any;"))
        assertTrue("缺少 images.findImagePair 声明", dts.contains("findImagePair: (...args: any[]) => any;"))
        assertTrue("缺少 images.readAll 声明", dts.contains("readAll: (...args: any[]) => any;"))
        assertTrue("缺少 images.releaseAll 声明", dts.contains("releaseAll: (...args: any[]) => any;"))
        assertTrue(dts.contains("findTextAny(image: any, texts: string[], options?: OcrOptions): OcrItem | null;"))
        // 新增类型
        assertTrue("缺少 AnyMatch", dts.contains("interface AnyMatch"))
        assertTrue("缺少 TemplateEntry", dts.contains("interface TemplateEntry"))
        assertTrue("缺少 ReadAllResult", dts.contains("interface ReadAllResult"))
        assertTrue("缺少 PairMatch", dts.contains("interface PairMatch"))
        assertTrue("缺少 MatchRect", dts.contains("interface MatchRect"))
        assertTrue("OcrItem 缺少 matched 字段", dts.contains("matched?: string"))
    }
}
