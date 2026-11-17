package com.forest.android.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forest.android.assistant.R

/** 各页面共用紧凑返回按钮，统一边框、图标尺寸与圆角。 */
@Composable
fun AppBackButton(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier,
    description: String = "返回", compactFrame: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(36.dp)
        .then(if (compactFrame) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)))) {
        if (compactFrame) {
            // 仅缩小可见外框，按钮占位、触摸区域和箭头尺寸保持不变。
            Box(Modifier.size(28.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_back, description, Modifier.size(17.dp))
            }
        } else AppIcon(R.drawable.ic_back, description, Modifier.size(17.dp))
    }
}

/** 详情流程共用的全屏容器：保留返回栈，表单可滚动，操作区避让键盘和系统栏。 */
@Composable
fun DetailPage(
    title: String, subtitle: String = "", onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    scrollable: Boolean = true,
    compact: Boolean = false,
    compactBackFrame: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AppBackButton(onClick = onBack, compactFrame = compactBackFrame)
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    letterSpacing = (-0.5).sp)
                                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 10.sp, lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                            actions()
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth()
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight())
                         .padding(horizontal = 18.dp, vertical = 4.dp),
                         // 紧凑详情页收紧内容块间距，保持表单和底部操作可滚动、可点击。
                         verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp), content = content)
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), content = footer)
                }
            }
        }
    }
}

