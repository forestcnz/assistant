package com.forest.android.assistant.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureModeTest {
    @Test fun missingLegacyAutomaticAndInvalidSettingsRequireSelection() {
        listOf(null, "", "AUTO", "UNKNOWN").forEach { stored ->
            val mode = ScreenCapture.Mode.fromStored(stored)
            assertEquals(ScreenCapture.Mode.UNSELECTED, mode)
            assertNull(mode.channel)
        }
    }

    @Test fun explicitUserChoicesSurviveUpgrade() {
        assertEquals(ScreenCapture.Mode.ROOT, ScreenCapture.Mode.fromStored("ROOT"))
        assertEquals(ScreenCapture.Mode.MEDIAPROJECTION, ScreenCapture.Mode.fromStored("MEDIAPROJECTION"))
    }

    @Test fun rootChoiceDoesNotUseAnAlreadyAuthorizedProjectionAsFallback() {
        val mode = ScreenCapture.Mode.ROOT
        assertEquals(ScreenCapture.Channel.ROOT, mode.channel)
        assertFalse(mode.available(rootAvailable = false, projectionReady = true))
        assertTrue(mode.available(rootAvailable = true, projectionReady = false))
    }

    @Test fun projectionChoiceDoesNotUseCachedRootAvailability() {
        val mode = ScreenCapture.Mode.MEDIAPROJECTION
        assertEquals(ScreenCapture.Channel.MEDIAPROJECTION, mode.channel)
        assertFalse(mode.available(rootAvailable = true, projectionReady = false))
        assertTrue(mode.available(rootAvailable = false, projectionReady = true))
    }

    @Test fun unselectedModeCannotCaptureEvenWhenBothChannelsAreReady() {
        assertFalse(ScreenCapture.Mode.UNSELECTED.available(rootAvailable = true, projectionReady = true))
    }
}
