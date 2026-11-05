package com.forest.android.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forest.android.assistant.R

/** 详情流程共用的全屏容器：保留返回栈，表单可滚动，操作区避让键盘和系统栏。 */
@Composable
fun DetailPage(
    title: String, subtitle: String = "", onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { AppIcon(R.drawable.ic_back, "返回") }
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            actions()
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), content = footer)
                }
            }
        }
    }
}

@Composable
fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(11.dp)) { Text(label) }
}

@Composable
fun SectionLabel(title: String) {
    Text(title, modifier = Modifier.padding(start = 3.dp, top = 8.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun IconTile(icon: Int, warm: Boolean = false, danger: Boolean = false, blue: Boolean = false) {
    val bg = when { danger -> AppPalette.ERROR_CONTAINER; warm -> AppPalette.PRIMARY_CONTAINER;
        blue -> AppPalette.BLUE_CONTAINER; else -> AppPalette.SECONDARY_CONTAINER }
    val fg = when { danger -> AppPalette.ERROR; warm -> AppPalette.ON_PRIMARY_CONTAINER;
        blue -> AppPalette.BLUE; else -> AppPalette.SUCCESS }
    Surface(color = Color(bg), contentColor = Color(fg), shape = RoundedCornerShape(11.dp)) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { AppIcon(icon, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
fun StatusBadge(label: String, warning: Boolean = false, error: Boolean = false) {
    Surface(color = Color(if (error) AppPalette.ERROR_CONTAINER else if (warning) AppPalette.PRIMARY_CONTAINER else AppPalette.SECONDARY_CONTAINER),
        contentColor = Color(if (error) AppPalette.ERROR else if (warning) AppPalette.TERTIARY else AppPalette.SUCCESS),
        shape = RoundedCornerShape(50)) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DesignRow(title: String, description: String, icon: Int, enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = 76.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, warm = icon == R.drawable.ic_clock || icon == R.drawable.ic_floaty || icon == R.drawable.ic_export,
            danger = title.contains("删除") || title.contains("清空"),
            blue = icon == R.drawable.ic_code || icon == R.drawable.ic_import || icon == R.drawable.ic_tool_shot)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f))
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
        if (trailing != null) trailing() else AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun HeroCard(overline: String, title: String, description: String, action: String, icon: Int, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(AppPalette.ON_SURFACE))) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = .16f), 80.dp.toPx(), Offset(size.width - 28.dp.toPx(), -8.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .10f), 57.dp.toPx(), Offset(size.width - 28.dp.toPx(), 18.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.padding(20.dp)) {
            Text(overline, color = Color(AppPalette.INVERSE_SUBTLE), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Text(description, color = Color(AppPalette.INVERSE_SUBTLE), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onClick, shape = RoundedCornerShape(11.dp), modifier = Modifier.weight(1f, fill = false)) {
                    AppIcon(icon, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(action)
                }
                Spacer(Modifier.weight(1f))
                Surface(Modifier.padding(start = 10.dp).size(48.dp).rotate(-12f), shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFF9A6E), contentColor = Color(AppPalette.ON_SURFACE)) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcon(if (icon == R.drawable.ic_add) R.drawable.ic_launcher_bird else icon, modifier = Modifier.size(28.dp).rotate(12f))
                    }
                }
            }
        }
    }
}

@Composable
fun AppBottomNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("任务" to R.drawable.ic_nav_tasks, "日志" to R.drawable.ic_tool_log,
        "权限" to R.drawable.ic_nav_permissions, "设置" to R.drawable.ic_nav_settings)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
                tabs.forEachIndexed { index, (title, icon) ->
                    Column(Modifier.weight(1f).selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(18.dp).height(3.dp).background(
                            if (selected == index) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)))
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides
                            if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) {
                            AppIcon(icon, modifier = Modifier.size(22.dp))
                            Text(title, Modifier.padding(top = 3.dp), fontSize = 11.sp,
                                fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, icon: Int, action: String? = null, onClick: () -> Unit = {}) {
    WorkCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color(AppPalette.SECONDARY_CONTAINER), contentColor = Color(AppPalette.SUCCESS), shape = RoundedCornerShape(20.dp)) {
                Box(Modifier.size(65.dp), contentAlignment = Alignment.Center) { AppIcon(icon, modifier = Modifier.size(29.dp)) }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 7.dp))
            Text(description, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) Button(onClick = onClick, shape = RoundedCornerShape(11.dp), modifier = Modifier.padding(top = 18.dp)) { Text(action) }
        }
    }
}

@Composable
fun ChoiceCard(title: String, description: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFFFFF8F3) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
