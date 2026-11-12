package com.forest.android.assistant.core.update

import com.forest.android.assistant.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal class UpdateRepository {
    private val giteeRepository = BuildConfig.UPDATE_GITEE_REPOSITORY
    private val sources = UpdateSource.entries.filter { it != UpdateSource.GITEE || giteeRepository.isNotEmpty() }

    fun releaseBase(source: UpdateSource): String = when (source) {
        UpdateSource.GITHUB -> "https://github.com/forestcnz/assistant/releases"
        UpdateSource.GITEE -> "https://gitee.com/$giteeRepository/releases"
    }

    suspend fun check(): Map<UpdateSource, Result<UpdateOffer>> = coroutineScope {
        sources.associateWith { source ->
            async(Dispatchers.IO) {
                try {
                    Result.success(UpdateOffer(source, fetchManifest(source)))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        }.mapValues { it.value.await() }
    }

    private fun fetchManifest(source: UpdateSource): UpdateManifest {
        val base = releaseBase(source)
        if (source == UpdateSource.GITHUB) {
            return UpdateManifest.parse(readJson("$base/latest/download/update.json"), base)
        }
        val release = readJson("https://gitee.com/api/v5/repos/$giteeRepository/releases/latest")
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")) && !release.getBoolean("prerelease"))
        val manifest = UpdateManifest.parse(readJson("$base/download/$tag/update.json"), base)
        require(manifest.tag == tag)
        return manifest
    }

    private fun readJson(address: String): JSONObject {
        var uri = URI(address)
        repeat(8) {
            require(uri.scheme == "https")
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json, application/octet-stream")
                connection.setRequestProperty("User-Agent", "DiaoDiaoAssistant-Android")
                val status = connection.responseCode
                if (status in 300..399) {
                    uri = uri.resolve(connection.getHeaderField("Location") ?: throw IOException("下载链接无效"))
                } else {
                    if (status == 404) throw IOException("尚未发布更新清单")
                    if (status != 200) throw IOException("服务器返回 HTTP $status")
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= 128 * 1024) { "更新清单过大" }
                            output.write(buffer, 0, count)
                        }
                        JSONObject(output.toString(Charsets.UTF_8.name()))
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("下载链接重定向次数过多")
    }
}
