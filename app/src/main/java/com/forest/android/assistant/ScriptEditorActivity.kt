package com.forest.android.assistant

import android.os.Bundle
import com.forest.android.assistant.ui.AppToast as Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.imePadding
import com.forest.android.assistant.ui.AppPalette
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.forest.android.assistant.core.editor.ApiDocs
import com.forest.android.assistant.core.editor.Edits
import com.forest.android.assistant.core.editor.JsHighlighter
import com.forest.android.assistant.core.editor.LineIndex
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.flow.FlowCompiler
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.AppIcon
import com.forest.android.assistant.ui.AppBackButton
import com.forest.android.assistant.ui.ConfirmActionSheet
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内置编辑器：编辑任务入口脚本 task.js，支持语法高亮、API 补全与保存。
 * 打开时可定位到上次运行的出错行（高亮 + 提示条）。
 * 对应设计 docs/README.md 第 4.4 节。
 */
/** 旋转屏幕保留未保存草稿，显式保存策略下不依赖后台落盘。 */
class ScriptEditorDraft : ViewModel() {
    var content by mutableStateOf(TextFieldValue(""))
    var dirty by mutableStateOf(false)
    var loaded = false
    var sourceWasFlow by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var contentVersion = 0L
    var requestSequence = 0L
    var latestRequest = 0L
    val saveMutex = Mutex()
    val savingVersions = mutableMapOf<Long, MutableList<Boolean>>()
}

class ScriptEditorActivity : ComponentActivity() {

    private val draft by lazy { ViewModelProvider(this)[ScriptEditorDraft::class.java] }
    private var content: TextFieldValue
        get() = draft.content
        set(value) { draft.content = value }
    private var dirty: Boolean
        get() = draft.dirty
        set(value) { draft.dirty = value }
    private var showClearDialog by mutableStateOf(false)
    private var requiresExplicitSave by mutableStateOf(false)
    private var contentLoaded by mutableStateOf(false)
    private var contentVersion: Long
        get() = draft.contentVersion
        set(value) { draft.contentVersion = value }
    private var saveRequestSequence: Long
        get() = draft.requestSequence
        set(value) { draft.requestSequence = value }
    private var latestQueuedRequest: Long
        get() = draft.latestRequest
        set(value) { draft.latestRequest = value }
    private val saveMutex get() = draft.saveMutex
    private val savingVersions get() = draft.savingVersions
    private var sourceWasFlow: Boolean
        get() = draft.sourceWasFlow
        set(value) { draft.sourceWasFlow = value }
    // 保存和离开分别确认，切到后台不能改写任务或删除流程源文件。
    private var showLeaveDialog by mutableStateOf(false)
    private var showConvertDialog by mutableStateOf(false)
    private var saving: Boolean
        get() = draft.saving
        set(value) { draft.saving = value }

    /** 需要定位/高亮的出错行（1 起）；0 表示无 */
    private var errorLine by mutableStateOf(0)

    /** 出错信息（显示在提示条上） */
    private var errorHint by mutableStateOf<String?>(null)

    private val taskName: String by lazy { intent.getStringExtra(EXTRA_TASK) ?: "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!draft.loaded) sourceWasFlow = FlowCompiler.isVisual(this, taskName)

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val source = try {
                    TaskManager.scriptFile(this@ScriptEditorActivity, taskName).readText()
                } catch (e: Exception) {
                    "// 读取失败: ${e.message}\n"
                }
                ApiDocs.writeDts(this@ScriptEditorActivity)
                source
            }