@Composable
fun PrimaryAction(label: String, enabled: Boolean = true, icon: Int = 0, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(AppPalette.PRIMARY),
            contentColor = Color(AppPalette.ON_PRIMARY)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (icon != 0) {
            AppIcon(icon, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionLabel(title: String, compact: Boolean = false) {
    Text(title, modifier = Modifier.padding(start = 3.dp, top = if (compact) 6.dp else 12.dp, bottom = 2.dp),
        fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun IconTile(icon: Int, warm: Boolean = false, danger: Boolean = false, blue: Boolean = false) {
    // 任务/设置图标底色按语义分组：沙色对应定时，淡雾蓝对应脚本，淡绿对应成功，淡红对应删除。
    val bg = when {
        danger -> AppPalette.ERROR_CONTAINER
        warm -> AppPalette.SAND_CONTAINER
        blue -> AppPalette.BLUE_CONTAINER
        else -> AppPalette.PRIMARY_CONTAINER
    }
    val fg = when {
        danger -> AppPalette.ERROR
        warm -> AppPalette.ON_SAND_CONTAINER
        blue -> AppPalette.BLUE
        else -> AppPalette.ON_SURFACE_VARIANT
    }
    Surface(color = Color(bg), contentColor = Color(fg), shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.size(39.dp), contentAlignment = Alignment.Center) { AppIcon(icon, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
fun StatusBadge(label: String, warning: Boolean = false, error: Boolean = false) {
    // 中性数量和任务类型使用雾蓝，只有明确的运行结果使用成功色。
    val success = Regex("已开启|成功|已完成|运行中|正在执行|就绪|已导入|实时").containsMatchIn(label)
    val attention = warning || label.contains("需更新") || label.contains("待设置")
    val bg = when {
        error -> AppPalette.ERROR_CONTAINER
        attention -> AppPalette.AMBER_CONTAINER
        success -> AppPalette.SECONDARY_CONTAINER
        else -> AppPalette.PRIMARY_CONTAINER
    }
    val fg = when {
        error -> AppPalette.ERROR
        attention -> AppPalette.TERTIARY
        success -> AppPalette.SUCCESS
        else -> AppPalette.PRIMARY
    }
    Surface(color = Color(bg), contentColor = Color(fg), shape = RoundedCornerShape(50)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 设置与详情页通用行：左侧图标，中间标题/说明，右侧自定义尾部或箭头。 */
@Composable
fun DesignRow(title: String, description: String, icon: Int, enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null, compact: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = if (compact) 56.dp else 70.dp)
        .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(AppPalette.SURFACE_CONTAINER), contentColor = Color(AppPalette.ON_SURFACE_VARIANT),
            shape = RoundedCornerShape(10.dp)) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { AppIcon(icon, modifier = Modifier.size(18.dp)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 13.sp, lineHeight = if (compact) 18.sp else 21.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f))
            if (description.isNotBlank()) Text(description, fontSize = 10.sp, lineHeight = if (compact) 14.sp else 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = if (compact) 2.dp else 4.dp))
        }
        if (trailing != null) trailing() else AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(15.dp))
    }
}

/** 任务与分组列表共用首页卡片，切换时保持相同内容与尺寸。 */
@Composable
fun TaskWorkspaceHero() {
    HeroCard(
        overline = "A LITTLE LESS REPETITION",
        title = "把琐事，交给雕雕。",
        description = "让每一个重复动作，都有条不紊。",
        action = "任务空间已就绪"
    )
}

/** 首页深色主视觉卡片：文案、玻璃胶囊与沙色圆形入口。 */
// 首页主视觉仅展示品牌文案和就绪状态，原来的沙色圆形入口已移除。
@Composable
fun HeroCard(overline: String, title: String, description: String, action: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp)).background(Color(AppPalette.INVERSE_SURFACE))) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = .10f), 82.dp.toPx(), Offset(size.width - 26.dp.toPx(), -20.dp.toPx()),
                style = Stroke(1.dp.toPx()))
        }
        // 品牌形象放在右上角，与设计稿的深色主视觉一致。
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 18.dp).size(56.dp)
        )
        // 首页主视觉压缩上下留白，品牌形象与文案同步收紧。
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(overline, color = Color(AppPalette.INVERSE_SUBTLE), fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.7.sp,
                fontWeight = FontWeight.SemiBold)
            Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Medium, lineHeight = 27.sp,
                letterSpacing = (-0.6).sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, end = 62.dp))
            Text(description, color = Color(0xFFC7D1DF), fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(end = 62.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = .07f), shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(Color(0xFFC7DFD2), CircleShape))
                        Text(action, Modifier.padding(start = 6.dp), color = Color(0xFFC7DFD2), fontSize = 10.sp, lineHeight = 14.sp)
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
            // 导航项保留完整触摸区域，缩小图标底座与文字间距以降低整体高度。
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 9.dp, vertical = 5.dp)) {
                tabs.forEachIndexed { index, (title, icon) ->
                    val active = selected == index
                    Column(Modifier.weight(1f).selectable(active, role = Role.Tab, onClick = { onSelect(index) }).heightIn(min = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        // 选中态使用淡雾蓝胶囊底，未选中保持透明。
                        Box(Modifier.size(width = 40.dp, height = 24.dp)
                            .background(if (active) Color(0xFFE5EDF7) else Color.Transparent, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides
                                if (active) Color(AppPalette.PRIMARY) else Color(0xFF7C8796)) {
                                AppIcon(icon, modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(title, Modifier.padding(top = 2.dp), fontSize = 10.sp, lineHeight = 12.sp,
                            color = if (active) Color(AppPalette.PRIMARY) else Color(0xFF7C8796),
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, icon: Int, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = Color(AppPalette.PRIMARY_CONTAINER), contentColor = Color(AppPalette.ON_SURFACE_VARIANT),
            shape = RoundedCornerShape(27.dp), modifier = Modifier.rotate(-5f)) {
            Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.rotate(5f)) { AppIcon(icon, modifier = Modifier.size(35.dp)) }
            }
        }
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
        Text(description, textAlign = TextAlign.Center, fontSize = 11.sp, lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
        if (action != null) Button(onClick = onClick, shape = RoundedCornerShape(13.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text(action, fontSize = 13.sp) }
    }
}

@Composable
fun ChoiceCard(title: String, description: String, selected: Boolean, enabled: Boolean = true, icon: Int = 0,
    compact: Boolean = false, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(AppPalette.PRIMARY_CONTAINER) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) Color(0xFF9DB6D4) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = if (compact) 13.dp else 16.dp, vertical = if (compact) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (icon != 0) {
                // 与设计稿一致：选项左侧显示浅色图标块。
                Surface(color = Color(AppPalette.SURFACE_CONTAINER), contentColor = Color(AppPalette.ON_SURFACE_VARIANT),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(end = if (compact) 10.dp else 12.dp)) {
                    Box(Modifier.size(if (compact) 32.dp else 38.dp), contentAlignment = Alignment.Center) {
                        AppIcon(icon, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = if (compact) 12.sp else 13.sp, lineHeight = if (compact) 17.sp else 21.sp,
                    fontWeight = FontWeight.SemiBold)
                if (description.isNotBlank()) Text(description, fontSize = 10.sp,
                    lineHeight = if (compact) 14.sp else 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (compact) 1.dp else 4.dp))
            }
            // 右对齐单选点，选中时填充雾蓝。
            Box(Modifier.size(if (compact) 16.dp else 17.dp)
                .background(if (selected) Color(AppPalette.PRIMARY) else Color.Transparent, CircleShape)
                .border(1.5.dp, if (selected) Color(AppPalette.PRIMARY) else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(5.dp).background(Color.White, CircleShape))
            }
        }
    }
}

/** 底部操作面板：替代系统 AlertDialog，统一标题、列表行、圆角与背景。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 18.dp)) {
            Text(title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 分段切换：任务 / 任务分组等两级视图共用，选中项浮起为白色。 */

/** 分段切换：任务 / 任务分组等两级视图共用，选中项浮起为白色。 */
@Composable
fun SegmentedTabs(options: List<String>, selectedIndex: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().background(Color(AppPalette.SURFACE_CONTAINER), RoundedCornerShape(12.dp))
        .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(Modifier.weight(1f).heightIn(min = 36.dp)
                .background(if (active) Color.White else Color.Transparent, RoundedCornerShape(9.dp))
                .selectable(active, role = Role.Tab) { onSelect(index) }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color(AppPalette.ON_SURFACE) else Color(AppPalette.ON_SURFACE_VARIANT))
            }
        }
    }
}

