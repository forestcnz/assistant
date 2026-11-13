package com.forest.android.assistant.core.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun parserAcceptsOnlyVersionedReleaseApkFromTheExpectedRepository() {
        val update = manifest()
        assertEquals(update, UpdateManifest.parse(JSONObject(update.toJson().toString())))
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(update.toJson().put("apkUrl", update.apkUrl.replace("release.apk", "debug.apk")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(update.toJson().put("apkUrl", update.apkUrl.replace("github.com", "gitee.com")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(update.toJson().put("versionCode", 8.5))
        }
    }

    private fun manifest() = UpdateManifest(
        versionCode = 8, versionName = "0.1.7", minSdk = 29, sizeBytes = 1024,
        sha256 = "a".repeat(64), notes = "更新说明", apkUrl = "$GITHUB_RELEASES/download/v0.1.7/assistant-v0.1.7-release.apk",
    )
}
