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
import kotlinx.coroutines.launch

/** 运行日志页与实时日志面板。 */

/** 日志页：独立显示实时输出。 */
@Composable
internal fun MainActivity.LogsTab(logs: List<ScriptRunner.LogEntry>) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    var jumpSignal by remember { mutableStateOf(0) }
    val filtered = when (filter) {
        "INFO" -> logs.filter { it.level == 'I' || it.level == 'D' }
        "WARN" -> logs.filter { it.level == 'W' }
        "ERROR" -> logs.filter { it.level == 'E' }
        else -> logs
    }
    Column(Modifier.fillMaxSize()) {
        PageHeading("运行日志", "每一步，都清楚可见", actions = {
            if (logs.isNotEmpty()) TextButton(onClick = { ScriptRunner.clearLogs() }) {
                Text("清空", fontSize = 12.sp, color = Color(AppPalette.PRIMARY))
            }
        })
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(AppPalette.SECONDARY_CONTAINER), contentColor = Color(AppPalette.SUCCESS),
                    shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(Color(AppPalette.SUCCESS), androidx.compose.foundation.shape.CircleShape))
                        Text("实时输出", Modifier.padding(start = 6.dp), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("最近 ${filtered.size} 条", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 日志级别筛选：仅影响当前展示，不删除历史记录。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("全部", "INFO", "WARN", "ERROR").forEach { item ->
                    val active = filter == item
                    Box(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50))
                        .background(if (active) Color(AppPalette.INVERSE_SURFACE) else MaterialTheme.colorScheme.surface)
                        .border(1.dp, if (active) Color(AppPalette.INVERSE_SURFACE) else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(50))
                        .clickable { filter = item }.padding(horizontal = 13.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center) {
                        Text(item, fontSize = 11.sp,
                            color = if (active) Color.White else Color(AppPalette.ON_SURFACE_VARIANT))
                    }
                }
            }
            if (filtered.isEmpty()) {
                EmptyState(if (logs.isEmpty()) "安静，等待下一次开始" else "没有这一级别的日志",
                    "任务运行后的输出会出现在这里。", R.drawable.ic_tool_log)
            } else {
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    LogPanel(filtered, Modifier.fillMaxWidth().weight(1f), jumpSignal)
                    Button(onClick = { jumpSignal++ }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(AppPalette.SURFACE_CONTAINER),
                            contentColor = Color(AppPalette.ON_SURFACE_VARIANT))) {
                        Text("回到最新", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActivity.LogPanel(
    logs: List<ScriptRunner.LogEntry>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(200.dp),
    jumpSignal: Int = 0
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followTail by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(jumpSignal) {
        if (logs.isNotEmpty()) {
            followTail = true
            listState.scrollToItem(logs.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        // 仅在用户滚动时切换跟随状态，追加日志本身不改变用户的选择。
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, hasMore) -> if (scrolling) followTail = !hasMore }
    }
    LaunchedEffect(logs) {
        if (logs.isNotEmpty() && followTail) listState.scrollToItem(logs.size - 1)
    }
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
            contentPadding = PaddingValues(bottom = if (followTail) 8.dp else 52.dp)
        ) {
            items(logs) { entry ->
                val levelColor = when (entry.level) {
                    'E' -> MaterialTheme.colorScheme.error
                    'W' -> MaterialTheme.colorScheme.tertiary
                    'D' -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> Color(AppPalette.SUCCESS)
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.time, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace)
                        Text(
                            when (entry.level) {
                                'E' -> "ERROR"
                                'W' -> "WARN"
                                'D' -> "DEBUG"
                                else -> "INFO"
                            },
                            fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = levelColor,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Text(
                        text = entry.msg,
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        color = when (entry.level) {
                            'E' -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (!followTail) {
            Button(onClick = {
                followTail = true
                scope.launch { if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex) }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Text("回到最新") }
        }
    }
}
