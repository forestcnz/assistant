package com.forest.android.assistant.core.update

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.forest.android.assistant.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class UpdateDownload(
    val fileName: String,
    val manifest: UpdateManifest,
    val patch: UpdatePatch? = null,
    val legacyId: Long? = null,
) {
    val sizeBytes get() = patch?.sizeBytes ?: manifest.sizeBytes
    val url get() = patch?.url ?: manifest.apkUrl
    val payloadName get() = if (patch == null) fileName else "$fileName.hdiff"
    val partialName get() = "$payloadName.download"
    val workName get() = "app-update-$fileName"
}

internal enum class DownloadStatus { ACTIVE, INTERRUPTED, COMPLETE }

internal data class DownloadProgress(
    val status: DownloadStatus,
    val received: Long = 0,
    val message: String? = null,
    val assetMissing: Boolean = false,
)

internal class UpdateDownloads(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private val manager by lazy { context.getSystemService(DownloadManager::class.java) }
    private val workManager by lazy { WorkManager.getInstance(context) }
    private val packageManager = context.packageManager

    fun installedPackage(): PackageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    fun isOfficial(info: PackageInfo): Boolean {
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        return signers.size == 1 && sha256(signers.single().toByteArray()) == BuildConfig.RELEASE_CERTIFICATE_SHA256
    }

    fun restore(): UpdateDownload? {
        val saved = prefs.getString("download", null) ?: return null
        var id: Long? = null
        var name = ""
        return try {
            val json = JSONObject(saved)
            if (json.has("id")) id = json.getLong("id").also { require(it > 0) }
            name = json.getString("fileName")
            require(fileNamePattern.matches(name))
            // 旧记录的第一项对应 DownloadManager 实际下载的地址。
            val manifest = json.optJSONObject("manifest")
                ?: json.getJSONArray("offers").getJSONObject(0).getJSONObject("manifest")
            val update = UpdateManifest.parse(manifest)
            val patch = if (json.has("patchUrl")) {
                val url = json.getString("patchUrl")
                requireNotNull(update.patches.firstOrNull { it.url == url })
            } else null
            UpdateDownload(name, update, patch, id)
        } catch (_: Exception) {
            prefs.edit().remove("download").apply()
            if (fileNamePattern.matches(name)) removeFiles(id, name)
            null
        }
    }

    fun start(manifest: UpdateManifest, fullPackage: Boolean): UpdateDownload {
        val directory = updateDirectory()
        check(directory.isDirectory || directory.mkdirs()) { "无法创建下载目录" }
        val patch = if (fullPackage) null else matchingPatch(manifest, directory)
        check(directory.usableSpace >= manifest.sizeBytes) { "设备空间不足，请清理后重试" }
        val download = UpdateDownload("${UUID.randomUUID()}.apk", manifest, patch)
        save(download)
        return download
    }

    private fun save(download: UpdateDownload) {
        val json = JSONObject().put("fileName", download.fileName).put("manifest", download.manifest.toJson())
        download.patch?.let { json.put("patchUrl", it.url) }
        check(prefs.edit().putString("download", json.toString()).commit()) { "无法保存下载记录" }
    }

    suspend fun enqueue(download: UpdateDownload) {
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(workDataOf(UpdateDownloadWorker.FILE_NAME to download.fileName))
            .build()
        workManager.enqueueUniqueWork(download.workName, ExistingWorkPolicy.REPLACE, request).await()
    }

    suspend fun replaceLegacy(download: UpdateDownload): UpdateDownload {
        val replacement = download.copy(fileName = "${UUID.randomUUID()}.apk", legacyId = null)
        save(replacement)
        discard(download)
        return replacement
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

    suspend fun status(download: UpdateDownload): DownloadProgress {
        val directory = updateDirectory()
        if ((download.patch != null || download.legacyId == null) && File(directory, download.fileName).isFile) {
            return DownloadProgress(DownloadStatus.COMPLETE)
        }
        val id = download.legacyId
        if (id != null) return legacyStatus(id)

        if (File(directory, download.payloadName).isFile) return DownloadProgress(DownloadStatus.COMPLETE)
        val work = workManager.getWorkInfosForUniqueWorkFlow(download.workName).first().firstOrNull()
        val received = File(directory, download.partialName).length()
        if (work != null && !work.state.isFinished) return DownloadProgress(DownloadStatus.ACTIVE, received)
        // Worker 可能在上一次文件检查之后才完成原子重命名。
        if (work?.state == WorkInfo.State.SUCCEEDED && File(directory, download.payloadName).isFile) {
            return DownloadProgress(DownloadStatus.COMPLETE)
        }
        val output = work?.outputData
        return DownloadProgress(DownloadStatus.INTERRUPTED, received,
            output?.getString(UpdateDownloadWorker.ERROR),
            assetMissing = output?.getBoolean(UpdateDownloadWorker.ASSET_MISSING, false) == true,
        )
    }

    private fun legacyStatus(id: Long): DownloadProgress {
        return manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use DownloadProgress(DownloadStatus.INTERRUPTED)
            val received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress(DownloadStatus.COMPLETE)
                DownloadManager.STATUS_FAILED, DownloadManager.STATUS_PAUSED -> DownloadProgress(
                    DownloadStatus.INTERRUPTED, received,
                    if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "设备空间不足，请清理后重试" else null,
                )
                else -> DownloadProgress(DownloadStatus.ACTIVE, received)
            }
        } ?: DownloadProgress(DownloadStatus.INTERRUPTED)
    }

    suspend fun clear(download: UpdateDownload) {
        prefs.edit().remove("download").commit()
        discard(download)
    }

    private suspend fun discard(download: UpdateDownload) {
        if (download.legacyId == null) {
            try {
                workManager.cancelUniqueWork(download.workName).await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // 下载记录已移除，尚未启动的 Worker 会自行结束。
            } finally {
                updateTransferMutex.withLock { removeFiles(null, download.fileName) }
            }
        } else {
            removeFiles(download.legacyId, download.fileName)
        }
    }

    private fun removeFiles(id: Long?, fileName: String) {
        if (id != null) runCatching { manager.remove(id) }
        runCatching {
            val directory = updateDirectory()
            for (payload in listOf(fileName, "$fileName.hdiff")) {
                File(directory, payload).delete()
                File(directory, "$payload.download").delete()
                File(directory, "$payload.download.etag").delete()
            }
            File(directory, "$fileName.part.apk").delete()
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
            if (download.legacyId != null) runCatching { manager.remove(download.legacyId) }
            payload.delete()
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

    internal fun updateDirectory(): File {
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

internal val updateTransferMutex = Mutex()
