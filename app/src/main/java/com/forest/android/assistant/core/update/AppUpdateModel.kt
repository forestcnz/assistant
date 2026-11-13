package com.forest.android.assistant.core.update

import android.app.Application
import android.app.DownloadManager
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

internal enum class DownloadPhase { IDLE, STARTING, DOWNLOADING, VERIFYING, READY }

internal data class AppUpdateState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val official: Boolean? = null,
    val automatic: Boolean = true,
    val checking: Boolean = false,
    val availableUpdate: UpdateManifest? = null,
    val notice: String? = null,
    val downloadPhase: DownloadPhase = DownloadPhase.IDLE,
    val receivedBytes: Long = 0,
    val paused: Boolean = false,
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
            mutableState.update { it.copy(checking = false, availableUpdate = latest, notice = notice) }
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
                    currentDownload = withContext(Dispatchers.IO) { downloads.start(manifest) }
                    showDownload(DownloadPhase.DOWNLOADING)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showDownload(DownloadPhase.IDLE, "无法开始下载，请重试")
                }
            }
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
            if (state.value.downloadPhase != DownloadPhase.DOWNLOADING) return
            val progress = try {
                withContext(Dispatchers.IO) { downloads.status(download) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failDownload(download, "无法读取下载状态，请重试")
                return
            }
            when (progress.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    showDownload(DownloadPhase.VERIFYING)
                    try {
                        withContext(Dispatchers.IO) { downloads.verifiedApk(download) }
                        showDownload(DownloadPhase.READY)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failDownload(download, error.message ?: "安装包校验失败")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val noSpace = progress.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                    val reason = if (noSpace) "设备空间不足，请清理后重试" else "GitHub 下载失败，请重试"
                    failDownload(download, reason)
                }
                else -> mutableState.update {
                    it.copy(receivedBytes = progress.received, paused = progress.status == DownloadManager.STATUS_PAUSED)
                }
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
        showDownload(DownloadPhase.IDLE, reason)
    }

    private fun showDownload(phase: DownloadPhase, notice: String? = null) {
        mutableState.update {
            it.copy(downloadPhase = phase, receivedBytes = 0, paused = false, notice = notice)
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