            // 出错行定位：优先用传入参数（菜单跳转），否则取运行器最近一次错误
            val extraLine = intent.getIntExtra(EXTRA_ERROR_LINE, 0)
            val extraMsg = intent.getStringExtra(EXTRA_ERROR_MSG)
            val last = ScriptRunner.lastError.value
            val matchedLast = last?.takeIf { it.taskName == taskName }
            val targetLine = if (intent.hasExtra(EXTRA_ERROR_LINE)) extraLine else (matchedLast?.line ?: 0)
            errorLine = targetLine
            errorHint = when {
                targetLine <= 0 -> null
                !extraMsg.isNullOrBlank() -> extraMsg
                else -> matchedLast?.message
            }
            val initialSelection = JsHighlighter.lineRange(text, targetLine)?.first ?: 0
            if (!draft.loaded) content = TextFieldValue(text, TextRange(initialSelection))
            draft.loaded = true
            contentLoaded = true
            // Keep the same first-visible state as the former synchronous load:
            // render the editor only after its initial file snapshot is ready.
            setContent {
                ScriptTheme {
                    EditorScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorScreen() {
        val codeScroll = rememberScrollState()
        val codeHorizontalScroll = rememberScrollState()
        BackHandler { finish() }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // 编辑器标题栏与内容卡片之间用轻阴影分层，操作项保持原位置。
                Surface(shadowElevation = 0.dp, color = MaterialTheme.colorScheme.background) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        navigationIcon = {
                            AppBackButton(onClick = { finish() }, modifier = Modifier.padding(start = 8.dp))
                        },
                        title = {
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("脚本编辑", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(taskName, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            TextButton(onClick = { save(showToast = true) }, enabled = contentLoaded && !saving) {
                                Text(if (saving) "保存中…" else "保存", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding).imePadding()
            ) {
                // 上次运行的出错行提示（可关闭；改动后会失效，故仅作定位参考）
                if (errorLine > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "上次运行第 $errorLine 行出错" +
                                    (errorHint?.lineSequence()?.firstOrNull()?.let { "：$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = {
                            JsHighlighter.lineRange(content.text, errorLine)?.let { range ->
                                content = content.copy(selection = TextRange(range.first))
                            }
                        }) { Text("定位") }
                        TextButton(onClick = {
                            errorLine = 0
                            errorHint = null
                        }) { Text("知道了") }
                    }
                }
                if (sourceWasFlow) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "当前为流程任务。修改并确认保存后，将转为普通脚本任务。",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // 代码卡片：顶部文件名与语言标签只做信息展示，编辑区域仍为原来的输入框。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .background(Color(AppPalette.INVERSE_SURFACE))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF182535)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary,
                            androidx.compose.foundation.shape.CircleShape))
                        Text("task.js", color = Color(AppPalette.INVERSE_ON_SURFACE), style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp))
                        Spacer(Modifier.weight(1f))
                        Text("JAVASCRIPT · UTF-8", style = MaterialTheme.typography.labelMedium,
                            color = Color(AppPalette.INVERSE_SUBTLE))
                    }
                    HorizontalDivider(color = Color(0xFF2A374A))
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                    // 行号与代码共用纵向滚动，横向滚动仅作用于代码，避免长行换行后错位。
                    val minimumCodeWidth = (maxWidth - 42.dp).coerceAtLeast(100.dp)
                    Row(Modifier.fillMaxSize().verticalScroll(codeScroll).padding(top = 12.dp, bottom = 28.dp)) {
                        Text((1..(content.text.count { it == '\n' } + 1)).joinToString("\n"),
                            modifier = Modifier.width(42.dp).padding(end = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp,
                            color = Color(AppPalette.INVERSE_SUBTLE))
                        Box(Modifier.weight(1f).horizontalScroll(codeHorizontalScroll)) {
                    BasicTextField(
                        value = content,
                        onValueChange = { new ->
                            // 自动缩进 / 括号配对（只影响单字符插入，粘贴原样放行）
                            val edited = Edits.apply(content, new)
                            if (edited.text != content.text) {
                                dirty = true
                                contentVersion++
                            }
                            content = edited
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                            color = Color(AppPalette.INVERSE_ON_SURFACE)
                        ),
                        cursorBrush = SolidColor(Color(AppPalette.INVERSE_ACCENT)),
                        visualTransformation = remember(errorLine) {
                            JsHighlighter.transformation(errorLine.takeIf { it > 0 })
                        },
                        enabled = contentLoaded,
                        modifier = Modifier.widthIn(min = minimumCodeWidth).padding(end = 12.dp)
                    )
                        }
                    }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF182535)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (dirty) "待保存" else "已保存",
                        style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.INVERSE_SUBTLE),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { showClearDialog = true }, enabled = contentLoaded && content.text.isNotEmpty()) {
                        Text("清空", color = Color(AppPalette.INVERSE_ACCENT))
                    }
                }
            }
        }
        if (showClearDialog) {
            AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
                onDismissRequest = { showClearDialog = false },
                title = { Text("清空脚本") },
                text = { Text("确定清空「$taskName」的编辑内容？点击保存后才会修改脚本文件。") },
                confirmButton = {
                    TextButton(onClick = {
                        content = TextFieldValue("", TextRange(0))
                        dirty = true
                        contentVersion++
                        requiresExplicitSave = true
                        errorLine = 0
                        errorHint = null
                        showClearDialog = false
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
            )
        }
        if (showLeaveDialog) ConfirmActionSheet("还有未保存的修改", "离开后，本次对脚本的修改将不会保存。",
            "放弃修改并离开", onDismiss = { showLeaveDialog = false }, danger = true, cancelLabel = "继续编辑",
            onConfirm = { showLeaveDialog = false; dirty = false; finish() })
        if (showConvertDialog) ConfirmActionSheet("转为普通脚本任务？",
            "确认保存后，此任务不能再使用可视化流程编辑。想继续编排流程，请在局域网 Web 编辑器中修改。",
            "确认转换并保存", onDismiss = { showConvertDialog = false },
            onConfirm = { showConvertDialog = false; save(showToast = true, allowConversion = true) })
    }

    /** 后台串行保存快照；旧请求不能覆盖更新的保存请求。 */
    private fun save(showToast: Boolean, allowConversion: Boolean = false) {
        if (!contentLoaded || saving) return
        // 未改动时保存不触发流程转换；只有用户明确确认的修改才落盘。
        if (!dirty) {
            if (showToast) Toast.makeText(this, "没有需要保存的修改", Toast.LENGTH_SHORT).show()
            return
        }
        if (sourceWasFlow && !allowConversion) { showConvertDialog = true; return }
        val text = content.text
        val version = contentVersion
        savingVersions[version]?.let { waiters ->
            waiters.add(showToast)
            return
        }
        savingVersions[version] = mutableListOf(showToast)
        val request = ++saveRequestSequence
        latestQueuedRequest = request
        saving = true
        draft.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Exception? = null
            var persisted = false
            try {
                withContext(NonCancellable) {
                    saveMutex.withLock {
                        if (request == latestQueuedRequest) {
                            withContext(Dispatchers.IO) {
                                TaskManager.scriptFile(applicationContext, taskName).writeText(text)
                                if (sourceWasFlow && allowConversion) FlowCompiler.convertToScript(applicationContext, taskName)
                            }
                            persisted = true
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            } finally {
                saving = false
                val shouldShowToast = savingVersions.remove(version)?.any { it } == true
                if (failure == null && persisted) {
                    sourceWasFlow = false
                    if (contentVersion == version && content.text == text) {
                        dirty = false
                        requiresExplicitSave = false
                    }
                    if (shouldShowToast && !isDestroyed) {
                        Toast.makeText(this@ScriptEditorActivity, "已保存", Toast.LENGTH_SHORT).show()
                    }
                } else if (failure != null && !isDestroyed) {
                    Toast.makeText(
                        this@ScriptEditorActivity,
                        "保存失败: ${failure.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onPause() {
        // 清空及其后续编辑必须由用户手动保存，离开或切后台时保留磁盘上的脚本。
        // 所有编辑现在都显式保存，后台切换不再绕过未保存和流程转换确认。
        super.onPause()
    }

    override fun finish() {
        if (saving) return
        if (dirty) showLeaveDialog = true else super.finish()
    }

    companion object {
        const val EXTRA_TASK = "task_name"

        /** 出错行（1 起，0/缺省表示不定位） */
        const val EXTRA_ERROR_LINE = "error_line"

        /** 出错信息（提示条展示） */
        const val EXTRA_ERROR_MSG = "error_msg"
    }
}
