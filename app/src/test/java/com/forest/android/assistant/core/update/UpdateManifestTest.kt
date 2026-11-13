package com.forest.android.assistant.core.update

import org.json.JSONArray
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

    @Test
    fun parserIgnoresNotesInPreviouslyPublishedManifests() {
        val update = manifest()
        assertEquals(update, UpdateManifest.parse(update.toJson().put("notes", "- fix: internal release work")))
    }

    @Test
    fun onlySmallerPatchesFromTheSameReleaseAreAccepted() {
        val update = manifest()
        val patch = UpdatePatch(7, "b".repeat(64), 256, "c".repeat(64),
            "$GITHUB_RELEASES/download/v0.1.7/assistant-7-to-8.hdiff")
        val entries = JSONArray()
            .put(patch.toJson().put("format", "unknown"))
            .put(patch.toJson().put("url", "https://example.com/assistant-7-to-8.hdiff"))
            .put(patch.toJson().put("sizeBytes", 512))
            .put(patch.toJson().put("fromVersionCode", update.versionCode))
            .put(patch.toJson())
        val parsed = UpdateManifest.parse(update.toJson().put("patches", entries))
        assertEquals(listOf(patch), parsed.patches)
        assertEquals(parsed, UpdateManifest.parse(JSONObject(parsed.toJson().toString())))
    }

    private fun manifest() = UpdateManifest(
        versionCode = 8, versionName = "0.1.7", minSdk = 29, sizeBytes = 1024,
        sha256 = "a".repeat(64), apkUrl = "$GITHUB_RELEASES/download/v0.1.7/assistant-v0.1.7-release.apk",
    )
}
