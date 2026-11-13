package com.forest.android.assistant.core.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forest.android.assistant.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.net.ssl.HttpsURLConnection

internal class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        updateTransferMutex.withLock {
            val prefs = applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            val downloads = UpdateDownloads(applicationContext, prefs)
            val download = downloads.restore()
            if (download == null || download.legacyId != null || download.fileName != inputData.getString(FILE_NAME)) {
                return@withLock Result.failure()
            }
            try {
                val directory = downloads.updateDirectory()
                val requiredSpace = if (download.patch == null) 0 else download.manifest.sizeBytes
                val transfer = UpdateTransfer(directory, download, requiredSpace)
                val notification = showNotification(download)
                try {
                    transfer.run { ensureActive() }
                } finally {
                    notification?.cancel(NOTIFICATION_ID)
                }
                Result.success()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                when (error) {
                    is UpdateStorageException -> Result.failure(workDataOf(ERROR to "设备空间不足，请清理后重试"))
                    is UpdateAssetMissingException -> Result.failure(workDataOf(ASSET_MISSING to true))
                    else -> Result.failure()
                }
            }
        }
    }

    private fun showNotification(download: UpdateDownload): NotificationManager? = runCatching {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(applicationContext, NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("雕雕助手 ${download.manifest.tag}")
            .setContentText(if (download.patch == null) "正在从 GitHub 下载更新" else "正在下载增量包")
            .setContentIntent(openApp)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        manager
    }.getOrNull()

    companion object {
        const val FILE_NAME = "fileName"
        const val ERROR = "error"
        const val ASSET_MISSING = "assetMissing"
        private const val CHANNEL_ID = "app_update"
        private const val NOTIFICATION_ID = 1006
    }
}

internal class UpdateTransfer(
    directory: File,
    private val download: UpdateDownload,
    private val reservedSpace: Long,
    private val openConnection: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection },
) {
    private val target = File(directory, download.payloadName)
    private val partial = File(directory, download.partialName)
    private val validator = File(directory, "${download.partialName}.etag")

    fun run(checkActive: () -> Unit) {
        if (target.isFile) return

        val size = download.sizeBytes
        val existing = partial.length()
        val etag = if (existing in 1 until size && validator.isFile) validator.readText().takeIf(::strongEtag) else null
        val offset = if (etag != null) existing else 0
        if (!request(offset, etag, checkActive)) request(0, null, checkActive)
    }

    private fun request(offset: Long, etag: String?, checkActive: () -> Unit): Boolean {
        var uri = URI(download.url)
        repeat(8) {
            checkActive()
            if (uri.scheme != "https") throw IOException("下载地址必须使用 HTTPS")
            val connection = openConnection(uri)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "DiaoDiaoAssistant-Android")
                if (etag != null) {
                    connection.setRequestProperty("Range", "bytes=$offset-")
                    connection.setRequestProperty("If-Range", etag)
                }
                val status = connection.responseCode
                when (status) {
                    in 300..399 -> {
                        uri = uri.resolve(connection.getHeaderField("Location") ?: throw IOException("下载链接无效"))
                    }
                    200 -> {
                        receive(connection, 0, checkActive)
                        return true
                    }
                    206 -> {
                        if (etag == null) throw IOException("续传响应无效")
                        val range = "bytes $offset-${download.sizeBytes - 1}/${download.sizeBytes}"
                        if (connection.getHeaderField("Content-Range") != range || connection.getHeaderField("ETag") != etag) {
                            return false
                        }
                        receive(connection, offset, checkActive)
                        return true
                    }
                    404, 410 -> throw UpdateAssetMissingException()
                    412, 416 -> if (etag != null) return false else throw IOException("服务器返回 HTTP $status")
                    else -> throw IOException("服务器返回 HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("下载链接重定向次数过多")
    }

    private fun receive(
        connection: HttpsURLConnection,
        start: Long,
        checkActive: () -> Unit,
    ) {
        val size = download.sizeBytes
        val remaining = size - start
        if (connection.contentLengthLong != -1L && connection.contentLengthLong != remaining) {
            throw IOException("更新文件大小不符")
        }
        val reclaimed = if (start == 0L) partial.length() else 0L
        if (remaining > partial.parentFile!!.usableSpace + reclaimed - reservedSpace) throw UpdateStorageException()

        connection.inputStream.use { input ->
            RandomAccessFile(partial, "rw").use { output ->
                checkActive()
                if (start == 0L) {
                    output.setLength(0)
                    validator.writeText(connection.getHeaderField("ETag").orEmpty())
                }
                output.seek(start)
                var received = start
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count > size - received) throw IOException("更新文件大小不符")
                    output.write(buffer, 0, count)
                    received += count
                }
                if (received != size) throw IOException("下载中断")
                output.fd.sync()
            }
        }
        checkActive()
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        validator.delete()
    }

    private fun strongEtag(value: String): Boolean = value.length >= 2 && value.first() == '"' && value.last() == '"'
}

private class UpdateStorageException : IOException()
private class UpdateAssetMissingException : IOException()
