package com.forest.android.assistant.core.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
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
    onInsert: (code: String, completeScript: Boolean) -> Unit,
    onPermissions: () -> Unit
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
        ApiExamplePage(preview.title, if (preview.completeScript) "完整示例" else preview.category,
            preview.description, preview.code, { selected = null }, { onCopy(preview.title, preview.code) },
            { onInsert(preview.code, preview.completeScript) }, onPermissions)
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
        // 默认按用途分区，筛选后仍保留分区标题，帮助查找 API。
        choices.groupBy { if (complete) "完整示例" else it.category }.forEach { (section, examples) ->
            SectionLabel(section)
            WorkCard(Modifier.fillMaxWidth()) {
                examples.forEachIndexed { index, choice ->
                    DesignRow(choice.title, choice.description, R.drawable.ic_code) { selected = choice }
                    if (index < examples.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** 示例与补全 API 说明共用摘要、代码预览、权限入口及固定双操作。 */
@Composable
fun ApiExamplePage(title: String, category: String, description: String, code: String, onBack: () -> Unit,
    onCopy: () -> Unit, onInsert: () -> Unit, onPermissions: () -> Unit) {
    DetailPage(title, "API 示例", onBack, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { SecondaryAction("复制代码", onClick = onCopy) }
            Box(Modifier.weight(1f)) { PrimaryAction("插入示例", icon = R.drawable.ic_add, onClick = onInsert) }
        }
    }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.ic_code)
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(category, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
            }
        }
        ContentBlock("如何使用") { Text(description, fontSize = 12.sp, lineHeight = 21.sp) }
        Surface(color = Color(AppPalette.INVERSE_SURFACE), shape = RoundedCornerShape(13.dp)) {
            SelectionContainer {
                Text(remember(code) { JsHighlighter.highlight(code) }, color = Color(AppPalette.INVERSE_ON_SURFACE),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 21.sp, softWrap = false,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp))
            }
        }
        InfoNotice("实际运行前请根据任务修改素材名和坐标，并检查所需权限。")
        TextButton(onClick = onPermissions) { Text("检查运行权限") }
    }
}

@Composable
private fun ExampleFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = Color(AppPalette.ON_SURFACE_VARIANT),
            selectedContainerColor = Color(AppPalette.INVERSE_SURFACE),
            selectedLabelColor = Color.White),
        border = BorderStroke(1.dp, if (selected) Color(AppPalette.INVERSE_SURFACE) else MaterialTheme.colorScheme.outlineVariant))
}
