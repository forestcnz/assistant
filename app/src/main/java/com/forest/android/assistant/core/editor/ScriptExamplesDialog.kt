package com.forest.android.assistant.core.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScriptExamplesDialog(
    onDismiss: () -> Unit,
    onCopy: (title: String, code: String) -> Unit,
    onInsert: (code: String, completeScript: Boolean) -> Unit
) {
    var complete by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("全部") }
    var selected by remember { mutableStateOf<ScriptExamples.Item?>(null) }
    val categories = remember { listOf("全部") + ScriptExamples.items.map { it.category }.distinct() }
    val choices = remember(complete, category) {
        if (complete) listOf(ScriptExamples.completeExample)
        else ScriptExamples.items.filter { category == "全部" || it.category == category }
    }

    val preview = selected
    if (preview != null) {
        // 预览布局：顶部说明与底部按钮区固定，中间代码区按内容自适应高度，
        // 超出部分在代码区内单独滚动（同时支持横向滚动）
        val scroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(preview.title) },
            text = {
                Column {
                    Text(preview.description)
                    // 代码区沿用编辑器的 JS 语法高亮，保持与编辑器一致的着色和行高
                    SelectionContainer {
                        Text(remember(preview.code) { JsHighlighter.highlight(preview.code) },
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(scroll)
                                .horizontalScroll(rememberScrollState()))
                    }
                }
            },
            confirmButton = {
                // 三个按钮放在同一行：返回 / 光标插入 / 复制代码
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = null }) { Text("返回") }
                    TextButton(onClick = { onInsert(preview.code, preview.completeScript) }) { Text("光标插入") }
                    Button(onClick = { onCopy(preview.title, preview.code) }) { Text("复制代码") }
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("示例脚本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (complete) "一个完整示例，展示全部公开 API。" else "每项都是可直接运行的完整片段，含截图、判断与释放；复制后按注释修改素材名和坐标。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExampleFilterChip("关键用法", !complete) { complete = false }
                    ExampleFilterChip("完整示例", complete) { complete = true }
                }
                if (!complete) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { name ->
                            ExampleFilterChip(name, category == name) { category = name }
                        }
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choices, key = { it.title }) { choice ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "查看代码") { selected = choice },
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(choice.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    choice.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ExampleFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    )
}
