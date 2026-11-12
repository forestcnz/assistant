package com.forest.android.assistant.core.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val github = "https://github.com/forestcnz/assistant/releases"
    private val gitee = "https://gitee.com/zhangwenlincn/assistant/releases"

    @Test
    fun onlyExactMirrorsOfLatestCompatibleApkAreOffered() {
        val primary = UpdateOffer(UpdateSource.GITHUB, manifest(github))
        val mirror = UpdateOffer(UpdateSource.GITEE, manifest(gitee))
        assertEquals(listOf(primary, mirror), selectUpdateOffers(listOf(mirror, primary), 7, 29))
        assertEquals(listOf(mirror), selectUpdateOffers(listOf(mirror), 7, 29))
        assertEquals(listOf(primary), selectUpdateOffers(listOf(primary, mirror.copy(
            manifest = mirror.manifest.copy(sha256 = "b".repeat(64)),
        )), 7, 29))
        assertEquals(listOf(primary), selectUpdateOffers(listOf(primary, mirror.copy(
            manifest = mirror.manifest.copy(versionCode = 9, minSdk = 30),
        )), 7, 29))
        assertTrue(selectUpdateOffers(listOf(primary, mirror), 8, 29).isEmpty())
    }

    @Test
    fun parserAcceptsOnlyVersionedReleaseApkFromTheExpectedRepository() {
        val update = manifest(github)
        assertEquals(update, UpdateManifest.parse(JSONObject(update.toJson().toString()), github))
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(update.toJson().put("apkUrl", update.apkUrl.replace("release.apk", "debug.apk")), github)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest(gitee).toJson(), github)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(update.toJson().put("versionCode", 8.5), github)
        }
    }

    private fun manifest(base: String) = UpdateManifest(
        versionCode = 8, versionName = "0.1.7", minSdk = 29, sizeBytes = 1024,
        sha256 = "a".repeat(64), notes = "更新说明", apkUrl = "$base/download/v0.1.7/assistant-v0.1.7-release.apk",
    )
}
