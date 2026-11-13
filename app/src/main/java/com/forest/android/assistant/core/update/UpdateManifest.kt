package com.forest.android.assistant.core.update

import org.json.JSONObject

private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private val sha256Pattern = Regex("[0-9a-f]{64}")

internal const val GITHUB_RELEASES = "https://github.com/forestcnz/assistant/releases"

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

    fun toJson(): JSONObject = JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("minSdk", minSdk)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("notes", notes)
        .put("apkUrl", apkUrl)

    companion object {
        fun parse(json: JSONObject): UpdateManifest {
            val name = json.getString("versionName")
            val code = json.positiveInteger("versionCode")
            val sdk = json.positiveInteger("minSdk")
            val size = json.positiveInteger("sizeBytes")
            val hash = json.getString("sha256")
            val url = json.getString("apkUrl")
            require(name.matches(versionPattern) && name.length <= 32 && sdk <= Int.MAX_VALUE)
            require(hash.matches(sha256Pattern))
            require(url == "$GITHUB_RELEASES/download/v$name/assistant-v$name-release.apk")
            return UpdateManifest(code, name, sdk.toInt(), size, hash, json.getString("notes"), url)
        }

        private fun JSONObject.positiveInteger(key: String): Long {
            val value = get(key)
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it > 0) }
        }
    }
}
