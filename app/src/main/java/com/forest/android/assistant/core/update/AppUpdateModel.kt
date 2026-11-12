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

internal enum class DownloadPhase { IDLE, STARTING, DOWNLOADING, VERIFYING, READY }

internal data class AppUpdateState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val official: Boolean? = null,
    val automatic: Boolean = true,
    val checking: Boolean = false,
    val offers: List<UpdateOffer> = emptyList(),
    val notice: String? = null,
    val downloadPhase: DownloadPhase = DownloadPhase.IDLE,
    val download: UpdateOffer? = null,
    val receivedBytes: Long = 0,
    val paused: Boolean = false,
    val canSwitchSource: Boolean = false,
    val announcement: UpdateManifest? = null,
)

internal class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val repository = UpdateRepository()
    private val downloads = UpdateDownloads(application, prefs, repository)
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
                    val manifest = it.offer.manifest
                    if (official && manifest.versionCode > installedCode && manifest.minSdk <= Build.VERSION.SDK_INT) true
                    else {
                        downloads.clear(it)
                        false
                    }
                }
            }
            currentDownload = saved
            mutableState.update {
                it.copy(currentVersion = installed.versionName ?: BuildConfig.VERSION_NAME, official = official,
                    offers = saved?.offers.orEmpty(), download = saved?.offer,
                    downloadPhase = if (saved == null) DownloadPhase.IDLE else DownloadPhase.DOWNLOADING,
                    canSwitchSource = (saved?.offers?.size ?: 0) > 1)
            }
        }
    }

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        mutableState.update { it.copy(automatic = enabled, announcement = if (enabled) it.announcement else null) }
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
            val results = repository.check()
            val available = results.values.mapNotNull { it.getOrNull() }
            val offers = selectUpdateOffers(available, installedCode, Build.VERSION.SDK_INT)
            val incompatible = available.filter { it.manifest.versionCode > installedCode }.maxByOrNull { it.manifest.versionCode }
            val notice = when {
                offers.isNotEmpty() -> null
                incompatible != null -> "新版需要 Android API ${incompatible.manifest.minSdk} 或更高版本"
                results.values.all { it.isSuccess } -> "已是最新版本"
                available.isNotEmpty() -> "可访问的发布源暂无新版，部分发布源检查失败"
                else -> results.entries.joinToString("\n") { (source, result) ->
                    val error = result.exceptionOrNull()
                    val message = when (error) {
                        is java.net.SocketTimeoutException -> "连接超时"
                        is java.net.UnknownHostException -> "无法访问服务器"
                        is java.io.IOException -> error.message?.take(100) ?: "网络请求失败"
                        else -> "更新清单无效"
                    }
                    "${source.label}：$message"
                }
            }
            val latest = offers.firstOrNull()?.manifest
            mutableState.update {
                val announcement = latest?.takeIf { release ->
                    !manual && it.automatic && release.versionCode > prefs.getLong("announced_version", 0)
                }
                it.copy(checking = false, offers = offers, notice = notice, announcement = announcement)
            }
        }
    }

    fun markAnnounced(versionCode: Long): Boolean {
        if (prefs.getLong("announced_version", 0) >= versionCode) return false
        prefs.edit().putLong("announced_version", versionCode).apply()
        return true
    }

    fun startDownload(source: UpdateSource) {
        viewModelScope.launch {
            downloadMutex.withLock {
                val current = state.value
                if (current.downloadPhase != DownloadPhase.IDLE || current.checking) return@withLock
                val selected = current.offers.firstOrNull { it.source == source } ?: return@withLock
                start(listOf(selected) + current.offers.filter { it.source != source })
            }
        }
    }

    fun switchDownloadSource() {
        viewModelScope.launch {
            downloadMutex.withLock {
                val download = currentDownload ?: return@withLock
                if (state.value.downloadPhase == DownloadPhase.DOWNLOADING && download.offers.size > 1) {
                    start(download.offers.drop(1), replacing = download)
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
                failDownload(download, "无法读取下载状态，请重试", allowFallback = false)
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
                        failDownload(download, error.message ?: "安装包校验失败", allowFallback = true)
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val noSpace = progress.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                    val reason = if (noSpace) "设备空间不足，请清理后重试" else "${download.offer.source.label} 下载失败，请重试"
                    failDownload(download, reason, allowFallback = !noSpace)
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
            failDownload(download, error.message ?: "安装包校验失败", allowFallback = false)
            null
        }
    }

    private suspend fun start(offers: List<UpdateOffer>, replacing: UpdateDownload? = null) {
        showDownload(DownloadPhase.STARTING)
        try {
            val next = withContext(Dispatchers.IO) { downloads.start(offers) }
            currentDownload = next
            showDownload(DownloadPhase.DOWNLOADING)
            if (replacing != null) withContext(Dispatchers.IO) { downloads.removeFiles(replacing) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val phase = if (currentDownload == null) DownloadPhase.IDLE else DownloadPhase.DOWNLOADING
            showDownload(phase, "无法开始下载，请重试")
        }
    }

    private suspend fun failDownload(download: UpdateDownload, reason: String, allowFallback: Boolean) {
        withContext(Dispatchers.IO) { downloads.clear(download) }
        currentDownload = null
        showDownload(DownloadPhase.IDLE, reason)
        if (allowFallback && download.offers.size > 1) start(download.offers.drop(1))
    }

    private fun showDownload(phase: DownloadPhase, notice: String? = null) {
        val download = currentDownload
        mutableState.update {
            it.copy(downloadPhase = phase, download = download?.offer, receivedBytes = 0, paused = false,
                canSwitchSource = (download?.offers?.size ?: 0) > 1, notice = notice)
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
