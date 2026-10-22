package com.forest.android.assistant.core.engine

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.forest.android.assistant.core.floaty.ConsoleOverlay
import com.forest.android.assistant.core.gesture.AccessibilityGuard
import com.forest.android.assistant.core.gesture.GestureClient
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.floaty.PickerOverlay
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.runner.ScriptForegroundService
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.task.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 脚本运行器：串联 任务脚本 → 引擎执行 → 悬浮控制条（状态/停止）→ 运行日志。
 * 是宿主 UI 与 ScriptEngine 之间的唯一入口，对应设计 docs/README.md 第 2 章「总体架构」。
 */
object ScriptRunner {

    data class LogEntry(val time: String, val level: Char, val msg: String, val id: Long)

    data class RunState(
        val taskName: String? = null,
        val running: Boolean = false
    )

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    /** 本次运行开始时间（0 = 空闲）；供悬浮窗/控制台显示计时 */
    @Volatile
    var runStartedAt: Long = 0L
        private set

    private val _logs = MutableStateFlow(emptyList<LogEntry>())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    private val logSnapshotBuffer = BoundedLogSnapshot<LogEntry>(MAX_LOGS)

    /** 最近一次运行错误的位置（供编辑器定位） */
    data class LastError(
        val taskName: String,
        val line: Int,
        val column: Int,
        val message: String
    )

    private val _lastError = MutableStateFlow<LastError?>(null)
    val lastError: StateFlow<LastError?> = _lastError.asStateFlow()

    /** 任务选择悬浮球是否启用（运行期自动收起，结束后恢复） */
    private val _launcherEnabled = MutableStateFlow(false)
    val launcherEnabled: StateFlow<Boolean> = _launcherEnabled.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private const val SCREENSHOT_OVERLAY_IDLE_MS = 300L
    private const val SCREENSHOT_OVERLAY_MAX_HIDDEN_MS = 450L
    private data class HiddenCaptureOverlays(
        val context: Context,
        val launcher: Boolean,
        val console: Boolean,
        val hiddenAt: Long
    )
    // Main-thread only. The run bar stays visible; only idle controls and the console are hidden.
    private var hiddenCaptureOverlays: HiddenCaptureOverlays? = null
    private val restoreCaptureOverlaysRunnable = Runnable { restoreCaptureOverlaysNow() }

    private var job: Job? = null
    private var appContext: Context? = null

    /** 日志去重：同内容 500ms 内只保留一条 */
    private val lastLogLock = Any()
    private var lastLogMsg: String? = null
    private var lastLogAt = 0L
    private var nextLogId = 0L

    init {
        // 悬浮条[停止]按钮 → 中断脚本
        FloatyManager.onStopClicked = { stop() }
        // 悬浮球面板选任务 → 直接运行
        FloatyManager.onTaskSelected = { name -> appContext?.let { run(it, name) } }
        // 悬浮球面板[关闭悬浮窗]：运行中先停止任务，再隐藏并记下用户意愿（下次启动不再自动显示）
        FloatyManager.onLauncherClose = {
            appContext?.let { ctx ->
                if (_state.value.running) stop()
                _launcherEnabled.value = false
                FloatyManager.setEnabledByUser(ctx, false)
                FloatyManager.hideLauncher()
                addLog('I', "悬浮球已关闭（下次启动不再自动显示，可在「工具」页重新开启）")
            }
        }
        // 任务列表来源（悬浮球每次展开时实时读取）
        FloatyManager.taskProvider = {
            appContext?.let { TaskManager.listTasks(it).map { t -> t.name } } ?: emptyList()
        }
        // 手势降级到 Root input 兜底时写运行日志
        GestureClient.onFallback = { msg -> addLog('W', msg) }
        // 坐标拾取/取色结果 → 写运行日志（代码已复制到剪贴板）
        PickerOverlay.onPicked = { code -> addLog('I', "拾取结果（已复制）: $code") }
        // 框选截图保存结果 → 写运行日志（包含完整路径）
        PickerOverlay.onScreenshotSaved = { location -> addLog('I', "截图已保存: $location") }
        // 悬浮控制台日志源（级别着色 + 自动滚底由控制台自身处理）
        var lastConsoleSnapshot: List<LogEntry>? = null
        var consoleLines = emptyList<ConsoleOverlay.ConsoleLine>()
        ConsoleOverlay.logProvider = {
            val snapshot = _logs.value
            if (snapshot !== lastConsoleSnapshot) {
                consoleLines = snapshot.map { ConsoleOverlay.ConsoleLine(it.time, it.level, it.msg, it.id) }
                lastConsoleSnapshot = snapshot
            }
            consoleLines
        }
        ConsoleOverlay.onClear = { clearLogs() }
        // 控制台底部运行态条：任务名 + 已运行时长；[停止] 与悬浮条等价
        ConsoleOverlay.runningInfo = {
            val st = _state.value
            if (st.running && st.taskName != null) st.taskName!! to runStartedAt else null
        }
        ConsoleOverlay.onStop = { stop() }
    }

