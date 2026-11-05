package com.forest.android.assistant.core.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*

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
        DetailPage(preview.title, if (preview.completeScript) "完整示例" else "关键用法 · ${preview.category}", { selected = null },
            footer = {
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = null }) { Text("返回") }
                    TextButton(onClick = { onInsert(preview.code, preview.completeScript) }) { Text("光标插入") }
                    Button(onClick = { onCopy(preview.title, preview.code) }, shape = RoundedCornerShape(11.dp)) { Text("复制代码") }
                }
            }) {
            Text(preview.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = Color(AppPalette.INVERSE_SURFACE), shape = RoundedCornerShape(13.dp)) {
                SelectionContainer {
                    Text(remember(preview.code) { JsHighlighter.highlight(preview.code) }, color = Color(AppPalette.INVERSE_ON_SURFACE),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 21.sp, softWrap = false,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp))
                }
            }
            Text("实际运行前请根据任务修改素材名和坐标。", Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.TERTIARY))
        }
        return
    }
    DetailPage("示例脚本", "从关键用法开始，再看完整示例", onDismiss) {
        Text("每个片段包含必要的截图、判断和释放步骤；复制后修改素材名与坐标。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExampleFilterChip("关键用法", !complete) { complete = false }
            ExampleFilterChip("完整示例", complete) { complete = true }
        }
        if (!complete) {
            SectionLabel("分类")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { name -> ExampleFilterChip(name, category == name) { category = name } }
            }
        }
        SectionLabel(if (complete) "完整示例" else "示例片段")
        WorkCard(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, choice ->
                DesignRow(choice.title, choice.category + " · " + choice.description, R.drawable.ic_code) { selected = choice }
                if (index < choices.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ExampleFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = Color(AppPalette.ON_PRIMARY_CONTAINER)),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
}
