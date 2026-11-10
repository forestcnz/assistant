package com.forest.android.assistant.core.flow

import android.content.Context
import android.util.AtomicFile
import com.forest.android.assistant.core.task.TaskManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** flow.json is the editable source; task.js is rebuilt before every run. */
object FlowCompiler {
    fun sourceFile(context: Context, taskName: String): File =
        File(TaskManager.taskDir(context, taskName), "flow.json")

    fun draftFile(context: Context, taskName: String): File =
        File(TaskManager.taskDir(context, taskName), "flow.draft.json")

    @Synchronized
    fun saveDraft(context: Context, taskName: String, source: String) {
        require(source.length <= 2_000_000) { "流程草稿过大" }
        JSONObject(source).getJSONArray("nodes")
        writeAtomic(draftFile(context, taskName), source)
    }

    fun isVisual(context: Context, taskName: String): Boolean = sourceFile(context, taskName).isFile

    /** 保存原生 task.js 后，流程任务正式转换为普通脚本任务。 */
    @Synchronized
    fun convertToScript(context: Context, taskName: String) {
        draftFile(context, taskName).delete()
        val source = sourceFile(context, taskName)
        if (source.exists() && !source.delete()) {
            error("无法移除流程源文件，任务仍保持流程模式")
        }
    }

    fun initialDocument(): String = JSONObject()
        .put("schemaVersion", 1)
        .put("nodes", JSONArray())
        .put("globalCount", 1)
        .toString(2)

    @Synchronized
    fun save(context: Context, taskName: String, source: String) {
        val document = JSONObject(source)
        val script = compile(document)
        val directory = TaskManager.taskDir(context, taskName)
        require(File(directory, "task.js").isFile) { "任务不存在" }
        check(!com.forest.android.assistant.core.engine.ScriptRunner.state.value.running) { "请先停止运行" }
        writeAtomic(File(directory, "flow.json"), document.toString(2) + "\n")
        writeAtomic(File(directory, "task.js"), script)
        draftFile(context, taskName).delete()
    }

    @Synchronized
    fun prepare(context: Context, taskName: String) {
        val source = sourceFile(context, taskName)
        if (!source.isFile) return
        val script = compile(JSONObject(source.readText()))
        val target = TaskManager.scriptFile(context, taskName)
        if (!target.isFile || target.readText() != script) writeAtomic(target, script)
    }

