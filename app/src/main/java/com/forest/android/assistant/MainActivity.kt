package com.forest.android.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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
import com.forest.android.assistant.ui.AppIcon
import com.forest.android.assistant.ui.CaptureModeCard
import com.forest.android.assistant.ui.PermissionStatusCard
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

    private val TAB_TITLES = listOf("任务", "日志", "权限", "设置")

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
        previewAsset = null
        previewBitmap = null
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
                    report.skipped.forEach { ScriptRunner.appendLog("导入跳过: $it", 'W') }
                    val message = buildString {
                        append("成功导入 ${report.imported.size} 个任务")
                        if (report.imported.isNotEmpty()) append("：${report.imported.joinToString("、")}")
                        if (report.skipped.isNotEmpty()) {
                            append("\n\n跳过 ${report.skipped.size} 个：\n")
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
        assetsTask = savedInstanceState?.getString("assets_task")
        assetsTask?.let { assets = TaskManager.imageInfos(this, it) }
        taskTransferBusy = pendingExportFile != null
        mainTab = savedInstanceState?.getInt("main_tab", 0)?.coerceIn(0, 3) ?: 0
        if (intent.getBooleanExtra(EXTRA_SHOW_TASKS, false)) mainTab = 0
        showCreateDialog = savedInstanceState?.getBoolean("create_dialog", false) ?: false
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
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                    modifier = Modifier.height(64.dp)) {
                    val icons = listOf(R.drawable.ic_nav_tasks, R.drawable.ic_tool_log,
                        R.drawable.ic_nav_permissions, R.drawable.ic_nav_settings)
                    TAB_TITLES.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = mainTab == index,
                            onClick = { mainTab = index },
                            icon = { AppIcon(icons[index]) },
                            label = { Text(title) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        ) { padding ->
            // 内容在宽屏居中；各页面分别保存滚动位置。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxHeight()
                    ) {
                        pageStates.SaveableStateProvider(mainTab) {
                            when (mainTab) {
                                0 -> TaskListSection(runState, Modifier.fillMaxSize())
                                1 -> LogsTab(logs)
                                2 -> PermissionsTab()
                                else -> SettingsTab(launcherEnabled)
                            }
                        }
                    }
                }
            }
        }


        if (showCreateDialog) {
            CreateTaskDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name, scaffold ->
                    val created = TaskManager.createTask(this, name, scaffold)
                    if (created == null) {
                        Toast.makeText(this, "创建失败：名称非法或已存在", Toast.LENGTH_SHORT).show()
                    } else {
                        showCreateDialog = false
                        Toast.makeText(this, "已创建「" + created.name + "」（" + scaffold.label + "）", Toast.LENGTH_SHORT).show()
                        ScriptRunner.appendLog("已创建任务: " + created.name + "（脚手架：" + scaffold.label + "）")
                        tasks = TaskManager.listTasks(this)
                        editTask(created.name)
                    }
                }
            )
        }

        menuTask?.let { name ->
            TaskMenuDialog(
                taskName = name,
                onDismiss = { menuTask = null },
                onEditScript = {
                    menuTask = null
                    editTask(name)
                },
                onRename = { menuTask = null; renamingTask = name },
                onDelete = { menuTask = null; deletingTask = name },
                onManageAssets = {
                    menuTask = null
                    assetsTask = name
                    assets = TaskManager.imageInfos(this, name)
                },
                onHistory = {
                    menuTask = null
                    historyTask = name
                    historyEntries = RunHistory.read(this, name)
                },
                onExportZip = {
                    menuTask = null
                    exportZip(name)
                },
                onLoop = {
                    menuTask = null
                    openLoopSettings(name)
                },
                onSchedule = {
                    menuTask = null
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
                onDismissRequest = { if (!taskMutationBusy) deletingTask = null },
                title = { Text("删除任务") },
                text = { Text("确定删除「$name」？脚本、素材、录制范围图和运行历史都会删除，定时任务也会取消。此操作无法撤销。") },
                confirmButton = {
                    TextButton(enabled = !taskMutationBusy, onClick = {
                        performTaskChange({ TaskManager.deleteTask(this, name) }, "已删除任务「$name」") { deletingTask = null }
                    }) { Text(if (taskMutationBusy) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(enabled = !taskMutationBusy, onClick = { deletingTask = null }) { Text("取消") } }
            )
        }

        if (showClearTasksDialog) {
            AlertDialog(
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

        previewAsset?.let { info ->
            AssetPreviewDialog(
                info = info,
                bitmap = previewBitmap,
                onCrop = { cropAsset(info) },
                onDismiss = {
                    previewAsset = null
                    previewBitmap = null
                }
            )
        }

        renameAsset?.let { info ->
            RenameAssetDialog(
                info = info,
                onDismiss = { renameAsset = null },
                onConfirm = { newName ->
                    renameAsset = null
                    val task = assetsTask
                    if (task == null) return@RenameAssetDialog
                    val preview = TaskManager.previewImageRename(this, task, info.file, newName)
                    if (preview == null) {
                        Toast.makeText(this, "重命名失败（名称非法、已存在或未修改）", Toast.LENGTH_SHORT).show()
                    } else {
                        // 先预览需要修改的脚本行，确认后再改文件
                        renamePreview = preview
                    }
                }
            )
        }

        renamePreview?.let { preview ->
            val task = assetsTask
            RenameRefPreviewDialog(
                preview = preview,
                onDismiss = { renamePreview = null },
                onConfirm = {
                    renamePreview = null
                    if (task == null) return@RenameRefPreviewDialog
                    val renamed = TaskManager.renameImageWithScript(preview)
                    if (renamed != null) {
                        val synced = preview.plan?.replacementCount ?: 0
                        val tip = if (synced > 0) "已重命名为 ${renamed.name}，同步脚本 $synced 处引用"
                        else "已重命名为 ${renamed.name}"
                        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
                        ScriptRunner.appendLog(tip)
                        assets = TaskManager.imageInfos(this, task)
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
                onPreview = { info -> loadAssetPreview(info) },
                onCrop = { info -> cropAsset(info) },
                onRename = { info -> renameAsset = info },
                onDelete = { info ->
                    TaskManager.deleteImage(info.file)
                    assets = TaskManager.imageInfos(this, name)
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

    /** 任务行：名称 + 定时状态 + 编辑 + 更多操作；从悬浮窗启动任务。 */
    @Composable
    private fun TaskRow(task: TaskManager.TaskInfo, runState: ScriptRunner.RunState) {
        val schedule = schedules[task.name]
        val canDelete = !runState.running && !RecordingService.active && !taskMutationBusy && !taskTransferBusy
        val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) deletingTask = task.name
            false
        })
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = canDelete,
            backgroundContent = {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                        .padding(end = 24.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        ) {
            WorkCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.name, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            schedule != null -> "定时：${schedule.describe()}"
                            else -> taskSubtitles[task.name]?.ifBlank { "待运行" } ?: "待运行"
                        },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { editTask(task.name) }, enabled = !runState.running,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text("编辑") }
                    IconButton(onClick = { menuTask = task.name }, enabled = !runState.running) {
                        AppIcon(R.drawable.ic_more, "${task.name}的更多操作")
                    }
                }
            }
        }
    }

    private fun editTask(name: String) {
        val err = ScriptRunner.lastError.value?.takeIf { it.taskName == name }
        startActivity(Intent(this, ScriptEditorActivity::class.java)
            .putExtra(ScriptEditorActivity.EXTRA_TASK, name)
            .putExtra(ScriptEditorActivity.EXTRA_ERROR_LINE, err?.line ?: 0)
            .putExtra(ScriptEditorActivity.EXTRA_ERROR_MSG, err?.message))
    }

    /** 任务操作菜单：编辑脚本 / 循环执行 / 定时任务 / 模板截取 / 素材管理 */
    @Composable
    private fun TaskMenuDialog(
        taskName: String,
        onDismiss: () -> Unit,
        onEditScript: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit,
        onManageAssets: () -> Unit,
        onLoop: () -> Unit,
        onSchedule: () -> Unit,
        onExportZip: () -> Unit,
        onHistory: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(taskName) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolRow("编辑脚本", "修改任务脚本", R.drawable.ic_code, onEditScript)
                    ToolRow("重命名", "修改任务名称，保留脚本与素材", R.drawable.ic_code, onRename)
                    ToolRow("删除任务", "删除脚本、素材及相关记录", R.drawable.ic_stop, onDelete)
                    ToolRow("循环执行", remember(taskName, taskMetadataVersion) {
                        TaskManager.readConfig(this@MainActivity, taskName).loop.describe()
                    }, R.drawable.ic_clock, onLoop)
                    ToolRow("定时运行", schedules[taskName]?.describe() ?: "尚未设置运行时间",
                        R.drawable.ic_clock, onSchedule)
                    ToolRow("运行历史", "查看执行结果与耗时", R.drawable.ic_tool_log, onHistory)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ToolRow("素材管理", "预览、裁剪、导入与整理图片", R.drawable.ic_tool_color, onManageAssets)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ToolRow("导出 ZIP", "分享脚本、素材与配置", R.drawable.ic_export, onExportZip)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
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
        taskName: String,
        mode: TaskLoop.Mode,
        count: String,
        duration: String,
        durationUnit: DurationUnit,
        interval: String,
        onModeChange: (TaskLoop.Mode) -> Unit,
        onCountChange: (String) -> Unit,
        onDurationChange: (String) -> Unit,
        onDurationUnitChange: (DurationUnit) -> Unit,
        onIntervalChange: (String) -> Unit,
        onDismiss: () -> Unit,
        onSave: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("循环执行 · $taskName") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("每轮从头执行整个任务；出错或按停止键时结束。",
                        style = MaterialTheme.typography.bodySmall)
                    listOf(
                        TaskLoop.Mode.COUNT to "指定次数",
                        TaskLoop.Mode.DURATION to "指定执行时间",
                        TaskLoop.Mode.UNTIL_STOPPED to "直到手动停止"
                    ).forEach { (option, label) ->
                        Row(Modifier.fillMaxWidth().clickable { onModeChange(option) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == option, onClick = null)
                            Text(label)
                        }
                    }
                    if (mode == TaskLoop.Mode.COUNT) {
                        OutlinedTextField(count, onCountChange, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, label = { Text("执行次数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    if (mode == TaskLoop.Mode.DURATION) {
                        OutlinedTextField(duration, onDurationChange, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, label = { Text("执行时间") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Row {
                            DurationUnit.entries.forEach { unit ->
                                TextButton(onClick = { onDurationUnitChange(unit) }) {
                                    Text(if (durationUnit == unit) "● ${unit.label}" else unit.label)
                                }
                            }
                        }
                        Text("计时从启动任务开始；到时会中断当前轮。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(interval, onIntervalChange, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("两轮间隔（秒）") },
                        supportingText = { Text("0 表示完成后立即开始下一轮") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
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
            if (m <= 0) null
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
        taskName: String,
        current: ScheduleManager.Schedule?,
        type: ScheduleManager.Type,
        value: String,
        onTypeChange: (ScheduleManager.Type) -> Unit,
        onValueChange: (String) -> Unit,
        onDismiss: () -> Unit,
        onSave: () -> Unit,
        onCancelSchedule: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("定时任务 · $taskName") },
            text = {
                Column {
                    Text(
                        current?.let { "当前：${it.describe()}" } ?: "当前：未设置",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(modifier = Modifier.padding(vertical = 8.dp)) {
                        listOf(
                            ScheduleManager.Type.ONCE_AFTER_MINUTES to "一次",
                            ScheduleManager.Type.EVERY_MINUTES to "间隔",
                            ScheduleManager.Type.DAILY_AT to "每天"
                        ).forEach { (t, label) ->
                            TextButton(onClick = { onTypeChange(t) }) {
                                Text(
                                    if (type == t) "● $label" else label,
                                    color = if (type == t) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        label = {
                            Text(
                                when (type) {
                                    ScheduleManager.Type.ONCE_AFTER_MINUTES -> "多少分钟后执行一次"
                                    ScheduleManager.Type.EVERY_MINUTES -> "每隔多少分钟（最少 15）"
                                    ScheduleManager.Type.DAILY_AT -> "每天几点（HH:mm）"
                                }
                            )
                        }
                    )
                    Text(
                        when (type) {
                            ScheduleManager.Type.ONCE_AFTER_MINUTES -> "到点自动运行一次"
                            ScheduleManager.Type.EVERY_MINUTES -> "每隔 N 分钟运行一次，首次在 N 分钟后"
                            ScheduleManager.Type.DAILY_AT -> "每天该时刻自动运行"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
            dismissButton = {
                Row {
                    if (current != null) {
                        TextButton(onClick = onCancelSchedule) { Text("取消定时") }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        )
    }

    /** 素材管理：列出任务 images/ 下的图片，支持预览、裁剪与整理。 */
    @Composable
    private fun AssetsDialog(
        taskName: String,
        images: List<TaskManager.ImageInfo>,
        onDismiss: () -> Unit,
        onRefresh: () -> Unit,
        onImport: () -> Unit,
        onHistory: () -> Unit,
        onPreview: (TaskManager.ImageInfo) -> Unit,
        onCrop: (TaskManager.ImageInfo) -> Unit,
        onRename: (TaskManager.ImageInfo) -> Unit,
        onDelete: (TaskManager.ImageInfo) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("素材 · $taskName") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onImport) { Text("+ 从相册导入") }
                        TextButton(onClick = onHistory) { Text("历史素材") }
                    }
                    if (images.isEmpty()) {
                        Text("images/ 目录暂无图片，可从相册导入素材", style = MaterialTheme.typography.bodySmall)
                    } else {
                        images.forEach { info ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPreview(info) }
                                ) {
                                    Text(info.file.name, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        info.dimensionText() + " · " + info.sizeText() + "（点击预览）",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onCrop(info) }, enabled = info.width > 0 && info.height > 0) { Text("裁剪") }
                                    TextButton(onClick = { onRename(info) }) { Text("重命名") }
                                    TextButton(onClick = { onDelete(info) }) { Text("删除") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }

    /** 历史素材仅提供移回操作，不提供预览、裁剪、重命名或删除入口。 */
    @Composable
    private fun AssetsHistoryDialog(
        entries: List<ImageHistory.Entry>,
        busy: Boolean,
        error: String?,
        onDismiss: () -> Unit,
        onMoveBack: (ImageHistory.Entry) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("历史素材") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("移回后恢复原文件名，直接覆盖当前同名素材。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (busy) Text("正在处理…", modifier = Modifier.padding(vertical = 8.dp))
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp))
                    if (entries.isEmpty() && !busy && error == null) {
                        Text("暂无历史素材，裁剪前的原图会保存在这里。", modifier = Modifier.padding(vertical = 16.dp))
                    }
                    entries.forEach { entry ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(entry.originalName, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry.archivedAt, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { onMoveBack(entry) }, enabled = !busy,
                                modifier = Modifier.align(Alignment.End)) { Text("移回素材") }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } }
        )
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
    private fun AssetPreviewDialog(
        info: TaskManager.ImageInfo,
        bitmap: android.graphics.Bitmap?,
        onCrop: () -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(info.file.name) },
            text = {
                Column {
                    Text(
                        info.dimensionText() + " · " + info.sizeText(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = info.file.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    } else {
                        Text("无法解码该图片（可能不是有效的图片文件）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            dismissButton = { TextButton(onClick = onCrop, enabled = bitmap != null) { Text("裁剪") } }
        )
    }

    /** 素材重命名对话框 */
    @Composable
    private fun RenameAssetDialog(
        info: TaskManager.ImageInfo,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit
    ) {
        var name by remember(info.file.absolutePath) { mutableStateOf(info.file.name) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("重命名素材") },
            text = {
                Column {
                    Text(info.dimensionText() + " · " + info.sizeText(), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
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
        val plan = preview.plan
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("重命名为 ${preview.newName}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (plan == null || !plan.hasChanges) {
                        Text(
                            "脚本 task.js 中没有引用「${preview.file.name}」，只改文件名。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "脚本中有 ${plan.replacementCount} 处引用，涉及 ${plan.changedLineCount} 行（确认后一并修改）：",
                            style = MaterialTheme.typography.bodySmall
                        )
                        plan.changes.forEach { change ->
                            Column(Modifier.padding(top = 10.dp)) {
                                Text(
                                    "第 ${change.lineNumber} 行" + if (change.replacements > 1) "（${change.replacements} 处）" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "- " + change.before.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "+ " + change.after.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(if (plan != null && plan.hasChanges) "改名并同步脚本" else "改名")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }

    /** 分区卡片标题：统一「标题 + 可选说明」的层级，替代散落的裸文本 */
    @Composable
    private fun SectionTitle(title: String, hint: String? = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    /** 三项权限横向显示为等大方卡，边框与状态文字同步反映授权情况。 */
    @Composable
    private fun PermissionCard(maxTileSize: Dp) {
        WorkCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("权限状态", "点击卡片管理权限")
                BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    val tileSize = minOf((maxWidth - 24.dp) / 3, maxTileSize)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        PermissionStatusCard(
                            title = "无障碍",
                            description = "点击、滑动与手势",
                            iconRes = R.drawable.ic_permission_gesture,
                            ready = a11yEnabled,
                            status = if (a11yEnabled) "已开启" else "未开启",
                            actionLabel = if (a11yEnabled) "查看保活设置" else "开启无障碍服务",
                            modifier = Modifier.size(tileSize),
                            onClick = {
                                if (a11yEnabled) keepAliveDialog = true
                                else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )
                        PermissionStatusCard(
                            title = "悬浮窗",
                            description = "在其他应用上显示",
                            iconRes = R.drawable.ic_floaty,
                            ready = overlayGranted,
                            status = if (overlayGranted) "已授权" else "未授权",
                            actionLabel = if (overlayGranted) "查看悬浮窗授权状态" else "授予悬浮窗权限",
                            modifier = Modifier.size(tileSize),
                            onClick = {
                                if (overlayGranted) {
                                    Toast.makeText(this@MainActivity, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
                                } else {
                                    FloatyManager.requestOverlayPermission(this@MainActivity)
                                }
                            }
                        )
                        PermissionStatusCard(
                            title = "截屏",
                            description = "找图、取色与识别",
                            iconRes = R.drawable.ic_capture_system,
                            ready = captureReady,
                            status = if (captureReady) "可用" else "未就绪",
                            actionLabel = "选择截屏方式",
                            modifier = Modifier.size(tileSize),
                            onClick = { openCaptureDialog() }
                        )
                    }
                }
            }
        }
    }

    /** 工具行：名称 + 用途 + 尾部箭头 */
    @Composable
    private fun ToolRow(title: String, desc: String, icon: Int = R.drawable.ic_nav_tools, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AppIcon(icon) }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppIcon(R.drawable.ic_arrow)
        }
    }

    /** 任务列表区：标题行（数量 + 导入/导出全部 + 新建） + 列表 */
    @Composable
    private fun TaskListSection(runState: ScriptRunner.RunState, modifier: Modifier = Modifier) {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("全部任务 · ${tasks.size}", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { showClearTasksDialog = true },
                    enabled = tasks.isNotEmpty() && !runState.running && !RecordingService.active &&
                        !taskTransferBusy && !taskMutationBusy,
                    shape = MaterialTheme.shapes.medium) {
                    AppIcon(R.drawable.ic_clear_tasks, "清空全部任务")
                }
                Spacer(Modifier.width(4.dp))
                FilledTonalIconButton(onClick = {
                    importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }, enabled = !taskTransferBusy, shape = MaterialTheme.shapes.medium) {
                    AppIcon(R.drawable.ic_import, "导入 ZIP")
                }
                Spacer(Modifier.width(4.dp))
                FilledTonalIconButton(onClick = { exportAllTasks() },
                    enabled = tasks.isNotEmpty() && !taskTransferBusy,
                    shape = MaterialTheme.shapes.medium) {
                    AppIcon(R.drawable.ic_export, "导出全部")
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(onClick = { showCreateDialog = true },
                    shape = MaterialTheme.shapes.medium) {
                    AppIcon(R.drawable.ic_add, "新建任务")
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(tasks, key = { it.name }) { task ->
                    TaskRow(task, runState)
                }
                if (tasks.isEmpty()) {
                    item {
                        WorkCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                AppIcon(R.drawable.ic_nav_tasks, modifier = Modifier.size(40.dp))
                                Text("创建你的第一个任务", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                                Text("从空白、签到或找图模板开始，也可导入单任务或合集 ZIP。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 权限页：三项能力与截屏通道设置。 */
    @Composable
    private fun PermissionsTab() {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 为页面标题、分区标题和间距留出空间，限制横屏卡片高度。
            val reservedHeight = (88 * LocalDensity.current.fontScale).dp + 76.dp
            val maxTileSize = (maxHeight - reservedHeight).coerceIn(80.dp, 180.dp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PageHeading("运行准备", "按需开启权限，准备好脚本运行环境。")
                PermissionCard(maxTileSize)
            }
        }
    }

    /** 设置页：悬浮球与截屏通道；屏幕拾取工具和悬浮日志入口统一收在悬浮窗面板中。 */
    @Composable
    private fun SettingsTab(launcherEnabled: Boolean) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeading("应用设置", "管理悬浮球与截屏通道。")
            // 悬浮日志开关：原属于工具页，屏幕工具展示移除后保留在这里；现在可从悬浮窗面板打开
            WorkCard(modifier = Modifier.fillMaxWidth()) {
                ToolRow(
                    if (launcherEnabled) "隐藏悬浮球" else "显示悬浮球",
                    if (launcherEnabled) "已开启 · 点击后隐藏" else "随时选择任务、控制运行",
                    R.drawable.ic_floaty
                ) {
                    if (!overlayGranted) FloatyManager.requestOverlayPermission(this@MainActivity)
                    else ScriptRunner.toggleLauncher(this@MainActivity)
                }
                // 自动贴边开关：控制拖动松手后是否吸到左右边缘
                AutoSnapRow()
                // 截屏通道设置与悬浮球选项放在同一卡片中
                ToolRow(
                    "截屏通道设置",
                    "系统授权截屏或超级权限截屏 · 使用前请手动选择",
                    R.drawable.ic_tool_shot
                ) { openCaptureDialog() }
            }
        }
    }

    /**
     * 自动贴边开关：拖动松手后是否自动吸到左右边缘（半隐藏）。
     * 状态持久化在 floaty 偏好中，切换后立即作用于当前悬浮球。
     */
    @Composable
    private fun AutoSnapRow() {
        val context = this@MainActivity
        var autoSnap by remember { mutableStateOf(FloatyManager.isAutoSnapEnabled(context)) }
        // 整行点击与 Switch 回调都走这里，避免 Switch 自带回调再取反两次
        val apply: (Boolean) -> Unit = { next ->
            autoSnap = next
            FloatyManager.setAutoSnapEnabled(context, next)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { apply(!autoSnap) }
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_floaty) }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("自动贴边", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (autoSnap) "拖动松手后吸到左右边缘并半隐藏" else "拖动松手后停在原位，仅收回屏内",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoSnap, onCheckedChange = { apply(it) })
        }
    }

    /** 日志页：独立显示实时输出。 */
    @Composable
    private fun LogsTab(logs: List<ScriptRunner.LogEntry>) {
        Column(Modifier.fillMaxSize()) {
            PageHeading("运行日志", "查看脚本输出，快速定位问题。")
            WorkCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("实时输出 · ${logs.size}", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.weight(1f))
                        if (logs.isNotEmpty()) {
                            TextButton(onClick = { ScriptRunner.clearLogs() }) { Text("清空") }
                        }
                    }
                    HorizontalDivider()
                    if (logs.isEmpty()) {
                        Text("暂无输出\n运行任务后，日志会显示在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp))
                    } else {
                        LogPanel(logs, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    private fun PageHeading(title: String, subtitle: String) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }

    /** 保活指引：降低无障碍服务被系统回收的概率（各厂商后台限制差异较大） */
    @Composable
    private fun KeepAliveDialog(onDismiss: () -> Unit, onOpenAppSettings: () -> Unit) {
        androidx.compose.material3.AlertDialog(
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
    private fun RunHistoryDialog(
        taskName: String,
        entries: List<RunHistory.Entry>,
        onDismiss: () -> Unit,
        onClear: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("运行历史 · $taskName") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    if (entries.isEmpty()) {
                        Text("暂无运行记录", style = MaterialTheme.typography.bodySmall)
                    }
                    entries.forEach { e ->
                        val color = when (e.outcome) {
                            RunHistory.Outcome.ERROR -> MaterialTheme.colorScheme.error
                            RunHistory.Outcome.STOPPED -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            e.time + "  " + e.outcome.label + "  " + e.durationText() + "  " + e.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                        e.message?.let { msg ->
                            Text(
                                "    " + msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        e.stack?.let { stack ->
                            // 只展示前几行堆栈，避免对话框过长
                            Text(
                                stack.lineSequence().take(6).joinToString("  ←  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("清空历史") }
            }
        )
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
    private fun CaptureChannelDialog(
        onDismiss: () -> Unit,
        onSelect: (ScreenCapture.Mode) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = { if (!captureModeTesting) onDismiss() },
            title = { Text("选择截屏方式") },
            text = {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val widthLimit = (maxWidth - 12.dp) / 2
                    // 高度由弹窗扣除标题和底部操作后提供，不用屏幕总高度估算。
                    val textSpace = (36 * LocalDensity.current.fontScale).dp + 24.dp
                    val heightLimit = if (constraints.hasBoundedHeight) {
                        (maxHeight - textSpace).coerceAtLeast(112.dp)
                    } else widthLimit
                    val cardSize = minOf(widthLimit, heightLimit)
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(
                            "当前设置：" + ScreenCapture.mode(this@MainActivity).label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                        ) {
                            listOf(
                                ScreenCapture.Mode.MEDIAPROJECTION,
                                ScreenCapture.Mode.ROOT
                            ).forEach { mode ->
                                CaptureModeCard(
                                    mode = mode,
                                    selected = selectedCaptureMode == mode,
                                    onClick = {
                                        if (!captureModeTesting) {
                                            selectedCaptureMode = mode
                                            captureModeError = null
                                        }
                                    },
                                    enabled = !captureModeTesting,
                                    modifier = Modifier.size(cardSize)
                                )
                            }
                        }
                        Text(
                            "仅使用所选方式，不会自动切换。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            // 操作区不参与内容滚动，横屏或大字体下也保留完整按钮。
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    captureModeError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            enabled = !captureModeTesting,
                            onClick = { if (!captureModeTesting) onDismiss() }
                        ) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = selectedCaptureMode != ScreenCapture.Mode.UNSELECTED && !captureModeTesting,
                            onClick = { onSelect(selectedCaptureMode) }
                        ) {
                            if (captureModeTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (captureModeTesting) "正在测试…" else "确认选择")
                        }
                    }
                }
            }
        )
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
                    performTaskChange({ TaskManager.renameTask(this, oldName, trimmed) }, "已改名为「$trimmed」") { renamingTask = null }
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
        val trimmed = name.trim()
        val nameError = when {
            trimmed.length > 50 -> "名称最多 50 个字符"
            trimmed.contains('/') || trimmed.contains('\\') || trimmed == "." || trimmed == ".." -> "名称不能包含路径符号"
            tasks.any { it.name == trimmed } -> "该名称已存在，请换一个名称"
            else -> null
        }
        AlertDialog(
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
                    TaskManager.Scaffold.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (scaffold == option) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { scaffold = option }
                                .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = scaffold == option, onClick = { scaffold = option })
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
                Button(onClick = { onConfirm(trimmed, scaffold) }, enabled = trimmed.isNotBlank() && nameError == null) {
                    Text("创建并编辑")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }
}
