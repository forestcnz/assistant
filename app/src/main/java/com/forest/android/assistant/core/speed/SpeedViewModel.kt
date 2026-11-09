package com.forest.android.assistant.core.speed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpeedScreenState(val apps: List<SpeedApp> = emptyList(), val loading: Boolean = true,
    val selected: SpeedApp? = null, val factor: Float = 2f, val nativeEnabled: Boolean = true,
    val busy: Boolean = false, val message: String = "", val error: String = "", val status: SpeedStatus? = null)

class SpeedViewModel(application: Application) : AndroidViewModel(application) {
    val repository = SpeedRepository(application)
    private val mutable = MutableStateFlow(SpeedScreenState())
    val state = mutable.asStateFlow()
    init { refresh() }
    fun refresh() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.listApps() } }
                .onSuccess { apps -> mutable.update { old -> old.copy(apps = apps, loading = false,
                    selected = old.selected?.let { selected -> apps.firstOrNull { it.packageName == selected.packageName } }) } }
                .onFailure { error -> mutable.update { it.copy(loading = false, error = error.message.orEmpty()) } }
        }
    }
    fun select(app: SpeedApp?) {
        if (mutable.value.busy) return
        mutable.update { it.copy(selected = app, factor = app?.let { entry -> repository.initialFactor(entry.packageName) } ?: 2f,
            nativeEnabled = app?.let { entry -> repository.initialNative(entry.packageName) } ?: true,
            status = null, error = "", message = "") }
    }
    fun factor(value: Float) { mutable.update { it.copy(factor = value) }; saveSelection() }
    fun nativeEnabled(value: Boolean) { mutable.update { it.copy(nativeEnabled = value) }; saveSelection() }
    private fun saveSelection() { mutable.value.let { s -> s.selected?.let { repository.saveSelection(it.packageName, s.factor, s.nativeEnabled) } } }
    fun prepare() {
        val snapshot = mutable.value
        val app = snapshot.selected ?: return
        if (snapshot.busy) return
        mutable.update { it.copy(busy = true, error = "", message = "正在准备…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.prepare(app.packageName, snapshot.factor, snapshot.nativeEnabled) { progress ->
                        ensureActive(); mutable.update { it.copy(message = progress) }
                    }
                }
                refresh()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "生成失败", message = "") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun control(readOnly: Boolean = false) {
        val snapshot = mutable.value
        val app = snapshot.selected ?: return
        if (snapshot.busy) return
        mutable.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    if (readOnly) repository.status(app.packageName)
                    else repository.setSpeed(app.packageName, snapshot.factor, snapshot.nativeEnabled)
                }
                mutable.update { it.copy(status = status, factor = status.factor, nativeEnabled = status.nativeEnabled,
                    error = status.error, message = if (status.error.isEmpty()) {
                        if (readOnly) "已读取变速模块状态" else "已发送倍率设置"
                    } else "") }
            } catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "无法连接变速模块") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun install(original: Boolean) {
        val snapshot = mutable.value
        val app = snapshot.selected ?: return
        if (snapshot.busy) return
        mutable.update { it.copy(busy = true, error = "", message = "正在交给系统安装…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { SpeedInstaller.install(getApplication(), app.packageName, repository.artifacts(app.packageName, original)) }
            } catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "无法启动安装") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun error(message: String) { mutable.update { it.copy(error = message) } }
}