    fun compile(document: JSONObject): String {
        require(document.optInt("schemaVersion", 1) == 1) { "流程版本不受支持" }
        val nodes = document.getJSONArray("nodes")
        require(nodes.length() <= 1000) { "步骤过多" }
        val ids = HashSet<String>()
        val templates = linkedSetOf<String>()
        val anchors = collectAnchors(nodes)
        val handler = findErrorHandler(nodes)
        val steps = StringBuilder("    if (__flowResume === '__start__') __flowResume = null;\n")
        emitList(nodes, steps, 1, 0, ids, templates, anchors, gated = true)
        val recovery = StringBuilder()
        handler?.optJSONArray("children")?.let { emitList(it, recovery, 1, 0, ids, templates, anchors) }
        val handlerProps = handler?.let { compileProperties(it) }
        val recoveryMode = if (handlerProps?.optString("mode", "stop") == "restart") "restart" else "stop"
        val recoveryRetries = handlerProps?.optInt("retries", 1) ?: 0
        require(recoveryRetries in 0..5) { "异常兜底重跑次数需为 0～5" }
        val recoveryDelay = handlerProps?.optInt("delay", 1000) ?: 0
        require(recoveryDelay in 0..60000) { "异常兜底等待时间需为 0～60000 ms" }
        val recoveryLimit = when {
            handler == null -> 0
            recoveryMode == "restart" -> recoveryRetries + 1
            else -> 1
        }

        val output = StringBuilder("// Generated from flow.json\nsleep(500);\n")
        output.append("var __flowTemplates = Object.create(null);\n")
        // 跳转状态必须在执行主流程前初始化：var 的赋值不会随声明提升
        output.append("var __flowResume = null;\n")
        output.append("var __flowJumps = 0;\n")
        output.append("var __flowRecoveries = 0;\n")
        output.append("var __flowRecoveryMode = ${quoted(recoveryMode)};\n")
        output.append("var __flowRecoveryLimit = $recoveryLimit;\n")
        output.append("var __flowRecoveryDelay = $recoveryDelay;\n")
        output.append("try {\n")
        if (templates.isNotEmpty()) {
            output.append("    for (var __flowPath of [\n")
            templates.forEach { output.append("        ${quoted(it)},\n") }
            output.append("    ]) {\n        __flowTemplates[__flowPath] = images.read(__flowPath);\n    }\n")
        }
        output.append("""
    while (true) {
        try {
            __flowMain();
            break;
        } catch (__flowError) {
            if (!(__flowError instanceof Error)) throw __flowError;
            if (!__flowRunning()) throw __flowError;
            if (__flowError && __flowError.__flowJump) {
                if (++__flowJumps > 20) throw Error('跳转次数过多，请检查兜底锚点');
                __flowResume = __flowError.__flowJump;
                continue;
            }
            if (__flowError && __flowError.__flowFatal) throw __flowError;
            if (__flowRecoveries < __flowRecoveryLimit) {
                __flowRecoveries++;
                log('流程异常：' + (__flowError && __flowError.message ? __flowError.message : __flowError));
                if (__flowRecoveryDelay > 0) sleep(__flowRecoveryDelay);
                __flowRecover();
                if (__flowRecoveryMode === 'restart') {
                    __flowResume = '__start__';
                    continue;
                }
            }
            throw __flowError;
        }
    }
} finally {
    images.releaseAll(__flowTemplates);
}

""".trimStart('\n'))
        output.append("""
/** 锚点闸门：跳转后只放行目标锚点及其之后的步骤。 */
function __flowGate(name) {
    if (__flowResume === null) return true;
    if (__flowResume === name) { __flowResume = null; return true; }
    return false;
}
function __flowGoto(name) {
    var signal = new Error('流程跳转');
    signal.__flowJump = name;
    throw signal;
}
/** 素材缺失、截屏不可用等无法通过重试恢复的错误。 */
function __flowFatal(message) {
    var error = new Error(message);
    error.__flowFatal = true;
    return error;
}
function __flowImage(path, timeoutMs, intervalMs, options) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var template = __flowTemplates[path];
    if (!template) return null;
    return waitImage(template, timeoutMs / 1000, intervalMs / 1000, options);
}
function __flowText(text, options) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    if (options.match === 'exact') {
        var started = __flowNow();
        var wanted = text.trim().toLowerCase();
        while (true) {
            var shot = captureScreen();
            if (!shot) throw Error('截图失败');
            try {
                var lines = ocr.recognize(shot, options.region ? { region: options.region } : {});
                for (var i = 0; i < lines.length; i++) {
                    if (lines[i].text.trim().toLowerCase() === wanted) return lines[i];
                }
            } finally { images.release(shot); }
            if (__flowNow() - started >= options.timeoutMs) return null;
            sleep(options.intervalMs);
        }
    }
    var result = waitText(text, options);
    if (result.reason === 'capture_error' || result.reason === 'ocr_error' || result.reason === 'invalid_argument')
        throw Error(result.lastError || result.reason);
    return result.value;
}
function __flowWaitCondition(check, timeoutMs, intervalMs) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var started = __flowNow();
    while (true) {
        if (check()) return true;
        if (__flowNow() - started >= timeoutMs) return false;
        sleep(intervalMs);
    }
}
function __flowWaitImageGone(path, timeoutMs, intervalMs, options) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var template = __flowTemplates[path];
    if (!template) throw __flowFatal('图片素材不存在');
    var started = __flowNow();
    while (true) {
        var shot = captureScreen();
        if (!shot) throw Error('截图失败');
        var exists = false;
        try { exists = images.existsImage(shot, template, options); }
        finally { images.release(shot); }
        if (!exists) return true;
        if (__flowNow() - started >= timeoutMs) return false;
        sleep(intervalMs);
    }
}
function __flowMultiImage(entries, mode, timeoutMs, intervalMs, options) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var started = __flowNow();
    while (true) {
        var shot = captureScreen();
        if (!shot) throw Error('截图失败');
        var first = null, matched = 0;
        try {
            for (var i = 0; i < entries.length; i++) {
                var template = __flowTemplates[entries[i].path];
                if (!template) throw __flowFatal('图片素材不存在：' + entries[i].path);
                var point = images.findImage(shot, template, Object.assign({}, options, { threshold: entries[i].threshold }));
                if (point) {
                    matched++;
                    if (!first) first = point;
                }
            }
        } finally { images.release(shot); }
        if (mode === 'any' && first) return first;
        if (mode === 'all' && matched === entries.length) return first;
        if (__flowNow() - started >= timeoutMs) return null;
        sleep(intervalMs);
    }
}
/** 采样画面指纹：按 step 间距取像素网格，用于判断画面是否稳定。 */
function __flowSignature(shot, region, step) {
    var width = images.width(shot), height = images.height(shot);
    var rx = 0, ry = 0, rw = width, rh = height;
    if (region) { rx = region[0]; ry = region[1]; rw = region[2]; rh = region[3]; }
    rx = Math.max(0, Math.min(rx, width - 1));
    ry = Math.max(0, Math.min(ry, height - 1));
    rw = Math.max(2, Math.min(rw, width - rx));
    rh = Math.max(2, Math.min(rh, height - ry));
    var cols = Math.max(2, Math.min(48, Math.floor(rw / step)));
    var rows = Math.max(2, Math.min(48, Math.floor(rh / step)));
    var gapX = Math.max(1, Math.floor(rw / cols));
    var gapY = Math.max(1, Math.floor(rh / rows));
    var signature = [];
    for (var y = 0; y < rh; y += gapY) {
        var row = [];
        for (var x = 0; x < rw; x += gapX) {
            var color = images.pixel(shot, rx + x, ry + y);
            row.push(color === null || color === undefined ? -1 : color);
        }
        signature.push(row);
    }
    return signature;
}
/** 两帧指纹的差异比例；单点通道差之和超过 24 视为变化。 */
function __flowStableDiff(a, b) {
    var total = 0, changed = 0;
    for (var y = 0; y < a.length; y++) {
        var rowA = a[y], rowB = b[y] || [];
        for (var x = 0; x < rowA.length; x++) {
            var ca = rowA[x], cb = rowB[x];
            if (ca === undefined || cb === undefined || ca < 0 || cb < 0) continue;
            total++;
            var dr = ((ca >> 16) & 255) - ((cb >> 16) & 255);
            var dg = ((ca >> 8) & 255) - ((cb >> 8) & 255);
            var db = (ca & 255) - (cb & 255);
            if (Math.abs(dr) + Math.abs(dg) + Math.abs(db) > 24) changed++;
        }
    }
    return total > 0 ? changed / total : 0;
}
function __flowWaitStable(region, step, diffRatio, frames, timeoutMs, pollMs) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var last = null, stable = 0, started = __flowNow();
    while (true) {
        var shot = captureScreen();
        if (!shot) throw Error('截图失败');
        var signature = null;
        try { signature = __flowSignature(shot, region, step); }
        finally { images.release(shot); }
        if (last && __flowStableDiff(last, signature) <= diffRatio) stable++;
        else stable = 1;
        last = signature;
        if (stable >= frames) return true;
        if (timeoutMs > 0 && __flowNow() - started >= timeoutMs) return false;
        sleep(pollMs);
    }
}
function __flowWaitClick(path, timeoutMs, intervalMs, options) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var template = __flowTemplates[path];
    if (!template) throw __flowFatal('图片素材不存在');
    var deadline = timeoutMs > 0 ? __flowNow() + timeoutMs : Infinity;
    var misses = 0, retries = 0;
    while (true) {
        var point = null;
        var shot = captureScreen();
        if (!shot) throw Error('截图失败');
        try { point = images.findImage(shot, template, options); }
        finally { images.release(shot); }
        if (point) {
            if (click(point.x + options.offsetX, point.y + options.offsetY)) {
                if (options.stable) __flowWaitStable(null, 8, 0.02, 2, 3000, 200);
                else if (options.afterWait > 0) sleep(options.afterWait);
                return point;
            }
            if (++retries > options.clickRetry) {
                if (options.failure === 'skip') { console.warn('点击失败'); return null; }
                throw Error('点击失败');
            }
            if (options.retryGap > 0) sleep(options.retryGap);
            continue;
        }
        misses++;
        if (options.missLimit > 0 && misses >= options.missLimit) return null;
        if (__flowNow() >= deadline) return null;
        sleep(intervalMs);
    }
}
/** 单帧内按顺序匹配多张图片，返回首个命中项；命中项可自带点击。 */
function __flowSwitch(entries, timeoutMs, intervalMs) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var deadline = timeoutMs > 0 ? __flowNow() + timeoutMs : Infinity;
    while (true) {
        var shot = captureScreen();
        if (!shot) throw Error('截图失败');
        var index = 0, point = null;
        try {
            for (var i = 0; i < entries.length; i++) {
                var entry = entries[i];
                var template = __flowTemplates[entry.path];
                if (!template) throw __flowFatal('图片素材不存在：' + entry.path);
                var options = { threshold: entry.threshold };
                if (entry.region) options.region = entry.region;
                var found = images.findImage(shot, template, options);
                if (found) { index = i + 1; point = found; break; }
            }
        } finally { images.release(shot); }
        if (index) {
            if (entries[index - 1].click && !click(point.x, point.y)) {
                if (entries[index - 1].failure === 'skip') console.warn('点击失败');
                else throw Error('点击失败');
            }
            return { index: index, x: point.x, y: point.y };
        }
        if (__flowNow() >= deadline) return null;
        sleep(intervalMs);
    }
}
/** 循环点掉一组弹窗图片，连续 emptyRounds 轮无命中后结束。 */
function __flowDialogClear(entries, pollMs, emptyRounds, maxMs, maxClicks, afterClick, afterWaitMs, clickFailure) {
    if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
    var started = __flowNow(), empty = 0, clicks = 0;
    while (true) {
        var shot = captureScreen();
        if (!shot) throw Error('截图失败');
        var hit = null;
        try {
            for (var i = 0; i < entries.length; i++) {
                var entry = entries[i];
                var template = __flowTemplates[entry.path];
                if (!template) throw __flowFatal('图片素材不存在：' + entry.path);
                var options = { threshold: entry.threshold };
                if (entry.region) options.region = entry.region;
                var found = images.findImage(shot, template, options);
                if (found) { hit = found; break; }
            }
        } finally { images.release(shot); }
        if (hit) {
            clicks++;
            if (!click(hit.x, hit.y) && clickFailure === 'stop') throw Error('点击失败');
            empty = 0;
            if (afterClick === 'stable') __flowWaitStable(null, 8, 0.02, 2, 3000, 200);
            else sleep(Math.max(afterWaitMs, 50));
            if (maxClicks > 0 && clicks >= maxClicks) break;
        } else {
            if (++empty >= emptyRounds) break;
            sleep(pollMs);
        }
        if (maxMs > 0 && __flowNow() - started >= maxMs) break;
    }
    return { clicks: clicks };
}
/** 长等待：固定时长、等图片出现/消失，或一直等待到停止；按心跳输出日志。 */
function __flowWaitEvent(mode, path, durationMs, pollMs, heartbeatMs, options) {
    var waitingImage = mode === 'imageAppear' || mode === 'imageGone';
    if (waitingImage) {
        if (!requestScreenCapture()) throw __flowFatal('截屏不可用');
        if (!__flowTemplates[path]) throw __flowFatal('图片素材不存在');
    }
    var started = __flowNow(), lastBeat = started;
    while (true) {
        if (mode === 'imageAppear') {
            if (__flowImage(path, 0, pollMs, options)) return true;
        } else if (mode === 'imageGone') {
            if (!__flowImage(path, 0, pollMs, options)) return true;
        }
        if (mode !== 'forever' && durationMs > 0 && __flowNow() - started >= durationMs) return mode === 'duration';
        if (heartbeatMs > 0 && __flowNow() - lastBeat >= heartbeatMs) {
            lastBeat = __flowNow();
            log('等待中：已等待 ' + Math.round((__flowNow() - started) / 1000) + ' 秒');
        }
        sleep(pollMs);
    }
}
function __flowMain() {
""".trimStart())
        output.append(steps).append("}\n")
        output.append("function __flowRecover() {\n").append(recovery).append("}\n")
        return output.toString()
    }

