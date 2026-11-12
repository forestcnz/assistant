package com.forest.android.assistant.core.update

import org.json.JSONObject

private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private val sha256Pattern = Regex("[0-9a-f]{64}")

internal enum class UpdateSource(val label: String) { GITHUB("GitHub"), GITEE("Gitee") }

internal data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sizeBytes: Long,
    val sha256: String,
    val notes: String,
    val apkUrl: String,
) {
    val tag get() = "v$versionName"

    fun matchesApk(other: UpdateManifest): Boolean = versionCode == other.versionCode &&
        versionName == other.versionName && minSdk == other.minSdk && sizeBytes == other.sizeBytes && sha256 == other.sha256

    fun toJson(): JSONObject = JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("minSdk", minSdk)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("notes", notes)
        .put("apkUrl", apkUrl)

    companion object {
        fun parse(json: JSONObject, releaseBase: String): UpdateManifest {
            val name = json.getString("versionName")
            val code = json.positiveInteger("versionCode")
            val sdk = json.positiveInteger("minSdk")
            val size = json.positiveInteger("sizeBytes")
            val hash = json.getString("sha256")
            val url = json.getString("apkUrl")
            require(name.matches(versionPattern) && name.length <= 32 && sdk <= Int.MAX_VALUE)
            require(hash.matches(sha256Pattern))
            require(url == "$releaseBase/download/v$name/assistant-v$name-release.apk")
            return UpdateManifest(code, name, sdk.toInt(), size, hash, json.getString("notes"), url)
        }

        private fun JSONObject.positiveInteger(key: String): Long {
            val value = get(key)
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it > 0) }
        }
    }
}

internal data class UpdateOffer(val source: UpdateSource, val manifest: UpdateManifest)

/** 保留最高版本。同一版本的镜像只有字节一致时才参与切换。 */
internal fun selectUpdateOffers(offers: List<UpdateOffer>, installedCode: Long, sdk: Int): List<UpdateOffer> {
    val sorted = offers.sortedWith(compareByDescending<UpdateOffer> { it.manifest.versionCode }.thenBy { it.source })
    val best = sorted.firstOrNull { it.manifest.versionCode > installedCode && it.manifest.minSdk <= sdk }
        ?: return emptyList()
    return sorted.filter { it.manifest.matchesApk(best.manifest) }
}
