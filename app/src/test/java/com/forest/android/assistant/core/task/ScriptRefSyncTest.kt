package com.forest.android.assistant.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRefSyncTest {

    @Test fun replacesDirReferenceAndReportsLine() {
        val script = """
            waitImage("images/tpl_161530.png", 10);
            click(10, 20);
        """.trimIndent()
        val plan = ScriptRefSync.plan(script, "tpl_161530.png", "btn_login.png")
        assertTrue(plan.hasChanges)
        assertEquals(1, plan.changedLineCount)
        assertEquals(1, plan.replacementCount)
        assertEquals(1, plan.changes[0].lineNumber)
        assertEquals("""waitImage("images/btn_login.png", 10);""", plan.changes[0].after)
        assertTrue(plan.updated.contains("images/btn_login.png"))
        assertFalse(plan.updated.contains("tpl_161530.png"))
    }

    @Test fun replacesBareNameAndWindowsSeparator() {
        val script = "var a = images.read(\"btn.png\");\nvar b = read(\"images\\\\btn.png\");"
        val plan = ScriptRefSync.plan(script, "btn.png", "ok.png")
        assertEquals(2, plan.replacementCount)
        assertEquals(2, plan.changedLineCount)
        assertEquals(1, plan.changes[0].lineNumber)
        assertEquals(2, plan.changes[1].lineNumber)
        assertEquals("var a = images.read(\"ok.png\");", plan.changes[0].after)
        assertEquals("var b = read(\"images\\\\ok.png\");", plan.changes[1].after)
    }

    @Test fun doesNotTouchLongerNamesOrOtherLines() {
        val script = "x = \"images/btn.png2\";\ny = \"images/old_btn.png\";\nz = 1;"
        val plan = ScriptRefSync.plan(script, "btn.png", "ok.png")
        assertFalse(plan.hasChanges)
        assertEquals(script, plan.updated)
    }

    @Test fun countsMultipleReplacementsInSameLine() {
        val line = "a(\"images/t.png\", \"images/t.png\")"
        val plan = ScriptRefSync.plan(line, "t.png", "u.png")
        assertEquals(1, plan.changedLineCount)
        assertEquals(2, plan.replacementCount)
        assertEquals("a(\"images/u.png\", \"images/u.png\")", plan.changes[0].after)
    }

    @Test fun noopWhenNameUnchangedOrScriptEmpty() {
        assertFalse(ScriptRefSync.plan("images/a.png", "a.png", "a.png").hasChanges)
        assertFalse(ScriptRefSync.plan("", "a.png", "b.png").hasChanges)
        assertFalse(ScriptRefSync.plan("images/a.png", "", "b.png").hasChanges)
    }

    @Test fun keepsUnrelatedLinesAndLineNumbersIntact() {
        val script = "line1\n\nimages/a.png\n\nline5"
        val plan = ScriptRefSync.plan(script, "a.png", "b.png")
        assertEquals(1, plan.changedLineCount)
        assertEquals(3, plan.changes[0].lineNumber)
        assertEquals("line1\n\nimages/b.png\n\nline5", plan.updated)
    }

    @Test fun newNameContainingOldNameIsNotReplacedTwice() {
        val plan = ScriptRefSync.plan("images/a.png", "a.png", "a.png.bak")
        assertEquals(1, plan.replacementCount)
        assertEquals("images/a.png.bak", plan.updated)
    }
}
