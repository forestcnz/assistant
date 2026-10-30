package com.forest.android.assistant.core.engine

import android.graphics.Bitmap
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.forest.android.assistant.core.gesture.GestureClient
import com.forest.android.assistant.core.image.ImageApi
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.ocr.OcrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import java.io.File

/**
 * 脚本引擎：QuickJS 封装 + Bridge 注入。
 * - 每次运行创建独立 QuickJS 实例（隔离作用域，支持多脚本并行）
 * - 注入全局函数：sleep/random/exit/log/console/toastLog、auto、click/swipe/gesture 系、
 *   requestScreenCapture/captureScreen/waitImage/waitImageVanish（截屏找图）
 * - 坐标 API 同步阻塞（内部派发手势并等待完成），符合脚本顺序执行心智
 * 对应设计见 docs/README.md 第 2.2 节，脚本 API 见 docs/README.md 第 5 章。
 */
object ScriptEngine {

    /** 脚本运行环境回调：日志/Toast/跳转系统设置/截屏授权由宿主（ScriptRunner）提供 */
    data class ScriptEnv(
        val scriptPath: String,
        val onLog: (level: Char, msg: String) -> Unit,
        val onToast: (msg: String) -> Unit,
        val openAccessibilitySettings: () -> Unit,
        val requestScreenCapture: () -> Boolean,
        val screenCaptureReady: () -> Boolean,
        /** Capture after the host has temporarily hidden its own floating controls. */
        val captureScreen: () -> Bitmap?
    )

    enum class OutcomeKind { FINISHED, EXITED, STOPPED, ERROR }

    data class RunOutcome(
        val kind: OutcomeKind,
        val message: String? = null,
        /** 出错时的异常堆栈（写入运行历史） */
        val stack: String? = null,
        /** 出错位置（脚本行/列，1 起）；解析不到为 null */
        val errorLine: Int? = null,
        val errorColumn: Int? = null
    )

    /** exit() 抛出的专用异常，按正常退出处理 */
    private class ScriptExitException : RuntimeException("exit() 被调用")

    /** 停止请求抛出的专用异常（悬浮条停止按钮/超时保护），按用户停止处理 */
    private class ScriptStoppedException : RuntimeException("脚本被停止")

    /** 单次运行的取消会话：绑定函数内的阻塞循环轮询此标志实现快速中断 */
    private class Session(
        /** 运行所在协程的 Job（测试环境可能为 null） */
        val job: Job?
    ) {
        @Volatile
        var cancelled = false

        /** 本次运行是否已自动重新申请过截屏（每轮最多一次，避免反复弹窗） */
        @Volatile
        var captureReRequested = false

        // VM 句柄：interruptEvaluation() 可中断纯 JS 忙循环；close 仍由执行线程完成，
        // 避免销毁正在执行的 VM（见下面 detach 与 finally）
        private var runtime: QuickJs? = null

        @Synchronized
        fun attach(vm: QuickJs) {
            runtime = vm
        }

        /** 解除 VM 引用；之后 interrupt 退化为只置标志 */
        @Synchronized
        fun detach() {
            runtime = null
        }

        /**
         * 置停止标志并请求中断 VM。
         * interruptEvaluation() 内部有锁，不应在调用方线程（可能是主线程）直接等待，
         * 因此由独立线程执行；VM 已销毁时该调用无副作用。
         */
        @Synchronized
        fun cancel() {
            cancelled = true
            val vm = runtime ?: return
            Thread {
                runCatching { vm.interruptEvaluation() }
            }.apply {
                name = "ScriptJs-interrupt"
                isDaemon = true
            }.start()
        }
    }

    @Volatile
    private var activeSession: Session? = null

    /** 请求中断当前正在运行的脚本（由 ScriptRunner.stop 调用） */
    fun cancel() {
        activeSession?.cancel()
    }

    /**
     * 执行脚本并阻塞至结束（挂起函数，宿主取消协程亦可中断）。
     * 绑定函数同步阻塞执行引擎线程，与脚本顺序执行语义一致。
     */
    suspend fun execute(env: ScriptEnv, script: String): RunOutcome {
        val session = Session(coroutineContext[Job])
        activeSession = session
        val outcome: RunOutcome = try {
            ImageApi.debugLogger = { level, msg -> env.onLog(level, msg) }
            ImageApi.cancellationCheck = { checkStop(session) }
            quickJs {
                session.attach(this)
                try {
                    checkStop(session)
                    defineGlobals(session, env)
                    // 末尾补 undefined 丢弃脚本完成值：否则函数/对象结果会走类型转换而报错
                    evaluate<JsObject?>(PRELUDE + ScriptApiExtensions.PRELUDE + DISCARD_RESULT, "prelude.js")
                    checkStop(session)
                    evaluate<JsObject?>(script + DISCARD_RESULT, env.scriptPath)
                    checkStop(session)
                    RunOutcome(OutcomeKind.FINISHED)
                } finally {
                    session.detach()
                }
            }
        } catch (e: QuickJsInterruptedException) {
            // interruptEvaluation() 或宿主取消协程触发：按用户停止处理
            RunOutcome(OutcomeKind.STOPPED)
        } catch (e: ScriptStoppedException) {
            RunOutcome(OutcomeKind.STOPPED)
        } catch (e: CancellationException) {
            RunOutcome(OutcomeKind.STOPPED)
        } catch (e: ScriptExitException) {
            RunOutcome(OutcomeKind.EXITED)
        } catch (e: Throwable) {
            val marker = generateSequence<Throwable>(e) { it.cause }
                .firstOrNull {
                    it is ScriptExitException || it is ScriptStoppedException ||
                            it is QuickJsInterruptedException
                }
            when (marker) {
                is ScriptExitException -> RunOutcome(OutcomeKind.EXITED)
                is ScriptStoppedException -> RunOutcome(OutcomeKind.STOPPED)
                is QuickJsInterruptedException -> RunOutcome(OutcomeKind.STOPPED)
                else -> {
                    env.onLog('E', "脚本异常: ${e.message}")
                    val pos = errorPositionOf(e)
                    RunOutcome(
                        kind = OutcomeKind.ERROR,
                        message = e.message,
                        // 优先记录 QuickJS 给出的 JS 堆栈（含脚本行列），否则退回 JVM 堆栈
                        stack = jsStackOf(e) ?: e.stackTraceToString(),
                        errorLine = pos?.first,
                        errorColumn = pos?.second
                    )
                }
            }
        } finally {
            if (activeSession === session) activeSession = null
            ImageApi.debugLogger = null
            ImageApi.cancellationCheck = null
            // 释放本次运行持有的所有图片句柄，避免 Bitmap 泄漏
            ImageApi.clearImages()
        }
        return outcome
    }

    /**
     * 脚本出错位置（行/列，1 起）。
     * quickjs-kt 1.0.9+ 的 QuickJsException 提供结构化 location，优先使用；
     * 其他异常或老版本回退到从消息中解析。
     */
    private fun errorPositionOf(e: Throwable): Pair<Int, Int>? {
        val qe = quickJsExceptionOf(e)
        qe?.let { exception ->
            val line = exception.lineNumber
            val column = exception.columnNumber
            if (line != null && line > 0) return Pair(line, column?.takeIf { it > 0 } ?: 1)
        }
        return parseErrorPosition(e.message)
    }

    /** QuickJS 提供的 JS 侧调用堆栈；没有结构化堆栈时返回 null */
    private fun jsStackOf(e: Throwable): String? =
        quickJsExceptionOf(e)?.stack?.takeIf { it.isNotBlank() }

    /** 沿 cause 链找第一个 QuickJsException（QuickJS 错误可能被包装） */
    private fun quickJsExceptionOf(e: Throwable): QuickJsException? =
        generateSequence(e) { it.cause }.filterIsInstance<QuickJsException>().firstOrNull()

