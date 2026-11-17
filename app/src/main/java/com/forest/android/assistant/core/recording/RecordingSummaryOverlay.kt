package com.forest.android.assistant.core.recording

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.forest.android.assistant.R
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.AppIcon
import com.forest.android.assistant.ui.AppPalette
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import com.forest.android.assistant.ui.DesignField
import com.forest.android.assistant.ui.InfoNotice
import com.forest.android.assistant.ui.MetricStrip
import com.forest.android.assistant.ui.PrimaryAction
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.SecondaryAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 录制完成后的确认弹窗：以系统悬浮层显示在当前应用之上，
 * 不再跳转 App 内的摘要页，避免打断正在操作的目标应用。
 */
internal object RecordingSummaryOverlay {
    private var dialog: Dialog? = null
    private var scope: CoroutineScope? = null

    /** @return 是否成功显示弹窗；失败时由调用方回退到应用内摘要页。 */
    fun show(context: Context, taskName: String): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return false
        hide()

        val lifetime = OverlayLifetime()
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = job
        val state = SummaryState(app, taskName, job) { hide() }
        val content = ComposeView(app).apply {
            setViewTreeLifecycleOwner(lifetime)
            setViewTreeSavedStateRegistryOwner(lifetime)
            setContent { ScriptTheme { SummaryCard(state) } }
        }

        val themed = ContextThemeWrapper(app, android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        val summaryDialog = Dialog(themed).apply {
            setContentView(content)
            // Dialog 默认把内容拉成整屏高；这里改为按内容高度收缩，保持弹窗形态。
            // 父容器是 FrameLayout，必须使用 MarginLayoutParams 子类，否则测量时会类型转换崩溃。
            content.layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 必须由用户明确保存或放弃，点击空白处不关闭。
            setCanceledOnTouchOutside(false)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (!state.busy && !state.loading) state.confirmingDiscard = true
                    true
                } else false
            }
        }
        summaryDialog.window?.apply {
            // minSdk 29：悬浮窗类型固定为 TYPE_APPLICATION_OVERLAY。
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setGravity(Gravity.CENTER)
        }
        summaryDialog.setOnDismissListener {
            lifetime.destroy()
            job.cancel()
            if (dialog === summaryDialog) {
                dialog = null
                scope = null
            }
        }
        dialog = summaryDialog
        lifetime.start()

        return try {
            summaryDialog.show()
            val width = (app.resources.displayMetrics.widthPixels - dp(app, 40)).coerceAtLeast(dp(app, 280))
            summaryDialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            true
        } catch (_: Exception) {
            hide()
            false
        }
    }

    fun hide() {
        val current = dialog ?: return
        dialog = null
        scope?.cancel()
        scope = null
        runCatching { current.dismiss() }
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}

/** 弹窗需要独立生命周期，Compose 才会在悬浮窗口内正常重组。 */
private class OverlayLifetime : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

/** 摘要弹窗的状态：加载、改名、保存与放弃都在同一个弹窗里完成。 */
private class SummaryState(
    private val app: Context,
    private val original: String,
    private val scope: CoroutineScope,
    private val onDone: () -> Unit
) {
    var summary by mutableStateOf<RecordingReview.Summary?>(null)
    var name by mutableStateOf(original)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var confirmingDiscard by mutableStateOf(false)

    val nameError: String?
        get() {
            val trimmed = name.trim()
            return when {
                trimmed.isBlank() -> "请输入任务名称"
                trimmed.length > 50 || trimmed.startsWith('.') || name.any { it == '/' || it == '\\' || it.isISOControl() } ->
                    "请输入合法名称，最多 50 个字符"
                trimmed != original && TaskManager.taskDir(app, trimmed).exists() -> "同名任务已存在"
                else -> null
            }
        }

    init {
        scope.launch {
            try {
                summary = withContext(Dispatchers.IO) { RecordingReview.read(app, original) }
            } catch (e: Exception) {
                error = e.message ?: "录制摘要不可用"
            } finally {
                loading = false
            }
        }
    }

    fun save() {
        if (busy || loading || nameError != null) return
        busy = true
        error = null
        scope.launch {
            try {
                val target = name.trim()
                withContext(Dispatchers.IO) {
                    if (target != original) TaskManager.renameTask(app, original, target).getOrThrow()
                    RecordingReview.finish(app, target)
                }
                ScriptRunner.appendLog("录制已保存：$target")
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    fun discard() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { RecordingReview.discard(app, original) }
                ScriptRunner.appendLog("已放弃本次录制：$original")
                onDone()
            } catch (e: Exception) {
                confirmingDiscard = false
                error = e.message ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }
}

@Composable
private fun SummaryCard(state: SummaryState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxCardHeight = maxHeight * 0.88f
        // 加载态、摘要态和放弃确认共用同一窗口高度，避免摘要加载后触发窗口二次测量、带动背景跳动。
        val cardHeight = minOf(340.dp, maxCardHeight)
        Surface(
            modifier = Modifier.fillMaxWidth().height(cardHeight),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                if (state.confirmingDiscard) DiscardConfirm(state) else SummaryBody(state)
            }
        }
    }
}

@Composable
private fun SummaryBody(state: SummaryState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(R.drawable.ic_record, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("完成录制", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                "保存为可重复执行的任务",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }

    when {
        state.loading -> Row(
            Modifier.fillMaxWidth().padding(vertical = 26.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                "正在读取录制摘要…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        state.summary != null -> {
            val summary = state.summary!!
            MetricStrip(listOf(
                "${summary.steps} 步" to "已记录操作",
                "%.1f s".format(summary.durationMs / 1000.0) to "录制时长",
                summary.mode to "录制方式"
            ))
            DesignField(
                "任务名称", state.name, { state.name = it },
                "最多 50 个字符，保存后可继续修改。", error = state.nameError
            )
        }
    }

    state.error?.let { InfoNotice(it, error = true) }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            SecondaryAction("放弃这次录制", enabled = !state.busy && !state.loading) {
                state.confirmingDiscard = true
            }
        }
        Box(Modifier.weight(1f)) {
            PrimaryAction(
                if (state.busy) "正在处理…" else "保存为任务",
                enabled = state.summary != null && !state.busy && !state.loading && state.nameError == null,
                icon = R.drawable.ic_check
            ) { state.save() }
        }
    }
}

@Composable
private fun DiscardConfirm(state: SummaryState) {
    Text("放弃这次录制？", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "将删除本次录制的步骤、脚本和素材。",
        fontSize = 11.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )
    state.error?.let { InfoNotice(it, error = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            TextButton(
                enabled = !state.busy,
                onClick = { state.confirmingDiscard = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text("取消", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.weight(1f)) {
            Button(
                onClick = { state.discard() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ComposeColor(AppPalette.ERROR_CONTAINER),
                    contentColor = ComposeColor(AppPalette.ERROR)
                )
            ) {
                Text(if (state.busy) "正在处理…" else "放弃录制", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