    /** 供宿主/打包等模块写入运行日志 */
    fun appendLog(msg: String, level: Char = 'I') {
        addLog(level, msg)
    }

    /** 清空运行日志 */
    fun clearLogs() {
        synchronized(lastLogLock) {
            _logs.value = emptyList()
            lastLogMsg = null
            lastLogAt = 0
        }
    }

    /** 绑定应用上下文（App 启动时调用，供悬浮球回调使用） */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 开关任务选择悬浮球（需悬浮窗权限）；开关结果持久化，下次启动按用户意愿决定是否自动显示 */
    fun toggleLauncher(context: Context): Boolean {
        val app = context.applicationContext
        appContext = app
        if (_launcherEnabled.value) {
            _launcherEnabled.value = false
            FloatyManager.setEnabledByUser(app, false)
            FloatyManager.hideLauncher()
            addLog('I', "悬浮球已隐藏（下次启动不再自动显示，可在「工具」页重新开启）")
        } else {
            if (!FloatyManager.showLauncher(app)) {
                addLog('W', "悬浮球显示失败：请先授权悬浮窗权限")
                return false
            }
            _launcherEnabled.value = true
            FloatyManager.setEnabledByUser(app, true)
            addLog('I', "悬浮球已开启：点击展开任务列表")
        }
        return true
    }

    /**
     * 自动恢复悬浮球（应用启动 / 回到前台时调用）：
     * 已授权且用户未主动隐藏时自动显示，实现「默认开启悬浮窗」。
     * @return 当前是否显示中
     */
    fun ensureLauncher(context: Context): Boolean {
        val app = context.applicationContext
        appContext = app
        if (!FloatyManager.hasOverlayPermission(app)) {
            _launcherEnabled.value = false
            FloatyManager.hideLauncher(app)
            return false
        }
        if (!FloatyManager.isEnabledByUser(app)) {
            _launcherEnabled.value = false
            FloatyManager.hideLauncher(app)
            return false
        }
        val wasShowing = FloatyManager.isLauncherShowing()
        val ok = FloatyManager.showLauncher(app)
        if (ok) {
            _launcherEnabled.value = true
            if (!wasShowing) addLog('I', "悬浮球已自动显示（默认开启，可拖动贴边）")
        } else {
            _launcherEnabled.value = false
        }
        return ok
    }

