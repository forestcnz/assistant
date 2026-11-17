package com.forest.android.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.recording.RecordingService
import com.forest.android.assistant.core.task.TaskLoop
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.AppToast as Toast
import com.forest.android.assistant.ui.AppBottomNavigation
import com.forest.android.assistant.ui.ScriptMarket
import com.forest.android.assistant.ui.TransferPage
import com.forest.android.assistant.ui.main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 主界面骨架：底部导航、四个页面容器与全局弹层入口。 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivity.MainScreen() {
    val runState by ScriptRunner.state.collectAsState()
    val logs by ScriptRunner.logs.collectAsState()
    val launcherEnabled by ScriptRunner.launcherEnabled.collectAsState()
    val pageStates = rememberSaveableStateHolder()
    val updateState by appUpdate.state.collectAsState()

    // 任务副标题（描述/目标应用）：任务集合变化时后台读取一次，避免每秒轮询做文件 IO
    LaunchedEffect(tasks, taskMetadataVersion) {
        taskSubtitles = withContext(Dispatchers.IO) {
            tasks.associate { t ->
                val cfg = TaskManager.readConfig(this@MainScreen, t.name)
                val base = cfg.desc.ifBlank { cfg.target?.let { "目标：$it" } ?: "" }
                val repeated = if (cfg.loop.mode != TaskLoop.Mode.COUNT || cfg.loop.count > 1)
                    cfg.loop.describe() else ""
                t.name to listOf(base, repeated).filter { it.isNotBlank() }.joinToString(" · ")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { AppBottomNavigation(mainTab) { mainTab = it } },
    ) { padding ->
        // 内容在宽屏居中；各页面分别保存滚动位置。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxHeight()
                ) {
                    pageStates.SaveableStateProvider(mainTab) {
                        when (mainTab) {
                            0 -> TaskWorkspace(runState)
                            1 -> LogsTab(logs)
                            2 -> PermissionsTab()
                            else -> SettingsTab(launcherEnabled, updateState)
                        }
                    }
                }
            }
        }
    }
    MainDialogHost()
}
