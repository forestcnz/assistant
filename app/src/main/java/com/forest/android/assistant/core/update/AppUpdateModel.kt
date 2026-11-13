package com.forest.android.assistant.core.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forest.android.assistant.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal enum class DownloadPhase { IDLE, STARTING, DOWNLOADING, INTERRUPTED, ASSEMBLING, VERIFYING, READY }

internal data class AppUpdateState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val official: Boolean? = null,
    val automatic: Boolean = true,
    val checking: Boolean = false,
    val availableUpdate: UpdateManifest? = null,
    val notice: String? = null,
    val downloadPhase: DownloadPhase = DownloadPhase.IDLE,
    val receivedBytes: Long = 0,
    val downloadSizeBytes: Long = 0,
    val incremental: Boolean = false,
    val useFullPackage: Boolean = false,
)

internal class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val repository = UpdateRepository()
    private val downloads = UpdateDownloads(application, prefs)
    private val downloadMutex = Mutex()
    private val mutableState = MutableStateFlow(AppUpdateState(automatic = prefs.getBoolean("automatic", true)))
    val state = mutableState.asStateFlow()
    private var installedCode = 0L
    private var currentDownload: UpdateDownload? = null
    private var downloadRefresh: Job? = null

    init {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { downloads.installedPackage() }
            installedCode = installed.longVersionCode
            val official = downloads.isOfficial(installed)
            val saved = withContext(Dispatchers.IO) {
                downloads.restore()?.takeIf {
                    val manifest = it.manifest
                    if (official && manifest.versionCode > installedCode && manifest.minSdk <= Build.VERSION.SDK_INT) true
                    else {
                        downloads.clear(it)
                        false
                    }
                }
            }
            currentDownload = saved
            mutableState.update {
                it.copy(
                    currentVersion = installed.versionName ?: BuildConfig.VERSION_NAME,
                    official = official,
                    availableUpdate = saved?.manifest,
                    downloadPhase = if (saved == null) DownloadPhase.IDLE else DownloadPhase.DOWNLOADING,
                    downloadSizeBytes = saved?.sizeBytes ?: 0,
                    incremental = saved?.patch != null,
                    useFullPackage = saved?.manifest?.sha256 == failedPatchTarget(),
                )
            }
        }
    }

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        mutableState.update { it.copy(automatic = enabled) }
        if (enabled) check(manual = false)
    }

    fun check(manual: Boolean) {
        val current = state.value
        if (current.official != true || current.checking || current.downloadPhase != DownloadPhase.IDLE) return
        if (!manual && !current.automatic) return
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("last_check", 0) in 0 until CHECK_INTERVAL_MS) return
        prefs.edit().putLong("last_check", now).apply()
        mutableState.update { it.copy(checking = true, notice = null) }
        viewModelScope.launch {
            val manifest = try {
                repository.check()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = when (error) {
                    is SocketTimeoutException -> "连接超时"
                    is UnknownHostException -> "无法访问服务器"
                    is IOException -> error.message?.take(100) ?: "网络请求失败"
                    else -> "更新清单无效"
                }
                mutableState.update { it.copy(checking = false, availableUpdate = null, notice = "GitHub：$message") }
                return@launch
            }
            val notice = when {
                manifest.versionCode <= installedCode -> "已是最新版本"
                manifest.minSdk > Build.VERSION.SDK_INT -> "新版需要 Android API ${manifest.minSdk} 或更高版本"
                else -> null
            }
            val latest = manifest.takeIf { notice == null }
            mutableState.update {
                it.copy(checking = false, availableUpdate = latest, notice = notice,
                    useFullPackage = latest?.sha256 == failedPatchTarget())
            }
        }
    }

    fun showNotice(message: String) {
        mutableState.update { it.copy(notice = message) }
    }

    fun startDownload() {
        viewModelScope.launch {
            downloadMutex.withLock {
                val current = state.value
                if (current.downloadPhase != DownloadPhase.IDLE || current.checking) return@withLock
                val manifest = current.availableUpdate ?: return@withLock
                showDownload(DownloadPhase.STARTING)
                try {
                    val download = withContext(Dispatchers.IO) { downloads.start(manifest, current.useFullPackage) }
                    currentDownload = download
                    enqueue(download)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    showDownload(DownloadPhase.IDLE, error.message ?: "无法开始下载，请重试")
                }
            }
        }
    }

    fun retryDownload() {
        viewModelScope.launch {
            downloadMutex.withLock {
                val saved = currentDownload ?: return@withLock
                val current = state.value
                if (current.downloadPhase != DownloadPhase.INTERRUPTED) return@withLock
                try {
                    val download = if (saved.legacyId == null) saved else withContext(Dispatchers.IO) {
                        if (downloads.status(saved).status == DownloadStatus.COMPLETE) saved else downloads.replaceLegacy(saved)
                    }
                    currentDownload = download
                    if (download.legacyId != null) showDownload(DownloadPhase.DOWNLOADING) else enqueue(download)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    showDownload(DownloadPhase.INTERRUPTED, error.message ?: "无法重试下载", current.receivedBytes)
                }
            }
        }
    }

    private suspend fun enqueue(download: UpdateDownload) {
        val received = state.value.receivedBytes
        showDownload(DownloadPhase.STARTING, receivedBytes = received)
        try {
            downloads.enqueue(download)
            showDownload(DownloadPhase.DOWNLOADING, receivedBytes = received)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            showDownload(DownloadPhase.INTERRUPTED, "无法启动下载，请重试", received)
        }
    }

    fun cancelDownload() {
        viewModelScope.launch {
            downloadMutex.withLock {
                val download = currentDownload ?: return@withLock
                withContext(Dispatchers.IO) { downloads.clear(download) }
                currentDownload = null
                val notice = if (state.value.downloadPhase == DownloadPhase.READY) "已删除安装包" else "已取消下载"
                showDownload(DownloadPhase.IDLE, notice)
            }
        }
    }

    fun refreshDownload() {
        if (downloadRefresh?.isActive == true) return
        downloadRefresh = viewModelScope.launch { readDownloadProgress() }
    }

    private suspend fun readDownloadProgress() {
        downloadMutex.withLock {
            val download = currentDownload ?: return
            val phase = state.value.downloadPhase
            if (phase != DownloadPhase.DOWNLOADING && (phase != DownloadPhase.INTERRUPTED || download.legacyId == null)) return
            val progress = try {
                withContext(Dispatchers.IO) { downloads.status(download) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showDownload(DownloadPhase.INTERRUPTED, "无法读取下载状态，请重试", state.value.receivedBytes)
                return
            }
            when (progress.status) {
                DownloadStatus.COMPLETE -> {
                    try {
                        if (withContext(Dispatchers.IO) { downloads.needsAssembly(download) }) {
                            showDownload(DownloadPhase.ASSEMBLING)
                            withContext(Dispatchers.IO) { downloads.assembleApk(download) }
                        } else {
                            showDownload(DownloadPhase.VERIFYING)
                            withContext(Dispatchers.IO) { downloads.verifiedApk(download) }
                        }
                        showDownload(DownloadPhase.READY)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failDownload(download, error.message ?: "安装包校验失败")
                    }
                }
                DownloadStatus.INTERRUPTED -> {
                    if (progress.assetMissing && download.patch != null) {
                        failDownload(download, "增量包不可用")
                    } else {
                        val message = when {
                            progress.assetMissing -> "GitHub 安装包暂不可用"
                            else -> progress.message ?: "GitHub 下载中断，可立即重试"
                        }
                        showDownload(DownloadPhase.INTERRUPTED, message, progress.received)
                    }
                }
                DownloadStatus.ACTIVE -> showDownload(DownloadPhase.DOWNLOADING, receivedBytes = progress.received)
            }
        }
    }

    suspend fun prepareInstaller(): Intent? = downloadMutex.withLock {
        val download = currentDownload ?: return null
        if (state.value.downloadPhase != DownloadPhase.READY) return null
        showDownload(DownloadPhase.VERIFYING)
        val app = getApplication<Application>()
        try {
            val file = withContext(Dispatchers.IO) { downloads.verifiedApk(download) }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            showDownload(DownloadPhase.READY)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: CancellationException) {
            showDownload(DownloadPhase.READY)
            throw error
        } catch (error: Exception) {
            failDownload(download, error.message ?: "安装包校验失败")
            null
        }
    }

    private suspend fun failDownload(download: UpdateDownload, reason: String) {
        withContext(Dispatchers.IO) { downloads.clear(download) }
        currentDownload = null
        if (download.patch != null) {
            prefs.edit().putString("failed_patch_target", download.manifest.sha256).apply()
            showDownload(DownloadPhase.IDLE, "增量更新失败，请下载完整包")
        } else {
            showDownload(DownloadPhase.IDLE, reason)
        }
    }

    private fun failedPatchTarget(): String = prefs.getString("failed_patch_target", "").orEmpty()

    private fun showDownload(phase: DownloadPhase, notice: String? = null, receivedBytes: Long = 0) {
        mutableState.update {
            it.copy(downloadPhase = phase, receivedBytes = receivedBytes, notice = notice,
                downloadSizeBytes = currentDownload?.sizeBytes ?: 0,
                incremental = currentDownload?.patch != null,
                useFullPackage = it.availableUpdate?.sha256 == failedPatchTarget())
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
