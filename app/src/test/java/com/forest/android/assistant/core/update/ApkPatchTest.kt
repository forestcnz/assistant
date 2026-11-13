package com.forest.android.assistant.core.update

import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkPatchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun onlyExpectedOutputSizeAndBoundedWorkingMemoryReachThePatcher() {
        val header = "HDIFFSF20&zstd\u0000".encodeToByteArray()
        val patch = temporary.newFile()
        // Sizes 129 and 130 use two bytes in the HDiffPatch 7-bit encoding.
        patch.writeBytes(header + byteArrayOf(0x81.toByte(), 1, 0x81.toByte(), 2, 1, 32))
        ApkPatch.validateHeader(patch, baseSize = 130, targetSize = 129)
        assertThrows(IllegalArgumentException::class.java) { ApkPatch.validateHeader(patch, 130, 128) }
        assertThrows(IllegalArgumentException::class.java) { ApkPatch.validateHeader(patch, 129, 129) }

        patch.writeBytes(header + byteArrayOf(0x81.toByte(), 1, 0x81.toByte(), 2, 1,
            0x82.toByte(), 0x80.toByte(), 0x80.toByte(), 1))
        assertThrows(IllegalArgumentException::class.java) { ApkPatch.validateHeader(patch, 130, 129) }
    }
}
