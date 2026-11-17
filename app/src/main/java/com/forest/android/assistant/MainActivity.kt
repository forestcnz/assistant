package com.forest.android.assistant

import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
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
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.ViewModelProvider
import androidx.core.net.toUri
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
import com.forest.android.assistant.core.task.TaskExecutionSettings
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.core.task.TaskLoop
import com.forest.android.assistant.core.task.TaskOrder
import com.forest.android.assistant.core.task.ImageHistory
import com.forest.android.assistant.core.task.TaskZip
import com.forest.android.assistant.core.update.AppUpdateModel
import com.forest.android.assistant.core.update.AppUpdateState
import com.forest.android.assistant.core.update.DownloadPhase
import com.forest.android.assistant.core.web.LanWebServer
import com.forest.android.assistant.core.web.LanWebService
import com.forest.android.assistant.core.web.LanWebState
import androidx.compose.material3.SwitchDefaults
import com.forest.android.assistant.ui.*
import com.forest.android.assistant.ui.AppIcon
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.StartupComic
import com.forest.android.assistant.ui.WorkCard
import com.forest.android.assistant.ui.AppToast as Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    private val appUpdate by lazy { ViewModelProvider(this)[AppUpdateModel::class.java] }

    // 权限状态（页面可见期间每秒轮询刷新）
    private var a11yEnabled by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var captureReady by mutableStateOf(false)
    private var tasks by mutableStateOf(emptyList<TaskManager.TaskInfo>())
    /** 任务列表顺序的版本号，保存拖拽结果时用于检测其他端的修改 */
    private var taskOrderRevision by mutableStateOf(0L)
    private var taskTransferBusy by mutableStateOf(false)
    private var pendingExportFile by mutableStateOf<File?>(null)
    private var showCreateDialog by mutableStateOf(false)
    private var taskMetadataVersion by mutableStateOf(0)



    /** 任务操作菜单/素材管理 */
    private var showTransferDialog by mutableStateOf(false)
    private var showScriptMarket by mutableStateOf(false)
    private var showAppUpdate by mutableStateOf(false)
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
    // 移回历史图片会覆盖同名素材，先保留待确认项。
    private var restoringAsset by mutableStateOf<ImageHistory.Entry?>(null)
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
    private var captureTestResult by mutableStateOf<ScreenCapture.ModeTestResult?>(null)

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

    // 截取结束后只刷新当前任务素材，保持原来的页面返回层级。
    private val templateCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        assetsTask?.let { task -> assets = TaskManager.imageInfos(this, task) }
    }

    private fun captureTemplate(task: String) {
        templateCaptureLauncher.launch(Intent(this, TemplateCaptureActivity::class.java)
            .putExtra(TemplateCaptureActivity.EXTRA_TASK, task))
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
                        if (report.overwritten.isNotEmpty()) {
                            append("\n\n覆盖 ${report.overwritten.size} 个同名任务：${report.overwritten.joinToString("、")}")
                        }
                        if (report.importedGroups.isNotEmpty()) {
                            append("\n\n恢复 ${report.importedGroups.size} 个分组：${report.importedGroups.joinToString("、")}")
                        }
                        if (report.overwrittenGroups.isNotEmpty()) {
                            append("\n\n覆盖 ${report.overwrittenGroups.size} 个分组：${report.overwrittenGroups.joinToString("、")}")
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
        if (savedInstanceState == null) intent.getStringExtra("open_task")?.let { menuTask = it }
        showScriptMarket = savedInstanceState?.getBoolean("script_market") ?: false
        assetsTask = savedInstanceState?.getString("assets_task")
        assetsTask?.let { assets = TaskManager.imageInfos(this, it) }
        taskTransferBusy = pendingExportFile != null
        mainTab = savedInstanceState?.getInt("main_tab", 0)?.coerceIn(0, 3) ?: 0
        if (intent.getBooleanExtra(EXTRA_SHOW_TASKS, false)) mainTab = 0
        if (intent.getBooleanExtra("open_permissions", false)) mainTab = 2
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
                    mutableStateOf(savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SHOW_TASKS, false) && !intent.getBooleanExtra("open_permissions", false))
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
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                appUpdate.state.first { it.official != null }
                appUpdate.check(manual = false)
                appUpdate.state.map { it.downloadPhase }.distinctUntilChanged().collectLatest { phase ->
                    if (phase == DownloadPhase.DOWNLOADING || phase == DownloadPhase.INTERRUPTED) {
                        while (true) {
                            appUpdate.refreshDownload()
                            delay(1000)
                        }
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
                        Triple(
                            TaskManager.listTasks(this@MainActivity),
                            ScheduleManager.load(this@MainActivity)
                                .filter { it.enabled }.associateBy { it.taskName },
                            TaskOrder.read(this@MainActivity).revision
                        )
                    }
                    tasks = snapshot.first
                    schedules = snapshot.second
                    // 顺带同步任务顺序版本，拖拽保存时可发现其他端的修改
                    if (snapshot.third != taskOrderRevision) taskOrderRevision = snapshot.third
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
        if (intent.getBooleanExtra("open_permissions", false)) mainTab = 2
        intent.getStringExtra("open_task")?.let { menuTask = it }
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
        outState.putBoolean("script_market", showScriptMarket)
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
        val updateState by appUpdate.state.collectAsState()

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

        if (showScriptMarket) {
            ScriptMarket(tasks.map { it.name }, onBack = { showScriptMarket = false }, onImported = { name ->
                // 刷新本地任务与详情缓存，导入成功后继续留在脚本市场。
                taskMetadataVersion++
                ScriptRunner.appendLog("已从脚本市场导入任务: $name")
                lifecycleScope.launch {
                    tasks = withContext(Dispatchers.IO) { TaskManager.listTasks(this@MainActivity) }
                }
            }, onOpenTask = { name ->
                showScriptMarket = false
                menuTask = name
                lifecycleScope.launch {
                    tasks = withContext(Dispatchers.IO) { TaskManager.listTasks(this@MainActivity) }
                }
            }, onUpdate = { showAppUpdate = true })
        }

        if (showTransferDialog) TransferPage(tasks.size, taskTransferBusy,
            tasks.isNotEmpty() && !runState.running && !RecordingService.active && !taskTransferBusy && !taskMutationBusy,
            onBack = { showTransferDialog = false },
            onImport = { importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            onExport = { exportAllTasks() }, onClear = { showClearTasksDialog = true })
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
            val canDelete = !runState.running && !RecordingService.active && !taskMutationBusy && !taskTransferBusy
            AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
                onDismissRequest = { if (!taskMutationBusy) deletingTask = null },
                title = { Text("删除任务") },
                text = { Text("确定删除「$name」？脚本、素材、录制范围图和运行历史都会删除，定时任务也会取消。此操作无法撤销。") },
                confirmButton = {
                    TextButton(enabled = canDelete, onClick = {
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
                onMoveBack = { restoringAsset = it }
            )
        }
        if (showAppUpdate) AppUpdatePage(updateState, appUpdate, ::installAppUpdate) { showAppUpdate = false }
        restoringAsset?.let { entry ->
            ConfirmActionSheet("移回历史素材？", "恢复「${entry.originalName}」并覆盖当前同名图片，请确认当前版本不再需要。",
                "覆盖并移回", onDismiss = { restoringAsset = null }, busy = assetsHistoryBusy,
                onConfirm = { restoringAsset = null; assetsHistoryTask?.let { moveHistoryImageBack(it, entry) } })
        }
    }

    /** 任务行：图标可长按拖动；名称、定时状态与详情入口；执行操作统一进入悬浮面板。 */
    @Composable
    private fun TaskRow(task: TaskManager.TaskInfo, runState: ScriptRunner.RunState,
        iconDragModifier: Modifier = Modifier) {
        val schedule = schedules[task.name]
        val enabled = !taskMutationBusy && !taskTransferBusy
        val subtitle = schedule?.describe() ?: taskSubtitles[task.name]?.ifBlank { "脚本任务 · 待运行" } ?: "脚本任务 · 待运行"
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { menuTask = task.name }
            .heightIn(min = 60.dp)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // 图标本身作为拖动区域，长按图标即可调整顺序。
            Box(Modifier.size(40.dp).then(iconDragModifier), contentAlignment = Alignment.Center) {
                val imageTask = task.name.contains("图") || task.name.contains("截")
                val readingTask = task.name.contains("阅读") || task.name.contains("日志")
                val checkTask = task.name.contains("签到") || task.name.contains("领取")
                IconTile(
                    when {
                        schedule != null -> R.drawable.ic_clock
                        imageTask -> R.drawable.ic_tool_shot
                        readingTask -> R.drawable.ic_tool_log
                        checkTask -> R.drawable.ic_check
                        else -> R.drawable.ic_code
                    },
                    warm = schedule != null || imageTask,
                    blue = schedule == null && !imageTask
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(task.name, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f))
                Text(subtitle, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            if (runState.running && runState.taskName == task.name) StatusBadge("运行中")
            else if (schedule != null) StatusBadge(schedule.describe())
            else AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(15.dp))
        }
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
        val config = remember(taskName, taskMetadataVersion) { TaskManager.readConfig(this@MainActivity, taskName) }
        val scheduleNow = schedules[taskName]
        DetailPage("任务详情", taskType + (latestRun?.let { " · 最近运行" + it.outcome.label } ?: " · 尚未运行"), onDismiss,
            compact = true,
            compactBackFrame = true,
            actions = { IconButton(onClick = { showMenu = true }) { AppIcon(R.drawable.ic_more, "任务操作") } }) {
            // 任务摘要 + 三项关键数据，对应设计稿的详情首屏。
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(AppPalette.PRIMARY_CONTAINER), contentColor = Color(AppPalette.ON_SURFACE_VARIANT),
                    shape = RoundedCornerShape(20.dp)) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        AppIcon(R.drawable.ic_code, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 15.dp)) {
                    Text(taskName, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(config.desc.ifBlank { if (visual) "可视化流程任务" else "JavaScript 脚本任务" },
                        fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(taskType)
                Spacer(Modifier.weight(1f))
                Text(latestRun?.let { "最近运行" + it.outcome.label } ?: "还没有运行记录", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(
                    (scheduleNow?.describe() ?: "未设置") to "定时运行",
                    (when (config.loop.mode) {
                        TaskLoop.Mode.COUNT -> "${config.loop.count} 次"
                        TaskLoop.Mode.DURATION -> "${config.loop.durationSec} 秒"
                        TaskLoop.Mode.UNTIL_STOPPED -> "手动停止"
                    }) to "每次执行",
                    (latestRun?.durationText() ?: "—") to "最近耗时"
                ).forEachIndexed { index, (value, label) ->
                    if (index > 0) Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.weight(1f).then(if (index > 0) Modifier.padding(start = 14.dp) else Modifier)) {
                        Text(value, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(label, fontSize = 9.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("编辑与执行", compact = true)
            if (com.forest.android.assistant.core.recording.RecordingReview.pending(this@MainActivity, taskName)) {
                SectionCard { DesignRow("完成录制保存", "查看本次录制摘要、改名或放弃", R.drawable.ic_record, compact = true) {
                    startActivity(Intent(this@MainActivity, RecordingSummaryActivity::class.java).putExtra("task_name", taskName))
                } }
            }
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow(
                    "编辑脚本",
                    if (visual) "task.js · 保存后转为普通脚本任务" else "task.js · JavaScript",
                    R.drawable.ic_code,
                    enabled = !runState.running,
                    compact = true,
                    onClick = onEditScript
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("循环执行", loop, R.drawable.ic_loop, enabled = !runState.running, compact = true, onClick = onLoop)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("定时运行", schedules[taskName]?.describe() ?: "尚未设置运行时间", R.drawable.ic_clock, compact = true, onClick = onSchedule)
            }
            SectionLabel("素材与记录", compact = true)
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow("素材管理", "预览、裁剪与整理任务图片", R.drawable.ic_tool_shot, compact = true, onClick = onManageAssets)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("运行历史", "查看每次运行结果与耗时", R.drawable.ic_tool_log, compact = true, onClick = onHistory)
            }
            SectionLabel("其他操作", compact = true)
            WorkCard(Modifier.fillMaxWidth()) {
                DesignRow("重命名任务", "保留脚本、素材及定时设置", R.drawable.ic_edit, enabled = !busy, compact = true, onClick = onRename)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("导出任务 ZIP", "打包脚本、配置和素材", R.drawable.ic_export, enabled = !taskTransferBusy, compact = true, onClick = onExportZip)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DesignRow("删除任务", "删除相关记录与素材", R.drawable.ic_stop, enabled = !busy, compact = true, onClick = onDelete)
            }
        }
        // 任务操作改用底部操作面板，与分组操作、其它详情页的样式保持一致。
        if (showMenu) ActionSheet("任务操作", onDismiss = { showMenu = false }) {
            SectionCard {
                buildList {
                    add(Triple(
                        if (visual) "编辑脚本并转为普通任务" else "编辑脚本",
                        R.drawable.ic_code,
                        onEditScript
                    ))
                    add(Triple("循环执行", R.drawable.ic_loop, onLoop))
                    add(Triple("定时运行", R.drawable.ic_clock, onSchedule))
                    add(Triple("素材管理", R.drawable.ic_tool_shot, onManageAssets))
                    add(Triple("运行历史", R.drawable.ic_tool_log, onHistory))
                    add(Triple("重命名任务", R.drawable.ic_edit, onRename))
                    add(Triple("导出任务 ZIP", R.drawable.ic_export, onExportZip))
                    add(Triple("删除任务", R.drawable.ic_stop, onDelete))
                }.forEachIndexed { index, (label, icon, action) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DesignRow(label, "", icon, enabled = !busy, compact = true) { showMenu = false; action() }
                }
            }
        }
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
        // 循环设置整体使用紧凑间距，减少选项卡片、输入框和说明文字的纵向占用。
        DetailPage("循环执行", taskName, onDismiss, compact = true, footer = { PrimaryAction("保存循环设置", onClick = onSave) }) {
            SectionLabel("选择执行方式", compact = true)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceCard("指定次数", "运行固定次数后结束", mode == TaskLoop.Mode.COUNT, compact = true) { onModeChange(TaskLoop.Mode.COUNT) }
                ChoiceCard("指定执行时间", "到时中断当前轮", mode == TaskLoop.Mode.DURATION, compact = true) { onModeChange(TaskLoop.Mode.DURATION) }
                ChoiceCard("直到手动停止", "持续运行，手动结束", mode == TaskLoop.Mode.UNTIL_STOPPED, compact = true) { onModeChange(TaskLoop.Mode.UNTIL_STOPPED) }
            }
            SectionLabel("运行参数", compact = true)
            if (mode == TaskLoop.Mode.COUNT) {
                DesignField("执行次数", count, onCountChange, "", KeyboardType.Number, compact = true)
            }
            if (mode == TaskLoop.Mode.DURATION) {
                DesignField("执行时长", duration, onDurationChange, "到时中断当前轮。", KeyboardType.Number, compact = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DurationUnit.entries.forEach { unit ->
                        FilterChip(durationUnit == unit, { onDurationUnitChange(unit) }, label = { Text(unit.label) })
                    }
                }
            }
            if (mode == TaskLoop.Mode.UNTIL_STOPPED) {
                Text("运行将持续到手动停止；任务出错时也会结束。",
                    Modifier.fillMaxWidth().padding(vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DesignField("两轮间隔 · 秒", interval, onIntervalChange, "设为 0 秒，上一轮完成后立即开始下一轮。", KeyboardType.Number, compact = true)
            // 说明文字改为普通小字，不再使用浅色提示块。
            Text("每轮从头执行整个任务，与脚本或流程内部的循环分别设置。",
                Modifier.fillMaxWidth().padding(vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        // 定时设置同样压缩为紧凑布局，频率选项与参数输入框保持可点、可读。
        DetailPage("定时运行", taskName, onDismiss, compact = true, footer = { PrimaryAction("保存定时设置", onClick = onSave) }) {
            SectionLabel("运行频率", compact = true)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceCard("延时一次", "等待指定时间后，运行一次", type == ScheduleManager.Type.ONCE_AFTER_MINUTES, compact = true) {
                    onTypeChange(ScheduleManager.Type.ONCE_AFTER_MINUTES)
                }
                ChoiceCard("固定间隔", "每隔一段时间重复运行", type == ScheduleManager.Type.EVERY_MINUTES, compact = true) {
                    onTypeChange(ScheduleManager.Type.EVERY_MINUTES)
                }
                ChoiceCard("每天固定时刻", "每天同一时刻运行", type == ScheduleManager.Type.DAILY_AT, compact = true) {
                    onTypeChange(ScheduleManager.Type.DAILY_AT)
                }
            }
            SectionLabel("运行参数", compact = true)
            DesignField(
                when (type) {
                    ScheduleManager.Type.ONCE_AFTER_MINUTES -> "延迟时间 · 分钟"
                    ScheduleManager.Type.EVERY_MINUTES -> "间隔时间 · 分钟"
                    ScheduleManager.Type.DAILY_AT -> "运行时间 · HH:mm"
                },
                value, onValueChange,
                when (type) {
                    ScheduleManager.Type.ONCE_AFTER_MINUTES -> "从保存时开始计时。"
                    ScheduleManager.Type.EVERY_MINUTES -> "最短间隔为 15 分钟。"
                    ScheduleManager.Type.DAILY_AT -> "每天该时刻自动运行。"
                },
                if (type == ScheduleManager.Type.DAILY_AT) KeyboardType.Text else KeyboardType.Number,
                compact = true
            )
            if (type == ScheduleManager.Type.DAILY_AT) TextButton(onClick = {
                // 系统时间选择器避免移动端输入时刻时误输分隔符。
                val parts = value.split(':').mapNotNull(String::toIntOrNull)
                android.app.TimePickerDialog(this@MainActivity, { _, hour, minute ->
                    onValueChange("%02d:%02d".format(hour, minute))
                }, parts.getOrElse(0) { 8 }.coerceIn(0, 23), parts.getOrElse(1) { 0 }.coerceIn(0, 59), true).show()
            }) { Text("选择运行时刻") }
            Text("定时启动沿用当前任务的循环设置。请保持无障碍服务可用，并按保活指引允许后台运行。",
                Modifier.fillMaxWidth().padding(vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { keepAliveDialog = true }) { Text("查看后台保活指引") }
            if (current != null) TextButton(onClick = onCancelSchedule) {
                Text("取消当前定时", color = Color(AppPalette.ERROR), fontSize = 12.sp)
            }
        }
    }

    /** 素材管理：列出任务 images/ 下的图片，支持预览、裁剪与整理。 */
    @Composable
    private fun AssetsDialog(
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
    private fun AssetsHistoryDialog(entries: List<ImageHistory.Entry>, busy: Boolean, error: String?,
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

    /** 图片信息行：左标签右值，与设计稿的 key-value 一致。 */
    // 两列等宽，数值从右半列起点左对齐，避免文件名、尺寸和格式各自贴右边。
    @Composable
    private fun KeyValueRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(value, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
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
    private fun RenameRefPreviewDialog(
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

    /** 三项权限纵向显示为状态卡，状态底色与文字同步反映授权情况。 */
    @Composable
    private fun PermissionCard() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionStatusCard(
                title = "无障碍服务",
                desc = "用于点击、滑动、手势和录制操作。",
                icon = R.drawable.ic_permission_gesture,
                granted = a11yEnabled,
                action = if (a11yEnabled) "管理" else "去开启"
            ) {
                if (a11yEnabled) keepAliveDialog = true
                else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            PermissionStatusCard(
                title = "悬浮窗权限",
                desc = "让任务面板和快捷工具出现在其他应用上。",
                icon = R.drawable.ic_floaty,
                granted = overlayGranted,
                action = if (overlayGranted) "管理" else "去开启"
            ) {
                if (overlayGranted) mainTab = 3 else FloatyManager.requestOverlayPermission(this@MainActivity)
            }
            PermissionStatusCard(
                title = "截屏能力",
                desc = "用于找图、找色、OCR 和截图。",
                icon = R.drawable.ic_tool_shot,
                granted = captureReady,
                action = "设置"
            ) { openCaptureDialog() }
        }
    }

    /** 权限卡片：标题、状态胶囊、说明和右上角入口。 */
    @Composable
    private fun PermissionStatusCard(title: String, desc: String, icon: Int, granted: Boolean,
        action: String, onClick: () -> Unit) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(19.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            // 标题和状态并排，说明置于同一内容列，减少卡片的纵向占用。
            // 状态与操作入口放入同一尾部行并垂直居中，避免状态跟随标题而与按钮上下错位。
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon)
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                    Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(desc, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusBadge(if (granted) "已开启" else "待设置", warning = !granted)
                    TextButton(onClick = onClick, modifier = Modifier.width(60.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(action, fontSize = 12.sp, lineHeight = 16.sp, color = Color(AppPalette.PRIMARY))
                    }
                }
            }
        }
    }

    /** 工具行：名称 + 用途 + 尾部箭头 */
    @Composable
    private fun ToolRow(title: String, desc: String, icon: Int = R.drawable.ic_nav_tools, onClick: () -> Unit) {
        DesignRow(title, desc, icon, onClick = onClick)
    }

    /** 首页快捷入口：白底描边按钮，与设计稿的 1/2 网格一致。 */
    @Composable
    private fun QuickEntry(label: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Row(modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides Color(AppPalette.ON_SURFACE_VARIANT)) {
                AppIcon(icon, modifier = Modifier.size(20.dp))
            }
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
        }
    }

    /** 任务列表区：标题行（数量 + 导入/导出全部 + 新建） + 列表 */
    @Composable
    private fun TaskWorkspace(runState: ScriptRunner.RunState) {
        var groupsSelected by rememberSaveable { mutableStateOf(false) }
        var groupCreateSignal by rememberSaveable { mutableStateOf(0) }
        val pages = rememberSaveableStateHolder()
        Column(Modifier.fillMaxSize()) {
            PageHeading("任务空间", "雕雕助手 · AUTOMATION COMPANION", compact = true, actions = {
                if (!groupsSelected) {
                    Box(Modifier.size(37.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable { showTransferDialog = true }, contentAlignment = Alignment.Center) {
                        AppIcon(R.drawable.ic_more, "任务工具", Modifier.size(19.dp))
                    }
                } else {
                    // 分组页标题栏的「＋」用于新建分组，与设计稿一致。
                    Box(Modifier.size(37.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable { groupCreateSignal++ }, contentAlignment = Alignment.Center) {
                        AppIcon(R.drawable.ic_add, "新建分组", Modifier.size(19.dp))
                    }
                }
            })
            Box(Modifier.weight(1f)) {
                pages.SaveableStateProvider(false) {
                    RetainedManagementPage(visible = !groupsSelected) {
                        TaskListSection(runState, Modifier.fillMaxSize(), onSwitchToGroups = { groupsSelected = true })
                    }
                }
                pages.SaveableStateProvider(true) {
                    RetainedManagementPage(visible = groupsSelected) {
                        TaskGroupsPage(lifecycle, Modifier.fillMaxSize(), onSwitchToTasks = { groupsSelected = false },
                            createSignal = groupCreateSignal, onOpenTask = { menuTask = it })
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
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        val haptics = LocalHapticFeedback.current
        // 拖动期间只改预览顺序，松手后一次保存；每秒的列表刷新不会打断拖动。
        var orderDraft by remember { mutableStateOf<List<String>?>(null) }
        var dragging by remember { mutableStateOf<String?>(null) }
        var dragRevision by remember { mutableStateOf(0L) }
        var pointerY by remember { mutableFloatStateOf(0f) }
        var listTop by remember { mutableFloatStateOf(0f) }
        val tasksByName = tasks.associateBy { it.name }
        val taskNames = orderDraft ?: tasks.map { it.name }
        val latestNames by rememberUpdatedState(taskNames)
        val latestTasks by rememberUpdatedState(tasks)
        fun reorderAtPointer() {
            val name = dragging ?: return
            val from = latestNames.indexOf(name)
            // 只处理任务行（key 为任务名），标题和工具栏项保持不动。
            val hit = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { pointerY >= it.offset && pointerY < it.offset + it.size } ?: return
            val to = latestNames.indexOf(hit.key as? String)
            if (from >= 0 && to >= 0 && from != to) {
                orderDraft = latestNames.toMutableList().apply { add(to, removeAt(from)) }
            }
        }
        // 拖到列表边缘时自动滚动，便于调整长列表。
        LaunchedEffect(dragging) {
            if (dragging != null) while (true) {
                val info = listState.layoutInfo
                val edge = 100f
                val delta = when {
                    pointerY < info.viewportStartOffset + edge -> -12f
                    pointerY > info.viewportEndOffset - edge -> 12f
                    else -> 0f
                }
                if (delta != 0f) {
                    listState.scrollBy(delta)
                    reorderAtPointer()
                }
                delay(16)
            }
        }
        LaunchedEffect(tasks) { if (dragging == null) orderDraft = null }
        Column(modifier) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { listTop = it.positionInRoot().y },
                state = listState,
                contentPadding = PaddingValues(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item {
                    Box(Modifier.padding(bottom = 16.dp)) {
                        TaskWorkspaceHero()
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SegmentedTabs(listOf("全部任务 ${tasks.size}", "任务分组"), 0, Modifier.weight(1f)) {
                            if (it == 1) onSwitchToGroups()
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(44.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { showCreateDialog = true }, contentAlignment = Alignment.Center) {
                            AppIcon(R.drawable.ic_add, "新建任务", Modifier.size(20.dp))
                        }
                    }
                }
                if (tasks.isEmpty()) item {
                    EmptyState("还没有任务", "用空白、签到或找图模板快速开始，也可以导入已有 ZIP 文件。",
                        R.drawable.ic_nav_tasks, "创建第一个任务") { showCreateDialog = true }
                }
                // 行按需组合，长任务列表不一次性加载所有内容。
                itemsIndexed(taskNames, key = { _, name -> name }) { index, name ->
                    val task = tasksByName[name] ?: return@itemsIndexed
                    // 长按任务图标即可拖动，跟随手指移动，松手自动保存顺序。
                    var iconTop by remember { mutableFloatStateOf(0f) }
                    val dragEnabled = !taskMutationBusy && !taskTransferBusy
                    // 被拖起的行高亮并浮起；其余行平滑移动，拖动过程更直观。
                    val isDragging = dragging == name
                    val shape = RoundedCornerShape(topStart = if (index == 0) 18.dp else 0.dp,
                        topEnd = if (index == 0) 18.dp else 0.dp,
                        bottomStart = if (index == taskNames.lastIndex) 18.dp else 0.dp,
                        bottomEnd = if (index == taskNames.lastIndex) 18.dp else 0.dp)
                    SwipeDeleteTask(
                        taskName = name,
                        enabled = dragEnabled && dragging == null && !runState.running && !RecordingService.active && deletingTask == null,
                        modifier = Modifier.then(if (isDragging) Modifier else Modifier.animateItem()).clip(shape),
                        onDelete = { deletingTask = name }
                    ) {
                        Surface(Modifier.fillMaxWidth(),
                            color = if (isDragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            shadowElevation = if (isDragging) 8.dp else 0.dp,
                            shape = shape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column {
                                TaskRow(task, runState, iconDragModifier = Modifier
                                    .semantics { contentDescription = "长按「$name」的图标拖动排序" }
                                    .onGloballyPositioned { iconTop = it.positionInRoot().y }
                                    .pointerInput(name, dragEnabled) {
                                        if (dragEnabled) detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                // 长按触发时给一次触感反馈，确认已进入拖动状态。
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                dragRevision = taskOrderRevision
                                                orderDraft = latestTasks.map { it.name }
                                                dragging = name
                                                pointerY = iconTop - listTop + offset.y
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                pointerY += amount.y
                                                reorderAtPointer()
                                            },
                                            onDragEnd = {
                                                val result = orderDraft
                                                val revision = dragRevision
                                                val before = latestTasks.map { it.name }
                                                dragging = null
                                                if (result != null && result != before) {
                                                    scope.launch {
                                                        val failure = withContext(Dispatchers.IO) {
                                                            runCatching { TaskOrder.save(this@MainActivity, revision, result) }
                                                                .exceptionOrNull()
                                                        }
                                                        // 成功或冲突都重新读取，界面始终展示已保存顺序。
                                                        tasks = withContext(Dispatchers.IO) { TaskManager.listTasks(this@MainActivity) }
                                                        taskOrderRevision = withContext(Dispatchers.IO) { TaskOrder.read(this@MainActivity).revision }
                                                        orderDraft = null
                                                        Toast.makeText(this@MainActivity,
                                                            failure?.message ?: "任务顺序已保存",
                                                            if (failure == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                                    }
                                                } else orderDraft = null
                                            },
                                            onDragCancel = { dragging = null; orderDraft = null }
                                        )
                                    })
                            }
                        }
                    }
                }
                if (tasks.isNotEmpty()) item {
                    Text("长按图标排序 · 左滑任务删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                item {
                    MarketBanner("去脚本市场，发现更多可能", "社区好用的脚本，即选即用", Modifier.padding(top = 16.dp)) {
                        showScriptMarket = true
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickEntry("导入与导出", R.drawable.ic_import, Modifier.weight(1f)) { showTransferDialog = true }
                        QuickEntry("悬浮任务面板", R.drawable.ic_floaty, Modifier.weight(1f)) { openFloatingTasks() }
                    }
                }
            }
        }
    }

    /** 权限页：三项能力与截屏通道设置。 */
    @Composable
    private fun PermissionsTab() {
        val readyCount = listOf(a11yEnabled, overlayGranted, captureReady).count { it }
        Column(Modifier.fillMaxSize()) {
            PageHeading("运行准备", "能力按需开启，状态一目了然", compact = true)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 深色就绪概览，右侧显示已开启数量。
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
                    .background(Color(AppPalette.INVERSE_SURFACE)).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("READY WHEN YOU ARE", color = Color(AppPalette.INVERSE_SUBTLE), fontSize = 9.sp, lineHeight = 12.sp,
                            letterSpacing = 1.7.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (readyCount == 3) "一切就绪，放心开始" else "再一步，就准备好了",
                            color = Color.White, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.5).sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        Text("按任务所需，开启对应能力。", color = Color(0xFFC7D1DF), fontSize = 10.sp, lineHeight = 16.sp)
                    }
                    Box(Modifier.size(52.dp).border(3.dp, Color(0xFF88A6CA), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("$readyCount/3", color = Color(0xFFD1DEEE), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                PermissionCard()
                // 权限说明改为普通小字，与列表提示保持同一层级。
                Text("先开启权限，再执行依赖该能力的任务；截屏方式需要手动确认并测试。",
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                    fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { keepAliveDialog = true }) { Text("服务容易中断？查看保活指引", fontSize = 12.sp) }
            }
        }
    }

    /** 设置页：悬浮球与截屏通道；屏幕拾取工具和悬浮日志入口统一收在悬浮窗面板中。 */
    @Composable
    private fun SettingsTab(launcherEnabled: Boolean, update: AppUpdateState) {
        val webState by LanWebServer.state.collectAsState()
        var showWebEditor by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            PageHeading("应用设置", "按你习惯的方式工作", compact = true)
            // 设置页收紧分区和列表间距，减少标题下方及卡片之间的空白。
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("悬浮控制", compact = true)
                WorkCard(Modifier.fillMaxWidth()) {
                    val toggle: () -> Unit = {
                        if (!overlayGranted) FloatyManager.requestOverlayPermission(this@MainActivity)
                        else ScriptRunner.toggleLauncher(this@MainActivity)
                    }
                    DesignRow("显示悬浮球", "在其他应用中快速启动任务", R.drawable.ic_floaty,
                        compact = true,
                        trailing = { Switch(checked = launcherEnabled, onCheckedChange = { toggle() },
                            modifier = Modifier.scale(0.78f),
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(AppPalette.PRIMARY), checkedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFE5E9EF), uncheckedThumbColor = Color.White)) }, onClick = toggle)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AutoSnapRow()
                }
                SectionLabel("任务执行", compact = true)
                WorkCard(Modifier.fillMaxWidth()) {
                    TaskQueueIntervalRow()
                }
                SectionLabel("截图与识别", compact = true)
                WorkCard(Modifier.fillMaxWidth()) {
                    DesignRow("截屏通道设置", ScreenCapture.mode(this@MainActivity).label + " · 点击更换并测试",
                        R.drawable.ic_tool_shot, compact = true) { openCaptureDialog() }
                }
                SectionLabel("快捷入口", compact = true)
                WorkCard(Modifier.fillMaxWidth()) {
                    DesignRow("打开悬浮任务面板", "查看任务队列及快捷工具", R.drawable.ic_nav_tasks, compact = true) { openFloatingTasks() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DesignRow(
                        "局域网 Web 编辑",
                        if (webState.running) "服务运行中 · ${webState.url.orEmpty()}"
                        else "通过其他设备的浏览器编辑脚本和流程",
                        R.drawable.ic_code,
                        compact = true
                    ) { showWebEditor = true }
                }
                SectionLabel("版本", compact = true)
                WorkCard(Modifier.fillMaxWidth()) {
                    DesignRow("应用更新", "当前版本 ${update.currentVersion}", R.drawable.ic_import,
                        compact = true,
                        trailing = if (update.availableUpdate != null) ({ StatusBadge("有新版本", warning = true) }) else null) { showAppUpdate = true }
                }
                Text("雕雕助手 · 把时间留给更喜欢的事", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp))
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

    private fun installAppUpdate() {
        fun taskBusy(): Boolean {
            if (!ScriptRunner.state.value.running && !RecordingService.active) return false
            appUpdate.showNotice("请在任务或录制结束后安装")
            return true
        }
        if (taskBusy()) return
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                appUpdate.showNotice("允许来自此来源的应用后，返回并点击立即安装")
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                return
            }
        } catch (_: ActivityNotFoundException) {
            appUpdate.showNotice("系统无法打开安装来源设置")
            return
        } catch (_: SecurityException) {
            appUpdate.showNotice("系统限制了应用安装")
            return
        }
        lifecycleScope.launch {
            val installer = appUpdate.prepareInstaller() ?: return@launch
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || taskBusy()) return@launch
            try {
                startActivity(installer)
            } catch (_: ActivityNotFoundException) {
                appUpdate.showNotice("系统无法打开 APK 安装器")
            } catch (_: SecurityException) {
                appUpdate.showNotice("系统限制了应用安装")
            }
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
        LanEditorPage(state, onDismiss, onStart, onStop, onCopy)
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
            compact = true,
            trailing = { Switch(checked = autoSnap, onCheckedChange = apply,
                modifier = Modifier.scale(0.78f),
                colors = SwitchDefaults.colors(checkedTrackColor = Color(AppPalette.PRIMARY), checkedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFE5E9EF), uncheckedThumbColor = Color.White)) }) { apply(!autoSnap) }
    }

    /** 多任务间隔以秒保存，未确认的输入在旋转屏幕后继续保留。 */
    @Composable
    private fun TaskQueueIntervalRow() {
        var intervalSec by remember { mutableStateOf(TaskExecutionSettings.queueIntervalSec(this@MainActivity)) }
        var showDialog by rememberSaveable { mutableStateOf(false) }
        var intervalValue by rememberSaveable { mutableStateOf(intervalSec.toString()) }
        DesignRow(
            "多任务执行间隔",
            if (intervalSec == 0) "0 秒 · 完成后立即执行下一个任务" else "$intervalSec 秒 · 两个任务之间等待",
            R.drawable.ic_clock,
            compact = true
        ) {
            intervalValue = intervalSec.toString()
            showDialog = true
        }
        if (showDialog) {
            QueueIntervalPage(intervalValue, { intervalValue = it }, { showDialog = false }) { seconds ->
                TaskExecutionSettings.setQueueIntervalSec(this@MainActivity, seconds)
                intervalSec = seconds
                showDialog = false
                Toast.makeText(this@MainActivity, "多任务执行间隔已保存：$seconds 秒", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 日志页：独立显示实时输出。 */
    @Composable
    private fun LogsTab(logs: List<ScriptRunner.LogEntry>) {
        var filter by rememberSaveable { mutableStateOf("全部") }
        var confirmClear by remember { mutableStateOf(false) }
        var jumpSignal by remember { mutableStateOf(0) }
        val filtered = when (filter) {
            "INFO" -> logs.filter { it.level == 'I' || it.level == 'D' }
            "WARN" -> logs.filter { it.level == 'W' }
            "ERROR" -> logs.filter { it.level == 'E' }
            else -> logs
        }
        Column(Modifier.fillMaxSize()) {
            PageHeading("运行日志", "每一步，都清楚可见", actions = {
                if (logs.isNotEmpty()) TextButton(onClick = { confirmClear = true }) {
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
        if (confirmClear) ConfirmActionSheet("清空运行日志？", "清空当前实时输出，已保存的任务运行历史不受影响。", "清空日志",
            onDismiss = { confirmClear = false }, danger = true,
            onConfirm = { confirmClear = false; ScriptRunner.clearLogs() })
    }

    @Composable
    private fun PageHeading(title: String, subtitle: String, compact: Boolean = false, actions: @Composable () -> Unit = {}) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp,
                    top = if (compact) 10.dp else 16.dp, bottom = if (compact) 10.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 23.sp, lineHeight = if (compact) 28.sp else 31.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp)
                        Text(subtitle, fontSize = 10.sp, lineHeight = if (compact) 14.sp else 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = if (compact) 2.dp else 5.dp))
                    }
                    actions()
                }
            }
        }
    }

    /** 保活指引：降低无障碍服务被系统回收的概率（各厂商后台限制差异较大） */
    @Composable
    private fun KeepAliveDialog(onDismiss: () -> Unit, onOpenAppSettings: () -> Unit) {
        KeepAlivePage(onDismiss, onOpenAppSettings) { AccessibilityGuard.openAccessibilitySettings(this@MainActivity) }
    }

    /** 运行历史对话框：时间 / 耗时 / 退出原因 / 异常信息与堆栈 */
    @Composable
    private fun RunHistoryDialog(taskName: String, entries: List<RunHistory.Entry>, onDismiss: () -> Unit, onClear: () -> Unit) {
        var confirmClear by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<RunHistory.Entry?>(null) }
        DetailPage("运行历史", taskName, onDismiss,
            actions = { TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("清空") } }) {
            if (entries.isEmpty()) EmptyState("暂无运行记录", "执行任务后，可以在这里查看结果与耗时。", R.drawable.ic_tool_log)
            else {
                SectionLabel("最近 ${entries.size} 次运行")
                entries.forEachIndexed { index, entry ->
                    val error = entry.outcome == RunHistory.Outcome.ERROR
                    val stopped = entry.outcome == RunHistory.Outcome.STOPPED
                    Row(Modifier.fillMaxWidth()) {
                        // 左侧时间线：圆点 + 竖线，出错记录用红色圆点。
                        Column(Modifier.width(18.dp).padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(9.dp).background(
                                if (error) Color(AppPalette.ERROR) else if (stopped) Color(AppPalette.TERTIARY) else Color(AppPalette.SUCCESS),
                                androidx.compose.foundation.shape.CircleShape))
                            if (index < entries.lastIndex) Box(Modifier.width(1.dp).height(110.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp, bottom = 14.dp)) {
                            Text("${entry.time} · ${entry.source}", fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(Modifier.fillMaxWidth().padding(top = 6.dp), color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.clickable { selected = entry }.padding(13.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(entry.outcome.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f))
                                        StatusBadge(entry.durationText(), warning = stopped, error = error)
                                    }
                                    entry.message?.let {
                                        Text(it, fontSize = 10.sp, lineHeight = 17.sp,
                                            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 8.dp))
                                    }
                                    if (error) {
                                        entry.stack?.let {
                                            Text(it.lineSequence().take(4).joinToString("\n"),
                                                Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp, lineHeight = 16.sp)
                                        }
                                        TextButton(onClick = { editHistoryError(taskName, entry) },
                                            contentPadding = PaddingValues(horizontal = 0.dp)) {
                                            Text("打开脚本并定位", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        selected?.let { entry ->
            RunResultPage(taskName, entry, onBack = { selected = null },
                onEdit = { editHistoryError(taskName, entry) }, onAssets = {
                    // 先退出现有结果浮层，再打开素材页，保证页面位于可操作的最上层。
                    selected = null
                    onDismiss()
                    assetsTask = taskName; assets = TaskManager.imageInfos(this, taskName)
                })
        }
        if (confirmClear) ConfirmActionSheet("清空运行历史？", "将删除「$taskName」的全部运行记录，脚本和素材会保留。", "清空历史",
            onDismiss = { confirmClear = false }, danger = true, onConfirm = { confirmClear = false; onClear() })
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
        captureTestResult = null
        captureDialog = true
    }

    /** 确认时先测试真实截图；成功才保存并关闭，失败允许重新选择。 */
    private fun applyCaptureMode(mode: ScreenCapture.Mode) {
        if (mode == ScreenCapture.Mode.UNSELECTED || captureModeTesting) return
        captureModeTesting = true
        captureModeError = null
        captureTestResult = null
        lifecycleScope.launch {
            try {
                val result = ScreenCapture.testAndSelectMode(applicationContext, mode)
                captureReady = ScreenCapture.captureAvailableCached(this@MainActivity)
                // 成功后停留结果页供用户核对通道和真实画面尺寸，再手动完成。
                captureTestResult = result
                if (!result.success) captureModeError = result.message
            } finally {
                captureModeTesting = false
            }
        }
    }

    /** 必须手动选择并确认，不提供自动模式或自动切换。 */
    @Composable
    private fun CaptureChannelDialog(onDismiss: () -> Unit, onSelect: (ScreenCapture.Mode) -> Unit) {
        val result = captureTestResult
        if (captureModeTesting || result != null) {
            DetailPage("截屏测试", selectedCaptureMode.label, { if (!captureModeTesting) captureTestResult = null }, footer = {
                if (!captureModeTesting) {
                    if (result?.success == true) PrimaryAction("完成", icon = R.drawable.ic_check, onClick = onDismiss)
                    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { SecondaryAction("更换方式") { captureTestResult = null; captureModeError = null } }
                        Box(Modifier.weight(1f)) { PrimaryAction("重试测试") { onSelect(selectedCaptureMode) } }
                    }
                }
            }) {
                if (captureModeTesting) {
                    Column(Modifier.fillMaxWidth().padding(top = 45.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("正在截取测试画面", fontSize = 19.sp, modifier = Modifier.padding(top = 24.dp))
                        Text("系统授权方式可能弹出授权提示，请稍候。", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                } else if (result?.success == true) {
                    EmptyState("截屏通道已就绪", "已成功获取测试画面，新设置已保存。", R.drawable.ic_check)
                    ContentBlock("测试画面") {
                        KeyValueRow("所选通道", selectedCaptureMode.label)
                        KeyValueRow("画面尺寸", "${result.width} × ${result.height} px")
                    }
                } else {
                    EmptyState("这次测试未通过", "未获取到有效画面。原有设置仍保留，可以重新授权或选择其他方式。", R.drawable.ic_tool_shot)
                    InfoNotice(result?.message ?: "截屏测试失败，请重试。", error = true)
                }
            }
            return
        }
        DetailPage(if (captureModeTesting) "正在测试截屏" else if (captureModeError != null) "截屏测试失败" else "截屏方式",
            "更换截屏能力并测试", { if (!captureModeTesting) onDismiss() },
            footer = { PrimaryAction(if (captureModeTesting) "正在测试…" else if (captureModeError != null) "重试测试" else "确认选择并测试",
                enabled = selectedCaptureMode != ScreenCapture.Mode.UNSELECTED && !captureModeTesting) { onSelect(selectedCaptureMode) } }) {
            InfoNotice("当前设置：${ScreenCapture.mode(this@MainActivity).label}。更换方式将在测试成功后保存。")
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
                            selectedCaptureMode == mode, icon = if (mode == ScreenCapture.Mode.ROOT) R.drawable.ic_nav_permissions else R.drawable.ic_phone) {
                            selectedCaptureMode = mode; captureModeError = null
                        }
                    }
                }
                ContentBlock("为什么需要测试？") {
                    Text("确认当前设备能够获取画面后，再用于找图、找色和 OCR。测试失败会保留原设置。", fontSize = 12.sp, lineHeight = 21.sp)
                }
                InfoNotice("仅使用你选择的方式，不会自动切换到另一种截屏方式。")
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

    @Composable
    private fun RenameTaskDialog(oldName: String) {
        var name by remember(oldName) { mutableStateOf(oldName) }
        val trimmed = name.trim()
        val invalid = trimmed.isBlank() || trimmed.length > 50 || trimmed == "." || trimmed == ".." ||
            trimmed.any { it == '/' || it == '\\' || it.isISOControl() } ||
            tasks.any { it.name == trimmed && it.name != oldName }
        // 与其它详情页共用标题栏、表单和底部操作，不再使用系统 AlertDialog。
        DetailPage("重命名任务", oldName, { if (!taskMutationBusy) renamingTask = null }, compact = true, footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryAction("取消", !taskMutationBusy) { renamingTask = null } }
                Box(Modifier.weight(1f)) {
                    PrimaryAction(if (taskMutationBusy) "正在保存…" else "保存", !invalid && !taskMutationBusy) {
                        performTaskChange({ TaskManager.renameTask(this@MainActivity, oldName, trimmed) }, "已改名为「$trimmed」") {
                            renamingTask = null
                            menuTask = trimmed
                        }
                    }
                }
            }
        }) {
            DesignField("任务名称", name, { name = it }, "脚本、素材与历史记录会一同保留",
                error = if (invalid) "名称非法或与现有任务重复" else null, compact = true)
            if (schedules.containsKey(oldName)) Text("定时任务将保留原设置，并从改名后重新计时。",
                Modifier.fillMaxWidth().padding(vertical = 2.dp), fontSize = 10.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun performTaskChange(operation: () -> Result<*>, successMessage: String, onSuccess: () -> Unit) {
        if (taskMutationBusy) return
        taskMutationBusy = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                val snapshot = withContext(Dispatchers.IO) {
                    Triple(
                        TaskManager.listTasks(this@MainActivity),
                        ScheduleManager.load(this@MainActivity)
                            .filter { it.enabled }.associateBy { it.taskName },
                        TaskOrder.read(this@MainActivity).revision
                    )
                }
                tasks = snapshot.first
                schedules = snapshot.second
                // 改名/删除会同步更新顺序文件，这里立即同步版本，随后的拖拽保存不会误判冲突
                taskOrderRevision = snapshot.third
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
        DetailPage("新建任务", "把重复动作，变成一个任务", onDismiss, footer = {
            PrimaryAction("创建任务", enabled = trimmed.isNotBlank() && nameError == null, icon = R.drawable.ic_add) {
                onConfirm(trimmed, selectedScaffold)
            }
        }) {
            Column(Modifier.padding(top = 8.dp)) {
                Text("任务名称", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 42.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, if (nameError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(AppPalette.PRIMARY)),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (name.isEmpty()) Text("例如：每日签到", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    )
                }
                Text(nameError ?: "使用易于辨认的名称，最多 50 个字符。", fontSize = 9.sp, lineHeight = 16.sp,
                    color = if (nameError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp))
            }
            SectionLabel("从一个模板开始")
            TaskManager.Scaffold.entries.filterNot { it == TaskManager.Scaffold.VISUAL }.forEach { option ->
                val icon = when (option) {
                    TaskManager.Scaffold.SIGN_IN -> R.drawable.ic_check
                    TaskManager.Scaffold.FIND_IMAGE -> R.drawable.ic_tool_shot
                    else -> R.drawable.ic_code
                }
                // 设计稿使用更短的模板说明，避免窄屏换行把选项撑高。
                val desc = when (option) {
                    TaskManager.Scaffold.BLANK -> "从一段基础脚本自由开始"
                    TaskManager.Scaffold.SIGN_IN -> "常用的点击与等待示例"
                    TaskManager.Scaffold.FIND_IMAGE -> "学习图片识别和自动点击"
                    else -> option.desc
                }
                ChoiceCard(option.label, desc, selectedScaffold == option, icon = icon) { scaffold = option }
            }
            Text("需要拖拽编排流程？在「设置 → 局域网 Web 编辑」中创建和编辑流程任务。",
                Modifier.fillMaxWidth().background(Color(AppPalette.PRIMARY_CONTAINER), RoundedCornerShape(12.dp)).padding(14.dp),
                fontSize = 10.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
