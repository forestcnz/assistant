package com.forest.android.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forest.android.assistant.core.editor.ApiDocs
import com.forest.android.assistant.core.editor.Edits
import com.forest.android.assistant.core.editor.JsHighlighter
import com.forest.android.assistant.core.editor.LineIndex
import com.forest.android.assistant.core.editor.ScriptExamplesDialog
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.AppIcon
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
class ScriptEditorActivity : ComponentActivity() {

    private var content by mutableStateOf(TextFieldValue(""))
    private var dirty by mutableStateOf(false)
    private var showClearDialog by mutableStateOf(false)
    private var requiresExplicitSave by mutableStateOf(false)
    private var contentLoaded by mutableStateOf(false)
    private var contentVersion = 0L
    private var saveRequestSequence = 0L
    private var latestQueuedRequest = 0L
    private val saveMutex = Mutex()
    private val savingVersions = mutableMapOf<Long, MutableList<Boolean>>()

    /** 需要定位/高亮的出错行（1 起）；0 表示无 */
    private var errorLine by mutableStateOf(0)

    /** 出错信息（显示在提示条上） */
    private var errorHint by mutableStateOf<String?>(null)

    private val taskName: String by lazy { intent.getStringExtra(EXTRA_TASK) ?: "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            val targetLine = if (extraLine > 0) extraLine else (matchedLast?.line ?: 0)
            errorLine = targetLine
            errorHint = when {
                targetLine <= 0 -> null
                !extraMsg.isNullOrBlank() -> extraMsg
                else -> matchedLast?.message
            }
            val initialSelection = JsHighlighter.lineRange(text, targetLine)?.first ?: 0
            content = TextFieldValue(text, TextRange(initialSelection))
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
        var apiHelp by remember { mutableStateOf<ApiDocs.Item?>(null) }
        var showExamples by remember { mutableStateOf(false) }
        var pendingReplacement by remember { mutableStateOf<Pair<String, String>?>(null) }
        val keyboard = LocalSoftwareKeyboardController.current
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            AppIcon(R.drawable.ic_back, if (requiresExplicitSave) "返回" else "返回并自动保存")
                        }
                    },
                    title = {
                        Column {
                            Text("脚本编辑", style = MaterialTheme.typography.titleMedium)
                            Text(taskName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            keyboard?.hide()
                            showExamples = true
                        }, enabled = contentLoaded) { Text("示例") }
                        TextButton(onClick = { showClearDialog = true }, enabled = contentLoaded && content.text.isNotEmpty()) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { save(showToast = true) }, enabled = contentLoaded) { Text("保存") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
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
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            errorLine = 0
                            errorHint = null
                        }) { Text("知道了") }
                    }
                }

                SuggestionBar(
                    prefix = JsHighlighter.wordBefore(content.text, content.selection.start)?.first,
                    onPick = { snippet -> insertAtCursor(snippet) },
                    onInspect = { apiHelp = it }
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                        .padding(12.dp)
                ) {
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
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = remember(errorLine) {
                            JsHighlighter.transformation(errorLine.takeIf { it > 0 })
                        },
                        enabled = contentLoaded,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val lineIndex = remember(content.text) { LineIndex.from(content.text) }
                    val position = remember(lineIndex, content.selection.start) {
                        lineIndex.position(content.selection.start.coerceIn(0, content.text.length))
                    }
                    Text("Ln ${position.first}, Col ${position.second}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text(when {
                        !dirty -> "已保存"
                        requiresExplicitSave -> "待保存 · 请点击保存"
                        else -> "待保存 · 离开时自动保存"
                    },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (showExamples) {
            ScriptExamplesDialog(
                onDismiss = { showExamples = false },
                onCopy = { title, code ->
                    if (copyExample(title, code)) showExamples = false
                },
                onInsert = { code, completeScript ->
                    insertAtCursor(code, completeWord = false, block = completeScript)
                    showExamples = false
                },
                onReplace = { title, code ->
                    showExamples = false
                    if (content.text.isBlank()) replaceWithExample(code)
                    else pendingReplacement = title to code
                }
            )
        }
        pendingReplacement?.let { (title, code) ->
            AlertDialog(
                onDismissRequest = { pendingReplacement = null },
                title = { Text("替换当前脚本？") },
                text = { Text("将当前编辑内容替换为「$title」。点击顶部“保存”后才会写入脚本文件；返回或切到后台不会保存本次替换。") },
                confirmButton = {
                    TextButton(onClick = {
                        replaceWithExample(code)
                        pendingReplacement = null
                    }) { Text("确认替换") }
                },
                dismissButton = { TextButton(onClick = { pendingReplacement = null }) { Text("取消") } }
            )
        }
        apiHelp?.let { item ->
            AlertDialog(
                onDismissRequest = { apiHelp = null },
                title = { Text(item.name) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(item.signature, fontFamily = FontFamily.Monospace)
                        Text(item.doc)
                        Text(item.snippet, fontFamily = FontFamily.Monospace)
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            insertAtCursor(item.snippet)
                            apiHelp = null
                        }) { Text("插入示例") }
                        TextButton(onClick = {
                            if (copyExample(item.name, item.snippet)) apiHelp = null
                        }) { Text("复制") }
                    }
                },
                dismissButton = { TextButton(onClick = { apiHelp = null }) { Text("关闭") } }
            )
        }
        if (showClearDialog) {
            AlertDialog(
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
    }

    /** 复制不修改编辑内容、光标或保存状态，交由用户选择粘贴位置。 */
    private fun copyExample(title: String, code: String): Boolean {
        return try {
            val clipboard = getSystemService(ClipboardManager::class.java)
                ?: error("系统剪贴板不可用")
            clipboard.setPrimaryClip(ClipData.newPlainText(title, code))
            Toast.makeText(this, "已复制代码，可长按编辑器粘贴", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(this, "复制失败，请重试", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** API 补全条：仅在输入了标识符前缀时出现，避免误触把片段插入脚本 */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SuggestionBar(prefix: String?, onPick: (String) -> Unit, onInspect: (ApiDocs.Item) -> Unit) {
        if (prefix.isNullOrBlank()) return
        val items = remember(prefix) { ApiDocs.suggestions(prefix, limit = 8) }
        if (items.isEmpty()) return
        Text(
            "API 补全：点击插入 · 长按查看用法",
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                Text(
                    item.name.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onPick(item.snippet) },
                            onLongClickLabel = "查看用法",
                            onLongClick = { onInspect(item) }
                        )
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }

    /** 补全替换前缀；示例按普通插入处理选区，完整脚本两侧补换行。 */
    private fun insertAtCursor(snippet: String, completeWord: Boolean = true, block: Boolean = false) {
        val text = content.text
        val cursor = content.selection.min.coerceIn(0, text.length)
        val end = content.selection.max.coerceIn(cursor, text.length)
        val word = if (completeWord && content.selection.collapsed) JsHighlighter.wordBefore(text, cursor) else null
        val start = word?.second ?: cursor
        val insertion = if (block) {
            (if (start > 0 && text[start - 1] != '\n') "\n" else "") + snippet.trimEnd() + "\n"
        } else snippet
        val newText = text.substring(0, start) + insertion + text.substring(end)
        val newCursor = start + insertion.length
        content = TextFieldValue(newText, TextRange(newCursor))
        dirty = true
        contentVersion++
    }

    private fun replaceWithExample(code: String) {
        content = TextFieldValue(code.trimEnd() + "\n", TextRange(0))
        dirty = true
        contentVersion++
        requiresExplicitSave = true
        errorLine = 0
        errorHint = null
    }

    /** 后台串行保存快照；旧请求不能覆盖更新的保存请求。 */
    private fun save(showToast: Boolean) {
        if (!contentLoaded) return
        val text = content.text
        val version = contentVersion
        savingVersions[version]?.let { waiters ->
            waiters.add(showToast)
            return
        }
        savingVersions[version] = mutableListOf(showToast)
        val request = ++saveRequestSequence
        latestQueuedRequest = request
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Exception? = null
            var persisted = false
            try {
                withContext(NonCancellable) {
                    saveMutex.withLock {
                        if (request == latestQueuedRequest) {
                            withContext(Dispatchers.IO) {
                                TaskManager.scriptFile(applicationContext, taskName).writeText(text)
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
                val shouldShowToast = savingVersions.remove(version)?.any { it } == true
                if (failure == null && persisted) {
                    if (contentVersion == version) {
                        dirty = false
                        requiresExplicitSave = false
                    }
                    if (shouldShowToast && !isDestroyed) {
                        Toast.makeText(this@ScriptEditorActivity, "已保存", Toast.LENGTH_SHORT).show()
                    }
                } else if (failure != null && !isDestroyed) {
                    Toast.makeText(
                        this@ScriptEditorActivity,
                        "保存失败: ${failure?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onPause() {
        // 清空及其后续编辑必须由用户手动保存，离开或切后台时保留磁盘上的脚本。
        if (contentLoaded && dirty && !requiresExplicitSave) save(showToast = false)
        super.onPause()
    }

    companion object {
        const val EXTRA_TASK = "task_name"

        /** 出错行（1 起，0/缺省表示不定位） */
        const val EXTRA_ERROR_LINE = "error_line"

        /** 出错信息（提示条展示） */
        const val EXTRA_ERROR_MSG = "error_msg"
    }
}