    /**
     * 从异常消息中解析脚本出错位置。
     * QuickJS 的报错形如：
     *   ReferenceError: xxx is not defined
     *       at <eval> (/path/to/task.js:3:1)
     * 取最后一个 "行:列" 作为出错位置（最内层帧）。
     */
    private fun parseErrorPosition(message: String?): Pair<Int, Int>? {
        if (message.isNullOrBlank()) return null
        val matches = Regex(":(\\d+):(\\d+)").findAll(message).toList()
        val m = matches.lastOrNull() ?: return null
        val line = m.groupValues[1].toIntOrNull() ?: return null
        val column = m.groupValues[2].toIntOrNull() ?: return null
        return Pair(line, column)
    }

    /** 检查停止标志；绑定函数内的长循环/休眠周期性调用 */
    private fun checkStop(session: Session) {
        if (session.cancelled) throw ScriptStoppedException()
    }

    /** 注入全局 Bridge：JS 函数名 → Kotlin 实现（全部同步绑定） */
    private fun QuickJs.defineGlobals(session: Session, env: ScriptEnv) {
        // 带停止检查的绑定注册：每个绑定函数入口先查停止标志。
        // interruptEvaluation() 只能中断 JS 字节码执行，无法打断已经进入的 Kotlin 原生调用
        // （截屏/找图/OCR/手势等待）；因此两层机制并存：
        // 1) VM 中断负责纯 JS 忙循环，毫秒级生效；
        // 2) 绑定入口检查保证原生调用返回后立刻停止，不再继续跑本轮剩余步骤。
        fun guard(name: String, block: (args: Array<Any?>) -> Any?) {
            function(name) { args ->
                checkStop(session)
                block(args).also { checkStop(session) }
            }
        }

        // —— 基础工具（API 1.2）——
        guard("sleep") { args ->
            val ms = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
            var remaining = ms.coerceAtLeast(0)
            while (remaining > 0) {
                checkStop(session)
                val step = if (remaining > 50) 50 else remaining
                Thread.sleep(step)
                remaining -= step
            }
            null
        }
        guard("random") { args ->
            val min = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val max = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            if (max <= min) min else Random.nextInt(min, max + 1)
        }
        guard("exit") { _ ->
            throw ScriptExitException()
        }
        guard("toastLog") { args ->
            val msg = args.getOrNull(0)?.toString() ?: ""
            env.onLog('I', msg)
            env.onToast(msg)
            null
        }
        guard("__log") { args ->
            val level = (args.getOrNull(0) as? String)?.firstOrNull() ?: 'I'
            env.onLog(level, args.getOrNull(1)?.toString() ?: "")
            null
        }

        // —— 无障碍（API 1.1）——
        guard("__autoService") { _ -> GestureClient.available() }
        guard("__autoWaitFor") { args ->
            val timeoutMs = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
            if (GestureClient.available()) return@guard true
            env.onLog('I', "等待无障碍服务开启…")
            val start = System.currentTimeMillis()
            while (!GestureClient.available()) {
                checkStop(session)
                if (System.currentTimeMillis() - start >= timeoutMs) {
                    env.onLog('W', "无障碍服务未开启，坐标操作将返回 false")
                    return@guard false
                }
                Thread.sleep(100)
            }
            env.onLog('I', "无障碍服务已就绪")
            true
        }
        guard("__autoOpenSettings") { _ ->
            env.onLog('I', "跳转无障碍设置页")
            env.openAccessibilitySettings()
            null
        }

        // —— 坐标与手势（API 2.1，默认参数在绑定侧处理；坐标经 adapter 换算到真机）——
        guard("click") { args ->
            val (x, y) = xy(args) ?: return@guard false
            val duration = (args.getOrNull(2) as? Number)?.toLong() ?: 30L
            GestureClient.click(rx(x), ry(y), duration)
                .also { logGesture(env, "click", x, y, it) }
        }
        guard("clickRandom") { args ->
            val (x, y) = xy(args) ?: return@guard false
            val range = (args.getOrNull(2) as? Number)?.toFloat() ?: 5f
            val duration = (args.getOrNull(3) as? Number)?.toLong() ?: 100L
            val ox = Random.nextFloat() * 2 * range - range
            val oy = Random.nextFloat() * 2 * range - range
            GestureClient.click(rx(x + ox), ry(y + oy), duration)
                .also { logGesture(env, "clickRandom", x + ox, y + oy, it) }
        }
        guard("longClick") { args ->
            val (x, y) = xy(args) ?: return@guard false
            val duration = (args.getOrNull(2) as? Number)?.toLong() ?: 600L
            GestureClient.longClick(rx(x), ry(y), duration)
                .also { logGesture(env, "longClick", x, y, it) }
        }
        guard("press") { args ->
            val (x, y) = xy(args) ?: return@guard false
            val duration = (args.getOrNull(2) as? Number)?.toLong() ?: 500L
            GestureClient.longClick(rx(x), ry(y), duration)
                .also { logGesture(env, "press", x, y, it) }
        }
        guard("swipe") { args ->
            if (args.size < 4 || !args.take(4).all { it is Number }) return@guard false
            val duration = (args.getOrNull(4) as? Number)?.toLong() ?: 500L
            val x1 = (args[0] as Number).toFloat()
            val y1 = (args[1] as Number).toFloat()
            val x2 = (args[2] as Number).toFloat()
            val y2 = (args[3] as Number).toFloat()
            GestureClient.swipe(rx(x1), ry(y1), rx(x2), ry(y2), duration)
                .also { logGesture(env, "swipe", x1, y1, it, x2, y2) }
        }
        guard("swipeRandom") { args ->
            if (args.size < 4 || !args.take(4).all { it is Number }) return@guard false
            val range = (args.getOrNull(4) as? Number)?.toFloat() ?: 10f
            val duration = (args.getOrNull(5) as? Number)?.toLong() ?: 500L
            fun rnd(v: Number) = v.toFloat() + Random.nextFloat() * 2 * range - range
            val x1 = rnd(args[0] as Number)
            val y1 = rnd(args[1] as Number)
            val x2 = rnd(args[2] as Number)
            val y2 = rnd(args[3] as Number)
            GestureClient.swipe(rx(x1), ry(y1), rx(x2), ry(y2), duration)
                .also { logGesture(env, "swipeRandom", x1, y1, it, x2, y2) }
        }
        guard("gesture") { args ->
            val duration = (args.getOrNull(0) as? Number)?.toLong() ?: return@guard false
            var delayMs = 0L
            val points = ArrayList<Pair<Float, Float>>()
            for (a in args.drop(1)) {
                when (a) {
                    is List<*> -> if (a.size >= 2 && a[0] is Number && a[1] is Number) {
                        points.add(Pair((a[0] as Number).toFloat(), (a[1] as Number).toFloat()))
                    }
                    is Number -> delayMs = a.toLong()
                }
            }
            val real = points.map { Pair(rx(it.first), ry(it.second)) }
            GestureClient.gesture(duration, real, delayMs)
                .also { logGesture(env, "gesture", points.firstOrNull()?.first ?: 0f, points.firstOrNull()?.second ?: 0f, it) }
        }

        // —— 多指手势（API 2.2；扁平数组传参，避免嵌套数组转换差异）——
        guard("__gestures_available") { _ -> GestureClient.available() }
        guard("__gestures") { args ->
            val flat = args.getOrNull(0) as? List<*> ?: return@guard false
            fun num(i: Int) = (flat.getOrNull(i) as? Number)?.toLong() ?: 0L
            var idx = 0
            val count = num(idx++).toInt()
            if (count <= 0) return@guard false
            val strokes = ArrayList<GestureClient.Stroke>(count)
            for (s in 0 until count) {
                if (idx + 2 >= flat.size) break
                val duration = num(idx++)
                val delay = num(idx++)
                val pointCount = num(idx++).toInt()
                if (pointCount < 2 || idx + pointCount * 2 > flat.size) return@guard false
                val pts = ArrayList<Pair<Float, Float>>(pointCount)
                for (p in 0 until pointCount) {
                    val x = num(idx++).toFloat()
                    val y = num(idx++).toFloat()
                    pts.add(Pair(rx(x), ry(y)))
                }
                strokes.add(GestureClient.Stroke(duration, delay, pts))
            }
            if (strokes.isEmpty()) return@guard false
            // 多笔必须同时起手：各指延迟不一致时统一为同时（否则系统会拒绝该手势）
            val delays = strokes.map { it.startDelayMs }.distinct()
            val common = if (delays.size == 1) delays[0] else 0L
            if (delays.size > 1) {
                env.onLog('W', "多指手势各指延迟不一致，已统一为同时起手")
            }
            val normalized = strokes.map { it.copy(startDelayMs = common) }
            val ok = GestureClient.gestures(normalized)
            val detail = normalized.joinToString(" | ") { st ->
                "(${st.points.joinToString(",") { "(${it.first.toInt()},${it.second.toInt()})" }})"
            }
            env.onLog(
                if (ok) 'D' else 'W',
                "gestures(${normalized.size} 指, ${normalized[0].durationMs}ms) -> $ok  [$detail]"
            )
            ok
        }

        // —— OCR（API 第 4 节；结果为扁平数组，由 PRELUDE 组装成 OcrItem）——
        guard("__ocr_init") { args ->
            val engine = args.getOrNull(0)?.toString()
            val ok = OcrApi.init(engine)
            env.onLog(if (ok) 'I' else 'E', "OCR 初始化: ${engine ?: "mlkit"} -> $ok")
            ok
        }
        guard("__ocr_ready") { _ -> OcrApi.isReady() }
        guard("__ocr_recognize") { args ->
            val bmp = ImageApi.image((args.getOrNull(0) as? Number)?.toInt()) ?: return@guard emptyList<Any>()
            OcrApi.recognize(bmp, regionOf(args, 1)).map { ocrItem(it) }
        }
        guard("__ocr_text") { args ->
            val bmp = ImageApi.image((args.getOrNull(0) as? Number)?.toInt()) ?: return@guard ""
            OcrApi.recognizeText(bmp, regionOf(args, 1))
        }
        guard("__ocr_find") { args ->
            val bmp = ImageApi.image((args.getOrNull(0) as? Number)?.toInt()) ?: return@guard emptyList<Any>()
            val text = args.getOrNull(1)?.toString() ?: return@guard emptyList<Any>()
            val all = (args.getOrNull(6) as? Number)?.toInt() == 1
            OcrApi.find(bmp, text, regionOf(args, 2), all).map { ocrItem(it) }
        }
        guard("__ocr_find_contains") { args ->
            val bmp = ImageApi.image((args.getOrNull(0) as? Number)?.toInt()) ?: return@guard emptyList<Any>()
            val text = args.getOrNull(1)?.toString() ?: return@guard emptyList<Any>()
            OcrApi.findContains(bmp, text, regionOf(args, 2))?.let { ocrItem(it) } ?: emptyList<Any>()
        }
        // 多关键词：一次 OCR 命中任意关键词即返回 [文字, left, top, right, bottom, confidence, 命中词]
        guard("__ocr_find_contains_any") { args ->
            val bmp = ImageApi.image((args.getOrNull(0) as? Number)?.toInt()) ?: return@guard emptyList<Any>()
            val texts = flattenStrings(args.getOrNull(1))
            if (texts.isEmpty()) return@guard emptyList<Any>()
            OcrApi.findContainsAny(bmp, texts, regionOf(args, 2))?.let { match ->
                ocrItem(match.item) + listOf(match.keyword)
            } ?: emptyList<Any>()
        }
        guard("__ocr_find_any_checked") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt()
                ?: return@guard listOf("invalid_image")
            val bitmap = ImageApi.image(imageId) ?: return@guard listOf("invalid_image")
            val texts = flattenStrings(args.getOrNull(1))
            if (texts.isEmpty() || texts.none { com.forest.android.assistant.core.ocr.OcrTextMatcher.normalize(it).isNotEmpty() }) {
                return@guard listOf("invalid_query")
            }
            val region = regionOf(args, 2)
            val recognition = OcrApi.recognizeResult(bitmap, region)
            if (!recognition.succeeded) return@guard listOf("ocr_error", recognition.error ?: "OCR 识别失败")
            val match = OcrApi.findContainsAny(recognition.items, texts)
                ?: return@guard listOf("miss")
            listOf("matched", ocrItem(match.item) + listOf(match.keyword))
        }

