package com.forest.android.assistant

import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.floaty.ConsoleOverlay
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.floaty.PickerOverlay
import com.forest.android.assistant.core.gesture.AccessibilityGuard
import com.forest.android.assistant.core.gesture.ScriptAccessibilityService
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.recording.RecordingService
import com.forest.android.assistant.core.task.ScheduleManager
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.core.task.TaskLoop
import com.forest.android.assistant.core.task.ImageHistory
import com.forest.android.assistant.core.task.TaskZip
import com.forest.android.assistant.core.web.LanWebServer
import com.forest.android.assistant.core.web.LanWebService
import com.forest.android.assistant.core.web.LanWebState
import androidx.compose.material3.SwitchDefaults
import com.forest.android.assistant.ui.*
import com.forest.android.assistant.ui.AppIcon
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.StartupComic
import com.forest.android.assistant.ui.WorkCard
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主界面：权限状态 + 任务管理（新建/导入/编辑）+ 运行日志面板。
 * 任务模型见 docs/README.md 第 4.2 节。
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SHOW_TASKS = "show_recorded_tasks"
    }

    // 权限状态（页面可见期间每秒轮询刷新）
    private var a11yEnabled by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var captureReady by mutableStateOf(false)
    private var tasks by mutableStateOf(emptyList<TaskManager.TaskInfo>())
    private var taskTransferBusy by mutableStateOf(false)
    private var pendingExportFile by mutableStateOf<File?>(null)
    private var showCreateDialog by mutableStateOf(false)
    private var taskMetadataVersion by mutableStateOf(0)



    /** 任务操作菜单/素材管理 */
    private var showTransferDialog by mutableStateOf(false)
    private var menuTask by mutableStateOf<String?>(null)
    private var renamingTask by mutableStateOf<String?>(null)
    private var deletingTask by mutableStateOf<String?>(null)
    private var showClearTasksDialog by mutableStateOf(false)
    private var taskMutationBusy by mutableStateOf(false)
    private var assetsTask by mutableStateOf<String?>(null)
    private var assets by mutableStateOf(emptyList<TaskManager.ImageInfo>())
    private var assetsHistoryTask by mutableStateOf<String?>(null)
    private var assetsHistory by mutableStateOf(emptyList<ImageHistory.Entry>())
    private var assetsHistoryBusy by mutableStateOf(false)
    private var assetsHistoryError by mutableStateOf<String?>(null)
    private var previewAsset by mutableStateOf<TaskManager.ImageInfo?>(null)
    private var previewBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    private var previewLoadJob: kotlinx.coroutines.Job? = null
    private var deletingAsset by mutableStateOf<TaskManager.ImageInfo?>(null)
    private var renameAsset by mutableStateOf<TaskManager.ImageInfo?>(null)
    /** 素材重命名的脚本引用预览（确认后才会落盘） */
    private var renamePreview by mutableStateOf<TaskManager.ImageRenamePreview?>(null)

    /** 定时任务编辑状态 */
    private var scheduleEditing by mutableStateOf<String?>(null)
    private var currentSchedule by mutableStateOf<ScheduleManager.Schedule?>(null)
    private var scheduleType by mutableStateOf(ScheduleManager.Type.ONCE_AFTER_MINUTES)
    private var scheduleValue by mutableStateOf("1")

    /** 整个任务的循环设置，保存到任务的 config.json。 */
    private var loopEditing by mutableStateOf<String?>(null)
    private var loopMode by mutableStateOf(TaskLoop.Mode.COUNT)
    private var loopCountValue by mutableStateOf("1")
    private var loopDurationValue by mutableStateOf("1")
    private var loopDurationUnit by mutableStateOf(DurationUnit.MINUTE)
    private var loopIntervalValue by mutableStateOf("0")

    private enum class DurationUnit(val label: String, val seconds: Long) {
        SECOND("秒", 1), MINUTE("分钟", 60), HOUR("小时", 3600)
    }

    /** 保活指引对话框 */
    private var keepAliveDialog by mutableStateOf(false)

    /** 运行历史对话框状态 */
    private var historyTask by mutableStateOf<String?>(null)
    private var historyEntries by mutableStateOf(emptyList<RunHistory.Entry>())

    /** 截屏通道对话框状态 */
    private var captureDialog by mutableStateOf(false)
    private var selectedCaptureMode by mutableStateOf(ScreenCapture.Mode.UNSELECTED)
    private var captureModeTesting by mutableStateOf(false)
    private var captureModeError by mutableStateOf<String?>(null)

    /** 各任务的定时设置（列表展示用） */
    private var schedules by mutableStateOf(emptyMap<String, ScheduleManager.Schedule>())

    /** 主导航：任务 / 日志 / 权限 / 设置；恢复旋转前的页面。 */
    private var mainTab by mutableStateOf(0)


    /** 任务副标题（config 的 desc / target），仅任务集合变化时刷新 */
    private var taskSubtitles by mutableStateOf(emptyMap<String, String>())

    /** 通知权限（API 33+）：前台保活通知可见才用得到通知栏「停止」 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ScriptRunner.appendLog(
                if (granted) "已授予通知权限：保活通知可见（可直接在通知栏停止脚本）"
                else "未授予通知权限：前台保活通知不可见，请在系统设置中允许通知",
                if (granted) 'I' else 'W'
            )
        }

    private val cropImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = result.data?.getStringExtra(AssetCropActivity.EXTRA_TASK) ?: assetsTask
            if (task != null) {
                assetsTask = task
                assets = TaskManager.imageInfos(this, task)
                previewAsset?.let { previous ->
                    assets.firstOrNull { it.file.name == previous.file.name }?.let { loadAssetPreview(it) }
                }
            }
            if (result.resultCode == RESULT_OK) {
                val name = result.data?.getStringExtra(AssetCropActivity.EXTRA_IMAGE).orEmpty()
                Toast.makeText(this, "已替换 $name，原图已移入历史素材", Toast.LENGTH_SHORT).show()
                ScriptRunner.appendLog("已裁剪替换 $name，原图已移入 images/history/")
            }
        }

    private fun cropAsset(info: TaskManager.ImageInfo) {
        val task = assetsTask ?: return
        previewLoadJob?.cancel()
        cropImageLauncher.launch(Intent(this, AssetCropActivity::class.java)
            .putExtra(AssetCropActivity.EXTRA_TASK, task)
            .putExtra(AssetCropActivity.EXTRA_IMAGE, info.file.name))
    }

    private fun openAssetsHistory(task: String) {
        assetsHistoryTask = task
        assetsHistory = emptyList()
        assetsHistoryError = null
        assetsHistoryBusy = true
        lifecycleScope.launch {
            try {
                assetsHistory = withContext(Dispatchers.IO) { ImageHistory.list(this@MainActivity, task) }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                assetsHistoryError = failure.message ?: "读取历史素材失败"
            } finally {
                assetsHistoryBusy = false
            }
        }
    }

    private fun moveHistoryImageBack(task: String, entry: ImageHistory.Entry) {
        if (assetsHistoryBusy) return
        assetsHistoryBusy = true
        assetsHistoryError = null
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    ImageHistory.moveBack(this@MainActivity, task, entry)
                }
                val refreshed = withContext(Dispatchers.IO) {
                    TaskManager.imageInfos(this@MainActivity, task) to ImageHistory.list(this@MainActivity, task)
                }
                assets = refreshed.first
                assetsHistory = refreshed.second
                Toast.makeText(this@MainActivity, "已移回素材：${entry.originalName}", Toast.LENGTH_SHORT).show()
                ScriptRunner.appendLog("已从历史移回素材：${entry.originalName}")
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                assetsHistoryError = failure.message ?: "移回失败，请重试"
            } finally {
                assetsHistoryBusy = false
            }
        }
    }

    /** 从相册/文件导入素材（SAF） */
    private val importImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val task = assetsTask ?: return@registerForActivityResult
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) { TaskManager.importImage(this@MainActivity, task, uri) }
                if (file != null) {
                    Toast.makeText(this@MainActivity, "已导入素材：" + file.name, Toast.LENGTH_SHORT).show()
                    ScriptRunner.appendLog("已导入素材: " + file.name)
                    assets = TaskManager.imageInfos(this@MainActivity, task)
                } else {
                    Toast.makeText(this@MainActivity, "导入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** 从一个 ZIP 导入单个任务或整个任务合集。 */
    private val importZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                taskTransferBusy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { TaskZip.importArchive(this@MainActivity, uri) }
                }
                taskTransferBusy = false
                result.onSuccess { report ->
                    tasks = TaskManager.listTasks(this@MainActivity)
                    taskMetadataVersion++
                    report.imported.forEach { ScriptRunner.appendLog("已导入任务: $it") }
                    report.importedGroups.forEach { ScriptRunner.appendLog("已恢复分组: $it") }
                    report.skipped.forEach { ScriptRunner.appendLog("导入跳过: $it", 'W') }
                    val message = buildString {
                        append("成功导入 ${report.imported.size} 个任务")
                        if (report.imported.isNotEmpty()) append("：${report.imported.joinToString("、")}")
                        if (report.importedGroups.isNotEmpty()) {
                            append("\n\n恢复 ${report.importedGroups.size} 个分组：${report.importedGroups.joinToString("、")}")
                        }
                        if (report.skipped.isNotEmpty()) {
                            append("\n\n跳过或未恢复 ${report.skipped.size} 项：\n")
                            append(report.skipped.joinToString("\n"))
                        }
                    }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("导入完成")
                        .setMessage(message)
                        .setPositiveButton("关闭", null)
                        .show()
                }.onFailure { error ->
                    ScriptRunner.appendLog("导入失败: ${error.message}", 'E')
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("导入失败")
                        .setMessage(error.message ?: "无法读取任务 ZIP")
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }
        }

    /** 用户选择 ZIP 保存位置后，将临时导出文件写入所选 URI。 */
    private val exportZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val file = pendingExportFile
            if (uri == null) {
                file?.delete()
                pendingExportFile = null
                taskTransferBusy = false
                Toast.makeText(this, "已取消导出", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val source = file ?: error("找不到待导出的 ZIP")
                        val output = contentResolver.openOutputStream(uri, "w") ?: error("无法写入所选位置")
                        output.buffered().use { destination ->
                            source.inputStream().buffered().use { input -> input.copyTo(destination) }
                        }
                    }
                }
                file?.delete()
                pendingExportFile = null
                taskTransferBusy = false
                result.onSuccess {
                    ScriptRunner.appendLog("任务 ZIP 已保存: $uri")
                    Toast.makeText(this@MainActivity, "ZIP 已保存到所选位置", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    ScriptRunner.appendLog("保存 ZIP 失败: ${error.message}", 'E')
                    Toast.makeText(this@MainActivity, "保存失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    /** 系统录屏授权结果 → 交给 ScreenCapture 建立投影 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ScreenCapture.onProjectionResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportFile = savedInstanceState?.getString("pending_export_file")?.let(::File)
        menuTask = savedInstanceState?.getString("detail_task")
        assetsTask = savedInstanceState?.getString("assets_task")
        assetsTask?.let { assets = TaskManager.imageInfos(this, it) }
        taskTransferBusy = pendingExportFile != null
        mainTab = savedInstanceState?.getInt("main_tab", 0)?.coerceIn(0, 3) ?: 0
        if (intent.getBooleanExtra(EXTRA_SHOW_TASKS, false)) mainTab = 0
        showCreateDialog = savedInstanceState?.getBoolean("create_dialog", false) ?: false
        if (savedInstanceState != null) {
            loopEditing = savedInstanceState.getString("loop_task")
            loopMode = TaskLoop.Mode.entries.firstOrNull { it.name == savedInstanceState.getString("loop_mode") } ?: loopMode
            loopCountValue = savedInstanceState.getString("loop_count", loopCountValue)
            loopDurationValue = savedInstanceState.getString("loop_duration", loopDurationValue)
            loopIntervalValue = savedInstanceState.getString("loop_interval", loopIntervalValue)
            loopDurationUnit = DurationUnit.entries.firstOrNull { it.name == savedInstanceState.getString("loop_unit") } ?: loopDurationUnit
            scheduleEditing = savedInstanceState.getString("schedule_task")
            scheduleEditing?.let { currentSchedule = ScheduleManager.scheduleOf(this, it) }
            scheduleType = ScheduleManager.Type.entries.firstOrNull { it.name == savedInstanceState.getString("schedule_type") } ?: scheduleType
            scheduleValue = savedInstanceState.getString("schedule_value", scheduleValue)
            historyTask = savedInstanceState.getString("history_task")
            historyTask?.let { historyEntries = RunHistory.read(this, it) }
            savedInstanceState.getString("preview_image")?.let { image ->
                assets.firstOrNull { it.file.name == image }?.let { loadAssetPreview(it) }
            }
        }
        setContent {
            ScriptTheme {
                var showStartup by rememberSaveable {
                    mutableStateOf(savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SHOW_TASKS, false))
                }
                if (showStartup) {
                    StartupComic(R.drawable.startup_comic, onFinished = { showStartup = false })
                } else {
                    MainScreen()
                }
                // 启动画面结束后再申请通知权限，避免系统弹窗遮住漫画。
                LaunchedEffect(showStartup) {
                    if (!showStartup && android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        lifecycleScope.launch {
            // APK 内置任务先复制到可写目录，之后任务列表与运行逻辑都从该目录读取。
            withContext(Dispatchers.IO) {
                TaskManager.ensureBuiltinTasks(this@MainActivity)
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    a11yEnabled = isAccessibilityEnabled()
                    overlayGranted = FloatyManager.hasOverlayPermission(this@MainActivity)
                    captureReady = ScreenCapture.captureAvailableCached(this@MainActivity)
                    // 文件扫描与定时配置读取不占用主线程，避免列表滚动时卡顿。
                    val snapshot = withContext(Dispatchers.IO) {
                        TaskManager.listTasks(this@MainActivity) to
                            ScheduleManager.load(this@MainActivity)
                                .filter { it.enabled }.associateBy { it.taskName }
                    }
                    tasks = snapshot.first
                    schedules = snapshot.second
                    delay(1000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        taskMetadataVersion++
        // 悬浮窗默认开启：已授权则自动显示悬浮球；未授权则首次自动引导去授权
        autoEnableFloaty()
        // 静默通道探测放到后台线程：完成后 chip 立即显示「截屏 ✓」
        if (!captureModeTesting) ScreenCapture.probeSilentAsync(this)
        // 每次回到本界面重新绑定截屏授权：脚本请求时若界面不在前台，先把自己拉到前台再弹窗
        ScreenCapture.bind(this) { intent ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                window.decorView.postDelayed({ projectionLauncher.launch(intent) }, 400)
            } else {
                projectionLauncher.launch(intent)
            }
        }
    }

    override fun onDestroy() {
        ScreenCapture.unbind(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_TASKS, false)) {
            mainTab = 0
            taskMetadataVersion++
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("loop_task", loopEditing)
        outState.putString("loop_mode", loopMode.name)
        outState.putString("loop_count", loopCountValue)
        outState.putString("loop_duration", loopDurationValue)
        outState.putString("loop_interval", loopIntervalValue)
        outState.putString("loop_unit", loopDurationUnit.name)
        outState.putString("schedule_task", scheduleEditing)
        outState.putString("schedule_type", scheduleType.name)
        outState.putString("schedule_value", scheduleValue)
        outState.putString("history_task", historyTask)
        outState.putString("preview_image", previewAsset?.file?.name)
        outState.putString("detail_task", menuTask)
        outState.putString("assets_task", assetsTask)
        outState.putInt("main_tab", mainTab)
        outState.putBoolean("create_dialog", showCreateDialog)
        outState.putString("pending_export_file", pendingExportFile?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    /**
     * 悬浮窗「默认开启」：
     * - 已授权：自动显示悬浮球（用户主动隐藏过则尊重其选择，不再自动弹）；
     * - 未授权：由权限页或运行/工具操作按需引导，首页保持可操作。
     *   开启后回到界面即自动出现悬浮球。
     */
    private fun autoEnableFloaty() {
        if (FloatyManager.hasOverlayPermission(this)) {
            ScriptRunner.ensureLauncher(this)
            return
        }
        // 权限被撤销时同步收起已失效的浮层，并停止悬浮球保活服务。
        ScriptRunner.ensureLauncher(this)
    }

    /** 检查本应用的手势无障碍服务是否已开启 */
    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, ScriptAccessibilityService::class.java)
        return enabled.contains(cn.flattenToString()) ||
                enabled.contains(cn.flattenToShortString())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val runState by ScriptRunner.state.collectAsState()
        val logs by ScriptRunner.logs.collectAsState()
        val launcherEnabled by ScriptRunner.launcherEnabled.collectAsState()
        val pageStates = rememberSaveableStateHolder()

        // 任务副标题（描述/目标应用）：任务集合变化时后台读取一次，避免每秒轮询做文件 IO
        LaunchedEffect(tasks, taskMetadataVersion) {
            taskSubtitles = withContext(Dispatchers.IO) {
                tasks.associate { t ->
                    val cfg = TaskManager.readConfig(this@MainActivity, t.name)
                    val base = cfg.desc.ifBlank { cfg.target?.let { "目标：$it" } ?: "" }
                    val repeated = if (cfg.loop.mode != TaskLoop.Mode.COUNT || cfg.loop.count > 1)
                        cfg.loop.describe() else ""
                    t.name to listOf(base, repeated).filter { it.isNotBlank() }.joinToString(" · ")
                }
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { AppBottomNavigation(mainTab) { mainTab = it } }
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
                                else -> SettingsTab(launcherEnabled)
                            }
                        }
                    }
                }
            }
        }



        if (showTransferDialog) AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { showTransferDialog = false },
            title = { Text("导入与导出") }, text = {
                Column {
                    DesignRow("导入任务 ZIP", "支持单任务和任务合集", R.drawable.ic_import, enabled = !taskTransferBusy) {
                        showTransferDialog = false
                        importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    DesignRow("导出全部任务", "保存脚本、素材、配置和分组顺序", R.drawable.ic_export,
                        enabled = tasks.isNotEmpty() && !taskTransferBusy) { showTransferDialog = false; exportAllTasks() }
                    Text("导入与保存位置由系统文件选择器提供。", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { showTransferDialog = false }) { Text("关闭") } })
        if (showCreateDialog) {
            CreateTaskDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name, scaffold ->
                    val created = TaskManager.createTask(this, name, scaffold)
                    if (created == null) {
                        Toast.makeText(this, "创建失败：名称非法或已存在", Toast.LENGTH_SHORT).show()
                    } else {
                        showCreateDialog = false
                        menuTask = created.name
                        Toast.makeText(this, "已创建「" + created.name + "」（" + scaffold.label + "）", Toast.LENGTH_SHORT).show()
                        ScriptRunner.appendLog("已创建任务: " + created.name + "（脚手架：" + scaffold.label + "）")
                        tasks = TaskManager.listTasks(this)
                    }
                }
            )
        }

        menuTask?.let { name ->
            TaskMenuDialog(
                taskName = name,
                onDismiss = { menuTask = null },
                onEditScript = {
                    editTask(name)
                },
                onRename = { renamingTask = name },
                onDelete = { deletingTask = name },
                onManageAssets = {
                    assetsTask = name
                    assets = TaskManager.imageInfos(this, name)
                },
                onHistory = {
                    historyTask = name
                    historyEntries = RunHistory.read(this, name)
                },
                onExportZip = {
                    exportZip(name)
                },
                onLoop = {
                    openLoopSettings(name)
                },
                onSchedule = {
                    scheduleEditing = name
                    currentSchedule = ScheduleManager.scheduleOf(this, name)
                    scheduleType = currentSchedule?.type ?: ScheduleManager.Type.ONCE_AFTER_MINUTES
                    scheduleValue = currentSchedule?.let { s ->
                        when (s.type) {
                            ScheduleManager.Type.ONCE_AFTER_MINUTES -> s.delayMinutes.toString()
                            ScheduleManager.Type.EVERY_MINUTES -> s.intervalMinutes.toString()
                            else -> "%02d:%02d".format(s.hour, s.minute)
                        }
                    } ?: "1"
                }
            )
        }

        renamingTask?.let { name -> RenameTaskDialog(name) }
        deletingTask?.let { name ->
            AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
                onDismissRequest = { if (!taskMutationBusy) deletingTask = null },
                title = { Text("删除任务") },
                text = { Text("确定删除「$name」？脚本、素材、录制范围图和运行历史都会删除，定时任务也会取消。此操作无法撤销。") },
                confirmButton = {
                    TextButton(enabled = !taskMutationBusy, onClick = {
                        performTaskChange({ TaskManager.deleteTask(this, name) }, "已删除任务「$name」") { deletingTask = null; menuTask = null }
                    }) { Text(if (taskMutationBusy) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(enabled = !taskMutationBusy, onClick = { deletingTask = null }) { Text("取消") } }
            )
        }

        if (showClearTasksDialog) {
            AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
                onDismissRequest = { if (!taskMutationBusy) showClearTasksDialog = false },
                title = { Text("清空全部任务") },
                text = { Text("确定删除全部 ${tasks.size} 个任务？脚本、素材、录制范围图和运行历史都会删除，定时任务也会取消。此操作无法撤销。") },
                confirmButton = {
                    TextButton(enabled = !taskMutationBusy, onClick = {
                        performTaskChange({ TaskManager.deleteAllTasks(this) }, "已清空全部任务") {
                            showClearTasksDialog = false
                        }
                    }) { Text(if (taskMutationBusy) "正在清空…" else "清空", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(enabled = !taskMutationBusy, onClick = { showClearTasksDialog = false }) { Text("取消") }
                }
            )
        }

        assetsTask?.let { name ->
            AssetsDialog(
                taskName = name,
                images = assets,
                onDismiss = { assetsTask = null },
                onRefresh = { assets = TaskManager.imageInfos(this, name) },
                onImport = {
                    importImageLauncher.launch(arrayOf("image/*"))
                },
                onHistory = { openAssetsHistory(name) },
                onPreview = { info -> loadAssetPreview(info) }
            )
        }

        previewAsset?.let { info ->
            AssetPreviewDialog(
                info = info,
                bitmap = previewBitmap,
                onCrop = { cropAsset(info) },
                onDismiss = {
                    previewLoadJob?.cancel()
                    previewAsset = null
                    previewBitmap = null
                }
            )
        }

        deletingAsset?.let { info ->
            AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { deletingAsset = null }, title = { Text("删除素材") },
                text = { Text("确定删除「${info.file.name}」？引用这张图片的脚本可能无法运行。此操作无法撤销。") },
                confirmButton = { TextButton(onClick = {
                    if (TaskManager.deleteImage(info.file)) {
                        assetsTask?.let { assets = TaskManager.imageInfos(this@MainActivity, it) }
                        previewLoadJob?.cancel()
                        previewAsset = null
                        previewBitmap = null
                        deletingAsset = null
                    } else Toast.makeText(this@MainActivity, "删除失败，请重试", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deletingAsset = null }) { Text("取消") } })
        }
        renameAsset?.let { info ->
            var name by remember(info.file) { mutableStateOf(info.file.name) }
            if (renamePreview == null) {
                RenameAssetDialog(
                    info = info,
                    name = name,
                    onNameChange = { name = it },
                    onDismiss = { renameAsset = null },
                    onConfirm = { newName ->
                        val task = assetsTask ?: return@RenameAssetDialog
                        val preview = TaskManager.previewImageRename(this, task, info.file, newName)
                        if (preview == null) {
                            Toast.makeText(this, "重命名失败（名称非法、已存在或未修改）", Toast.LENGTH_SHORT).show()
                        } else {
                            // 确认前只预览，保留原输入以便返回修改。
                            renamePreview = preview
                        }
                    }
                )
            }
        }

        renamePreview?.let { preview ->
            val task = assetsTask
            RenameRefPreviewDialog(
                preview = preview,
                onDismiss = { renamePreview = null },
                onConfirm = {
                    renamePreview = null
                    renameAsset = null
                    if (task == null) return@RenameRefPreviewDialog
                    val renamed = TaskManager.renameImageWithScript(preview)
                    if (renamed != null) {
                        val synced = preview.plan?.replacementCount ?: 0
                        val tip = if (synced > 0) "已重命名为 ${renamed.name}，同步脚本 $synced 处引用"
                        else "已重命名为 ${renamed.name}"
                        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
                        ScriptRunner.appendLog(tip)
                        assets = TaskManager.imageInfos(this, task)
                        assets.firstOrNull { it.file.name == renamed.name }?.let { loadAssetPreview(it) }
                    } else {
                        Toast.makeText(this, "重命名失败（名称非法或已存在）", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        historyTask?.let { name ->
            RunHistoryDialog(
                taskName = name,
                entries = historyEntries,
                onDismiss = { historyTask = null },
                onClear = {
                    RunHistory.clear(this, name)
                    historyEntries = emptyList()
                    Toast.makeText(this, "已清空运行历史", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (keepAliveDialog) {
            KeepAliveDialog(
                onDismiss = { keepAliveDialog = false },
                onOpenAppSettings = {
                    keepAliveDialog = false
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + packageName))
                    )
                }
            )
        }

        if (captureDialog) {
            CaptureChannelDialog(
                onDismiss = { captureDialog = false },
                onSelect = { mode -> applyCaptureMode(mode) }
            )
        }

        loopEditing?.let { name ->
            LoopDialog(
                taskName = name,
                mode = loopMode,
                count = loopCountValue,
                duration = loopDurationValue,
                durationUnit = loopDurationUnit,
                interval = loopIntervalValue,
                onModeChange = { loopMode = it },
                onCountChange = { loopCountValue = it },
                onDurationChange = { loopDurationValue = it },
                onDurationUnitChange = { loopDurationUnit = it },
                onIntervalChange = { loopIntervalValue = it },
                onDismiss = { loopEditing = null },
                onSave = { saveLoopSettings(name) }
            )
        }

        scheduleEditing?.let { name ->
            ScheduleDialog(
                taskName = name,
                current = currentSchedule,
                type = scheduleType,
                value = scheduleValue,
                onTypeChange = { t ->
                    scheduleType = t
                    scheduleValue = when (t) {
                        ScheduleManager.Type.DAILY_AT -> "08:00"
                        ScheduleManager.Type.EVERY_MINUTES -> "30"
                        else -> "1"
                    }
                },
                onValueChange = { scheduleValue = it },
                onDismiss = { scheduleEditing = null },
                onSave = {
                    val schedule = buildSchedule(name)
                    if (schedule == null) {
                        Toast.makeText(this, "参数不合法", Toast.LENGTH_SHORT).show()
                    } else if (ScheduleManager.upsert(this, schedule)) {
                        Toast.makeText(this, "已设置：${schedule.describe()}", Toast.LENGTH_LONG).show()
                        scheduleEditing = null
                    } else {
                        Toast.makeText(this, "设置失败", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancelSchedule = {
                    ScheduleManager.remove(this, name)
                    Toast.makeText(this, "已取消定时", Toast.LENGTH_SHORT).show()
                    scheduleEditing = null
                }
            )
        }

        assetsHistoryTask?.let { name ->
            AssetsHistoryDialog(
                entries = assetsHistory,
                busy = assetsHistoryBusy,
                error = assetsHistoryError,
                onDismiss = { assetsHistoryTask = null },
                onMoveBack = { moveHistoryImageBack(name, it) }
            )
        }
    }

    /** 任务行：名称、定时状态与详情入口；执行操作统一进入悬浮面板。 */
    @Composable
    private fun TaskRow(task: TaskManager.TaskInfo, runState: ScriptRunner.RunState) {
        val schedule = schedules[task.name]
        DesignRow(task.name,
            schedule?.describe() ?: taskSubtitles[task.name]?.ifBlank { "脚本任务 · 待运行" } ?: "脚本任务 · 待运行",
            if (schedule != null) R.drawable.ic_clock else R.drawable.ic_code,
            enabled = !taskMutationBusy && !taskTransferBusy,
            trailing = {
                if (runState.running && runState.taskName == task.name) StatusBadge("运行中")
                else if (schedule != null) StatusBadge("已定时", warning = true)
                else AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(16.dp))
            }
        ) { menuTask = task.name }
    }


    private fun openFloatingTasks() {
        if (!FloatyManager.hasOverlayPermission(this)) {
            FloatyManager.requestOverlayPermission(this)
            return
        }
        if (!ScriptRunner.launcherEnabled.value) ScriptRunner.toggleLauncher(this)
        if (!FloatyManager.showTaskPanel(this)) Toast.makeText(this, "无法打开悬浮面板，请重新开启悬浮球", Toast.LENGTH_SHORT).show()
    }

    private fun editTask(name: String) {
        val err = ScriptRunner.lastError.value?.takeIf { it.taskName == name }
        startActivity(Intent(this, ScriptEditorActivity::class.java)
            .putExtra(ScriptEditorActivity.EXTRA_TASK, name)
            .putExtra(ScriptEditorActivity.EXTRA_ERROR_LINE, err?.line ?: 0)
            .putExtra(ScriptEditorActivity.EXTRA_ERROR_MSG, err?.message))
    }

    private fun editHistoryError(taskName: String, entry: RunHistory.Entry) {
        val source = listOfNotNull(entry.message, entry.stack).joinToString("\n")
        val line = Regex("task\\.js:(\\d+)(?::\\d+)?").find(source)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        startActivity(Intent(this, ScriptEditorActivity::class.java)
            .putExtra(ScriptEditorActivity.EXTRA_TASK, taskName)
            .putExtra(ScriptEditorActivity.EXTRA_ERROR_LINE, line)
            .putExtra(ScriptEditorActivity.EXTRA_ERROR_MSG, entry.message))
    }

    /** 任务操作菜单：编辑脚本 / 循环执行 / 定时任务 / 模板截取 / 素材管理 */
    @Composable
    private fun TaskMenuDialog(
        taskName: String, onDismiss: () -> Unit, onEditScript: () -> Unit, onRename: () -> Unit,
        onDelete: () -> Unit, onManageAssets: () -> Unit, onLoop: () -> Unit,
        onSchedule: () -> Unit, onExportZip: () -> Unit, onHistory: () -> Unit
    ) {
        val runState by ScriptRunner.state.collectAsState()
        val busy = runState.running || RecordingService.active || taskMutationBusy || taskTransferBusy
        var showMenu by remember { mutableStateOf(false) }
        var latestRun by remember(taskName) { mutableStateOf<RunHistory.Entry?>(null) }
        LaunchedEffect(taskName, runState.running) {
            latestRun = withContext(Dispatchers.IO) { RunHistory.read(this@MainActivity, taskName).firstOrNull() }
        }
        val loop = remember(taskName, taskMetadataVersion) { TaskManager.readConfig(this@MainActivity, taskName).loop.describe() }
        val visual = remember(taskName, taskMetadataVersion) {
            com.forest.android.assistant.core.flow.FlowCompiler.isVisual(this@MainActivity, taskName)
        }
        val taskType = if (visual) "流程任务" else "脚本任务"
        DetailPage(taskName, taskType + (latestRun?.let { " · 最近运行" + it.outcome.label } ?: " · 尚未运行"), onDismiss,
            actions = { IconButton(onClick = { showMenu = true }) { AppIcon(R.drawable.ic_more, "任务操作") } }) {
            SectionLabel("编辑与执行")
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow(
                    "编辑脚本",
                    if (visual) "task.js · 保存后转为普通脚本任务" else "task.js · JavaScript",
                    R.drawable.ic_code,
                    enabled = !runState.running,
                    onClick = onEditScript
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("循环执行", loop, R.drawable.ic_clock, enabled = !runState.running, onClick = onLoop)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("定时运行", schedules[taskName]?.describe() ?: "尚未设置运行时间", R.drawable.ic_clock, onClick = onSchedule)
            }
            SectionLabel("素材与记录")
            WorkCard(Modifier.fillMaxWidth()) {
                ToolRow("素材管理", "预览、裁剪与整理任务图片", R.drawable.ic_tool_shot, onManageAssets)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToolRow("运行历史", "查看每次运行结果与耗时", R.drawable.ic_tool_log, onHistory)
            }
            SectionLabel("其他操作")
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow("重命名任务", "保留脚本、素材及定时设置", R.drawable.ic_code, enabled = !busy, onClick = onRename)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("导出任务 ZIP", "打包脚本、配置和素材", R.drawable.ic_export, enabled = !taskTransferBusy, onClick = onExportZip)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("删除任务", "删除相关记录与素材", R.drawable.ic_stop, enabled = !busy, onClick = onDelete)
            }
        }
        if (showMenu) AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { showMenu = false }, title = { Text("任务操作") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    buildList {
                        add(Triple(
                            if (visual) "编辑脚本并转为普通任务" else "编辑脚本",
                            R.drawable.ic_code,
                            onEditScript
                        ))
                        add(Triple("循环执行", R.drawable.ic_clock, onLoop))
                        add(Triple("定时运行", R.drawable.ic_clock, onSchedule))
                        add(Triple("素材管理", R.drawable.ic_tool_shot, onManageAssets))
                        add(Triple("运行历史", R.drawable.ic_tool_log, onHistory))
                        add(Triple("重命名任务", R.drawable.ic_code, onRename))
                        add(Triple("导出任务 ZIP", R.drawable.ic_export, onExportZip))
                        add(Triple("删除任务", R.drawable.ic_stop, onDelete))
                    }.forEach { (label, icon, action) ->
                        DesignRow(label, "", icon, enabled = !busy) { showMenu = false; action() }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showMenu = false }) { Text("关闭") } })
    }

    /** 从任务配置加载循环设置；旧任务没有 mode 时沿用原有执行次数。 */
    private fun openLoopSettings(taskName: String) {
        val loop = TaskManager.readConfig(this, taskName).loop
        loopMode = loop.mode
        loopCountValue = loop.count.toString()
        loopDurationUnit = when {
            loop.durationSec % 3600L == 0L -> DurationUnit.HOUR
            loop.durationSec % 60L == 0L -> DurationUnit.MINUTE
            else -> DurationUnit.SECOND
        }
        loopDurationValue = (loop.durationSec / loopDurationUnit.seconds).toString()
        loopIntervalValue = loop.intervalSec.toString()
        loopEditing = taskName
    }

    /** 校验界面输入后更新 loop，保留任务的脚本、素材及其他配置字段。 */
    private fun saveLoopSettings(taskName: String) {
        val old = TaskManager.readConfig(this, taskName).loop
        val count = if (loopMode == TaskLoop.Mode.COUNT) loopCountValue.trim().toIntOrNull() else old.count
        val amount = loopDurationValue.trim().toLongOrNull()
        val duration = if (loopMode == TaskLoop.Mode.DURATION)
            amount?.takeIf { it in 1L..(Int.MAX_VALUE.toLong() / loopDurationUnit.seconds) }
                ?.times(loopDurationUnit.seconds)
        else old.durationSec
        val interval = loopIntervalValue.trim().toIntOrNull()
        if (count == null || count < 1 || duration == null || interval == null || interval < 0) {
            Toast.makeText(this, "请输入有效的次数、时长和间隔", Toast.LENGTH_SHORT).show()
            return
        }
        val loop = TaskLoop(loopMode, count, duration, interval)
        TaskManager.updateLoop(this, taskName, loop)
            .onSuccess {
                loopEditing = null
                taskMetadataVersion++
                Toast.makeText(this, "已设置循环：${loop.describe()}", Toast.LENGTH_SHORT).show()
            }
            .onFailure { Toast.makeText(this, it.message ?: "循环设置保存失败", Toast.LENGTH_LONG).show() }
    }

    /** 整个任务循环设置：每轮重新运行 task.js，间隔只发生在轮次之间。 */
    @Composable
    private fun LoopDialog(
        taskName: String, mode: TaskLoop.Mode, count: String, duration: String, durationUnit: DurationUnit,
        interval: String, onModeChange: (TaskLoop.Mode) -> Unit, onCountChange: (String) -> Unit,
        onDurationChange: (String) -> Unit, onDurationUnitChange: (DurationUnit) -> Unit,
        onIntervalChange: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit
    ) {
        DetailPage("循环执行", taskName, onDismiss, footer = { PrimaryAction("保存循环设置", onClick = onSave) }) {
            Text("每轮从头执行整个任务；出错或按停止键时结束。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceCard("指定次数", "运行固定次数后结束", mode == TaskLoop.Mode.COUNT) { onModeChange(TaskLoop.Mode.COUNT) }
                ChoiceCard("指定执行时间", "到时中断当前轮", mode == TaskLoop.Mode.DURATION) { onModeChange(TaskLoop.Mode.DURATION) }
                ChoiceCard("直到手动停止", "持续运行，手动结束", mode == TaskLoop.Mode.UNTIL_STOPPED) { onModeChange(TaskLoop.Mode.UNTIL_STOPPED) }
            }
            SectionLabel("运行参数")
            WorkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mode == TaskLoop.Mode.COUNT) OutlinedTextField(count, onCountChange, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("执行次数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (mode == TaskLoop.Mode.DURATION) {
                        OutlinedTextField(duration, onDurationChange, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("执行时间") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DurationUnit.entries.forEach { unit ->
                                FilterChip(durationUnit == unit, { onDurationUnitChange(unit) }, label = { Text(unit.label) })
                            }
                        }
                    }
                    if (mode == TaskLoop.Mode.UNTIL_STOPPED) Text("运行到按停止键为止。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(interval, onIntervalChange, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("两轮间隔 · 秒") }, supportingText = { Text("0 表示完成后立即开始下一轮") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        }
    }

    /** 把对话框输入解析为定时设置；参数非法返回 null */
    private fun buildSchedule(taskName: String): ScheduleManager.Schedule? = when (scheduleType) {
        ScheduleManager.Type.ONCE_AFTER_MINUTES -> {
            val m = scheduleValue.trim().toIntOrNull() ?: return null
            if (m <= 0) null
            else ScheduleManager.Schedule(taskName, scheduleType, delayMinutes = m)
        }

        ScheduleManager.Type.EVERY_MINUTES -> {
            val m = scheduleValue.trim().toIntOrNull() ?: return null
            if (m < 15) null
            else ScheduleManager.Schedule(taskName, scheduleType, intervalMinutes = m)
        }

        ScheduleManager.Type.DAILY_AT -> {
            val parts = scheduleValue.trim().split(":")
            val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val min = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
            if (h !in 0..23 || min !in 0..59) null
            else ScheduleManager.Schedule(taskName, scheduleType, hour = h, minute = min)
        }
    }

    /** 定时任务设置对话框：选类型 + 填参数，可保存或取消定时 */
    @Composable
    private fun ScheduleDialog(
        taskName: String, current: ScheduleManager.Schedule?, type: ScheduleManager.Type, value: String,
        onTypeChange: (ScheduleManager.Type) -> Unit, onValueChange: (String) -> Unit,
        onDismiss: () -> Unit, onSave: () -> Unit, onCancelSchedule: () -> Unit
    ) {
        DetailPage("定时运行", taskName, onDismiss, footer = { PrimaryAction("保存定时设置", onClick = onSave) }) {
            Text(current?.let { "当前设置：${it.describe()}" } ?: "当前尚未设置定时",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.TERTIARY))
            SectionLabel("运行频率")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ScheduleManager.Type.ONCE_AFTER_MINUTES to "一次", ScheduleManager.Type.EVERY_MINUTES to "间隔",
                    ScheduleManager.Type.DAILY_AT to "每天").forEach { (option, label) ->
                    FilterChip(type == option, { onTypeChange(option) }, label = { Text(label) })
                }
            }
            WorkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(when (type) {
                            ScheduleManager.Type.ONCE_AFTER_MINUTES -> "多少分钟后运行一次"
                            ScheduleManager.Type.EVERY_MINUTES -> "每隔多少分钟（至少 15）"
                            ScheduleManager.Type.DAILY_AT -> "每天几点 · HH:mm"
                        }) }, keyboardOptions = KeyboardOptions(keyboardType = if (type == ScheduleManager.Type.DAILY_AT) KeyboardType.Text else KeyboardType.Number))
                    Text(when (type) {
                        ScheduleManager.Type.ONCE_AFTER_MINUTES -> "到点自动运行一次。"
                        ScheduleManager.Type.EVERY_MINUTES -> "首次在设定的间隔后运行。"
                        ScheduleManager.Type.DAILY_AT -> "每天该时刻自动运行。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (current != null) TextButton(onClick = onCancelSchedule) { Text("取消当前定时", color = MaterialTheme.colorScheme.error) }
        }
    }

    /** 素材管理：列出任务 images/ 下的图片，支持预览、裁剪与整理。 */
    @Composable
    private fun AssetsDialog(
        taskName: String, images: List<TaskManager.ImageInfo>, onDismiss: () -> Unit,
        onRefresh: () -> Unit, onImport: () -> Unit, onHistory: () -> Unit,
        onPreview: (TaskManager.ImageInfo) -> Unit
    ) {
        DetailPage("素材管理", taskName, onDismiss, actions = { TextButton(onClick = onImport) { Text("导入") } }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("任务图片", style = MaterialTheme.typography.titleMedium)
                Text("${images.size} 张图片", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (images.isEmpty()) EmptyState("还没有素材", "从相册导入图片，或使用悬浮工具截取模板。", R.drawable.ic_tool_shot, "从相册导入", onImport)
            else WorkCard(Modifier.fillMaxWidth()) {
                images.forEachIndexed { index, info ->
                    Row(Modifier.fillMaxWidth().clickable { onPreview(info) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetThumbnail(info.file, info.file.lastModified(), info.file.length())
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(info.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(info.dimensionText() + " · " + info.sizeText(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(16.dp))
                    }
                    if (index < images.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            SectionLabel("素材工具")
            WorkCard(Modifier.fillMaxWidth()) {
                ToolRow("从相册导入", "选择图片并添加到当前任务", R.drawable.ic_import, onImport)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToolRow("历史素材", "查看裁剪前保留的原图", R.drawable.ic_clock, onHistory)
            }
            TextButton(onClick = onRefresh) { Text("刷新素材") }
        }
    }

    /** 历史素材仅提供移回操作，不提供预览、裁剪、重命名或删除入口。 */
    @Composable
    private fun AssetsHistoryDialog(entries: List<ImageHistory.Entry>, busy: Boolean, error: String?,
        onDismiss: () -> Unit, onMoveBack: (ImageHistory.Entry) -> Unit) {
        DetailPage("历史素材", "裁剪前保留的原始图片", { if (!busy) onDismiss() }) {
            Text("移回后恢复原文件名，并直接覆盖当前同名素材。",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.TERTIARY))
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (entries.isEmpty() && !busy && error == null) EmptyState("暂无历史素材", "裁剪前的原图会保存在这里。", R.drawable.ic_clock)
            if (entries.isNotEmpty()) {
                SectionLabel("已归档版本")
                WorkCard(Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { index, entry ->
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.originalName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(entry.archivedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onMoveBack(entry) }, enabled = !busy) { Text("移回素材") }
                        }
                        if (index < entries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    /** 素材预览：显示图片与尺寸/大小信息 */
    private fun loadAssetPreview(info: TaskManager.ImageInfo) {
        previewLoadJob?.cancel()
        previewLoadJob = lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                try {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(info.file.absolutePath, bounds)
                    var scale = 1
                    while (bounds.outWidth / scale > 720 || bounds.outHeight / scale > 720) scale *= 2
                    android.graphics.BitmapFactory.decodeFile(
                        info.file.absolutePath,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                    )
                } catch (_: Exception) {
                    null
                }
            }
            if (previewLoadJob?.isActive == true && !isFinishing && !isDestroyed) {
                previewBitmap = decoded
                previewAsset = info
            }
        }
    }

    @Composable
    private fun AssetPreviewDialog(info: TaskManager.ImageInfo, bitmap: android.graphics.Bitmap?,
        onCrop: () -> Unit, onDismiss: () -> Unit) {
        DetailPage("图片详情", info.file.name, onDismiss) {
            Text("图片预览", style = MaterialTheme.typography.titleMedium)
            WorkCard(Modifier.fillMaxWidth()) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = info.file.name,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp).padding(16.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                else Text("无法解码该图片，请检查文件是否有效。", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall)
            }
            SectionLabel("文件信息")
            WorkCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(info.file.name, style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(info.dimensionText() + " · " + info.sizeText(), style = MaterialTheme.typography.bodySmall)
                    Text("images/${info.file.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionLabel("图片操作")
            val busy = ScriptRunner.state.collectAsState().value.running || RecordingService.active
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow("裁剪图片", "原图将保存到历史素材", R.drawable.ic_tool_region, enabled = bitmap != null && !busy, onClick = onCrop)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("重命名图片", "预览并同步修改脚本引用", R.drawable.ic_code, enabled = !busy) { renameAsset = info }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("删除图片", "移除当前图片", R.drawable.ic_stop, enabled = !busy) { deletingAsset = info }
            }
        }
    }

    /** 素材重命名对话框 */
    @Composable
    private fun RenameAssetDialog(
        info: TaskManager.ImageInfo,
        name: String,
        onNameChange: (String) -> Unit,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit
    ) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = onDismiss,
            title = { Text("重命名素材") },
            text = {
                Column {
                    Text(info.dimensionText() + " · " + info.sizeText(), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }

    /**
     * 素材重命名的脚本引用预览：只列出 task.js 中需要修改的行（旧 → 新）。
     * 无引用时仅提示「不会修改脚本」，确认后直接改名。
     */
    @Composable
    private fun RenameRefPreviewDialog(
        preview: TaskManager.ImageRenamePreview,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit
    ) {
        val plan = preview.plan?.takeIf { it.hasChanges }
        val colors = MaterialTheme.colorScheme
        val buttonShape = RoundedCornerShape(11.dp)
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = colors.surface
            ) {
                Column {
                    Column(
                        Modifier.padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("脚本引用确认", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (plan != null) "图片改名时一并更新 task.js" else "确认图片的新名称",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (plan == null) {
                            Text(
                                "脚本 task.js 中没有引用「${preview.file.name}」。确认后改名为「${preview.newName}」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "脚本中有 ${plan.replacementCount} 处引用，涉及 ${plan.changedLineCount} 行。确认后一并修改。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(13.dp),
                                color = Color(AppPalette.INVERSE_SURFACE),
                                contentColor = Color(AppPalette.INVERSE_ON_SURFACE)
                            ) {
                                val codeStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp
                                )
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    plan.changes.forEach { change ->
                                        Column {
                                            val count = if (change.replacements > 1) "（${change.replacements} 处）" else ""
                                            Text("第 ${change.lineNumber} 行$count", style = codeStyle)
                                            Text("- ${change.before.trim()}", style = codeStyle, color = Color(0xFFF1A9A0))
                                            Text("+ ${change.after.trim()}", style = codeStyle, color = Color(0xFFA8DBC0))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = buttonShape,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) { Text("返回") }
                        Button(
                            onClick = onConfirm,
                            shape = buttonShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) { Text(if (plan != null) "改名并同步脚本" else "改名") }
                    }
                }
            }
        }
    }

    /** 三项权限纵向显示为状态卡，状态底色与文字同步反映授权情况。 */
    @Composable
    private fun PermissionCard() {
        WorkCard(Modifier.fillMaxWidth()) {
            DesignRow("无障碍服务", "点击、滑动与手势", R.drawable.ic_permission_gesture,
                trailing = { StatusBadge(if (a11yEnabled) "已开启" else "未开启", warning = !a11yEnabled) }) {
                if (a11yEnabled) keepAliveDialog = true
                else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("悬浮窗", "在其他应用上显示控制面板", R.drawable.ic_floaty,
                trailing = { StatusBadge(if (overlayGranted) "已授权" else "未授权", warning = !overlayGranted) }) {
                if (overlayGranted) mainTab = 3 else FloatyManager.requestOverlayPermission(this@MainActivity)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("截屏能力", ScreenCapture.mode(this@MainActivity).label, R.drawable.ic_tool_shot,
                trailing = { StatusBadge(if (captureReady) "可用" else "未就绪", warning = !captureReady) }) { openCaptureDialog() }
        }
    }

    /** 工具行：名称 + 用途 + 尾部箭头 */
    @Composable
    private fun ToolRow(title: String, desc: String, icon: Int = R.drawable.ic_nav_tools, onClick: () -> Unit) {
        DesignRow(title, desc, icon, onClick = onClick)
    }

    /** 任务列表区：标题行（数量 + 导入/导出全部 + 新建） + 列表 */
    @Composable
    private fun TaskWorkspace(runState: ScriptRunner.RunState) {
        var groupsSelected by rememberSaveable { mutableStateOf(false) }
        val pages = rememberSaveableStateHolder()
        Column(Modifier.fillMaxSize()) {
            PageHeading(if (groupsSelected) "分组空间" else "任务空间", "把重复的事交给脚本，留时间给自己。")
            Box(Modifier.weight(1f)) {
                pages.SaveableStateProvider(false) {
                    RetainedManagementPage(visible = !groupsSelected) {
                        TaskListSection(runState, Modifier.fillMaxSize(), onSwitchToGroups = { groupsSelected = true })
                    }
                }
                pages.SaveableStateProvider(true) {
                    RetainedManagementPage(visible = groupsSelected) {
                        TaskGroupsPage(lifecycle, Modifier.fillMaxSize(), onSwitchToTasks = { groupsSelected = false })
                    }
                }
            }
        }
    }

    /** 保留两页的组合、数据和滚动状态；未放置的页面不绘制，也不接收点击。 */
    @Composable
    private fun RetainedManagementPage(visible: Boolean, content: @Composable () -> Unit) {
        Layout(modifier = Modifier.fillMaxSize(), content = { Box(Modifier.fillMaxSize()) { content() } }) { children, constraints ->
            val page = children.single().measure(constraints)
            layout(page.width, page.height) { if (visible) page.placeRelative(0, 0) }
        }
    }

    @Composable
    private fun TaskListSection(runState: ScriptRunner.RunState, modifier: Modifier = Modifier, onSwitchToGroups: () -> Unit) {
        Column(modifier) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("全部任务", style = MaterialTheme.typography.titleMedium)
                            Text("${tasks.size} 个任务 · 按名称管理", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showCreateDialog = true }) { AppIcon(R.drawable.ic_add, "新建任务") }
                        IconButton(onClick = { showTransferDialog = true }) { AppIcon(R.drawable.ic_more, "导入与导出") }
                        IconButton(onClick = onSwitchToGroups) { AppIcon(R.drawable.ic_switch_lists, "切换到分组列表") }
                    }
                }
                if (tasks.isEmpty()) item {
                    EmptyState("还没有任务", "用空白、签到或找图模板快速开始，也可以导入已有 ZIP 文件。",
                        R.drawable.ic_nav_tasks, "创建第一个任务") { showCreateDialog = true }
                }
                // 行按需组合，长任务列表不一次性加载所有内容。
                itemsIndexed(tasks, key = { _, task -> task.name }) { index, task ->
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = if (index == 0) 18.dp else 0.dp,
                            topEnd = if (index == 0) 18.dp else 0.dp,
                            bottomStart = if (index == tasks.lastIndex) 18.dp else 0.dp,
                            bottomEnd = if (index == tasks.lastIndex) 18.dp else 0.dp)) {
                        Column {
                            TaskRow(task, runState)
                            if (index < tasks.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                item { Column(Modifier.padding(top = 10.dp, bottom = 10.dp)) { SectionLabel("任务工具") } }
                item {
                    WorkCard(Modifier.fillMaxWidth()) {
                        DesignRow("导入任务", "支持单任务与任务合集 ZIP", R.drawable.ic_import,
                            enabled = !taskTransferBusy) {
                            importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DesignRow("导出全部", "备份任务、素材、配置和分组顺序", R.drawable.ic_export,
                            enabled = tasks.isNotEmpty() && !taskTransferBusy) { exportAllTasks() }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DesignRow("清空全部任务", "删除所有任务及运行记录", R.drawable.ic_clear_tasks,
                            enabled = tasks.isNotEmpty() && !runState.running && !RecordingService.active &&
                                !taskTransferBusy && !taskMutationBusy) { showClearTasksDialog = true }
                    }
                }
            }
        }
    }

    /** 权限页：三项能力与截屏通道设置。 */
    @Composable
    private fun PermissionsTab() {
        Column(Modifier.fillMaxSize()) {
            PageHeading("运行准备", "按需开启权限，清楚知道每项能力的状态。")
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("必需能力")
                PermissionCard()
                Text("先开启权限，再执行依赖该能力的任务；截屏方式需要手动确认并测试。",
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.TERTIARY))
            }
        }
    }

    /** 设置页：悬浮球与截屏通道；屏幕拾取工具和悬浮日志入口统一收在悬浮窗面板中。 */
    @Composable
    private fun SettingsTab(launcherEnabled: Boolean) {
        val webState by LanWebServer.state.collectAsState()
        var showWebEditor by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            PageHeading("应用设置", "让工具在你习惯的位置，按你喜欢的方式工作。")
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("悬浮控制")
                WorkCard(Modifier.fillMaxWidth()) {
                    val toggle: () -> Unit = {
                        if (!overlayGranted) FloatyManager.requestOverlayPermission(this@MainActivity)
                        else ScriptRunner.toggleLauncher(this@MainActivity)
                    }
                    DesignRow("显示悬浮球", "在其他应用中快速启动任务", R.drawable.ic_floaty,
                        trailing = { Switch(checked = launcherEnabled, onCheckedChange = { toggle() },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(AppPalette.SUCCESS))) }, onClick = toggle)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AutoSnapRow()
                }
                SectionLabel("截图与识别")
                WorkCard(Modifier.fillMaxWidth()) {
                    ToolRow("截屏通道设置", ScreenCapture.mode(this@MainActivity).label + " · 点击更换并测试",
                        R.drawable.ic_tool_shot) { openCaptureDialog() }
                }
                SectionLabel("快捷入口")
                WorkCard(Modifier.fillMaxWidth()) {
                    ToolRow("打开悬浮任务面板", "查看任务队列及快捷工具", R.drawable.ic_nav_tasks) { openFloatingTasks() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToolRow(
                        "局域网 Web 编辑",
                        if (webState.running) "服务运行中 · ${webState.url.orEmpty()}"
                        else "通过其他设备的浏览器编辑脚本和流程",
                        R.drawable.ic_code
                    ) { showWebEditor = true }
                }
            }
        }
        if (showWebEditor) {
            LanWebDialog(
                state = webState,
                onStart = { LanWebService.start(this@MainActivity) },
                onStop = { LanWebService.stop(this@MainActivity) },
                onCopy = { url ->
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("局域网编辑地址", url))
                    Toast.makeText(this@MainActivity, "地址已复制", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showWebEditor = false }
            )
        }
    }

    @Composable
    private fun LanWebDialog(
        state: LanWebState,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onCopy: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = onDismiss,
            title = { Text("局域网 Web 编辑") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "启动后，同一局域网内的手机、平板或电脑可通过浏览器编辑当前 App 中的任务。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        color = if (state.running) Color(AppPalette.SECONDARY_CONTAINER)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                if (state.running) "服务运行中" else "服务未启动",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (state.running) Color(AppPalette.SUCCESS)
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                state.url ?: state.error ?: "启动后显示局域网访问地址",
                                Modifier.padding(top = 5.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        "同一局域网内的设备无需令牌即可访问，请仅在可信网络中使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                if (state.running) {
                    TextButton(onClick = onStop) { Text("停止服务") }
                } else {
                    TextButton(onClick = onStart) { Text("启动服务") }
                }
            },
            dismissButton = {
                Row {
                    state.url?.let { url ->
                        TextButton(onClick = { onCopy(url) }) { Text("复制地址") }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        )
    }

    /**
     * 自动贴边开关：拖动松手后是否自动吸到左右边缘（半隐藏）。
     * 状态持久化在 floaty 偏好中，切换后立即作用于当前悬浮球。
     */
    @Composable
    private fun AutoSnapRow() {
        var autoSnap by remember { mutableStateOf(FloatyManager.isAutoSnapEnabled(this@MainActivity)) }
        val apply: (Boolean) -> Unit = { next ->
            autoSnap = next
            FloatyManager.setAutoSnapEnabled(this@MainActivity, next)
        }
        DesignRow("自动贴边", "拖动后吸附到屏幕左右边缘", R.drawable.ic_tool_point,
            trailing = { Switch(checked = autoSnap, onCheckedChange = apply,
                colors = SwitchDefaults.colors(checkedTrackColor = Color(AppPalette.SUCCESS))) }) { apply(!autoSnap) }
    }

    /** 日志页：独立显示实时输出。 */
    @Composable
    private fun LogsTab(logs: List<ScriptRunner.LogEntry>) {
        Column(Modifier.fillMaxSize()) {
            PageHeading("运行日志", "每次执行，都有迹可循。")
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("实时输出", style = MaterialTheme.typography.titleMedium)
                        Text("${logs.size} 条记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (logs.isNotEmpty()) TextButton(onClick = { ScriptRunner.clearLogs() }) { Text("清空") }
                }
                if (logs.isEmpty()) {
                    EmptyState("等待第一条日志", "运行任务后，脚本输出和状态会出现在这里。",
                        R.drawable.ic_tool_log, "去任务空间") { mainTab = 0 }
                } else {
                    WorkCard(Modifier.fillMaxWidth().weight(1f)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge("实时输出")
                            Spacer(Modifier.weight(1f))
                            Text("最近 ${logs.size} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LogPanel(logs, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    private fun PageHeading(title: String, subtitle: String) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("雕雕助手 / AUTOMATION", fontSize = 10.sp, letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold, color = Color(AppPalette.ON_PRIMARY_CONTAINER))
                        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 10.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Image(
                        painter = painterResource(R.drawable.ic_brand_mark),
                        contentDescription = null,
                        modifier = Modifier.size(38.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    /** 保活指引：降低无障碍服务被系统回收的概率（各厂商后台限制差异较大） */
    @Composable
    private fun KeepAliveDialog(onDismiss: () -> Unit, onOpenAppSettings: () -> Unit) {
        androidx.compose.material3.AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = onDismiss,
            title = { Text("保活指引") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    listOf(
                        "① 电池优化：在应用详情中把本应用设为「不受限制」（不优化电池）",
                        "② 自启动/后台运行：允许自启动与后台弹出界面（部分厂商需单独开启）",
                        "③ 最近任务里锁定本应用，避免被一键清理带走",
                        "④ 无障碍服务被系统回收后，本应用会提示并在「设置 → 无障碍」重新开启即可",
                        "⑤ 需要长时间跑图色脚本时，建议常驻前台并保持屏幕常亮"
                    ).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        AccessibilityGuard.openAccessibilitySettings(this@MainActivity)
                    }) { Text("重试开启无障碍") }
                    TextButton(onClick = onOpenAppSettings) { Text("应用设置") }
                }
            }
        )
    }

    /** 运行历史对话框：时间 / 耗时 / 退出原因 / 异常信息与堆栈 */
    @Composable
    private fun RunHistoryDialog(taskName: String, entries: List<RunHistory.Entry>, onDismiss: () -> Unit, onClear: () -> Unit) {
        DetailPage("运行历史", taskName, onDismiss,
            actions = { TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("清空") } }) {
            if (entries.isEmpty()) EmptyState("暂无运行记录", "执行任务后，可以在这里查看结果与耗时。", R.drawable.ic_tool_log)
            else {
                Text("最近运行 · 共 ${entries.size} 次", style = MaterialTheme.typography.titleSmall)
                entries.forEach { entry ->
                    val error = entry.outcome == RunHistory.Outcome.ERROR
                    val stopped = entry.outcome == RunHistory.Outcome.STOPPED
                    WorkCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(if (error || stopped) R.drawable.ic_stop else R.drawable.ic_check, danger = error, warm = stopped)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(entry.outcome.label, style = MaterialTheme.typography.titleSmall)
                                Text("${entry.time} · ${entry.durationText()} · ${entry.source}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusBadge(entry.outcome.label, warning = stopped, error = error)
                        }
                        entry.message?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (error) {
                            entry.stack?.let { Text(it.lineSequence().take(6).joinToString("\n"),
                                Modifier.padding(14.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { editHistoryError(taskName, entry) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("打开脚本并定位") }
                        }
                    }
                }
            }
        }
    }

    /** 导出任务 ZIP，并让用户选择保存位置。 */
    private fun exportZip(taskName: String) {
        if (taskTransferBusy) return
        taskTransferBusy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { TaskZip.exportTask(this@MainActivity, taskName) }
            if (result == null) {
                taskTransferBusy = false
                Toast.makeText(this@MainActivity, "导出失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            requestZipSaveLocation(result.file)
        }
    }

    /** 一键将当前全部任务导出为一个 ZIP，并让用户选择保存位置。 */
    private fun exportAllTasks() {
        if (taskTransferBusy) return
        taskTransferBusy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { TaskZip.exportAll(this@MainActivity) }
            if (result == null) {
                taskTransferBusy = false
                Toast.makeText(this@MainActivity, "导出全部任务失败", Toast.LENGTH_LONG).show()
                return@launch
            }
            requestZipSaveLocation(result.file)
        }
    }

    private fun requestZipSaveLocation(file: File) {
        pendingExportFile = file
        try {
            exportZipLauncher.launch(file.name)
        } catch (e: Exception) {
            file.delete()
            pendingExportFile = null
            taskTransferBusy = false
            Toast.makeText(this, "无法打开保存位置：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 只展示选择，不探测权限或替用户选择通道。 */
    private fun openCaptureDialog() {
        if (captureModeTesting) return
        selectedCaptureMode = ScreenCapture.mode(this)
        captureModeError = null
        captureDialog = true
    }

    /** 确认时先测试真实截图；成功才保存并关闭，失败允许重新选择。 */
    private fun applyCaptureMode(mode: ScreenCapture.Mode) {
        if (mode == ScreenCapture.Mode.UNSELECTED || captureModeTesting) return
        captureModeTesting = true
        captureModeError = null
        lifecycleScope.launch {
            try {
                val result = ScreenCapture.testAndSelectMode(applicationContext, mode)
                captureReady = ScreenCapture.captureAvailableCached(this@MainActivity)
                if (result.success) {
                    captureDialog = false
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                } else {
                    captureModeError = result.message
                }
            } finally {
                captureModeTesting = false
            }
        }
    }

    /** 必须手动选择并确认，不提供自动模式或自动切换。 */
    @Composable
    private fun CaptureChannelDialog(onDismiss: () -> Unit, onSelect: (ScreenCapture.Mode) -> Unit) {
        DetailPage(if (captureModeTesting) "正在测试截屏" else if (captureModeError != null) "截屏测试失败" else "截屏方式",
            "更换截屏能力并测试", { if (!captureModeTesting) onDismiss() },
            footer = { PrimaryAction(if (captureModeTesting) "正在测试…" else if (captureModeError != null) "重试测试" else "确认选择并测试",
                enabled = selectedCaptureMode != ScreenCapture.Mode.UNSELECTED && !captureModeTesting) { onSelect(selectedCaptureMode) } }) {
            Text("当前设置：${ScreenCapture.mode(this@MainActivity).label}。测试成功后才会保存新方式。",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.TERTIARY))
            if (captureModeTesting) {
                WorkCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("正在截取测试画面", style = MaterialTheme.typography.titleMedium)
                        Text("系统授权方式可能会弹出授权提示，请稍候。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                captureModeError?.let {
                    EmptyState("截屏测试未通过", it + "\n原有设置仍然保留，可重试或更换方式。", R.drawable.ic_tool_shot)
                }
                SectionLabel("选择通道")
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(ScreenCapture.Mode.MEDIAPROJECTION, ScreenCapture.Mode.ROOT).forEach { mode ->
                        ChoiceCard(mode.label,
                            if (mode == ScreenCapture.Mode.ROOT) "需要设备已授予超级用户权限。" else "通过系统弹窗授权，可用于找图和 OCR。",
                            selectedCaptureMode == mode) { selectedCaptureMode = mode; captureModeError = null }
                    }
                }
                Text("仅使用所选方式，不会自动切换到另一种方式。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    /** 开关悬浮控制台（可滚动日志面板，需悬浮窗权限） */
    private fun toggleConsole() {
        if (!overlayGranted) {
            FloatyManager.requestOverlayPermission(this)
            return
        }
        ConsoleOverlay.toggle(this)
    }

    /** 打开屏幕拾取工具（需要悬浮窗权限；取色和截图还需截屏授权） */
    private fun showPicker(mode: PickerOverlay.Mode) {
        if (!overlayGranted) {
            FloatyManager.requestOverlayPermission(this)
            return
        }
        if ((mode == PickerOverlay.Mode.COLOR || mode == PickerOverlay.Mode.SCREENSHOT) &&
            ScreenCapture.mode(this) == ScreenCapture.Mode.UNSELECTED
        ) {
            openCaptureDialog()
            return
        }
        if (mode == PickerOverlay.Mode.COLOR && !ScreenCapture.captureAvailableCached(this)) {
            // 取色依赖截屏：先弹授权，成功后再打开拾取层
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { ScreenCapture.requestBlocking() }
                if (ok) {
                    PickerOverlay.show(this@MainActivity, mode)
                } else {
                    Toast.makeText(this@MainActivity, "取色需要截屏授权", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        PickerOverlay.show(this, mode)
    }

    @Composable
    private fun LogPanel(
        logs: List<ScriptRunner.LogEntry>,
        modifier: Modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var followTail by rememberSaveable { mutableStateOf(true) }
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
                    Text(
                        text = "${entry.time}  ${entry.level}  ${entry.msg}",
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when (entry.level) {
                            'E' -> MaterialTheme.colorScheme.error
                            'W' -> MaterialTheme.colorScheme.tertiary
                            'D' -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
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

    @Composable
    private fun RenameTaskDialog(oldName: String) {
        var name by remember(oldName) { mutableStateOf(oldName) }
        val trimmed = name.trim()
        val invalid = trimmed.isBlank() || trimmed.length > 50 || trimmed == "." || trimmed == ".." ||
            trimmed.any { it == '/' || it == '\\' || it.isISOControl() } ||
            tasks.any { it.name == trimmed && it.name != oldName }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { if (!taskMutationBusy) renamingTask = null },
            title = { Text("重命名任务") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("任务名称") }, singleLine = true, enabled = !taskMutationBusy,
                        isError = invalid, supportingText = { Text(if (invalid) "名称非法或与现有任务重复" else "脚本、素材与历史记录会一同保留") })
                    if (schedules.containsKey(oldName)) Text("定时任务将保留原设置，并从改名后重新计时。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !invalid && !taskMutationBusy, onClick = {
                    performTaskChange({ TaskManager.renameTask(this, oldName, trimmed) }, "已改名为「$trimmed」") { renamingTask = null; menuTask = trimmed }
                }) { Text(if (taskMutationBusy) "正在保存…" else "保存") }
            },
            dismissButton = { TextButton(enabled = !taskMutationBusy, onClick = { renamingTask = null }) { Text("取消") } }
        )
    }

    private fun performTaskChange(operation: () -> Result<*>, successMessage: String, onSuccess: () -> Unit) {
        if (taskMutationBusy) return
        taskMutationBusy = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                val snapshot = withContext(Dispatchers.IO) {
                    TaskManager.listTasks(this@MainActivity) to
                        ScheduleManager.load(this@MainActivity)
                            .filter { it.enabled }.associateBy { it.taskName }
                }
                tasks = snapshot.first
                schedules = snapshot.second
                taskMetadataVersion++
                if (result.isSuccess) {
                    ScriptRunner.appendLog(successMessage)
                    Toast.makeText(this@MainActivity, successMessage, Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else Toast.makeText(this@MainActivity, result.exceptionOrNull()?.message ?: "操作失败", Toast.LENGTH_LONG).show()
            } finally {
                taskMutationBusy = false
            }
        }
    }

    @Composable
    private fun CreateTaskDialog(
        onDismiss: () -> Unit,
        onConfirm: (String, TaskManager.Scaffold) -> Unit
    ) {
        var name by rememberSaveable { mutableStateOf("") }
        var scaffold by rememberSaveable { mutableStateOf(TaskManager.Scaffold.BLANK) }
        // APP 只创建脚本任务；兼容旧版保存过的 VISUAL 选中状态。
        val selectedScaffold = scaffold.takeUnless { it == TaskManager.Scaffold.VISUAL } ?: TaskManager.Scaffold.BLANK
        val trimmed = name.trim()
        val nameError = when {
            trimmed.length > 50 -> "名称最多 50 个字符"
            trimmed.contains('/') || trimmed.contains('\\') || trimmed == "." || trimmed == ".." -> "名称不能包含路径符号"
            tasks.any { it.name == trimmed } -> "该名称已存在，请换一个名称"
            else -> null
        }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = onDismiss,
            title = { Text("新建任务") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("任务名称") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = { Text(nameError ?: "使用易于辨认的名称，如：每日签到") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "选择起始模板",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    TaskManager.Scaffold.entries.filterNot { it == TaskManager.Scaffold.VISUAL }.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selectedScaffold == option) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { scaffold = option }
                                .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedScaffold == option, onClick = { scaffold = option })
                            Column(Modifier.weight(1f)) {
                                Text(option.label, style = MaterialTheme.typography.titleSmall)
                                Text(option.desc, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onConfirm(trimmed, selectedScaffold) }, shape = MaterialTheme.shapes.small, enabled = trimmed.isNotBlank() && nameError == null) {
                    Text("创建任务")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }
}
