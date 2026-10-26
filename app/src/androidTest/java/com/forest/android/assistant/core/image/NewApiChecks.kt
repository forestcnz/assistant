package com.forest.android.assistant.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.forest.android.assistant.core.engine.ScriptAdapter
import com.forest.android.assistant.core.engine.ScriptEngine
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 新增脚本 API 的端到端回归：在真实 QuickJS 中验证等图点击、多模板匹配、
 * 多关键词 OCR、批量读写图片与固定坐标重试，无需无障碍或真实截屏权限。
 *
 * 截屏通道由 [ScriptEngine.ScriptEnv.captureScreen] 提供合成图片，
 * 因此这些用例同时覆盖「Kotlin → 桥接 → JS 包装层」的完整链路。
 */
object NewApiChecks {

    // 合成画面尺寸：够放下两个模板与一段文字
    private const val W = 640
    private const val H = 480

    fun run(context: Context) {
        val baseDir = File(context.cacheDir, "new-api").apply { mkdirs() }
        // 准备两个模板：左上角纯色块、右下角纯色块
        val red = makeBlock(Color.RED)
        val blue = makeBlock(Color.BLUE)
        val redPath = File(baseDir, "red.png").also { save(red, it) }.absolutePath.replace('\\', '/')
        val bluePath = File(baseDir, "blue.png").also { save(blue, it) }.absolutePath.replace('\\', '/')
        val textBitmap = makeTextImage()
        val textPath = File(baseDir, "text.png").also { save(textBitmap, it) }.absolutePath.replace('\\', '/')

        // 画面：红块在 (60,60)，蓝块在 (400,300)，另有一行文字
        val screen = buildScreen(red, blue, textBitmap)
        ScriptAdapter.setRealSize(W, H)
        try {
            runScript(baseDir, "new-api.js", screen, """
                function check(ok, message) { if (!ok) throw new Error(message); }
                adapter.setDesignSize(640, 480);
                var RED = "$redPath";
                var BLUE = "$bluePath";
                var TEXT = "$textPath";

                // ---- images.readAll：对象入参返回同 key 对象，缺失项进 missing ----
                var batch = images.readAll({ red: RED, blue: BLUE, nope: "images/missing.png" });
                check(batch.images.red !== null, "readAll 应读到 red");
                check(batch.images.blue !== null, "readAll 应读到 blue");
                check(batch.images.nope === null, "readAll 缺失项应为 null");
                check(batch.missing.length === 1, "readAll missing 应有 1 项");
                check(batch.missing[0] === "images/missing.png", "readAll missing 路径不符");

                // ---- images.readAll：数组入参返回等长数组，下标对齐 ----
                var arr = images.readAll([RED, "images/missing.png", BLUE]);
                check(arr.images.length === 3, "数组入参应返回等长数组");
                check(arr.images[1] === null, "数组缺失位应为 null");
                check(arr.images[2] !== null, "数组第 3 项应读到 blue");

                var shot = captureScreen();
                check(shot !== null, "合成截屏应可用");

                // ---- images.findImageAny：数组顺序即优先级 ----
                var hit = images.findImageAny(shot, [
                    { tpl: batch.images.blue, threshold: 0.9, name: "blue" },
                    { tpl: batch.images.red, threshold: 0.9, name: "red" }
                ]);
                check(hit !== null, "findImageAny 应命中");
                check(hit.name === "blue", "数组顺序优先，应命中 blue 而不是 red，实际 " + hit.name);
                check(hit.index === 0, "命中下标应为 0");

                // 换顺序后应命中 red
                var hit2 = images.findImageAny(shot, [
                    { tpl: batch.images.red, threshold: 0.9, name: "red" },
                    { tpl: batch.images.blue, threshold: 0.9, name: "blue" }
                ]);
                check(hit2 !== null, "换序后 findImageAny 应命中");
                check(hit2.name === "red", "换序后应命中 red，实际 " + hit2.name);

                // 路径字符串条目也能用
                var hit3 = images.findImageAny(shot, [BLUE]);
                check(hit3 !== null, "路径字符串条目应可用");
                check(hit3.index === 0, "路径条目下标应为 0");

                // 无效条目跳过，不影响后续条目
                var hit4 = images.findImageAny(shot, [
                    { tpl: null, name: "broken" },
                    { tpl: batch.images.red, threshold: 0.9, name: "red" }
                ]);
                check(hit4 !== null, "无效条目应被跳过");
                check(hit4.name === "red", "应命中后续有效条目");

                // 空数组与无效图片
                check(images.findImageAny(shot, []) === null, "空数组应返回 null");
                check(images.findImageAny(0, [{ tpl: BLUE }]) === null, "无效图片应返回 null");

                // ---- waitImageAny：命中即返回 ----
                var any = waitImageAny([
                    { tpl: BLUE, name: "blue" },
                    { tpl: RED, name: "red" }
                ], 1, 0.2);
                check(any !== null, "waitImageAny 应命中");
                check(any.name === "blue", "waitImageAny 应按数组顺序命中 blue");
                check(any.x > 0 && any.y > 0, "waitImageAny 应返回坐标");

                // missLimit：目标不存在时按次数结束并返回 null
                var miss = waitImageAny([{ tpl: "images/missing.png" }], 5, 0.05, { missLimit: 2 });
                check(miss === null, "waitImageAny 缺失目标应返回 null");

                // timeout=0 只检查一次
                var once = waitImageAny([{ tpl: BLUE, name: "blue" }], 0, 0.2);
                check(once !== null, "timeout=0 应只检查一次且能命中");

                // ---- ocr.findTextAny：一次 OCR 命中任意关键词 ----
                var found = ocr.findTextAny(shot, ["确定", "同意"]);
                check(found !== null, "findTextAny 应命中");
                check(found.matched === "同意" || found.matched === "确定", "matched 应为命中的关键词，实际 " + found.matched);
                check(typeof found.centerX === "function", "findTextAny 应返回 OcrItem");
                check(typeof found.text === "string" && found.text.length > 0, "findTextAny 应保留原始文字");
                check(found.bounds.left === found.left, "bounds 序列化应一致");

                // 无效关键词被忽略，全部无效时不触发识别
                check(ocr.findTextAny(shot, ["", "★"]) === null, "关键词全无效应返回 null");
                check(ocr.findTextAny(shot, []) === null, "空数组应返回 null");
                check(ocr.findTextAny(shot, ["不存在的文字"]) === null, "未命中应返回 null");

                // ---- images.findImagePair：红块与蓝块不在同一行，但仍返回坐标 ----
                var pair = images.findImagePair(shot, RED, BLUE, { threshold: 0.88 });
                check(pair !== null, "findImagePair 应返回结果");
                check(pair.sameRow === false, "红蓝块位置不同行，sameRow 应为 false");
                check(pair.rowDelta > pair.tolerance, "不同行时 rowDelta 应大于容差");
                // 中点为两个匹配中心的中点
                check(pair.midX === Math.floor((pair.a.x + pair.b.x) / 2), "midX 应为两中心中点");
                check(pair.midY === Math.floor((pair.a.y + pair.b.y) / 2), "midY 应为两中心中点");
                check(pair.dx === pair.b.x - pair.a.x, "dx 应为 b.x - a.x");
                check(pair.dy === pair.b.y - pair.a.y, "dy 应为 b.y - a.y");
                check(pair.a.x > 0 && pair.a.right > pair.a.left, "a 框应有有效边界");
                check(pair.b.x > 0 && pair.b.bottom > pair.b.top, "b 框应有有效边界");

                // 放大容差后应判为同行（容差覆盖实际 Y 差）
                var loose = images.findImagePair(shot, RED, BLUE, {
                    threshold: 0.88, rowTolerance: 400
                });
                check(loose !== null, "大容差时仍应返回结果");
                check(loose.sameRow === true, "容差覆盖 Y 差时应判为同行");
                check(loose.tolerance === 400, "rowTolerance 应被采用");

                // 同一模板：画面上有两个红块（同一行、水平隔开），应配对成功
                var reds = images.findAllImages(shot, RED, { threshold: 0.88, max: 4 });
                if (reds.length < 2) {
                    throw new Error("画面应有两个红块，实际 findAllImages 返回 " + reds.length + " 个");
                }
                var redPos = "";
                for (var ri = 0; ri < reds.length; ri++) redPos += "(" + reds[ri].x + "," + reds[ri].y + ") ";
                var sameTpl = images.findImagePair(shot, RED, RED, { threshold: 0.88 });
                check(sameTpl !== null, "画面有两个红块时同一模板应配对成功");
                check(sameTpl.sameRow === true, "两个红块在同一行，sameRow 应为 true");
                check(sameTpl.a.x !== sameTpl.b.x,
                    "位置应不同，a=(" + sameTpl.a.x + "," + sameTpl.a.y + ") b=(" +
                    sameTpl.b.x + "," + sameTpl.b.y + ") findAll=[" + redPos + "]");
                check(sameTpl.tolerance >= 4, "容差不应小于 4px");

                // 只有一个文字块时，同一模板去重后不足两个位置，应返回 null
                var oneOnly = images.findImagePair(shot, TEXT, TEXT, { threshold: 0.8 });
                check(oneOnly === null, "画面上只有一个文字块时同一模板应返回 null");

                // 缺少任一模板时返回 null
                check(images.findImagePair(shot, RED, "images/missing.png") === null,
                    "缺少模板时应返回 null");

                // ---- images.releaseAll：去重计数，忽略 null ----
                // batch 与 arr 各自 read 过同一路径，产生的是 4 个独立句柄（各 2 个），
                // 加上 shot 与 extra，共 6 个；重复传入同一对象才应被去重。
                var extra = images.read("$bluePath");
                var released = images.releaseAll(batch.images, [shot], extra, null, arr.images);
                check(released === 6, "releaseAll 应释放 6 个独立句柄，实际 " + released);
                // 同一句柄重复传入只算一次
                var dup = images.read("$redPath");
                check(images.releaseAll(dup, dup, [dup], { a: dup }) === 1, "重复句柄应只释放一次");
                check(images.releaseAll(null) === 0, "releaseAll 无有效参数应返回 0");
                check(images.releaseAll() === 0, "releaseAll 无参数应返回 0");
                check(images.releaseAll({}) === 0, "releaseAll 空对象应返回 0");

                // 释放后再查应失败，验证确实已释放
                check(images.findImageAny(shot, [{ tpl: BLUE }]) === null, "shot 已释放应返回 null");

                log("NEW_API_OK");
            """.trimIndent())

            // stop 行为：waitImageClick 的 missLimit 与 clickRetry 的点击通道
            // 无障碍不可用时 click 返回 false，因此仅验证「不推进 / 按次数结束」的语义
            runScript(baseDir, "wait-miss.js", screen, """
                function check(ok, message) { if (!ok) throw new Error(message); }
                adapter.setDesignSize(640, 480);
                // 目标不存在：连续 2 次未找到后返回 false
                var ok = waitImageClick("images/missing.png", 5, 0.05, { missLimit: 2 });
                check(ok === false, "waitImageClick 目标缺失应按 missLimit 返回 false");

                // 点击通道不可用时，找到目标也不会返回 true（不会误报成功）
                var ok2 = waitImageClick("$redPath", 0, 0.2, { threshold: 0.9 });
                check(ok2 === false, "无点击通道时 waitImageClick 不应返回 true");

                // clickRetry：times 含首次，点击通道不可用时按次数结束
                var ok3 = clickRetry(10, 10, 2, 10);
                check(ok3 === false, "无点击通道时 clickRetry 应返回 false");

                // waitTextClick：无点击通道时不应误报成功
                var ok4 = waitTextClick(["确定"], 0, 0.2, {});
                check(ok4 === false, "无点击通道时 waitTextClick 不应返回 true");

                log("WAIT_SEMANTICS_OK");
            """.trimIndent())
        } finally {
            ScriptAdapter.clear()
            red.recycle()
            blue.recycle()
            textBitmap.recycle()
            screen.recycle()
        }
    }