        // —— adapter 分辨率适配（API 第 6 节）——
        guard("__adapter_set") { args ->
            ScriptAdapter.setDesignSize(numArg(args, 0).toInt(), numArg(args, 1).toInt())
            null
        }
        guard("__adapter_reset") { _ ->
            ScriptAdapter.reset()
            null
        }
        guard("__adapter_enabled") { _ -> ScriptAdapter.enabled() }
        guard("__adapter_scale") { args ->
            listOf(
                ScriptAdapter.toRealX(numArg(args, 0).toFloat()).toLong(),
                ScriptAdapter.toRealY(numArg(args, 1).toFloat()).toLong()
            )
        }

        // —— 截屏与图像（API 第 3 节；由 PRELUDE 组装成手册形态，这里只收标量参数）——
        val scriptDir = File(env.scriptPath).parentFile ?: File("/")
        guard("__capture_ready") { _ -> env.screenCaptureReady() }
        guard("__request_capture") { _ -> env.requestScreenCapture() }
        guard("__img_capture") { _ ->
            var bmp = env.captureScreen()
            // 未授权或授权失效（切屏/息屏）：按设计「找图失败自动重新请求」自动再申请一次
            if (bmp == null && !ScreenCapture.isReady() && !session.captureReRequested) {
                session.captureReRequested = true
                env.onLog('W', "截屏不可用（未授权或授权已失效），自动重新申请屏幕录制…")
                if (env.requestScreenCapture()) {
                    bmp = env.captureScreen()
                }
            }
            if (bmp == null) {
                env.onLog(
                    'W',
                    if (ScreenCapture.isReady()) "截屏暂无可用帧，请稍后重试"
                    else "截屏未授权：请先调用 requestScreenCapture()"
                )
                -1L
            } else {
                ImageApi.storeImage(bmp, "屏幕截图").toLong()
            }
        }
        guard("__img_read") { args ->
            val path = args.getOrNull(0)?.toString() ?: return@guard -1L
            val id = ImageApi.read(path, scriptDir)
            if (id == null) {
                env.onLog('W', "读图失败: $path")
                -1L
            } else {
                id.toLong()
            }
        }
        guard("__img_save") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard false
            val path = args.getOrNull(1)?.toString() ?: return@guard false
            val format = args.getOrNull(2) as? String
            val quality = (args.getOrNull(3) as? Number)?.toInt() ?: 100
            ImageApi.save(id, path, scriptDir, format, quality)
        }
        guard("__img_clip") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard -1L
            val x = numArg(args, 1).toInt()
            val y = numArg(args, 2).toInt()
            val w = numArg(args, 3).toInt()
            val h = numArg(args, 4).toInt()
            (ImageApi.clip(id, x, y, w, h) ?: -1).toLong()
        }
        guard("__img_resize") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard -1L
            val width = (args.getOrNull(1) as? Number)?.toInt() ?: return@guard -1L
            val height = (args.getOrNull(2) as? Number)?.toInt() ?: return@guard -1L
            val filter = args.getOrNull(3) as? Boolean ?: true
            (ImageApi.resize(id, width, height, filter) ?: -1).toLong()
        }
        guard("__img_pixel") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard -1L
            val color = ImageApi.pixel(id, numArg(args, 1).toInt(), numArg(args, 2).toInt())
            color?.toLong()?.and(0xFFFFFFFFL) ?: -1L
        }
        guard("__img_width") { args ->
            (ImageApi.width((args.getOrNull(0) as? Number)?.toInt() ?: -1)).toLong()
        }
        guard("__img_height") { args ->
            (ImageApi.height((args.getOrNull(0) as? Number)?.toInt() ?: -1)).toLong()
        }
        guard("__img_release") { args ->
            (args.getOrNull(0) as? Number)?.toInt()?.let { ImageApi.releaseImage(it) }
            null
        }
        guard("__img_find") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard null
            val tplId = resolveTemplate(args.getOrNull(1), scriptDir) ?: return@guard null
            val threshold = (args.getOrNull(2) as? Number)?.toDouble() ?: return@guard null
            val region = regionOf(args, 3)
            ImageApi.findImage(imageId, tplId, threshold, region, scalesOf(args, 7))?.let { pt(it) }
        }
        guard("__img_find_all") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard emptyList<Any>()
            val tplId = resolveTemplate(args.getOrNull(1), scriptDir) ?: return@guard emptyList<Any>()
            val threshold = (args.getOrNull(2) as? Number)?.toDouble() ?: return@guard emptyList<Any>()
            val region = regionOf(args, 3)
            val max = (args.getOrNull(7) as? Number)?.toInt() ?: 5
            ImageApi.findAllImages(imageId, tplId, threshold, region, max, scalesOf(args, 8)).map { pt(it) }
        }
        guard("__img_match_details") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard emptyList<Any>()
            val templateId = (args.getOrNull(1) as? Number)?.toInt() ?: return@guard emptyList<Any>()
            val threshold = (args.getOrNull(2) as? Number)?.toDouble() ?: return@guard emptyList<Any>()
            val max = (args.getOrNull(7) as? Number)?.toInt() ?: 5
            val templateWidth = ImageApi.width(templateId)
            val templateHeight = ImageApi.height(templateId)
            if (templateWidth <= 0 || templateHeight <= 0) return@guard emptyList<Any>()
            ImageApi.findImageDetails(imageId, templateId, threshold, regionOf(args, 3), max, scalesOf(args, 8)).map { match ->
                val width = match.width.takeIf { it > 0 } ?: templateWidth
                val height = match.height.takeIf { it > 0 } ?: templateHeight
                listOf(
                    ScriptAdapter.toDesignX(match.x).toLong(),
                    ScriptAdapter.toDesignY(match.y).toLong(),
                    match.score,
                    ScriptAdapter.toDesignX(match.x - width / 2).toLong(),
                    ScriptAdapter.toDesignY(match.y - height / 2).toLong(),
                    ScriptAdapter.toDesignX(match.x - width / 2 + width).toLong(),
                    ScriptAdapter.toDesignY(match.y - height / 2 + height).toLong()
                )
            }
        }
        guard("__img_exists") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard false
            val tplId = resolveTemplate(args.getOrNull(1), scriptDir) ?: return@guard false
            val threshold = (args.getOrNull(2) as? Number)?.toDouble() ?: return@guard false
            ImageApi.existsImage(imageId, tplId, threshold, regionOf(args, 3), scalesOf(args, 7))
        }
        guard("__img_find_with_text") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard null
            val tplId = resolveTemplate(args.getOrNull(1), scriptDir) ?: return@guard null
            val threshold = (args.getOrNull(2) as? Number)?.toDouble() ?: return@guard null
            val region = regionOf(args, 3)
            val padding = (args.getOrNull(7) as? Number)?.toInt() ?: 0
            val m = ImageApi.findImageWithText(imageId, tplId, threshold, region, padding, scalesOf(args, 8))
                ?: return@guard null
            // [x, y, left, top, right, bottom, text, items]，由 JS 侧组装成 Match 对象
            listOf(
                ScriptAdapter.toDesignX(m.x).toLong(),
                ScriptAdapter.toDesignY(m.y).toLong(),
                ScriptAdapter.toDesignX(m.left).toLong(),
                ScriptAdapter.toDesignY(m.top).toLong(),
                ScriptAdapter.toDesignX(m.right).toLong(),
                ScriptAdapter.toDesignY(m.bottom).toLong(),
                m.text,
                m.items.map { ocrItem(it) }
            )
        }
        guard("__img_find_color") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard null
            // 色值可能是超过 Int 范围的正数（ARGB），先经 Long 再截断，避免 Double.toInt() 饱和
            val color = (args.getOrNull(1) as? Number)?.toLong()?.toInt() ?: return@guard null
            val threshold = (args.getOrNull(2) as? Number)?.toInt() ?: 16
            ImageApi.findColor(imageId, color, threshold, regionOf(args, 3))?.let { pt(it) }
        }
        // 双模板：返回扁平数组 [sameRow, rowDelta, tolerance, midX, midY, dx, dy,
        //   aX, aY, aLeft, aTop, aRight, aBottom, bX, bY, bLeft, bTop, bRight, bBottom]
        // 参数顺序：imageId, tplA, tplB, thresholdA, thresholdB, rx, ry, rw, rh, rowTolerance
        guard("__img_find_pair") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard null
            val tplA = resolveTemplate(args.getOrNull(1), scriptDir) ?: return@guard null
            val tplB = resolveTemplate(args.getOrNull(2), scriptDir) ?: return@guard null
            val thresholdA = (args.getOrNull(3) as? Number)?.toDouble() ?: 0.9
            val thresholdB = (args.getOrNull(4) as? Number)?.toDouble() ?: 0.9
            val region = regionOf(args, 5)              // 读取 5..8 四个分量
            val rowTolerance = (args.getOrNull(9) as? Number)?.toInt() ?: 0
            // 所有找图入口均采用轮廓匹配，倍率参数传入同一个匹配入口。
            val pair = ImageApi.findImagePair(
                imageId, tplA, tplB, thresholdA, thresholdB, region, rowTolerance, scalesOf(args, 10)
            ) ?: return@guard null
            listOf(
                if (pair.sameRow) 1L else 0L,
                pair.rowDelta.toLong(),
                pair.tolerance.toLong(),
                ScriptAdapter.toDesignX(pair.midX).toLong(),
                ScriptAdapter.toDesignY(pair.midY).toLong(),
                ScriptAdapter.toDesignX(pair.dx).toLong(),
                ScriptAdapter.toDesignY(pair.dy).toLong(),
                ScriptAdapter.toDesignX(pair.a.x).toLong(),
                ScriptAdapter.toDesignY(pair.a.y).toLong(),
                ScriptAdapter.toDesignX(pair.a.left).toLong(),
                ScriptAdapter.toDesignY(pair.a.top).toLong(),
                ScriptAdapter.toDesignX(pair.a.right).toLong(),
                ScriptAdapter.toDesignY(pair.a.bottom).toLong(),
                ScriptAdapter.toDesignX(pair.b.x).toLong(),
                ScriptAdapter.toDesignY(pair.b.y).toLong(),
                ScriptAdapter.toDesignX(pair.b.left).toLong(),
                ScriptAdapter.toDesignY(pair.b.top).toLong(),
                ScriptAdapter.toDesignX(pair.b.right).toLong(),
                ScriptAdapter.toDesignY(pair.b.bottom).toLong()
            )
        }
        guard("__img_find_multi") { args ->
            val imageId = (args.getOrNull(0) as? Number)?.toInt() ?: return@guard null
            val first = (args.getOrNull(1) as? Number)?.toInt() ?: return@guard null
            val flat = args.getOrNull(2) as? List<*> ?: return@guard null
            val threshold = (args.getOrNull(3) as? Number)?.toInt() ?: 16
            val offsets = ArrayList<IntArray>()
            var i = 0
            while (i + 2 < flat.size) {
                val dx = (flat[i] as? Number)?.toInt()
                val dy = (flat[i + 1] as? Number)?.toInt()
                val c = (flat[i + 2] as? Number)?.toInt()
                if (dx != null && dy != null && c != null) offsets.add(intArrayOf(dx, dy, c))
                i += 3
            }
            ImageApi.findMultiColors(imageId, first, offsets, threshold, regionOf(args, 4))
                ?.let { pt(it) }
        }

        // —— 全局常量 ——
        guard("__scriptPath") { _ -> env.scriptPath }
    }

    /** 取前两个 number 参数为坐标；非法时返回 null */
    private fun xy(args: Array<Any?>): Pair<Float, Float>? {
        val x = args.getOrNull(0) as? Number ?: return null
        val y = args.getOrNull(1) as? Number ?: return null
        return Pair(x.toFloat(), y.toFloat())
    }

    private fun numArg(args: Array<Any?>, index: Int): Long =
        (args.getOrNull(index) as? Number)?.toLong() ?: 0L

    /** JS 侧的倍率数组传到统一找图入口；无效条目交给匹配器拒绝。 */
    private fun scalesOf(args: Array<Any?>, index: Int): List<Double> =
        (args.getOrNull(index) as? List<*>)?.map { (it as? Number)?.toDouble() ?: Double.NaN }
            ?: listOf(1.0)

    /** JS 数组 → 字符串列表（非字符串项转成文本，null/undefined 忽略） */
    private fun flattenStrings(arg: Any?): List<String> = when (arg) {
        is List<*> -> arg.mapNotNull { it?.toString()?.takeIf(String::isNotEmpty) }
        else -> emptyList()
    }

    /** 模板引用：数字 = Image 句柄，字符串 = 相对脚本目录的路径 */
    private fun resolveTemplate(arg: Any?, scriptDir: File): Int? = when (arg) {
        is Number -> arg.toInt().takeIf { it > 0 }
        is String -> ImageApi.read(arg, scriptDir)
        else -> null
    }

    /** 坐标结果：真机坐标换算回设计坐标（未启用适配时原样） */
    private fun pt(p: ImageApi.Point) = listOf(
        ScriptAdapter.toDesignX(p.x).toLong(),
        ScriptAdapter.toDesignY(p.y).toLong()
    )

    /** OCR 结果：文字 + 边界（换算到设计坐标）+ 置信度 */
    private fun ocrItem(item: OcrApi.Item): List<Any> = listOf(
        item.text,
        ScriptAdapter.toDesignX(item.left).toLong(),
        ScriptAdapter.toDesignY(item.top).toLong(),
        ScriptAdapter.toDesignX(item.right).toLong(),
        ScriptAdapter.toDesignY(item.bottom).toLong(),
        item.confidence.toDouble()
    )

    /** 读取 [rx, ry, rw, rh] 四个参数；全为负表示全图（返回 null） */
    private fun regionOf(args: Array<Any?>, start: Int): IntArray? {
        val rx = numArg(args, start).toInt()
        val ry = numArg(args, start + 1).toInt()
        val rw = numArg(args, start + 2).toInt()
        val rh = numArg(args, start + 3).toInt()
        if (rx < 0 && ry < 0 && rw < 0 && rh < 0) return null
        // 区域同样是脚本侧的设计坐标，换算到真机再交给匹配器
        val rl = ScriptAdapter.toRealX(rx.coerceAtLeast(0).toFloat()).toInt()
        val rt = ScriptAdapter.toRealY(ry.coerceAtLeast(0).toFloat()).toInt()
        val rr = if (rw <= 0) -1 else ScriptAdapter.toRealX((rx + rw).toFloat()).toInt() - rl
        val rb = if (rh <= 0) -1 else ScriptAdapter.toRealY((ry + rh).toFloat()).toInt() - rt
        return intArrayOf(rl, rt, rr, rb)
    }

    private fun logGesture(
        env: ScriptEnv,
        name: String,
        x: Float,
        y: Float,
        ok: Boolean,
        x2: Float? = null,
        y2: Float? = null
    ) {
        val coords = if (x2 != null && y2 != null) {
            "(${fmt(x)}, ${fmt(y)}) → (${fmt(x2)}, ${fmt(y2)})"
        } else {
            "(${fmt(x)}, ${fmt(y)})"
        }
        // 启用分辨率适配时附上换算后的真机坐标，便于排查
        val real = if (ScriptAdapter.enabled()) {
            if (x2 != null && y2 != null) {
                "  [真机 (${rx(x).toInt()}, ${ry(y).toInt()}) → (${rx(x2).toInt()}, ${ry(y2).toInt()})]"
            } else {
                "  [真机 (${rx(x).toInt()}, ${ry(y).toInt()})]"
            }
        } else {
            ""
        }
        env.onLog(if (ok) 'D' else 'W', "$name$coords -> $ok$real")
    }

    private fun fmt(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

    /** 设计坐标 → 真机坐标（未启用适配时原样返回） */
    private fun rx(x: Float): Float = ScriptAdapter.toRealX(x)

    private fun ry(y: Float): Float = ScriptAdapter.toRealY(y)

    /**
     * 追加到脚本末尾，把完成值置为 undefined。
     * quickjs-kt 的 evaluate 会把完成值按请求类型转换，undefined 走 null 分支直接返回。
     */
    private const val DISCARD_RESULT = "\n;undefined;\n"

    /**
     * JS 预置脚本：用少量 JS 胶水把绑定函数组装成 API 形态
     * （console 对象、auto 可调用对象 + service 属性、print 别名）。
     */
    private const val PRELUDE = """
var __scriptPath__ = __scriptPath();
var console = {
    log: function() { __log('I', Array.prototype.join.call(arguments, ' ')); },
    info: function() { __log('I', Array.prototype.join.call(arguments, ' ')); },
    warn: function() { __log('W', Array.prototype.join.call(arguments, ' ')); },
    error: function() { __log('E', Array.prototype.join.call(arguments, ' ')); }
};
var print = function() { console.log.apply(console, arguments); };
var log = print;
var auto = function(timeoutMs) {
    __autoOpenSettings();
    if (timeoutMs !== undefined) return __autoWaitFor(timeoutMs);
};
Object.defineProperty(auto, 'service', {
    get: function() { return __autoService(); }
});
auto.waitFor = function(timeoutMs) { return __autoWaitFor(timeoutMs === undefined ? 0 : timeoutMs); };

// ==================== 图像 API（API 第 3 节，JS 胶水层） ====================
function __opt(o, k, d) { return (o && typeof o === 'object' && o[k] !== undefined) ? o[k] : d; }
function __regionArgs(o) {
    var r = __opt(o, 'region', null);
    if (!r || r.length < 2) return [-1, -1, -1, -1];
    return [r[0], r[1],
        (r.length > 2 && r[2] !== undefined) ? r[2] : -1,
        (r.length > 3 && r[3] !== undefined) ? r[3] : -1];
}
function __pt(a) { return (a && a.length >= 2) ? { x: a[0], y: a[1] } : null; }
function __img(x) {
    if (x === null || x === undefined) return -1;
    if (typeof x === 'number') return x;
    if (typeof x === 'object' && x.__id !== undefined) return x.__id;
    return -1;
}
function __tplRef(t) { return (typeof t === 'string') ? t : __img(t); }
function __wrapImage(id) { return id < 0 ? null : { __id: id, width: __img_width(id), height: __img_height(id) }; }
function __parseColor(c) {
    if (typeof c === 'number') return c;
    var s = String(c).replace('#', '');
    if (s.length === 6) s = 'FF' + s;
    return parseInt(s, 16);
}

requestScreenCapture = function(landscape) { return __request_capture(); };
requestScreenCaptureAsync = function() { return Promise.resolve(__request_capture()); };
captureScreen = function() { return __wrapImage(__img_capture()); };

var images = {
    read: function(path) { return __wrapImage(__img_read(String(path))); },
    save: function(img, path, format, quality) {
        var id = __img(img);
        if (id < 0) return false;
        return __img_save(id, String(path), format === undefined ? null : String(format),
            quality === undefined ? 100 : quality);
    },
    clip: function(img, x, y, w, h) { return __wrapImage(__img_clip(__img(img), x, y, w, h)); },
    pixel: function(img, x, y) { var v = __img_pixel(__img(img), x, y); return v < 0 ? null : v; },
    width: function(img) { return __img_width(__img(img)); },
    height: function(img) { return __img_height(__img(img)); },
    findImage: function(img, tpl, options) {
        var r = __regionArgs(options);
        return __pt(__img_find(__img(img), __tplRef(tpl), __opt(options, 'threshold', 0.9),
            r[0], r[1], r[2], r[3], __opt(options, 'scales', [1])));
    },
    findAllImages: function(img, tpl, options) {
        var r = __regionArgs(options);
        var raw = __img_find_all(__img(img), __tplRef(tpl), __opt(options, 'threshold', 0.9),
            r[0], r[1], r[2], r[3], __opt(options, 'max', 5), __opt(options, 'scales', [1]));
        var out = [];
        if (raw) for (var i = 0; i < raw.length; i++) out.push({ x: raw[i][0], y: raw[i][1] });
        return out;
    },
    existsImage: function(img, tpl, options) {
        var r = __regionArgs(options);
        return __img_exists(__img(img), __tplRef(tpl), __opt(options, 'threshold', 0.9),
            r[0], r[1], r[2], r[3], __opt(options, 'scales', [1]));
    },
    findImageInRegion: function(img, tpl, x, y, w, h, threshold) {
        return __pt(__img_find(__img(img), __tplRef(tpl), threshold === undefined ? 0.9 : threshold,
            x, y, w === undefined ? -1 : w, h === undefined ? -1 : h, [1]));
    },
    // 找图 + 对匹配区域做 OCR：返回 { x, y, bounds, text, items }
    findImageWithText: function(img, tpl, options) {
        var r = __regionArgs(options);
        var padding = __opt(options, 'padding', 0);
        var raw = __img_find_with_text(__img(img), __tplRef(tpl),
            __opt(options, 'threshold', 0.9), r[0], r[1], r[2], r[3], padding, __opt(options, 'scales', [1]));
        if (!raw) return null;
        var items = [];
        if (raw[7]) for (var i = 0; i < raw[7].length; i++) items.push(__ocrItem(raw[7][i]));
        return {
            x: raw[0], y: raw[1],
            bounds: { left: raw[2], top: raw[3], right: raw[4], bottom: raw[5] },
            text: raw[6],
            items: items
        };
    },
    findColor: function(img, color, options) {
        var r = __regionArgs(options);
        return __pt(__img_find_color(__img(img), __parseColor(color), __opt(options, 'threshold', 16),
            r[0], r[1], r[2], r[3]));
    },
    findMultiColors: function(img, firstColor, offsets, options) {
        var r = __regionArgs(options);
        var flat = [];
        for (var i = 0; i < offsets.length; i++) {
            flat.push(offsets[i][0], offsets[i][1], __parseColor(offsets[i][2]));
        }
        return __pt(__img_find_multi(__img(img), __parseColor(firstColor), flat, __opt(options, 'threshold', 16),
            r[0], r[1], r[2], r[3]));
    },
    // 双模板同行校验：两个模板各用自己的阈值查找，返回中点与同行结论
    findImagePair: function(img, tplA, tplB, options) {
        var r = __regionArgs(options);
        var raw = __img_find_pair(__img(img), __tplRef(tplA), __tplRef(tplB),
            __opt(options, 'thresholdA', __opt(options, 'threshold', 0.9)),
            __opt(options, 'thresholdB', __opt(options, 'threshold', 0.9)),
            r[0], r[1], r[2], r[3],
            __opt(options, 'rowTolerance', 0), __opt(options, 'scales', [1]));
        if (!raw) return null;
        function rect(x, y, l, t, rr, b) {
            return {
                x: raw[x], y: raw[y],
                left: raw[l], top: raw[t], right: raw[rr], bottom: raw[b],
                bounds: { left: raw[l], top: raw[t], right: raw[rr], bottom: raw[b] }
            };
        }
        return {
            sameRow: raw[0] === 1,
            rowDelta: raw[1],
            tolerance: raw[2],
            midX: raw[3],
            midY: raw[4],
            dx: raw[5],
            dy: raw[6],
            a: rect(7, 8, 9, 10, 11, 12),
            b: rect(13, 14, 15, 16, 17, 18)
        };
    },
    release: function(img) { __img_release(__img(img)); }
};

// ==================== 多指手势（API 2.2） ====================
gesturesAvailable = function() { return __gestures_available(); };

// gestures([duration, [x,y], [x,y]..., delay?], ...)  每根手指一条轨迹
gestures = function() {
    var parts = [];
    var count = 0;
    for (var i = 0; i < arguments.length; i++) {
        var spec = arguments[i];
        if (!spec || spec.length < 2) continue;
        var duration = spec[0];
        var pts = [];
        var delay = 0;
        for (var j = 1; j < spec.length; j++) {
            var item = spec[j];
            if (typeof item === 'number') delay = item;
            else if (item && item.length >= 2) pts.push(item[0], item[1]);
        }
        if (pts.length < 4) continue;            // 至少两个点
        parts.push(duration, delay, pts.length / 2);
        for (var k = 0; k < pts.length; k++) parts.push(pts[k]);
        count++;
    }
    if (count === 0) return false;
    parts.unshift(count);
    return __gestures(parts);
};

// ==================== adapter 分辨率适配（API 第 6 节） ====================
var adapter = {
    // 声明设计基准分辨率，此后坐标 API 与找图返回坐标均按此基准换算
    setDesignSize: function(w, h) { __adapter_set(w, h); },
    reset: function() { __adapter_reset(); },
    enabled: function() { return __adapter_enabled(); },
    scale: function(x, y) { return __adapter_scale(x, y); }
};

// ==================== colors 颜色工具（API 第 5 节，纯 JS 实现） ====================
var colors = {
    parseColor: function(s) {
        if (typeof s === 'number') return s >>> 0;
        var t = String(s).replace('#', '');
        if (t.length === 6) t = 'FF' + t;
        return parseInt(t, 16) >>> 0;
    },
    rgb: function(r, g, b) { return ((0xFF << 24) | (r << 16) | (g << 8) | b) >>> 0; },
    argb: function(a, r, g, b) { return ((a << 24) | (r << 16) | (g << 8) | b) >>> 0; },
    red: function(c) { return (c >>> 16) & 0xFF; },
    green: function(c) { return (c >>> 8) & 0xFF; },
    blue: function(c) { return c & 0xFF; },
    alpha: function(c) { return (c >>> 24) & 0xFF; },
    toString: function(c) {
        var hex = (c >>> 0).toString(16);
        while (hex.length < 8) hex = '0' + hex;
        return '#' + hex;
    },
    // 颜色相似判断：diff=各通道最大差；rgb=欧氏距离；hsv=色相/饱和度/明度加权差
    // c1/c2 支持色值数字或 "#RRGGBB"/"#AARRGGBB" 字符串
    isSimilar: function(c1, c2, threshold, algorithm) {
        threshold = (threshold === undefined) ? 4 : threshold;
        algorithm = algorithm || 'diff';
        var p1 = colors.parseColor(c1), p2 = colors.parseColor(c2);
        var a1 = colors.alpha(p1), r1 = colors.red(p1), g1 = colors.green(p1), b1 = colors.blue(p1);
        var a2 = colors.alpha(p2), r2 = colors.red(p2), g2 = colors.green(p2), b2 = colors.blue(p2);
        if (algorithm === 'rgb') {
            var d = Math.sqrt(Math.pow(r1 - r2, 2) + Math.pow(g1 - g2, 2) + Math.pow(b1 - b2, 2));
            return d <= threshold;
        }
        if (algorithm === 'hsv') {
            var h1 = __rgb2hsv(r1, g1, b1), h2 = __rgb2hsv(r2, g2, b2);
            var dh = Math.min(Math.abs(h1[0] - h2[0]), 360 - Math.abs(h1[0] - h2[0])) / 360;
            var ds = Math.abs(h1[1] - h2[1]);
            var dv = Math.abs(h1[2] - h2[2]);
            return (dh * 255) <= threshold && (ds * 255) <= threshold && (dv * 255) <= threshold;
        }
        return Math.abs(a1 - a2) <= threshold &&
               Math.abs(r1 - r2) <= threshold &&
               Math.abs(g1 - g2) <= threshold &&
               Math.abs(b1 - b2) <= threshold;
    }
};

function __rgb2hsv(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
    var h = 0;
    if (d !== 0) {
        if (max === r) h = ((g - b) / d) % 6;
        else if (max === g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h *= 60;
        if (h < 0) h += 360;
    }
    return [h, max === 0 ? 0 : d / max, max];
}

// ==================== OCR 文字识别（API 第 4 节） ====================
function __ocrItem(a) {
    return {
        text: a[0],
        left: a[1], top: a[2], right: a[3], bottom: a[4],
        confidence: a[5],
        bounds: { left: a[1], top: a[2], right: a[3], bottom: a[4] },
        centerX: function() { return Math.round((this.left + this.right) / 2); },
        centerY: function() { return Math.round((this.top + this.bottom) / 2); }
    };
}
var ocr = {
    // 初始化引擎：mlkit（默认，模型内置）/ paddle（本版本回退到 mlkit）
    init: function(engine) { return __ocr_init(engine === undefined ? "mlkit" : String(engine)); },
    ready: function() { return __ocr_ready(); },
    recognize: function(image, options) {
        var r = __regionArgs(options);
        var raw = __ocr_recognize(__img(image), r[0], r[1], r[2], r[3]);
        var out = [];
        if (raw) for (var i = 0; i < raw.length; i++) out.push(__ocrItem(raw[i]));
        return out;
    },
    recognizeText: function(image, options) {
        var r = __regionArgs(options);
        return __ocr_text(__img(image), r[0], r[1], r[2], r[3]);
    },
    findText: function(image, text, options) {
        var r = __regionArgs(options);
        var raw = __ocr_find(__img(image), String(text), r[0], r[1], r[2], r[3], 0);
        return (raw && raw.length) ? __ocrItem(raw[0]) : null;
    },
    // 清理空格、标点和符号后包含匹配，返回首个匹配行的原始文字与坐标。
    findTextContains: function(image, text, options) {
        var r = __regionArgs(options);
        var raw = __ocr_find_contains(__img(image), text == null ? "" : String(text), r[0], r[1], r[2], r[3]);
        return (raw && raw.length) ? __ocrItem(raw) : null;
    },
    findAllText: function(image, text, options) {
        var r = __regionArgs(options);
        var raw = __ocr_find(__img(image), String(text), r[0], r[1], r[2], r[3], 1);
        var out = [];
        if (raw) for (var i = 0; i < raw.length; i++) out.push(__ocrItem(raw[i]));
        return out;
    }
};

// ==================== 模块引用（兼容 require("images") 写法） ====================
function require(name) {
    if (name === 'images') return images;
    if (name === 'adapter') return adapter;
    if (name === 'colors') return colors;
    if (name === 'ocr') return ocr;
    throw new Error("模块未实现: " + name);
}

// 轮询等图出现（等待期间可被停止按钮打断）
waitImage = function(tpl, timeout, interval, options) {
    timeout = (timeout === undefined) ? 10 : timeout;
    interval = (interval === undefined) ? 0.5 : interval;
    if (!__capture_ready() && !__request_capture()) return null;
    var deadline = Date.now() + timeout * 1000;
    while (true) {
        var p = images.findImage(captureScreen(), tpl, options);
        if (p) return p;
        if (Date.now() >= deadline) return null;
        sleep(Math.max(50, Math.round(interval * 1000)));
    }
};

// 轮询等图消失（如加载框关闭）
waitImageVanish = function(tpl, timeout, interval, options) {
    timeout = (timeout === undefined) ? 10 : timeout;
    interval = (interval === undefined) ? 0.5 : interval;
    if (!__capture_ready() && !__request_capture()) return false;
    var deadline = Date.now() + timeout * 1000;
    while (true) {
        if (!images.existsImage(captureScreen(), tpl, options)) return true;
        if (Date.now() >= deadline) return false;
        sleep(Math.max(50, Math.round(interval * 1000)));
    }
};

// ==================== 新增 API：等待点击、多模板/多关键词匹配 ====================
// 超时语义统一：省略或负数 = 一直等到手动停止；0 = 只检查一次；正数 = 最多等待的秒数。
// 内部用 Infinity 作为截止时间，但它不出现在对外接口上。
function __deadline(timeout) {
    if (timeout === undefined || timeout === null) return Infinity;
    var t = Number(timeout);
    if (isNaN(t) || t < 0) return Infinity;
    return Date.now() + t * 1000;
}
// 未命中后的等待毫秒数；最小 50ms，避免空转
function __gap(interval) {
    var v = (interval === undefined) ? 0.5 : Number(interval);
    if (!isFinite(v) || v <= 0) v = 0.5;
    return Math.max(50, Math.round(v * 1000));
}

// 模板条目：支持 ImageRef、路径字符串、{ tpl, threshold, name } 三种写法
function __tplEntries(templates) {
    var out = [];
    if (!templates || !templates.length) return out;
    for (var i = 0; i < templates.length; i++) {
        var e = templates[i];
        if (e === null || e === undefined) continue;
        if (typeof e === 'object' && e.tpl !== undefined) {
            out.push({ tpl: e.tpl, threshold: e.threshold, name: e.name == null ? '' : String(e.name) });
        } else {
            out.push({ tpl: e, threshold: undefined, name: (typeof e === 'string') ? e : '' });
        }
    }
    return out;
}

// 在单帧内按数组顺序查找多个模板，首个命中即返回（数组顺序即优先级，不比较分数）
function __findAnyIn(shot, entries, options) {
    var base = __opt(options, 'threshold', 0.9);
    var region = __opt(options, 'region', null);
    for (var i = 0; i < entries.length; i++) {
        var e = entries[i];
        var tpl = __tplRef(e.tpl);
        if (tpl === -1) continue;                        // 无效句柄/路径读取失败：跳过该条目
        var p = images.findImage(shot, e.tpl, {
            threshold: (e.threshold === undefined || e.threshold === null) ? base : e.threshold,
            region: region,
            scales: __opt(options, 'scales', [1])
        });
        if (p) return { x: p.x, y: p.y, index: i, name: e.name };
    }
    return null;
}

// images.findImageAny：单帧多模板匹配
images.findImageAny = function(img, templates, options) {
    var id = __img(img);
    if (id < 0) return null;
    var entries = __tplEntries(templates);
    if (!entries.length) return null;
    var shot = __wrapImage(id);
    if (!shot) return null;
    return __findAnyIn(shot, entries, options);
};

// 轮询等待多个模板中的任意一个出现
waitImageAny = function(templates, timeout, interval, options) {
    var entries = __tplEntries(templates);
    if (!entries.length) return null;
    var missLimit = __opt(options, 'missLimit', 0);
    if (!__capture_ready() && !__request_capture()) return null;
    var deadline = __deadline(timeout);
    var gap = __gap(interval);
    var misses = 0, retries = 0;
    while (true) {
        var shot = captureScreen();
        if (shot) {
            var hit = null;
            try {
                hit = __findAnyIn(shot, entries, options);
            } finally {
                images.release(shot);
            }
            if (hit) return hit;
            // 只有成功截图却没命中才计数；截图失败不能证明目标不存在
            misses++;
            if (missLimit > 0 && misses >= missLimit) {
                console.warn('waitImageAny：连续 ' + misses + ' 次未找到，结束等待');
                return null;
            }
        }
        retries++;
        if (retries === 1 || retries % 10 === 0) {
            console.warn('waitImageAny：重试 ' + retries + ' 次，连续未找到 ' + misses + ' 次');
        }
        if (Date.now() >= deadline) return null;
        sleep(gap);
    }
};

// 轮询等图出现并点击；missLimit > 0 时连续未找到达到次数即返回 false
waitImageClick = function(tpl, timeout, interval, options) {
    var missLimit = __opt(options, 'missLimit', 0);
    var delay = __opt(options, 'delay', 0);
    if (!__capture_ready() && !__request_capture()) return false;
    var deadline = __deadline(timeout);
    var gap = __gap(interval);
    var misses = 0, retries = 0;
    while (true) {
        var shot = captureScreen();
        if (shot) {
            var point = null;
            try {
                point = images.findImage(shot, tpl, options);
            } finally {
                images.release(shot);
            }
            if (point) {
                misses = 0;                              // 找到即清零，点击失败也不推进步骤
                if (click(point.x, point.y)) {
                    if (delay > 0) sleep(delay);
                    return true;
                }
                console.warn('waitImageClick：点击未完成，重新定位');
            } else {
                misses++;
                if (missLimit > 0 && misses >= missLimit) {
                    console.warn('waitImageClick：连续 ' + misses + ' 次未找到，结束等待');
                    return false;
                }
            }
        }
        retries++;
        if (retries === 1 || retries % 10 === 0) {
            console.warn('waitImageClick：重试 ' + retries + ' 次，连续未找到 ' + misses + ' 次');
        }
        if (Date.now() >= deadline) return false;
        sleep(gap);
    }
};

// ocr.findTextAny：一次 OCR 命中任意关键词，返回带 matched 的 OcrItem
ocr.findTextAny = function(image, texts, options) {
    var list = [];
    if (texts && texts.length) {
        for (var i = 0; i < texts.length; i++) {
            if (texts[i] !== null && texts[i] !== undefined) list.push(String(texts[i]));
        }
    }
    if (!list.length) return null;
    var r = __regionArgs(options);
    var raw = __ocr_find_contains_any(__img(image), list, r[0], r[1], r[2], r[3]);
    if (!raw || !raw.length) return null;
    var item = __ocrItem(raw);
    item.matched = raw[6];                              // 实际命中的关键词原文
    return item;
};

// waitTextClick：轮询截图 → OCR → 命中任意关键词后点击整行中心
waitTextClick = function(texts, timeout, interval, options) {
    var list = [];
    if (texts && texts.length) {
        for (var i = 0; i < texts.length; i++) {
            if (texts[i] !== null && texts[i] !== undefined) list.push(String(texts[i]));
        }
    }
    if (!list.length) return false;
    if (!ocr.ready() && !ocr.init()) return false;
    if (!__capture_ready() && !__request_capture()) return false;
    var missLimit = __opt(options, 'missLimit', 0);
    var delay = __opt(options, 'delay', 0);
    var deadline = __deadline(timeout);
    var gap = __gap(interval === undefined ? 1 : interval);
    var misses = 0, retries = 0;
    while (true) {
        var startedAt = Date.now();
        var shot = captureScreen();
        if (shot) {
            var found = null;
            try {
                found = ocr.findTextAny(shot, list, options);
            } finally {
                images.release(shot);
            }
            if (found) {
                if (click(found.centerX(), found.centerY())) {
                    if (delay > 0) sleep(delay);
                    return true;
                }
                console.warn('waitTextClick：点击未完成，下一轮重新识别');
            } else {
                misses++;
                if (missLimit > 0 && misses >= missLimit) {
                    console.warn('waitTextClick：连续 ' + misses + ' 次未找到，结束等待');
                    return false;
                }
            }
        }
        retries++;
        if (retries === 1 || retries % 10 === 0) {
            console.warn('waitTextClick：重试 ' + retries + ' 次，连续未找到 ' + misses + ' 次');
        }
        if (Date.now() >= deadline) return false;
        // 按本轮开始时间补足到目标周期；OCR 超时则立即进入下一轮
        var remaining = gap - (Date.now() - startedAt);
        if (remaining > 0) sleep(remaining);
    }
};

// clickRetry：固定坐标点击失败时重试；times 含首次，0 表示一直重试
clickRetry = function(x, y, times, interval, duration) {
    var max = (times === undefined || times === null) ? 3 : Number(times);
    if (isNaN(max) || max < 0) max = 3;
    var wait = (interval === undefined || interval === null) ? 500 : Number(interval);
    if (isNaN(wait) || wait < 0) wait = 500;
    var hold = (duration === undefined || duration === null) ? 30 : duration;
    var attempts = 0;
    while (true) {
        attempts++;
        if (click(x, y, hold)) return true;
        if (max > 0 && attempts >= max) {
            console.warn('clickRetry：已尝试 ' + attempts + ' 次仍未完成');
            return false;
        }
        if (wait > 0) sleep(wait);
    }
};

// —— images.readAll / releaseAll ——
// 数组入参返回等长数组（失败位为 null），对象入参返回同 key 对象（失败 key 为 null）
images.readAll = function(source) {
    var isArray = (source && typeof source.length === 'number' && typeof source !== 'string');
    var imagesOut = isArray ? [] : {};
    var missing = [];
    if (!source) return { images: imagesOut, missing: missing };
    if (isArray) {
        for (var i = 0; i < source.length; i++) {
            var path = source[i];
            var img = (path === null || path === undefined) ? null : images.read(String(path));
            imagesOut.push(img);
            if (!img) missing.push(String(path));
        }
    } else {
        for (var key in source) {
            if (!Object.prototype.hasOwnProperty.call(source, key)) continue;
            var p = source[key];
            var im = (p === null || p === undefined) ? null : images.read(String(p));
            imagesOut[key] = im;
            if (!im) missing.push(String(p));
        }
    }
    return { images: imagesOut, missing: missing };
};

// 收集图片句柄：支持可变参数、数组和对象（只遍历 value），按内部 id 去重
function __collectImageIds(target, out) {
    if (target === null || target === undefined) return;
    if (typeof target === 'object' && target.__id !== undefined) {
        if (target.__id > 0) out[target.__id] = target;
        return;
    }
    if (typeof target === 'object' && typeof target.length === 'number') {
        for (var i = 0; i < target.length; i++) __collectImageIds(target[i], out);
        return;
    }
    if (typeof target === 'object') {
        for (var key in target) {
            if (Object.prototype.hasOwnProperty.call(target, key)) __collectImageIds(target[key], out);
        }
    }
}

images.releaseAll = function() {
    var ids = {};
    for (var i = 0; i < arguments.length; i++) __collectImageIds(arguments[i], ids);
    var count = 0;
    for (var id in ids) {
        // 收集阶段已按 __id 去重，这里直接释放并计数
        images.release(ids[id]);
        count++;
    }
    return count;
};
"""
}
