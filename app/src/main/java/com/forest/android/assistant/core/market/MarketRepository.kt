package com.forest.android.assistant.core.market

import android.content.Context
import com.forest.android.assistant.BuildConfig
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.core.task.TaskZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class MarketRepository(private val context: Context) {
    suspend fun load(): List<MarketRelease> = withContext(Dispatchers.IO) {
        val bytes = download("$MARKET_RELEASES/latest/download/catalog.json") { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CATALOG_BYTES) { "市场目录过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        MarketCatalog.parse(bytes.toString(Charsets.UTF_8))
    }

    suspend fun installedTasks(taskNames: List<String>): Map<MarketSource, String> = withContext(Dispatchers.IO) {
        buildMap {
            taskNames.forEach { name ->
                val source = TaskZip.marketSource(TaskManager.taskDir(context, name))
                if (source != null && source !in this) put(source, name)
            }
        }
    }

    suspend fun importTask(release: MarketRelease, name: String): String = withContext(Dispatchers.IO) {
        require(BuildConfig.VERSION_CODE >= release.minAppVersionCode) { "请先更新雕雕助手" }
        val current = load().firstOrNull { it.source == release.source }
            ?: error("脚本已下架，请返回市场刷新")
        require(current.copy(revision = release.revision) == release) { "脚本信息已更新，请返回市场刷新" }
        val archive = File.createTempFile("market-", ".zip", context.cacheDir)
        val job = currentCoroutineContext()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            download(current.downloadUrl) { input ->
                archive.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        job.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= release.sizeBytes) { "下载文件超过预期大小" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            require(size == release.sizeBytes && sha == release.sha256) { "文件校验失败，请刷新市场后重试" }
            job.ensureActive()
            TaskZip.importMarketTask(context, archive, release, name)
        } finally {
            archive.delete()
        }
    }

    private fun <T> download(address: String, read: (InputStream) -> T): T {
        var uri = URI(address)
        repeat(6) {
            require(uri.scheme == "https" && uri.port in setOf(-1, 443) &&
                uri.userInfo == null && uri.host in DOWNLOAD_HOSTS) { "市场下载地址无效" }
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "DiaoDiaoAssistant-Android")
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.buffered().use(read)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("市场下载链接无效")
                        uri = uri.resolve(location)
                    }
                    404, 410 -> throw IOException("市场目录或脚本暂不可下载，请刷新后重试")
                    else -> throw IOException("市场服务器返回 HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("市场下载链接重定向次数过多")
    }

    private companion object {
        const val MAX_CATALOG_BYTES = 1024 * 1024
        val DOWNLOAD_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
