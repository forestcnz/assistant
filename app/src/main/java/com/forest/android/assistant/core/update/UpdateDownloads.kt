package com.forest.android.assistant.core.update

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.forest.android.assistant.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class UpdateDownload(val id: Long, val fileName: String, val offers: List<UpdateOffer>) {
    val offer get() = offers.first()
}

internal data class DownloadProgress(val status: Int, val received: Long = 0, val reason: Int = 0)

internal class UpdateDownloads(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val repository: UpdateRepository,
) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val packageManager = context.packageManager

    fun installedPackage(): PackageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    fun isOfficial(info: PackageInfo): Boolean {
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        return signers.size == 1 && sha256(signers.single().toByteArray()) == BuildConfig.RELEASE_CERTIFICATE_SHA256
    }

    fun restore(): UpdateDownload? {
        val saved = prefs.getString("download", null) ?: return null
        return try {
            val json = JSONObject(saved)
            val id = json.getLong("id")
            val name = json.getString("fileName")
            val items = json.getJSONArray("offers")
            val offers = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                val source = UpdateSource.valueOf(item.getString("source"))
                UpdateOffer(source, UpdateManifest.parse(item.getJSONObject("manifest"), repository.releaseBase(source)))
            }
            require(id > 0 && name.matches(Regex("[0-9a-f-]{36}\\.apk")) && offers.isNotEmpty())
            require(offers.all { it.manifest.matchesApk(offers.first().manifest) })
            UpdateDownload(id, name, offers)
        } catch (_: Exception) {
            prefs.edit().remove("download").apply()
            null
        }
    }

    fun start(offers: List<UpdateOffer>): UpdateDownload {
        val offer = offers.first()
        val fileName = "${UUID.randomUUID()}.apk"
        val directory = updateDirectory()
        check(directory.isDirectory || directory.mkdirs()) { "无法创建下载目录" }
        val request = DownloadManager.Request(offer.manifest.apkUrl.toUri())
            .setTitle("雕雕助手 ${offer.manifest.tag}")
            .setDescription("从 ${offer.source.label} 下载更新")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "app-updates/$fileName")
        val download = UpdateDownload(manager.enqueue(request), fileName, offers)
        val items = JSONArray(offers.map {
            JSONObject().put("source", it.source.name).put("manifest", it.manifest.toJson())
        })
        val json = JSONObject().put("id", download.id).put("fileName", fileName).put("offers", items)
        if (!prefs.edit().putString("download", json.toString()).commit()) {
            removeFiles(download)
            error("无法保存下载记录")
        }
        return download
    }

    fun status(download: UpdateDownload): DownloadProgress =
        manager.query(DownloadManager.Query().setFilterById(download.id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use DownloadProgress(DownloadManager.STATUS_FAILED)
            DownloadProgress(
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        } ?: DownloadProgress(DownloadManager.STATUS_FAILED)

    fun clear(download: UpdateDownload) {
        prefs.edit().remove("download").commit()
        removeFiles(download)
    }

    fun removeFiles(download: UpdateDownload) {
        runCatching { manager.remove(download.id) }
        runCatching { apkFile(download).delete() }
    }

    fun verifiedApk(download: UpdateDownload): File {
        val manifest = download.offer.manifest
        val file = apkFile(download)
        require(file.isFile && file.length() == manifest.sizeBytes) { "安装包大小不符" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hex() == manifest.sha256) { "安装包 SHA-256 校验失败" }
        val archive = packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive != null && archive.packageName == context.packageName && isOfficial(archive)) {
            "安装包的包名或签名不匹配"
        }
        require(archive.longVersionCode == manifest.versionCode && archive.versionName == manifest.versionName &&
            archive.longVersionCode > installedPackage().longVersionCode
        ) { "安装包版本不匹配" }
        return file
    }

    private fun apkFile(download: UpdateDownload): File = File(updateDirectory(), download.fileName)

    private fun updateDirectory(): File {
        val storage = requireNotNull(context.getExternalFilesDir(null)) { "应用存储不可用" }
        return File(storage, "app-updates")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
