package com.forest.android.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forest.android.assistant.R
import kotlinx.coroutines.launch

/** 左滑达到行宽的 1/3 后请求删除确认，行随即复位；实际删除由外层确认弹窗处理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeDeleteTask(
    taskName: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { width -> width / 3f })
    SwipeToDismissBox(
        state = state,
        modifier = modifier.fillMaxWidth().semantics {
            customActions = listOf(CustomAccessibilityAction("删除任务「$taskName」") {
                if (enabled) onDelete()
                enabled
            })
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        gesturesEnabled = enabled,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart && enabled) onDelete()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            // 左滑操作使用暖白底和小圆角图标块，与任务列表的配色、字号和间距保持一致。
            Box(
                Modifier.fillMaxSize().background(Color(AppPalette.SURFACE_CONTAINER_LOW))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(Modifier.width(80.dp).fillMaxHeight(), shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.error,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        AppIcon(R.drawable.ic_clear_tasks, modifier = Modifier.size(18.dp))
                        Text("删除任务", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    ) { content() }
}
