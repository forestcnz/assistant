package com.forest.android.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.*
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.gesture.ScriptAccessibilityService
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.task.ImageHistory
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.task.ScheduleManager
import com.forest.android.assistant.core.task.TaskLoop
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.core.task.TaskOrder
import com.forest.android.assistant.core.task.TaskZip
import com.forest.android.assistant.core.update.AppUpdateModel
import com.forest.android.assistant.core.update.DownloadPhase
import com.forest.android.assistant.ui.AppToast as Toast
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.StartupComic
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

    internal val appUpdate by lazy { ViewModelProvider(this)[AppUpdateModel::class.java] }

    // 权限状态（页面可见期间每秒轮询刷新）
    internal var a11yEnabled by mutableStateOf(false)
    internal var overlayGranted by mutableStateOf(false)
    internal var captureReady by mutableStateOf(false)
    internal var tasks by mutableStateOf(emptyList<TaskManager.TaskInfo>())
    /** 任务列表顺序的版本号，保存拖拽结果时用于检测其他端的修改 */
    internal var taskOrderRevision by mutableStateOf(0L)
    internal var taskTransferBusy by mutableStateOf(false)
    internal var pendingExportFile by mutableStateOf<File?>(null)
    internal var showCreateDialog by mutableStateOf(false)
    internal var taskMetadataVersion by mutableStateOf(0)



    /** 任务操作菜单/素材管理 */
    internal var showTransferDialog by mutableStateOf(false)
    internal var showScriptMarket by mutableStateOf(false)
    internal var showAppUpdate by mutableStateOf(false)
    internal var menuTask by mutableStateOf<String?>(null)
    internal var renamingTask by mutableStateOf<String?>(null)
    internal var deletingTask by mutableStateOf<String?>(null)
    internal var showClearTasksDialog by mutableStateOf(false)
    internal var taskMutationBusy by mutableStateOf(false)
    internal var assetsTask by mutableStateOf<String?>(null)
    internal var assets by mutableStateOf(emptyList<TaskManager.ImageInfo>())
    internal var assetsHistoryTask by mutableStateOf<String?>(null)
    internal var assetsHistory by mutableStateOf(emptyList<ImageHistory.Entry>())
    internal var assetsHistoryBusy by mutableStateOf(false)
    internal var assetsHistoryError by mutableStateOf<String?>(null)
    // 移回历史图片会覆盖同名素材，先保留待确认项。
    internal var restoringAsset by mutableStateOf<ImageHistory.Entry?>(null)
    internal var previewAsset by mutableStateOf<TaskManager.ImageInfo?>(null)
    internal var previewBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    internal var previewLoadJob: kotlinx.coroutines.Job? = null
    internal var deletingAsset by mutableStateOf<TaskManager.ImageInfo?>(null)
    internal var renameAsset by mutableStateOf<TaskManager.ImageInfo?>(null)
    /** 素材重命名的脚本引用预览（确认后才会落盘） */
    internal var renamePreview by mutableStateOf<TaskManager.ImageRenamePreview?>(null)

    /** 定时任务编辑状态 */
    internal var scheduleEditing by mutableStateOf<String?>(null)
    internal var currentSchedule by mutableStateOf<ScheduleManager.Schedule?>(null)
    internal var scheduleType by mutableStateOf(ScheduleManager.Type.ONCE_AFTER_MINUTES)
    internal var scheduleValue by mutableStateOf("1")

    /** 整个任务的循环设置，保存到任务的 config.json。 */
    internal var loopEditing by mutableStateOf<String?>(null)
    internal var loopMode by mutableStateOf(TaskLoop.Mode.COUNT)
    internal var loopCountValue by mutableStateOf("1")
    internal var loopDurationValue by mutableStateOf("1")
    internal var loopDurationUnit by mutableStateOf(DurationUnit.MINUTE)
    internal var loopIntervalValue by mutableStateOf("0")

    internal enum class DurationUnit(val label: String, val seconds: Long) {
        SECOND("秒", 1), MINUTE("分钟", 60), HOUR("小时", 3600)
    }

    /** 保活指引对话框 */
    internal var keepAliveDialog by mutableStateOf(false)

    /** 运行历史对话框状态 */
    internal var historyTask by mutableStateOf<String?>(null)
    internal var historyEntries by mutableStateOf(emptyList<RunHistory.Entry>())

    /** 截屏通道对话框状态 */
    internal var captureDialog by mutableStateOf(false)
    internal var selectedCaptureMode by mutableStateOf(ScreenCapture.Mode.UNSELECTED)
    internal var captureModeTesting by mutableStateOf(false)
    internal var captureModeError by mutableStateOf<String?>(null)
    internal var captureTestResult by mutableStateOf<ScreenCapture.ModeTestResult?>(null)

    /** 各任务的定时设置（列表展示用） */
    internal var schedules by mutableStateOf(emptyMap<String, ScheduleManager.Schedule>())

    /** 主导航：任务 / 日志 / 权限 / 设置；恢复旋转前的页面。 */
    internal var mainTab by mutableStateOf(0)


    /** 任务副标题（config 的 desc / target），仅任务集合变化时刷新 */
    internal var taskSubtitles by mutableStateOf(emptyMap<String, String>())

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

    internal fun captureTemplate(task: String) {
        templateCaptureLauncher.launch(Intent(this, TemplateCaptureActivity::class.java)
            .putExtra(TemplateCaptureActivity.EXTRA_TASK, task))
    }

    internal fun cropAsset(info: TaskManager.ImageInfo) {
        val task = assetsTask ?: return
        previewLoadJob?.cancel()
        cropImageLauncher.launch(Intent(this, AssetCropActivity::class.java)
            .putExtra(AssetCropActivity.EXTRA_TASK, task)
            .putExtra(AssetCropActivity.EXTRA_IMAGE, info.file.name))
    }

    internal fun openAssetsHistory(task: String) {
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

    internal fun moveHistoryImageBack(task: String, entry: ImageHistory.Entry) {
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
    internal val importImageLauncher =
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
    internal val importZipLauncher =
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
    internal val exportZipLauncher =
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

}
