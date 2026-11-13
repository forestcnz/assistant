package com.forest.android.assistant.core.update

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.forest.android.assistant.BuildConfig
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class UpdateDownload(
    val id: Long,
    val fileName: String,
    val manifest: UpdateManifest,
    val patch: UpdatePatch? = null,
) {
    val sizeBytes get() = patch?.sizeBytes ?: manifest.sizeBytes
    val payloadName get() = if (patch == null) fileName else "$fileName.hdiff"
}

internal data class DownloadProgress(val status: Int, val received: Long = 0, val reason: Int = 0)

internal class UpdateDownloads(
    private val context: Context,
    private val prefs: SharedPreferences,
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
        var id = 0L
        var name = ""
        return try {
            val json = JSONObject(saved)
            id = json.getLong("id")
            name = json.getString("fileName")
            require(id > 0 && fileNamePattern.matches(name))
            // 旧记录的第一项对应 DownloadManager 实际下载的地址。
            val manifest = json.optJSONObject("manifest")
                ?: json.getJSONArray("offers").getJSONObject(0).getJSONObject("manifest")
            val update = UpdateManifest.parse(manifest)
            val patch = if (json.has("patchUrl")) {
                val url = json.getString("patchUrl")
                requireNotNull(update.patches.firstOrNull { it.url == url })
            } else null
            UpdateDownload(id, name, update, patch)
        } catch (_: Exception) {
            prefs.edit().remove("download").apply()
            if (id > 0 && fileNamePattern.matches(name)) removeFiles(id, name)
            null
        }
    }

    fun start(manifest: UpdateManifest, fullPackage: Boolean): UpdateDownload {
        val fileName = "${UUID.randomUUID()}.apk"
        val directory = updateDirectory()
        check(directory.isDirectory || directory.mkdirs()) { "无法创建下载目录" }
        val patch = if (fullPackage) null else matchingPatch(manifest, directory)
        val pending = UpdateDownload(0, fileName, manifest, patch)
        check(directory.usableSpace >= manifest.sizeBytes) { "设备空间不足，请清理后重试" }
        val request = DownloadManager.Request((patch?.url ?: manifest.apkUrl).toUri())
            .setTitle("雕雕助手 ${manifest.tag}")
            .setDescription(if (patch != null) "从 GitHub 下载增量包" else "从 GitHub 下载更新")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "app-updates/${pending.payloadName}")
        val download = pending.copy(id = manager.enqueue(request))
        val json = JSONObject().put("id", download.id).put("fileName", fileName).put("manifest", manifest.toJson())
        if (patch != null) json.put("patchUrl", patch.url)
        if (!prefs.edit().putString("download", json.toString()).commit()) {
            removeFiles(download.id, download.fileName)
            error("无法保存下载记录")
        }
        return download
    }

    private fun matchingPatch(manifest: UpdateManifest, directory: File): UpdatePatch? {
        val installed = installedPackage()
        val patchSpace = directory.usableSpace - manifest.sizeBytes
        val candidates = manifest.patches.filter {
            it.fromVersionCode == installed.longVersionCode && it.sizeBytes <= patchSpace
        }
        if (candidates.isEmpty()) return null
        val base = baseApk(installed) ?: return null
        val hash = runCatching { sha256(base) }.getOrNull() ?: return null
        return candidates.filter { it.fromSha256 == hash }.minByOrNull { it.sizeBytes }
    }

    private fun baseApk(installed: PackageInfo): File? {
        val info = installed.applicationInfo ?: return null
        if (!info.splitSourceDirs.isNullOrEmpty()) return null
        return info.sourceDir?.let(::File)?.takeIf { it.isFile && it.canRead() }
    }

    fun status(download: UpdateDownload): DownloadProgress {
        if (download.patch != null && File(updateDirectory(), download.fileName).isFile) {
            return DownloadProgress(DownloadManager.STATUS_SUCCESSFUL)
        }
        return manager.query(DownloadManager.Query().setFilterById(download.id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use DownloadProgress(DownloadManager.STATUS_FAILED)
            DownloadProgress(
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        } ?: DownloadProgress(DownloadManager.STATUS_FAILED)
    }

    fun clear(download: UpdateDownload) {
        prefs.edit().remove("download").commit()
        removeFiles(download.id, download.fileName)
    }

    private fun removeFiles(id: Long, fileName: String) {
        runCatching { manager.remove(id) }
        runCatching {
            val directory = updateDirectory()
            for (name in listOf(fileName, "$fileName.hdiff", "$fileName.part.apk")) File(directory, name).delete()
        }
    }

    fun needsAssembly(download: UpdateDownload): Boolean =
        download.patch != null && !File(updateDirectory(), download.fileName).isFile

    fun assembleApk(download: UpdateDownload) {
        val patch = requireNotNull(download.patch)
        val directory = updateDirectory()
        val payload = File(directory, download.payloadName)
        require(payload.isFile && payload.length() == patch.sizeBytes && sha256(payload) == patch.sha256) {
            "增量包校验失败"
        }
        val installed = installedPackage()
        val base = baseApk(installed)
        require(installed.longVersionCode == patch.fromVersionCode && base != null && sha256(base) == patch.fromSha256) {
            "旧版 APK 与增量包不匹配"
        }
        val target = File(directory, download.fileName)
        val temporary = File(directory, "${download.fileName}.part.apk")
        Files.deleteIfExists(temporary.toPath())
        check(directory.usableSpace >= download.manifest.sizeBytes) { "设备空间不足，请清理后重试" }
        try {
            ApkPatch.apply(base, payload, temporary, download.manifest.sizeBytes)
            verifyApk(temporary, download.manifest)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            runCatching { manager.remove(download.id) }
        } finally {
            temporary.delete()
        }
    }

    fun verifiedApk(download: UpdateDownload): File {
        val file = File(updateDirectory(), download.fileName)
        verifyApk(file, download.manifest)
        return file
    }

    private fun verifyApk(file: File, manifest: UpdateManifest) {
        require(file.isFile && file.length() == manifest.sizeBytes) { "安装包大小不符" }
        require(sha256(file) == manifest.sha256) { "安装包 SHA-256 校验失败" }
        val archive = packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive != null && archive.packageName == context.packageName && isOfficial(archive)) {
            "安装包的包名或签名不匹配"
        }
        require(archive.longVersionCode == manifest.versionCode && archive.versionName == manifest.versionName &&
            archive.longVersionCode > installedPackage().longVersionCode
        ) { "安装包版本不匹配" }
    }

    private fun updateDirectory(): File {
        val storage = requireNotNull(context.getExternalFilesDir(null)) { "应用存储不可用" }
        return File(storage, "app-updates")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val fileNamePattern = Regex("[0-9a-f-]{36}\\.apk")
    }
}
