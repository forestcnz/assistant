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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScriptExamplesDialog(
    onDismiss: () -> Unit,
    onCopy: (title: String, code: String) -> Unit,
    onInsert: (code: String, completeScript: Boolean) -> Unit,
    onReplace: (title: String, code: String) -> Unit
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
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(preview.title) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(preview.description)
                    SelectionContainer {
                        Text(preview.code, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onInsert(preview.code, preview.completeScript) }) { Text("光标插入") }
                        if (preview.completeScript) {
                            TextButton(onClick = { onReplace(preview.title, preview.code) }) { Text("替换全文") }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onCopy(preview.title, preview.code) }) { Text("复制代码") }
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
                Text(if (complete) "一个完整示例，展示全部公开 API。" else "每项 1～2 行，按需组合；点开后复制。",
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