    /** 运行任务（异步）：重复运行被忽略 */
    fun run(context: Context, taskName: String) {
        if (_state.value.running) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runAndWait(context, taskName)
        }
    }

    /**
     * 运行任务并等待结束（定时任务 Worker 也复用本方法）。
     * @param source 触发来源（手动/定时任务），写入日志便于区分
     * @return 是否正常执行（未运行/读取失败返回 false）
     */
    suspend fun runAndWait(context: Context, taskName: String, source: String = "手动"): Boolean {
        if (com.forest.android.assistant.core.recording.RecordingService.active) {
            addLog('W', "正在录制操作，请完成录制后再运行任务: $taskName")
            return false
        }
        if (_state.value.running) {
            addLog('W', "已有任务在运行，跳过: $taskName")
            return false
        }
        val app = context.applicationContext
        val script = try {
            TaskManager.scriptFile(app, taskName).readText()
        } catch (e: Exception) {
            addLog('E', "读取脚本失败: ${e.message}")
            return false
        }
        val config = TaskManager.readConfig(app, taskName)

        appContext = app
        val startedAt = System.currentTimeMillis()
        runStartedAt = startedAt                 // 悬浮窗/控制台据此显示运行计时
        var lastOutcome: ScriptEngine.RunOutcome? = null
        job = coroutineContext[Job]              // 供悬浮条/界面停止按钮取消本次运行
        _state.value = RunState(taskName, running = true)
        val total = config.loopCount.coerceAtLeast(1)
        val sourceTag = if (source != "手动") "（$source）" else ""
        val isScheduled = source != "手动"
        _lastError.value = null
        addLog('I', "▶ 启动任务: $taskName$sourceTag" + if (total > 1) "（循环 $total 次）" else "")
        if (isScheduled) preflightCapabilities(app)

        try {
            // 分辨率适配：注入真机尺寸并清空上次运行残留的设计基准
            ScriptAdapter.clear()
            screenSize(app)?.let { (w, h) -> ScriptAdapter.setRealSize(w, h) }
            // config.json 的 designSize：按基准分辨率自动换算坐标（脚本内仍可调 adapter 覆盖）
            if (config.hasDesignSize()) {
                ScriptAdapter.setDesignSize(config.designWidth, config.designHeight)
                addLog(
                    'I',
                    "已应用基准分辨率 ${config.designWidth}×${config.designHeight}（坐标按此编写，运行时自动换算）"
                )
            }
            // 运行期保活：常驻前台服务（低优先级通知 + 状态自检 + 通知栏停止）
            ScriptForegroundService.start(app, taskName)
            withContext(Dispatchers.Main) {
                // 悬浮控件进入运行态，在屏幕左下角显示任务名与停止按钮。
                FloatyManager.setRunning(app, taskName)
            }

            var iteration = 0
            while (true) {
                iteration++
                if (total > 1) addLog('I', "第 $iteration/$total 次执行")

                // 目标应用：每次循环前确保其在前台（config.json 的 target）
                config.target?.let { launchTarget(app, it) }

                val outcome = ScriptEngine.execute(env(taskName, isScheduled), script)
                lastOutcome = outcome

                val summary = when (outcome.kind) {
                    ScriptEngine.OutcomeKind.FINISHED -> "任务完成 ✓"
                    ScriptEngine.OutcomeKind.EXITED -> "任务退出（exit()）"
                    ScriptEngine.OutcomeKind.STOPPED -> "任务已停止"
                    ScriptEngine.OutcomeKind.ERROR -> "任务出错: ${outcome.message}"
                }
                addLog(
                    when (outcome.kind) {
                        ScriptEngine.OutcomeKind.ERROR -> 'E'
                        ScriptEngine.OutcomeKind.STOPPED -> 'W'
                        else -> 'I'
                    },
                    if (total > 1) "第 $iteration/$total 次 — $summary" else "■ $taskName — $summary"
                )

                // 出错/停止/已达次数则结束循环
                if (outcome.kind == ScriptEngine.OutcomeKind.ERROR ||
                    outcome.kind == ScriptEngine.OutcomeKind.STOPPED ||
                    iteration >= total
                ) break

                if (config.loopIntervalSec > 0) {
                    addLog('D', "等待 ${config.loopIntervalSec}s 后进入下一次循环")
                    delay(config.loopIntervalSec * 1000L)
                }
            }
        } finally {
            // 运行历史：耗时 / 退出原因 / 异常堆栈（文件 IO 非挂起，取消后仍会执行）
            val durationMs = System.currentTimeMillis() - startedAt
            val result = lastOutcome
            val historyOutcome = when {
                result == null || result.kind == ScriptEngine.OutcomeKind.STOPPED -> RunHistory.Outcome.STOPPED
                result.kind == ScriptEngine.OutcomeKind.ERROR -> RunHistory.Outcome.ERROR
                result.kind == ScriptEngine.OutcomeKind.EXITED -> RunHistory.Outcome.EXITED
                else -> RunHistory.Outcome.FINISHED
            }
            if (historyOutcome == RunHistory.Outcome.ERROR) {
                result?.let { r ->
                    val line = r.errorLine
                    if (line != null) {
                        _lastError.value = LastError(taskName, line, r.errorColumn ?: 1, r.message ?: "脚本出错")
                    }
                }
            }
            RunHistory.append(
                context = app,
                taskName = taskName,
                outcome = historyOutcome,
                durationMs = durationMs,
                source = source,
                message = result?.message,
                stack = result?.stack
            )
            addLog(
                'I',
                "本次耗时 " + (if (durationMs < 1000) durationMs.toString() + "ms" else "%.1fs".format(durationMs / 1000.0)) +
                        "（" + historyOutcome.label + "，已记入运行历史）"
            )
            // 停止会取消协程：清理与状态复位必须全部放在 NonCancellable 块内，
            // 否则 withContext 之后（协程已取消）的语句不会执行，UI 会卡在运行中
            withContext(NonCancellable + Dispatchers.Main) {
                restoreCaptureOverlaysNow()
                // 回到空闲态；用户没开启悬浮控件时直接隐藏，不留残留
                FloatyManager.clearRunning(app)
                if (!_launcherEnabled.value) FloatyManager.hideLauncher()
                _state.value = RunState(null, running = false)
                runStartedAt = 0L
                // 以下清理同样必须在 NonCancellable 内：停止任务时协程已取消，
                // 写在外面的语句不会执行（曾导致保活服务/通知栏残留）
                ScriptAdapter.clear()
                ScriptForegroundService.stop(app)
            }
        }
        return true
    }

    /**
     * 定时任务到点运行前的能力预置：
     * 1) 无障碍未开启 → 记录指引并尝试自动打开无障碍设置页（后台可能被系统拦截）；
     * 2) 无截屏能力（未授权录屏且无 Root/Shizuku 静默通道）→ 记录指引，
     *    脚本内 requestScreenCapture() 会直接返回 false，据此跳过找图/找色步骤。
     */
    private fun preflightCapabilities(context: Context) {
        if (!GestureClient.available()) {
            addLog('W', "前置检查：无障碍服务未开启，坐标操作不可用；正在尝试打开无障碍设置页…")
            val opened = AccessibilityGuard.openAccessibilitySettings(context)
            addLog(
                'W',
                if (opened) "若设置页未出现在前台（后台启动受限），请手动开启：设置 → 无障碍 → JS脚本执行器"
                else "打开无障碍设置失败，请手动开启：设置 → 无障碍 → JS脚本执行器"
            )
        } else {
            addLog('I', "前置检查：无障碍服务已就绪")
        }

        val mediaProjectionOnly =
            ScreenCapture.activeChannel(context) == ScreenCapture.Channel.MEDIAPROJECTION
        if (mediaProjectionOnly && !ScreenCapture.isReady()) {
            addLog('W', "前置检查：无截屏能力（未授权录屏且无 Root/Shizuku 静默通道）")
            addLog('W', "脚本内 requestScreenCapture() 将直接返回 false，请跳过找图/找色步骤")
        } else {
            addLog('I', "前置检查：截屏能力就绪")
        }
    }

    /** 真机屏幕尺寸（像素），用于分辨率适配换算 */
    private fun screenSize(context: Context): Pair<Int, Int>? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val wm = context.getSystemService(android.view.WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = context.resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    } catch (e: Exception) {
        null
    }

    /** 按 config.json 的 target 拉起目标应用（运行前置条件） */
    private fun launchTarget(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            addLog('W', "未找到目标应用: $pkg")
            return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            addLog('I', "拉起目标应用: $pkg")
        } catch (e: Exception) {
            addLog('W', "拉起目标应用失败: ${e.message}")
        }
    }

    /** 停止当前任务（悬浮条停止按钮 / 悬浮球面板 / UI 停止按钮共用） */
    fun stop() {
        val name = _state.value.taskName ?: return
        addLog('I', "停止任务: $name")
        ScriptEngine.cancel()   // 打断绑定函数内的阻塞循环（sleep/等待）
        job?.cancel()           // 取消引擎协程（挂起点）
    }

    /**
     * 通知栏「隐藏悬浮球」等外部入口关闭悬浮球后调用（floaty 模块不依赖 engine，
     * 由 Service 反向通知同步开关状态，避免「工具」页开关显示与实际不符）。
     */
    fun onLauncherHiddenByUser() {
        _launcherEnabled.value = false
    }

    /** 引擎回调环境：日志进运行面板 + logcat；Toast/设置跳转切主线程 */
    private fun env(taskName: String, isScheduled: Boolean = false): ScriptEngine.ScriptEnv {
        val app = appContext
        return ScriptEngine.ScriptEnv(
            scriptPath = app?.let { TaskManager.scriptFile(it, taskName).absolutePath } ?: "task.js",
            onLog = { level, msg -> addLog(level, msg) },
            onToast = { msg ->
                mainHandler.post {
                    appContext?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
                }
            },
            openAccessibilitySettings = {
                mainHandler.post {
                    app?.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            requestScreenCapture = {
                // 定时任务在后台运行：系统限制后台弹授权窗，直接降级并给出指引，
                // 避免脚本阻塞等待一个不会出现的弹窗（脚本据此跳过找图/找色步骤）
                if (isScheduled && !ScreenCapture.isReady()) {
                    addLog('W', "截屏不可用：定时任务在后台无法弹出录屏授权窗，已跳过申请")
                    addLog('W', "请在应用内手动授权录屏，或改用 Root/Shizuku 静默截屏；脚本请跳过找图/找色步骤")
                    false
                } else {
                    val ok = ScreenCapture.requestBlocking()
                    if (ok) addLog('I', "截屏授权成功") else addLog('W', "截屏授权失败或被取消")
                    ok
                }
            },
            screenCaptureReady = { ScreenCapture.isReady() },
            captureScreen = { captureScreenForScript(app) }
        )
    }

    /** 截图期间保留运行条及停止按钮；临时隐藏空闲悬浮球和控制台。 */
    internal fun captureScreenForScript(
        context: Context?,
        capture: (Long) -> android.graphics.Bitmap? = { after ->
            ScreenCapture.capture(newerThanUptimeMs = after)
        }
    ): android.graphics.Bitmap? {
        if (context == null) {
            return if ((!FloatyManager.isLauncherShowing() || FloatyManager.isShowing()) &&
                !ConsoleOverlay.isShowing())
                capture(0) else null
        }
        var hiddenAt = 0L
        var prepared = false
        val onMain = runOnMainBlocking {
            val now = SystemClock.uptimeMillis()
            val existing = hiddenCaptureOverlays
            if (existing != null && now - existing.hiddenAt >= SCREENSHOT_OVERLAY_MAX_HIDDEN_MS) {
                restoreCaptureOverlaysNow()
            }
            val stillHidden = hiddenCaptureOverlays
            if (stillHidden != null) {
                mainHandler.removeCallbacks(restoreCaptureOverlaysRunnable)
                hiddenAt = stillHidden.hiddenAt
                prepared = true
            } else {
                val launcher = FloatyManager.isLauncherShowing() && !FloatyManager.isShowing()
                val console = ConsoleOverlay.isShowing()
                if (!launcher && !console) {
                    prepared = true
                } else {
                    val launcherHidden = !launcher || FloatyManager.hideLauncherForScreenshot()
                    val consoleHidden = !console || ConsoleOverlay.hideForScreenshot()
                    if (launcherHidden && consoleHidden) {
                        hiddenAt = SystemClock.uptimeMillis()
                        hiddenCaptureOverlays = HiddenCaptureOverlays(context, launcher, console, hiddenAt)
                        prepared = true
                    } else {
                        if (launcherHidden && launcher) FloatyManager.restoreLauncherAfterScreenshot()
                        if (consoleHidden && console) ConsoleOverlay.restoreAfterScreenshot()
                    }
                }
            }
        }
        if (!onMain || !prepared) return null

        return try {
            if (hiddenAt == 0L) capture(0)
            else {
                // 维持原有等待：首次确保窗口已从合成画面消失，后续给目标界面更新留时间。
                Thread.sleep(100)
                capture(hiddenAt)
            }
        } finally {
            if (hiddenAt != 0L) mainHandler.post {
                mainHandler.removeCallbacks(restoreCaptureOverlaysRunnable)
                val remaining = SCREENSHOT_OVERLAY_MAX_HIDDEN_MS -
                    (SystemClock.uptimeMillis() - hiddenAt)
                mainHandler.postDelayed(
                    restoreCaptureOverlaysRunnable,
                    minOf(SCREENSHOT_OVERLAY_IDLE_MS, remaining.coerceAtLeast(0L))
                )
            }
        }
    }

    /** 主线程调用；运行结束时立即恢复，普通截图则由空闲定时器恢复。 */
    private fun restoreCaptureOverlaysNow() {
        mainHandler.removeCallbacks(restoreCaptureOverlaysRunnable)
        val hidden = hiddenCaptureOverlays ?: return
        hiddenCaptureOverlays = null
        if (hidden.launcher) {
            if (_state.value.running) {
                if (!FloatyManager.restoreLauncherAfterScreenshot()) {
                    FloatyManager.showLauncher(hidden.context)
                }
            } else if (!_state.value.running) {
                FloatyManager.hideLauncher(hidden.context, stopKeepAlive = false)
            }
        }
        if (hidden.console && ConsoleOverlay.isShowing() && !ConsoleOverlay.restoreAfterScreenshot()) {
            ConsoleOverlay.show(hidden.context)
        }
    }

    private fun runOnMainBlocking(action: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                action()
                true
            } catch (_: Exception) {
                false
            }
        }
        val completed = CountDownLatch(1)
        var succeeded = true
        if (!mainHandler.post {
                try {
                    action()
                } catch (_: Exception) {
                    succeeded = false
                } finally {
                    completed.countDown()
                }
            }) return false
        return try {
            completed.await(2, TimeUnit.SECONDS) && succeeded
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 追加一条运行日志：StateFlow（UI 面板）+ logcat（调试）；同内容短时间重复会自动折叠 */
    private fun addLog(level: Char, msg: String) {
        val now = System.currentTimeMillis()
        synchronized(lastLogLock) {
            if (msg == lastLogMsg && now - lastLogAt < 500) return
            lastLogMsg = msg
            lastLogAt = now
            val entry = LogEntry(timeFormat.format(Date(now)), level, msg, ++nextLogId)
            _logs.update { list -> logSnapshotBuffer.append(list, entry) }
        }
        Log.println(
            when (level) {
                'E' -> Log.ERROR
                'W' -> Log.WARN
                'D' -> Log.DEBUG
                else -> Log.INFO
            },
            "ScriptJs", msg
        )
    }

    private const val MAX_LOGS = 300
}