/** 白色圆角列表容器；列表行自带分隔线，容器只负责边框与圆角。 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(content = content)
    }
}

/** 深色主视觉下方的市场入口条。 */
@Composable
fun MarketBanner(title: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
        .background(Color(AppPalette.PRIMARY_CONTAINER))
        .border(1.dp, Color(0xFFD9E3EF), RoundedCornerShape(17.dp))
        .clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(0xFFDAE9C9), contentColor = Color(0xFF4C6B37), shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.size(39.dp), contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_code, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(16.dp))
    }
}

/** 设计稿表单字段：标签在上，输入框圆角 12，说明文字在下。 */
@Composable
fun DesignField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    error: String? = null,
    compact: Boolean = false
) {
    Column(modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 4.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = if (compact) 16.sp else 21.sp, fontWeight = FontWeight.SemiBold)
        // 输入框高度对齐设计稿的 42px，不再使用 Material 默认的 56dp。
        // 像素选区的紧凑字段缩至 36dp，并减少标签与输入框之间的空白。
        Box(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp).heightIn(min = if (compact) 36.dp else 42.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 11.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = TextStyle(fontSize = 12.sp, lineHeight = if (compact) 16.sp else TextUnit.Unspecified,
                    color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Color(AppPalette.PRIMARY)),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (error != null || hint.isNotBlank()) {
            Text(error ?: hint, fontSize = 10.sp, lineHeight = 16.sp,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
    }
}

/** 页面说明统一使用品牌浅底，警告和错误单独传达状态。 */
@Composable
fun InfoNotice(text: String, warning: Boolean = false, error: Boolean = false, compact: Boolean = false) {
    // 简短操作提示可使用紧凑样式，减少字号、行高和上下留白。
    Text(text, Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 4.dp)
        .background(Color(when { error -> AppPalette.ERROR_CONTAINER; warning -> AppPalette.AMBER_CONTAINER; else -> AppPalette.PRIMARY_CONTAINER }), RoundedCornerShape(12.dp))
        .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 7.dp else 12.dp),
        fontSize = if (compact) 10.sp else 11.sp, lineHeight = if (compact) 15.sp else 19.sp,
        color = Color(when { error -> AppPalette.ERROR; warning -> AppPalette.TERTIARY; else -> AppPalette.ON_SURFACE_VARIANT }))
}

/** 内容块和统计条复用于运行结果、更新、录制摘要等状态页。 */
@Composable
fun ContentBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionCard {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun MetricStrip(items: List<Pair<String, String>>) {
    Column(Modifier.padding(vertical = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
            items.forEachIndexed { index, (value, label) ->
                if (index > 0) Box(Modifier.height(36.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.weight(1f).padding(start = if (index > 0) 14.dp else 0.dp)) {
                    Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun SecondaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(
            containerColor = Color(AppPalette.SURFACE_CONTAINER), contentColor = Color(AppPalette.ON_SURFACE_VARIANT))) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 有副作用的操作先展示底部确认；取消和主操作始终各占一半宽度。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmActionSheet(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit,
    onConfirm: () -> Unit, danger: Boolean = false, busy: Boolean = false,
    cancelLabel: String = "取消", enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 23.dp).verticalScroll(rememberScrollState())) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(message, fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
            content()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryAction(cancelLabel, !busy, onDismiss) }
                Box(Modifier.weight(1f)) {
                    Button(onConfirm, enabled = !busy && enabled,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(if (danger) AppPalette.ERROR_CONTAINER else AppPalette.PRIMARY),
                            contentColor = Color(if (danger) AppPalette.ERROR else AppPalette.ON_PRIMARY))) {
                        Text(confirmLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
