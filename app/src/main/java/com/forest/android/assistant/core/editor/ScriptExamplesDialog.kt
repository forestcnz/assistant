package com.forest.android.assistant.core.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ExamplePreview(
    val title: String,
    val description: String,
    val code: String,
    val completeScript: Boolean
)

@Composable
fun ScriptExamplesDialog(
    onDismiss: () -> Unit,
    onInsert: (code: String, completeScript: Boolean) -> Unit,
    onReplace: (title: String, code: String) -> Unit
) {
    var snippets by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ExamplePreview?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val choices = remember(snippets, query) {
        val all = if (snippets) ApiDocs.items.map {
            ExamplePreview(it.name, it.signature + "\n" + it.doc, it.snippet, false)
        } else ScriptExamples.items.map {
            ExamplePreview(it.title, it.description, it.code, true)
        }
        val search = query.trim()
        all.filter { search.isEmpty() || it.title.contains(search, true) || it.description.contains(search, true) }
    }

    val preview = selected
    if (preview != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(preview.title) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(preview.description)
                    Text(
                        if (preview.completeScript) "可插入光标处，或替换全文后手动保存。"
                        else "将片段插入光标处；选中文字时替换选中部分。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    SelectionContainer {
                        Text(preview.code, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { onInsert(preview.code, preview.completeScript) }) { Text("光标插入") }
                    if (preview.completeScript) {
                        TextButton(onClick = { onReplace(preview.title, preview.code) }) { Text("替换全文") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("返回") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("示例脚本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择示例，预览后写入编辑器。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !snippets, onClick = { snippets = false }, label = { Text("完整脚本") })
                    FilterChip(selected = snippets, onClick = { snippets = true }, label = { Text("API 片段") })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索名称或功能") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (choices.isEmpty()) {
                    Text("没有匹配的示例，请换个关键词。", modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(choices, key = { it.title }) { choice ->
                            TextButton(
                                onClick = { keyboard?.hide(); selected = choice },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(choice.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        choice.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
