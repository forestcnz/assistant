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

    @Test fun declarationsUseObjectMembersAndDescribeOcrResults() {
        val dts = ApiDocs.buildDts()
        assertFalse(Regex("declare function \\w+\\.").containsMatchIn(dts))
        assertTrue(dts.contains("declare function sleep(ms: any): any;"))
        assertTrue(dts.contains("findTextContains(image: any, text: string, options?: OcrOptions): OcrItem | null;"))
        assertTrue(dts.contains("region?: [number, number, number, number]"))
        assertTrue(dts.contains("interface OcrItem extends Rect"))
        assertTrue(dts.contains("centerX(): number; centerY(): number;"))
    }
}
