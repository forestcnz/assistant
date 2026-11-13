package com.forest.android.assistant.core.update

import org.json.JSONArray
import org.json.JSONObject

private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private val sha256Pattern = Regex("[0-9a-f]{64}")

internal const val GITHUB_RELEASES = "https://github.com/forestcnz/assistant/releases"
private const val PATCH_FORMAT = "hdiff-sd-zstd-v1"

internal data class UpdatePatch(
    val fromVersionCode: Long,
    val fromSha256: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", PATCH_FORMAT)
        .put("fromVersionCode", fromVersionCode)
        .put("fromSha256", fromSha256)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("url", url)
}

internal data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sizeBytes: Long,
    val sha256: String,
    val apkUrl: String,
    val patches: List<UpdatePatch> = emptyList(),
) {
    val tag get() = "v$versionName"

    fun toJson(): JSONObject = JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("minSdk", minSdk)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("apkUrl", apkUrl)
        .put("patches", JSONArray(patches.map { it.toJson() }))

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
            val patches = json.optJSONArray("patches")?.let { entries ->
                (0 until minOf(entries.length(), 8)).mapNotNull { index ->
                    val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                    // 差分字段可选，未知格式或无效条目仍允许使用完整包。
                    runCatching { parsePatch(entry, code, size, name) }.getOrNull()
                }
            }.orEmpty()
            return UpdateManifest(code, name, sdk.toInt(), size, hash, url, patches)
        }

        private fun parsePatch(json: JSONObject, targetCode: Long, apkSize: Long, targetName: String): UpdatePatch {
            require(json.getString("format") == PATCH_FORMAT)
            val base = json.positiveInteger("fromVersionCode")
            val size = json.positiveInteger("sizeBytes")
            val baseHash = json.getString("fromSha256")
            val hash = json.getString("sha256")
            val url = json.getString("url")
            require(base < targetCode && size < apkSize - size)
            require(baseHash.matches(sha256Pattern) && hash.matches(sha256Pattern))
            require(url == "$GITHUB_RELEASES/download/v$targetName/assistant-$base-to-$targetCode.hdiff")
            return UpdatePatch(base, baseHash, size, hash, url)
        }

        private fun JSONObject.positiveInteger(key: String): Long {
            val value = get(key)
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it > 0) }
        }
    }
}
