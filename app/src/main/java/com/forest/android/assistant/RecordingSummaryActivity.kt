package com.forest.android.assistant

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.forest.android.assistant.core.recording.RecordingReview
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 录制已停并完成快照后进入摘要；改名和放弃只作用于这一次待确认录制。 */
class RecordingSummaryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[RecordingSummaryModel::class.java]
        model.load(intent.getStringExtra("task_name").orEmpty())
        setContent {
            ScriptTheme {
                var discard by remember { mutableStateOf(false) }
                BackHandler { if (!model.busy) discard = true }
                LaunchedEffect(model.finished) {
                    if (model.finished) {
                        model.savedName?.let { name ->
                            startActivity(Intent(this@RecordingSummaryActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(MainActivity.EXTRA_SHOW_TASKS, true).putExtra("open_task", name))
                        }
                        finish()
                    }
                }
                val summary = model.summary
                DetailPage("完成录制", "保存为可重复执行的任务", onBack = { if (!model.busy) discard = true }, footer = {
                    PrimaryAction(if (model.busy) "正在处理…" else "保存为任务", summary != null && !model.busy && model.nameError == null,
                        R.drawable.ic_check) { model.save() }
                }) {
                    EmptyState("这段操作，已经记下了", "保存为任务，之后就可以自动重复执行。", R.drawable.ic_record)
                    if (summary != null) {
                        MetricStrip(listOf("${summary.steps} 步" to "已记录操作", "%.1f s".format(summary.durationMs / 1000.0) to "录制时长", summary.mode to "录制方式"))
                        DesignField("任务名称", model.name, { model.name = it }, "最多 50 个字符。", error = model.nameError)
                        TextButton(enabled = !model.busy, onClick = { discard = true }) { Text("放弃这次录制") }
                    }
                    model.error?.let { InfoNotice(it, error = true) }
                }
                if (discard) ConfirmActionSheet("放弃这次录制？", "将删除本次录制的步骤、脚本和素材。", "放弃录制",
                    onDismiss = { discard = false }, danger = true, busy = model.busy,
                    onConfirm = { discard = false; model.discard() })
            }
        }
    }
}

/** 保存操作由 ViewModel 持有，旋转时不重复重命名或删除。 */
class RecordingSummaryModel(application: Application) : AndroidViewModel(application) {
    var summary by mutableStateOf<RecordingReview.Summary?>(null)
    var name by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var savedName: String? = null
    private var original = ""
    val nameError: String? get() = when {
        name.isBlank() -> "请输入任务名称"
        name.trim().length > 50 || name.trim().startsWith('.') || name.any { it == '/' || it == '\\' || it.isISOControl() } -> "请输入合法名称，最多 50 个字符"
        name.trim() != original && TaskManager.taskDir(getApplication(), name.trim()).exists() -> "同名任务已存在"
        else -> null
    }

    fun load(task: String) {
        if (original.isNotEmpty()) return
        original = task; name = task
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { RecordingReview.read(getApplication(), task) } }
                .onSuccess { summary = it }.onFailure { error = it.message ?: "录制摘要不可用" }
        }
    }

    fun save() = mutate {
        check(nameError == null) { nameError.orEmpty() }
        val target = name.trim()
        if (target != original) TaskManager.renameTask(getApplication(), original, target).getOrThrow()
        original = target
        RecordingReview.finish(getApplication(), target)
        savedName = target
    }

    fun discard() = mutate { RecordingReview.discard(getApplication(), original) }

    private fun mutate(action: () -> Unit) {
        if (busy || finished) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { action() }
                finished = true
            } catch (failure: Exception) { error = failure.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
}