    private fun emitList(
        nodes: JSONArray, out: StringBuilder, depth: Int, loops: Int,
        ids: MutableSet<String>, templates: MutableSet<String>, anchors: Set<String>,
        gated: Boolean = false
    ) {
        require(depth <= 20) { "嵌套层级过深" }
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val type = node.getString("type")
            // 异常兜底不参与正常顺序，由外层 __flowRecover 调用
            if (type == "onError") continue
            val id = node.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && ids.add(id)) { "步骤 ID 重复或非法：$id" }
            val children = node.optJSONArray("children")
            val thenNodes = node.optJSONArray("then")
            val elseNodes = node.optJSONArray("else")
            val properties = compileProperties(node)
            if (properties.optBoolean("enabled", true).not()) continue
            val basePad = "    ".repeat(depth)
            if (gated) {
                val gate = if (type == "anchor") "anchor:" + anchorName(properties) else id
                out.append(basePad).append("if (__flowGate(${quoted(gate)})) {\n")
            }
            val pad = if (gated) basePad + "    " else basePad
            val childDepth = if (gated) depth + 1 else depth
            out.append(pad).append("__flowNode(${quoted(id)});\n")
            val variable = "__flow_${id.replace('-', '_')}"
            when (type) {
                "anchor" -> Unit
                "sequence" -> emitList(children ?: error("顺序组缺少步骤列表"), out, childDepth, loops, ids, templates, anchors)
                "repeat" -> {
                    val body = children ?: error("循环缺少步骤列表")
                    require(body.length() > 0) { "循环体不能为空" }
                    val mode = properties.optString("mode", "count")
                    val count = properties.optInt("count", 1)
                    val interval = properties.optInt("interval", 0)
                    require(interval >= 0 && (mode == "count" || interval >= 50)) { "循环间隔不合法" }
                    if (mode == "count") require(count in 1..999999) { "循环次数不合法" }
                    else require(mode in listOf("while", "until", "forever")) { "未知循环方式" }
                    val maxDuration = properties.optInt("maxDuration", 0)
                    if (maxDuration > 0) out.append(pad).append("var ${variable}_started = __flowNow();\n")
                    out.append(pad).append("for (var $variable = 0; ")
                        .append(if (mode == "count") "$variable < $count" else "")
                        .append("; $variable++) {\n")
                    if (interval > 0) out.append(pad).append("    if ($variable > 0) sleep($interval);\n")
                    if (mode == "while" || mode == "until") {
                        val condition = condition(properties, templates)
                        out.append(pad).append("    if (")
                            .append(if (mode == "while") "!($condition)" else condition)
                            .append(") break;\n")
                    }
                    val maxIterations = properties.optInt("maxIterations", 0)
                    if (maxIterations > 0) out.append(pad).append("    if ($variable >= $maxIterations) throw Error('循环次数已达上限');\n")
                    if (maxDuration > 0) out.append(pad).append("    if (__flowNow() - ${variable}_started >= $maxDuration) throw Error('循环时间已达上限');\n")
                    emitList(body, out, childDepth + 1, loops + 1, ids, templates, anchors)
                    out.append(pad).append("}\n")
                }
                "condition" -> {
                    out.append(pad).append("if (").append(condition(properties, templates)).append(") {\n")
                    emitList(thenNodes ?: JSONArray(), out, childDepth + 1, loops, ids, templates, anchors)
                    out.append(pad).append("} else {\n")
                    emitList(elseNodes ?: JSONArray(), out, childDepth + 1, loops, ids, templates, anchors)
                    out.append(pad).append("}\n")
                }
                "click", "swipe" -> {
                    val command = if (type == "click")
                        "click(${number(properties, "x")}, ${number(properties, "y")}, ${number(properties, "duration", 50)})"
                    else "swipe(${number(properties, "x")}, ${number(properties, "y")}, ${number(properties, "endX")}, ${number(properties, "endY")}, ${number(properties, "duration", 300)})"
                    val plan = failurePlan(properties, "failure", "stop", anchors)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $command;\n")
                            out.append(bodyPad).append("${variable}_ok = !!$variable;\n")
                        }
                        emitFailure(out, pad, "${variable}_ok", plan, "操作失败")
                    } else {
                        out.append(pad).append("var $variable = $command;\n")
                        emitFailure(out, pad, variable, plan, "操作失败")
                    }
                }
                "wait" -> out.append(pad).append("sleep(${number(properties, "duration", 1000)});\n")
                "log" -> out.append(pad).append("log(${quoted(properties.optString("text"))});\n")
                "image", "text" -> {
                    val expression = if (type == "image") imageSearch(properties, templates) else textSearch(properties)
                    val clickEnabled = properties.optString("onMatch", "recognize") == "click"
                    val missPlan = failurePlan(properties, "onMiss", "continue", anchors)
                    val clickPlan = failurePlan(properties, "failure", "stop", anchors)
                    val x = if (type == "image") "$variable.x" else "$variable.centerX()"
                    val y = if (type == "image") "$variable.y" else "$variable.centerY()"
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        if (clickEnabled) out.append(pad).append("var ${variable}_found = false;\n")
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $expression;\n")
                            out.append(bodyPad).append("${variable}_found = !!$variable;\n")
                            out.append(bodyPad).append("${variable}_ok = ${variable}_found;\n")
                            if (clickEnabled) {
                                out.append(bodyPad)
                                    .append("if (${variable}_ok && !click($x, $y)) ${variable}_ok = false;\n")
                            }
                        }
                        emitDualFailure(
                            out, pad, "${variable}_ok", "${variable}_found",
                            if (clickEnabled) clickPlan else missPlan, missPlan, "点击失败", "识别未命中"
                        )
                    } else {
                        out.append(pad).append("var $variable = $expression;\n")
                        emitFailure(out, pad, variable, missPlan, "识别未命中")
                        if (clickEnabled) {
                            out.append(pad).append("if ($variable && !click($x, $y)) {\n")
                            emitPlanBody(out, pad + "    ", clickPlan, "点击失败")
                            out.append(pad).append("}\n")
                        }
                    }
                }
                "waitCondition" -> {
                    val timeout = positive(properties, "timeout", 10000)
                    val interval = positive(properties, "poll", 300, 50)
                    val expression = "__flowWaitCondition(function() { return " +
                        condition(properties, templates) + "; }, $timeout, $interval)"
                    val plan = failurePlan(properties, "onTimeout", "stop", anchors)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $expression;\n")
                            out.append(bodyPad).append("${variable}_ok = !!$variable;\n")
                        }
                        emitFailure(out, pad, "${variable}_ok", plan, "等待条件超时")
                    } else {
                        out.append(pad).append("var $variable = $expression;\n")
                        emitFailure(out, pad, variable, plan, "等待条件超时")
                    }
                }
                "waitImageAppear", "waitImageGone" -> {
                    val path = imagePath(properties.getString("asset")).also { templates.add(it) }
                    val timeout = positive(properties, "timeout", 10000)
                    val interval = positive(properties, "poll", 300, 50)
                    val threshold = threshold(properties)
                    val options = "{ threshold: $threshold${region(properties)} }"
                    val expression = if (type == "waitImageAppear")
                        "__flowImage(${quoted(path)}, $timeout, $interval, $options)"
                    else "__flowWaitImageGone(${quoted(path)}, $timeout, $interval, $options)"
                    val message = if (type == "waitImageAppear") "等待图片出现超时" else "等待图片消失超时"
                    val plan = failurePlan(properties, "onTimeout", "stop", anchors)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $expression;\n")
                            out.append(bodyPad).append("${variable}_ok = !!$variable;\n")
                        }
                        emitFailure(out, pad, "${variable}_ok", plan, message)
                    } else {
                        out.append(pad).append("var $variable = $expression;\n")
                        emitFailure(out, pad, variable, plan, message)
                    }
                }
                "multiImage" -> {
                    val timeout = positive(properties, "timeout", 5000)
                    val interval = positive(properties, "poll", 300, 50)
                    val mode = properties.optString("matchMode", "any")
                    require(mode == "any" || mode == "all") { "多图匹配方式不合法" }
                    val entries = multiImageEntries(properties, templates)
                    val regionOption = region(properties).removePrefix(", ")
                    val expression = "__flowMultiImage($entries, ${quoted(mode)}, $timeout, $interval, { $regionOption })"
                    val clickEnabled = properties.optString("onMatch", "recognize") == "click"
                    val missPlan = failurePlan(properties, "onMiss", "continue", anchors)
                    val clickPlan = failurePlan(properties, "failure", "stop", anchors)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        if (clickEnabled) out.append(pad).append("var ${variable}_found = false;\n")
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $expression;\n")
                            out.append(bodyPad).append("${variable}_found = !!$variable;\n")
                            out.append(bodyPad).append("${variable}_ok = ${variable}_found;\n")
                            if (clickEnabled) {
                                out.append(bodyPad).append(
                                    "if (${variable}_ok && !click($variable.x, $variable.y)) ${variable}_ok = false;\n"
                                )
                            }
                        }
                        emitDualFailure(
                            out, pad, "${variable}_ok", "${variable}_found",
                            if (clickEnabled) clickPlan else missPlan, missPlan, "点击失败", "多图匹配未命中"
                        )
                    } else {
                        out.append(pad).append("var $variable = $expression;\n")
                        emitFailure(out, pad, variable, missPlan, "多图匹配未命中")
                        if (clickEnabled) {
                            out.append(pad).append("if ($variable && !click($variable.x, $variable.y)) {\n")
                            emitPlanBody(out, pad + "    ", clickPlan, "点击失败")
                            out.append(pad).append("}\n")
                        }
                    }
                }
                "waitClick" -> {
                    val path = imagePath(properties.getString("asset")).also { templates.add(it) }
                    val timeout = number(properties, "timeout", 10000)
                    val interval = positive(properties, "poll", 300, 50)
                    val offsetX = offset(properties, "offsetX")
                    val offsetY = offset(properties, "offsetY")
                    val afterWait = number(properties, "afterWait", 0)
                    val clickRetry = properties.optInt("clickRetry", 2)
                    require(clickRetry in 0..5) { "点击失败重试次数需为 0～5" }
                    val retryGap = positive(properties, "retryGap", 300, 50)
                    val missLimit = number(properties, "missLimit", 0)
                    val stable = properties.optBoolean("stable", false)
                    val failure = properties.optString("failure", "stop")
                    require(failure == "stop" || failure == "skip") { "点击失败处理不合法" }
                    val options = "{ threshold: ${threshold(properties)}${region(properties)}, " +
                        "offsetX: $offsetX, offsetY: $offsetY, afterWait: $afterWait, " +
                        "stable: $stable, clickRetry: $clickRetry, retryGap: $retryGap, " +
                        "missLimit: $missLimit, failure: ${quoted(failure)} }"
                    val expression = "__flowWaitClick(${quoted(path)}, $timeout, $interval, $options)"
                    val plan = failurePlan(properties, "onMiss", "stop", anchors)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $expression;\n")
                            out.append(bodyPad).append("${variable}_ok = !!$variable;\n")
                        }
                        emitFailure(out, pad, "${variable}_ok", plan, "等待图片超时")
                    } else {
                        out.append(pad).append("var $variable = $expression;\n")
                        emitFailure(out, pad, variable, plan, "等待图片超时")
                    }
                }
                "waitStable" -> {
                    val precision = properties.optString("precision", "medium")
                    require(precision in setOf("low", "medium", "high")) { "采样精度不合法：$precision" }
                    val step = when (precision) {
                        "low" -> 16
                        "high" -> 4
                        else -> 8
                    }
                    val ratio = fraction(properties, "diff", 0.02)
                    val frames = properties.optInt("frames", 2)
                    require(frames in 1..10) { "连续稳定帧数需为 1～10" }
                    val timeout = number(properties, "timeout", 10000)
                    val interval = positive(properties, "poll", 200, 50)
                    val regionValue = regionValues(properties) ?: "null"
                    val expression =
                        "__flowWaitStable($regionValue, $step, $ratio, $frames, $timeout, $interval)"
                    val plan = failurePlan(properties, "onTimeout", "continue", anchors)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $expression;\n")
                            out.append(bodyPad).append("${variable}_ok = !!$variable;\n")
                        }
                        emitFailure(out, pad, "${variable}_ok", plan, "等待界面稳定超时")
                    } else {
                        out.append(pad).append("var $variable = $expression;\n")
                        emitFailure(out, pad, variable, plan, "等待界面稳定超时")
                    }
                }
                "imageSwitch" -> {
                    val timeout = number(properties, "timeout", 5000)
                    val interval = positive(properties, "poll", 300, 50)
                    val caseArray = children ?: error("图片分支缺少命中项")
                    val branches = (0 until caseArray.length())
                        .map { caseArray.getJSONObject(it) }
                        .filter { it.optBoolean("enabled", true) }
                    require(branches.isNotEmpty()) { "图片分支至少需要 1 个命中项" }
                    require(branches.size <= 20) { "图片分支最多 20 个命中项" }
                    val entries = branches.map { caseNode ->
                        require(caseNode.optString("type") == "switchCase") { "图片分支的命中项无效" }
                        val caseProps = compileProperties(caseNode)
                        val asset = caseProps.optString("asset")
                        require(asset.isNotBlank()) { "图片分支存在未选择的图片" }
                        val casePath = imagePath(asset).also { templates.add(it) }
                        val click = caseProps.optBoolean("click", false)
                        val failure = caseProps.optString("failure", "stop")
                        require(failure == "stop" || failure == "skip") { "点击失败处理不合法" }
                        val regionValue = regionValues(caseProps)
                        "{ path: ${quoted(casePath)}, threshold: ${threshold(caseProps)}" +
                            (if (regionValue != null) ", region: $regionValue" else "") +
                            ", click: $click, failure: ${quoted(failure)} }"
                    }
                    val entriesText = entries.joinToString(", ")
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        out.append(pad).append("var $variable = null;\n")
                        out.append(pad).append("for (var ${variable}_try = 0; ${variable}_try <= $retries; ${variable}_try++) {\n")
                        out.append(pad).append("    if (${variable}_try > 0) sleep(${retryGapOf(properties)});\n")
                        out.append(pad).append("    $variable = __flowSwitch([$entriesText], $timeout, $interval);\n")
                        out.append(pad).append("    if ($variable) break;\n")
                        out.append(pad).append("}\n")
                    } else {
                        out.append(pad).append("var $variable = __flowSwitch([$entriesText], $timeout, $interval);\n")
                    }
                    branches.forEachIndexed { index, caseNode ->
                        out.append(pad).append(if (index == 0) "if (" else "else if (")
                            .append("$variable && $variable.index === ${index + 1}) {\n")
                        emitList(
                            caseNode.optJSONArray("children") ?: JSONArray(),
                            out, childDepth + 1, loops, ids, templates, anchors
                        )
                        out.append(pad).append("}\n")
                    }
                    out.append(pad).append("else {\n")
                    emitList(elseNodes ?: JSONArray(), out, childDepth + 1, loops, ids, templates, anchors)
                    emitPlanBody(out, pad + "    ", failurePlan(properties, "onMiss", "continue", anchors), "图片分支未命中")
                    out.append(pad).append("}\n")
                }
                "dialogClear" -> {
                    val entries = multiImageEntries(properties, templates)
                    val poll = positive(properties, "poll", 200, 50)
                    val emptyRounds = properties.optInt("emptyRounds", 3)
                    require(emptyRounds in 1..20) { "连续无命中轮数需为 1～20" }
                    val maxDuration = number(properties, "maxDuration", 0)
                    val maxClicks = number(properties, "maxClicks", 0)
                    val afterClick = properties.optString("afterClick", "wait")
                    require(afterClick == "wait" || afterClick == "stable") { "点击后等待方式不合法" }
                    val afterWait = number(properties, "afterWait", 300)
                    val clickFailure = properties.optString("clickFailure", "skip")
                    require(clickFailure == "stop" || clickFailure == "skip") { "点击失败处理不合法" }
                    out.append(pad).append(
                        "var $variable = __flowDialogClear($entries, $poll, $emptyRounds, $maxDuration, " +
                            "$maxClicks, ${quoted(afterClick)}, $afterWait, ${quoted(clickFailure)});\n"
                    )
                    if (properties.optString("onDone", "continue") == "stop") out.append(pad).append("__flowStop();\n")
                }
                "repeatClick" -> {
                    val count = properties.optInt("count", 3)
                    require(count in 1..999) { "点击次数需为 1～999" }
                    val interval = positive(properties, "interval", 300, 50)
                    val jitter = number(properties, "jitter", 0)
                    require(jitter <= 200) { "随机抖动不能超过 200" }
                    val duration = number(properties, "duration", 50)
                    val plan = failurePlan(properties, "failure", "stop", anchors)
                    val x = number(properties, "x", 0)
                    val y = number(properties, "y", 0)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("${variable}_ok = true;\n")
                            out.append(bodyPad).append("for (var ${variable}_i = 0; ${variable}_i < $count; ${variable}_i++) {\n")
                            out.append(bodyPad).append("    if (${variable}_i > 0) sleep($interval);\n")
                            val xExpr: String
                            val yExpr: String
                            if (jitter > 0) {
                                out.append(bodyPad).append("    var ${variable}_jx = Math.round((Math.random() * 2 - 1) * $jitter);\n")
                                out.append(bodyPad).append("    var ${variable}_jy = Math.round((Math.random() * 2 - 1) * $jitter);\n")
                                xExpr = "($x + ${variable}_jx)"
                                yExpr = "($y + ${variable}_jy)"
                            } else {
                                xExpr = "$x"
                                yExpr = "$y"
                            }
                            out.append(bodyPad).append("    if (!click($xExpr, $yExpr, $duration)) ${variable}_ok = false;\n")
                            out.append(bodyPad).append("}\n")
                        }
                        emitFailure(out, pad, "${variable}_ok", plan, "操作失败")
                    } else {
                        out.append(pad).append("for (var $variable = 0; $variable < $count; $variable++) {\n")
                        out.append(pad).append("    if ($variable > 0) sleep($interval);\n")
                        val xExpr: String
                        val yExpr: String
                        if (jitter > 0) {
                            out.append(pad).append("    var ${variable}_jx = Math.round((Math.random() * 2 - 1) * $jitter);\n")
                            out.append(pad).append("    var ${variable}_jy = Math.round((Math.random() * 2 - 1) * $jitter);\n")
                            xExpr = "($x + ${variable}_jx)"
                            yExpr = "($y + ${variable}_jy)"
                        } else {
                            xExpr = "$x"
                            yExpr = "$y"
                        }
                        out.append(pad).append("    if (!click($xExpr, $yExpr, $duration)) {\n")
                        emitPlanBody(out, pad + "        ", plan, "操作失败")
                        out.append(pad).append("    }\n")
                        out.append(pad).append("}\n")
                    }
                }
                "waitEvent" -> {
                    val mode = properties.optString("mode", "duration")
                    require(mode in setOf("duration", "imageAppear", "imageGone", "forever")) { "等待方式不合法" }
                    val duration = properties.optInt("duration", 60000)
                    val waitingImage = mode == "imageAppear" || mode == "imageGone"
                    if (mode == "duration") require(duration in 50..86_400_000) { "等待时长需为 50 ms～24 小时" }
                    else require(duration in 0..86_400_000) { "最长等待需为 0～24 小时" }
                    val poll = positive(properties, "poll", 1000, 100)
                    val heartbeat = properties.optInt("heartbeat", 30000)
                    require(heartbeat in 0..600000) { "心跳日志间隔不合法" }
                    val path: String? = if (waitingImage) {
                        val asset = properties.optString("asset")
                        require(asset.isNotBlank()) { "等待图片不能为空" }
                        imagePath(asset).also { templates.add(it) }
                    } else null
                    val options = "{ threshold: ${threshold(properties)}${region(properties)} }"
                    val expression = "__flowWaitEvent(${quoted(mode)}, ${path?.let { quoted(it) } ?: "null"}, " +
                        "$duration, $poll, $heartbeat, $options)"
                    val plan = failurePlan(properties, "onTimeout", "continue", anchors)
                    val retries = retriesOf(properties)
                    if (retries > 0) {
                        emitRetryLoop(out, pad, variable, retries, retryGapOf(properties)) { bodyPad ->
                            out.append(bodyPad).append("var $variable = $expression;\n")
                            out.append(bodyPad).append("${variable}_ok = !!$variable;\n")
                        }
                        emitFailure(out, pad, "${variable}_ok", plan, "等待超时")
                    } else {
                        out.append(pad).append("var $variable = $expression;\n")
                        emitFailure(out, pad, variable, plan, "等待超时")
                    }
                }
                "break", "continue" -> {
                    require(loops > 0) { "退出循环和进入下一轮只能放在循环内" }
                    out.append(pad).append(type).append(";\n")
                }
                "endRound" -> out.append(pad).append("return;\n")
                "stop" -> out.append(pad).append("__flowStop();\n")
                else -> error("未知模块：$type")
            }
            if (gated) out.append(basePad).append("}\n")
        }
    }

    private data class FailurePlan(val action: String, val silent: Boolean)

    /** 失败后的最终动作：沿用旧字段（failure/onMiss/onTimeout），额外支持回到开头和跳到锚点。 */
    private fun failurePlan(
        properties: JSONObject, legacyKey: String, legacyDefault: String, anchors: Set<String>
    ): FailurePlan {
        val value = properties.optString(legacyKey, legacyDefault).trim()
        return when {
            value == "continue" -> FailurePlan("skip", silent = true)
            value == "skip" -> FailurePlan("skip", silent = false)
            value == "stop" -> FailurePlan("stop", silent = false)
            value == "start" -> FailurePlan("start", silent = false)
            value.startsWith("goto:") -> {
                val name = value.removePrefix("goto:")
                require(name in anchors) { "跳转锚点不存在：$name" }
                FailurePlan(value, silent = false)
            }
            else -> error("失败处理不合法：$value")
        }
    }

    private fun retriesOf(properties: JSONObject): Int {
        val value = properties.optInt("retries", 0)
        require(value in 0..5) { "失败重试次数需为 0～5" }
        return value
    }

    private fun retryGapOf(properties: JSONObject): Int = positive(properties, "retryGap", 500, 50)

    /** 生成重试循环：body 负责给 `<variable>_ok` 赋值，成功后立即结束。 */
    private fun emitRetryLoop(
        out: StringBuilder, pad: String, variable: String, retries: Int, gap: Int,
        body: (String) -> Unit
    ) {
        out.append(pad).append("var ${variable}_ok = false;\n")
        out.append(pad).append("for (var ${variable}_try = 0; ${variable}_try <= $retries; ${variable}_try++) {\n")
        out.append(pad).append("    if (${variable}_try > 0) sleep($gap);\n")
        body(pad + "    ")
        out.append(pad).append("    if (${variable}_ok) break;\n")
        out.append(pad).append("}\n")
    }

    private fun emitFailure(
        out: StringBuilder, pad: String, failVar: String, plan: FailurePlan, message: String
    ) {
        if (plan.silent && plan.action == "skip") return
        out.append(pad).append("if (!$failVar) ").append(planStatement(plan, message)).append('\n')
    }

    private fun emitPlanBody(out: StringBuilder, pad: String, plan: FailurePlan, message: String) {
        if (plan.silent && plan.action == "skip") return
        out.append(pad).append(planStatement(plan, message)).append('\n')
    }

    /** 找图失败和点击失败可以配置不同去向时，按"命中但点击失败 / 完全没命中"分开处理。 */
    private fun emitDualFailure(
        out: StringBuilder, pad: String, okVar: String, foundVar: String,
        foundPlan: FailurePlan, missPlan: FailurePlan,
        foundMessage: String, missMessage: String
    ) {
        if (foundPlan == missPlan) {
            emitFailure(out, pad, okVar, missPlan, missMessage)
            return
        }
        out.append(pad).append("if (!$okVar) {\n")
        out.append(pad).append("    if ($foundVar) {\n")
        emitPlanBody(out, pad + "        ", foundPlan, foundMessage)
        out.append(pad).append("    } else {\n")
        emitPlanBody(out, pad + "        ", missPlan, missMessage)
        out.append(pad).append("    }\n")
        out.append(pad).append("}\n")
    }

    private fun planStatement(plan: FailurePlan, message: String): String = when {
        plan.action == "skip" -> "console.warn('$message，继续执行');"
        plan.action == "start" -> "__flowGoto(\"__start__\");"
        plan.action.startsWith("goto:") -> "__flowGoto(${quoted(plan.action.removePrefix("goto:"))});"
        else -> "throw Error('$message');"
    }

    private fun anchorName(properties: JSONObject): String {
        val name = properties.optString("name").trim()
        require(name.matches(Regex("[\\p{L}\\p{N}_-]{1,20}"))) { "锚点名称需为 1～20 个中英文、数字、下划线或短横线" }
        return name
    }

    /** 收集主流程顶层的锚点；锚点名称不能重复。 */
    private fun collectAnchors(nodes: JSONArray): Set<String> {
        val names = linkedSetOf<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            if (node.optString("type") == "anchor" && node.optBoolean("enabled", true)) {
                val name = anchorName(compileProperties(node))
                require(names.add(name)) { "锚点名称重复：$name" }
            }
            listOf("children", "then", "else").forEach { key ->
                node.optJSONArray(key)?.let { rejectNestedSpecials(it) }
            }
        }
        return names
    }

    /** 锚点和异常兜底只能放在主流程顶层，避免执行顺序难以预测。 */
    private fun rejectNestedSpecials(nodes: JSONArray) {
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val type = node.optString("type")
            require(type != "anchor" && type != "onError") { "锚点和异常兜底只能放在主流程顶层" }
            listOf("children", "then", "else").forEach { key ->
                node.optJSONArray(key)?.let { rejectNestedSpecials(it) }
            }
        }
    }

    private fun findErrorHandler(nodes: JSONArray): JSONObject? {
        var handler: JSONObject? = null
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            if (node.optString("type") != "onError") continue
            if (!node.optBoolean("enabled", true)) continue
            require(handler == null) { "异常兜底只能添加 1 个" }
            handler = node
        }
        return handler
    }

    /** Web 编辑器使用 props 嵌套结构，原生流程和旧数据使用根字段；编译时兼容两种格式。 */
    private fun compileProperties(node: JSONObject): JSONObject {
        val props = node.optJSONObject("props") ?: return node
        return JSONObject(props.toString()).apply {
            node.keys().forEach { key ->
                if (key != "props") put(key, node.get(key))
            }
        }
    }

    /** Keep both the saved flow and any draft in sync with a renamed task image. */
    fun replaceImageReferences(source: String, oldName: String, newName: String): String? {
        val document = JSONObject(source)
        var changed = false
        fun replace(fields: JSONObject, key: String) {
            val value = fields.optString(key)
            val updated = when (value.replace('\\', '/')) {
                oldName -> newName
                "images/$oldName" -> "images/$newName"
                else -> return
            }
            fields.put(key, updated)
            changed = true
        }
        fun visit(nodes: JSONArray?) {
            if (nodes == null) return
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                val props = node.optJSONObject("props")
                val type = node.optString("type")
                val primary = node.optString("conditionKind", props?.optString("conditionKind", "image") ?: "image")
                val secondary = node.optString("secondKind", props?.optString("secondKind") ?: "")
                listOfNotNull(node, props).forEach { fields ->
                    if (type in setOf(
                            "image", "waitImageAppear", "waitImageGone", "waitClick", "waitEvent", "switchCase"
                        )) replace(fields, "asset")
                    if (type == "multiImage" || type == "dialogClear") {
                        fields.optJSONArray("images")?.let { images ->
                            for (index in 0 until images.length()) {
                                images.optJSONObject(index)?.let { replace(it, "asset") }
                            }
                        }
                    }
                    if (type in setOf("condition", "waitCondition", "repeat")) {
                        if (primary == "image") replace(fields, "conditionValue")
                        if (secondary == "image") replace(fields, "secondValue")
                    }
                }
                listOf("children", "then", "else").forEach { visit(node.optJSONArray(it)) }
            }
        }
        visit(document.optJSONArray("nodes"))
        return if (changed) document.toString(2) + "\n" else null
    }

    private fun condition(node: JSONObject, templates: MutableSet<String>): String {
        val first = conditionPart(
            node.optString("conditionKind", "image"), node.optString("conditionValue"),
            node.optDouble("conditionThreshold", 0.9), templates
        )
        return when (node.optString("combine", "single")) {
            "single" -> first
            "not" -> "!($first)"
            "all", "any" -> {
                val second = conditionPart(
                    node.optString("secondKind", "text"), node.optString("secondValue"),
                    node.optDouble("secondThreshold", 0.9), templates
                )
                val operator = if (node.optString("combine") == "all") "&&" else "||"
                "($first $operator $second)"
            }
            else -> error("未知条件组合")
        }
    }

    private fun conditionPart(kind: String, value: String, threshold: Double, templates: MutableSet<String>): String {
        require(value.isNotBlank()) { "检测内容不能为空" }
        return when (kind) {
            "image" -> {
                require(threshold.isFinite() && threshold in 0.0..1.0) { "找图阈值不合法" }
                "!!__flowImage(${quoted(imagePath(value).also { templates.add(it) })}, 0, 300, { threshold: $threshold })"
            }
            "text" -> "!!__flowText(${quoted(value)}, { timeoutMs: 100, intervalMs: 50 })"
            else -> error("未知检测方式")
        }
    }

    private fun imageSearch(node: JSONObject, templates: MutableSet<String>): String {
        val path = imagePath(node.getString("asset")).also { templates.add(it) }
        val timeout = number(node, "timeout", 5000)
        val interval = number(node, "poll", 300)
        require(timeout > 0 && interval >= 50) { "找图等待参数不合法" }
        val threshold = node.optDouble("threshold", 0.9)
        require(threshold.isFinite() && threshold in 0.0..1.0) { "找图阈值不合法" }
        return "__flowImage(${quoted(path)}, $timeout, $interval, { threshold: $threshold${region(node)} })"
    }

    private fun multiImageEntries(node: JSONObject, templates: MutableSet<String>): String {
        val images = node.optJSONArray("images") ?: error("多图匹配缺少图片")
        require(images.length() in 1..20) { "多图匹配需要 1～20 张图片" }
        val entries = (0 until images.length()).map { index ->
            val image = images.getJSONObject(index)
            val asset = image.optString("asset")
            require(asset.isNotBlank()) { "多图匹配存在未选择的图片" }
            val path = imagePath(asset).also { templates.add(it) }
            val value = image.optDouble("threshold", 0.9)
            require(value.isFinite() && value in 0.0..1.0) { "多图匹配阈值不合法" }
            "{ path: ${quoted(path)}, threshold: $value }"
        }
        return "[${entries.joinToString(", ")}]"
    }

    private fun textSearch(node: JSONObject): String {
        val query = node.getString("text")
        require(query.isNotBlank()) { "找字内容不能为空" }
        val timeout = number(node, "timeout", 5000)
        val interval = number(node, "poll", 300)
        require(timeout > 0 && interval >= 50) { "找字等待参数不合法" }
        val match = node.optString("match", "contains")
        require(match == "contains" || match == "exact") { "找字匹配方式不合法" }
        return "__flowText(${quoted(query)}, { timeoutMs: $timeout, intervalMs: $interval, match: ${quoted(match)}${region(node)} })"
    }

    private fun region(node: JSONObject): String {
        val values = regionValues(node) ?: return ""
        return ", region: $values"
    }

    /** 解析 `[X,Y,宽,高]`，返回 JS 数组字面量；未配置返回 null。 */
    private fun regionValues(node: JSONObject): String? {
        val text = node.optString("region", "").trim()
        if (text.isEmpty()) return null
        val parts = text.removePrefix("[").removeSuffix("]").split(',').map { it.trim().toIntOrNull() ?: error("区域格式不正确") }
        require(parts.size == 4 && parts.take(2).all { it >= 0 } && parts.drop(2).all { it > 0 }) { "区域格式不正确" }
        return "[${parts.joinToString(", ")}]"
    }

    private fun fraction(node: JSONObject, key: String, default: Double): Double {
        val value = node.optDouble(key, default)
        require(value.isFinite() && value in 0.0..1.0) { "$key 需为 0～100%" }
        return value
    }

    private fun offset(node: JSONObject, key: String): Int {
        val value = node.optInt(key, 0)
        require(value in -2000..2000) { "$key 超出范围（-2000～2000）" }
        return value
    }

    private fun number(node: JSONObject, key: String, default: Int = -1): Int {
        val value = node.optInt(key, default)
        require(value >= 0) { "$key 不能小于 0" }
        return value
    }

    private fun positive(node: JSONObject, key: String, default: Int, minimum: Int = 1): Int {
        val value = node.optInt(key, default)
        require(value >= minimum) { "$key 不能小于 $minimum" }
        return value
    }

    private fun threshold(node: JSONObject, key: String = "threshold"): Double {
        val value = node.optDouble(key, 0.9)
        require(value.isFinite() && value in 0.0..1.0) { "找图阈值不合法" }
        return value
    }

    private fun imagePath(value: String): String {
        val path = value.replace('\\', '/')
        require(path.isNotBlank() && !path.startsWith('/') && ':' !in path && path.split('/').none { it == ".." || it == "." }) { "图片路径不合法" }
        return if (path.startsWith("images/")) path else "images/$path"
    }

    private fun quoted(value: String): String = JSONObject.quote(value)

    private fun writeAtomic(file: File, text: String) {
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }
}
