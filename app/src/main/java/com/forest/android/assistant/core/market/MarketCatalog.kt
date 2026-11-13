package com.forest.android.assistant.core.market

import org.json.JSONObject
import java.net.URI
import java.time.LocalDate

internal const val MARKET_REPO = "https://github.com/forestcnz/assistant-scripts"
internal const val MARKET_RELEASES = "$MARKET_REPO/releases"
internal const val MAX_MARKET_ZIP_BYTES = 20L * 1024 * 1024

internal data class MarketSource(val scriptId: String) {
    fun toJson(): JSONObject = JSONObject().put("scriptId", scriptId)

    companion object {
        private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        fun parse(json: JSONObject): MarketSource {
            val id = json.getString("scriptId")
            require(id.length <= 48 && idPattern.matches(id)) { "脚本 ID 无效" }
            return MarketSource(id)
        }
    }
}

internal data class MarketRelease(
    val source: MarketSource,
    val title: String,
    val description: String,
    val author: String,
    val targetApp: String?,
    val targetPackage: String?,
    val usage: String,
    val environment: String,
    val capabilities: List<String>,
    val changes: String,
    val updatedAt: LocalDate,
    val version: Int,
    val minAppVersionCode: Int,
    val sizeBytes: Long,
    val sha256: String,
    val revision: String
) {
    val downloadUrl: String get() = "$MARKET_RELEASES/download/market-$revision/${source.scriptId}.zip"
    val sourceUrl: String get() =
        "$MARKET_REPO/tree/$revision" + URI(null, null, "/scripts/${targetApp ?: "通用"}/$title", null).toASCIIString()
}

internal object MarketCatalog {
    private const val MAX_RELEASES = 500
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val revisionPattern = Regex("[0-9a-f]{40}")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun parse(text: String): List<MarketRelease> {
        val catalog = JSONObject(text)
        require(catalog.getInt("schemaVersion") == 1) { "市场目录版本不受支持" }
        val entries = catalog.getJSONArray("releases")
        require(entries.length() <= MAX_RELEASES) { "市场目录条目过多" }
        val releases = (0 until entries.length()).map { readRelease(entries.getJSONObject(it)) }
        require(releases.map { it.source }.toSet().size == releases.size) { "市场目录包含重复脚本" }
        return releases.sortedWith(compareByDescending<MarketRelease> { it.updatedAt }.thenBy { it.title })
    }

    private fun readRelease(json: JSONObject): MarketRelease {
        val size = json.getLong("sizeBytes")
        val sha = json.getString("sha256")
        val revision = json.getString("revision")
        // 旧目录未提供脚本版本，视为首版。
        val version = if (json.has("version")) json.get("version") else 1
        val minVersion = json.getInt("minAppVersionCode")
        val targetApp = json.optionalText("targetApp", 80)?.also(::validateDirectoryName)
        val targetPackage = json.optionalText("targetPackage", 160)
        val title = json.requiredText("title", 50).also(::validateDirectoryName)
        require(size in 1..MAX_MARKET_ZIP_BYTES && sha256Pattern.matches(sha) && revisionPattern.matches(revision)) {
            "市场包校验信息无效"
        }
        require(minVersion > 0) { "最低助手版本无效" }
        require(version is Int && version > 0) { "脚本版本号无效" }
        require(targetPackage == null || (targetApp != null && packagePattern.matches(targetPackage))) {
            "目标应用信息无效"
        }
        val capabilities = json.getJSONArray("capabilities")
        require(capabilities.length() <= 8) { "脚本能力条目过多" }
        return MarketRelease(
            source = MarketSource.parse(json),
            title = title,
            description = json.requiredText("description", 200),
            author = json.requiredText("author", 60),
            targetApp = targetApp,
            targetPackage = targetPackage,
            usage = json.requiredText("usage", 2000),
            environment = json.requiredText("environment", 500),
            capabilities = (0 until capabilities.length()).map {
                capabilities.getString(it).trim().also { value ->
                    require(value.isNotEmpty() && value.length <= 80) { "脚本能力说明无效" }
                }
            },
            changes = json.requiredText("changes", 500),
            updatedAt = LocalDate.parse(json.getString("updatedAt")),
            version = version,
            minAppVersionCode = minVersion,
            sizeBytes = size,
            sha256 = sha,
            revision = revision
        )
    }

    private fun validateDirectoryName(name: String) {
        require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "市场源码路径无效"
        }
    }

    private fun JSONObject.requiredText(field: String, limit: Int): String =
        getString(field).trim().also { require(it.isNotEmpty() && it.length <= limit) { "市场目录字段无效：$field" } }

    private fun JSONObject.optionalText(field: String, limit: Int): String? =
        if (isNull(field)) null else requiredText(field, limit)
}