    private fun runScript(baseDir: File, name: String, screen: Bitmap, script: String) {
        val logs = mutableListOf<String>()
        val env = ScriptEngine.ScriptEnv(
            scriptPath = File(baseDir, name).absolutePath,
            onLog = { _, message -> synchronized(logs) { logs.add(message) } },
            onToast = {},
            openAccessibilitySettings = {},
            requestScreenCapture = { true },
            screenCaptureReady = { true },
            captureScreen = { screen.copy(Bitmap.Config.ARGB_8888, false) }
        )
        val outcome = runBlocking { ScriptEngine.execute(env, script) }
        check(outcome.kind == ScriptEngine.OutcomeKind.FINISHED) {
            "$name 执行失败：${outcome.kind} ${outcome.message}\n" +
                    synchronized(logs) { logs.takeLast(15) }.joinToString("\n")
        }
        check(synchronized(logs) { logs.any { it.startsWith("NEW_API_OK") || it.startsWith("WAIT_SEMANTICS_OK") } }) {
            "$name 未输出成功标记：${synchronized(logs) { logs.takeLast(15) }}"
        }
    }

    /** 构造纯色方块模板（带少量噪点，避免完全平坦导致 NCC 退化） */
    private fun makeBlock(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        val paint = Paint().apply { this.color = Color.WHITE }
        canvas.drawRect(4f, 4f, 12f, 10f, paint)
        return bmp
    }

    /** 文字图：在透明底上画一行中文，供 OCR 用例使用 */
    private fun makeTextImage(): Bitmap {
        val bmp = Bitmap.createBitmap(240, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 36f
        }
        canvas.drawText("同意", 12f, 44f, paint)
        return bmp
    }

    /** 合成整屏：红块与蓝块分开放置，下方贴文字行 */
    private fun buildScreen(red: Bitmap, blue: Bitmap, text: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        canvas.drawBitmap(red, 40f, 40f, null)
        canvas.drawBitmap(blue, 380f, 280f, null)
        canvas.drawBitmap(text, 180f, 160f, null)
        // 第二个红块：与第一个红块在同一行、水平方向隔开，用于「同一模板配两个位置」的用例
        canvas.drawBitmap(red, 480f, 40f, null)
        return bmp
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
