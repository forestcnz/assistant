package com.forest.android.assistant.ui.main

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import com.forest.android.assistant.MainActivity
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.recording.RecordingService
import com.forest.android.assistant.core.task.ImageHistory
import com.forest.android.assistant.core.task.TaskManager

/** 任务素材相关页面：素材列表、历史素材、预览与重命名。 */

/** 素材管理：列出任务 images/ 下的图片，支持预览、裁剪与整理。 */
@Composable
internal fun MainActivity.AssetsDialog(
    taskName: String, images: List<TaskManager.ImageInfo>, onDismiss: () -> Unit,
    onRefresh: () -> Unit, onImport: () -> Unit, onHistory: () -> Unit,
    onPreview: (TaskManager.ImageInfo) -> Unit
) {
    DetailPage("素材管理", taskName, onDismiss, actions = {
        // 刷新入口固定在顶部，长素材列表无需滚动到底部。
        TextButton(onClick = onRefresh) { Text("刷新") }
        TextButton(onClick = onImport) { Text("导入") }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusBadge("${images.size} 张任务图片")
            Text("历史素材", fontSize = 11.sp, color = Color(AppPalette.PRIMARY),
                modifier = Modifier.clickable { onHistory() }.padding(vertical = 6.dp))
        }
        if (images.isEmpty()) EmptyState("还没有任务图片", "导入按钮截图，或从屏幕中截取模板。", R.drawable.ic_tool_shot, "截取第一张模板") { captureTemplate(taskName) }
        else {
            // 设计稿使用两列缩略图网格，图片信息放在卡片底部。
            images.chunked(2).forEach { rowImages ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowImages.forEach { info ->
                        Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.clickable { onPreview(info) }) {
                                Box(Modifier.fillMaxWidth().height(116.dp)
                                    .checkerboardBackground().padding(10.dp),
                                    contentAlignment = Alignment.Center) {
                                    AssetThumbnail(info.file, info.file.lastModified(), info.file.length(),
                                        Modifier.fillMaxSize())
                                }
                                Column(Modifier.padding(12.dp)) {
                                    Text(info.file.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(info.dimensionText() + " · " + info.sizeText(), fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    if (rowImages.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        SectionLabel("素材工具")
        WorkCard(Modifier.fillMaxWidth()) {
            // 按要求移除素材工具中的“截取模板”入口，仅保留历史素材。
            DesignRow("历史素材", "查看裁剪前保留的原图", R.drawable.ic_history, compact = true, onClick = onHistory)
        }
        Text("脚本可使用 images/图片名称.png 引用素材。",
            Modifier.fillMaxWidth().background(Color(AppPalette.PRIMARY_CONTAINER), RoundedCornerShape(12.dp)).padding(14.dp),
            fontSize = 10.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 历史素材仅提供移回操作，不提供预览、裁剪、重命名或删除入口。 */
@Composable
internal fun MainActivity.AssetsHistoryDialog(entries: List<ImageHistory.Entry>, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onMoveBack: (ImageHistory.Entry) -> Unit) {
    DetailPage("历史素材", "裁剪前保留的原始图片", { if (!busy) onDismiss() }) {
        InfoNotice("移回后恢复原文件名，并覆盖当前同名素材，移回前会再次确认。", warning = true)
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (entries.isEmpty() && !busy && error == null) EmptyState("暂无历史素材", "裁剪前的原图会保存在这里。", R.drawable.ic_clock)
        if (entries.isNotEmpty()) {
            // 缩略图只用于识别历史版本，不额外提供会修改原图的操作。
            entries.groupBy { it.archivedAt.take(10) }.forEach { (date, versions) ->
                SectionLabel(date)
                versions.forEach { entry ->
                    SectionCard {
                        Box(Modifier.fillMaxWidth().height(124.dp).checkerboardBackground().padding(12.dp)) {
                            AssetThumbnail(entry.file, entry.file.lastModified(), entry.file.length(), Modifier.fillMaxSize())
                        }
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.originalName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(entry.archivedAt, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            TextButton(onClick = { onMoveBack(entry) }, enabled = !busy) { Text("移回素材") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.AssetPreviewDialog(info: TaskManager.ImageInfo, bitmap: android.graphics.Bitmap?,
    onCrop: () -> Unit, onDismiss: () -> Unit) {
    // 图片详情使用紧凑间距，重点压缩文件信息与图片操作列表的纵向留白。
    DetailPage("图片详情", assetsTask ?: info.file.name, onDismiss, compact = true) {
        Surface(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 220.dp), color = Color(AppPalette.SURFACE_CONTAINER),
            shape = RoundedCornerShape(20.dp)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = info.file.name,
                modifier = Modifier.fillMaxSize().checkerboardBackground().padding(12.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无法解码该图片，请检查文件是否有效。", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionLabel("文件信息", compact = true)
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                KeyValueRow("文件名", info.file.name)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                KeyValueRow("尺寸", info.dimensionText())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                KeyValueRow("格式 / 大小", info.file.extension.uppercase() + " / " + info.sizeText())
            }
        }
        SectionLabel("图片操作", compact = true)
        val busy = ScriptRunner.state.collectAsState().value.running || RecordingService.active
        WorkCard(Modifier.fillMaxWidth()) {
            DesignRow("裁剪图片", "原图将保存到历史素材", R.drawable.ic_tool_region,
                enabled = bitmap != null && !busy, compact = true, onClick = onCrop)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("重命名图片", "预览并同步修改脚本引用", R.drawable.ic_code, enabled = !busy, compact = true) { renameAsset = info }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("删除图片", "移除当前图片", R.drawable.ic_stop, enabled = !busy, compact = true) { deletingAsset = info }
        }
    }
}

/** 素材重命名对话框 */
@Composable
internal fun MainActivity.RenameAssetDialog(
    info: TaskManager.ImageInfo,
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val valid = name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }
    DetailPage("重命名素材", info.file.name, onDismiss, footer = {
        PrimaryAction("查看引用变更", valid && name != info.file.name, R.drawable.ic_arrow) { onConfirm(name.trim()) }
    }) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(17.dp)).checkerboardBackground().padding(14.dp)) {
            AssetThumbnail(info.file, info.file.lastModified(), info.file.length(), Modifier.fillMaxSize())
        }
        DesignField("新文件名", name, onNameChange, "保留原扩展名，名称不能包含路径分隔符。",
            error = if (name.isNotEmpty() && !valid) "请输入合法的文件名" else null)
        InfoNotice("下一步会展示受影响的引用，确认后一起更新脚本；流程任务还会同步更新流程和草稿引用。")
    }
}

/**
 * 素材重命名的脚本引用预览：只列出 task.js 中需要修改的行（旧 → 新）。
 * 无引用时仅提示「不会修改脚本」，确认后直接改名。
 */
@Composable
internal fun MainActivity.RenameRefPreviewDialog(
    preview: TaskManager.ImageRenamePreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val plan = preview.plan?.takeIf { it.hasChanges }
    DetailPage("确认引用变更", "重命名素材 · 第 2 步", onDismiss, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { SecondaryAction("返回修改", onClick = onDismiss) }
            Box(Modifier.weight(1f)) { PrimaryAction("确认重命名", onClick = onConfirm) }
        }
    }) {
        InfoNotice(if (plan == null) "task.js 中没有引用「${preview.file.name}」，确认后不会修改脚本。"
            else "将更新 ${plan.replacementCount} 处脚本引用，涉及 ${plan.changedLineCount} 行。流程和草稿中的相应引用也会一起更新。")
        SectionLabel("task.js")
        Surface(color = Color(AppPalette.INVERSE_SURFACE), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (plan == null) {
                    Text("− ${preview.file.name}", color = Color(0xFFE3A59A), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("+ ${preview.newName}", color = Color(0xFFAFCFB9), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else plan.changes.forEach { change ->
                    Column {
                        val count = if (change.replacements > 1) "（${change.replacements} 处）" else ""
                        Text("第 ${change.lineNumber} 行$count", color = Color(AppPalette.INVERSE_SUBTLE), fontSize = 10.sp)
                        Text("− ${change.before.trim()}", color = Color(0xFFE3A59A), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("+ ${change.after.trim()}", color = Color(0xFFAFCFB9), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
        ContentBlock("变更后") { Text("图片使用新文件名，识别逻辑和素材内容保持不变。", fontSize = 12.sp, lineHeight = 21.sp) }
    }
}
