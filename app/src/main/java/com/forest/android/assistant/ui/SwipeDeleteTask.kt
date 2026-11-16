package com.forest.android.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 右滑仅请求删除确认，行随即复位；实际删除由外层确认弹窗处理。 */
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
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { width -> minOf(width * .35f, threshold) })
    SwipeToDismissBox(
        state = state,
        modifier = modifier.fillMaxWidth().semantics {
            customActions = listOf(CustomAccessibilityAction("删除任务「$taskName」") {
                if (enabled) onDelete()
                enabled
            })
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        gesturesEnabled = enabled,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.StartToEnd && enabled) onDelete()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("删除任务", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
            }
        }
    ) { content() }
}
