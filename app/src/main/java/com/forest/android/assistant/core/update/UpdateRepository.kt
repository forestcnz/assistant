package com.forest.android.assistant.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal class UpdateRepository {
    suspend fun check(): UpdateManifest = withContext(Dispatchers.IO) {
        UpdateManifest.parse(readJson("$GITHUB_RELEASES/latest/download/update.json"))
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
