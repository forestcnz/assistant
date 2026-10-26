package com.forest.android.assistant.core.editor

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 脚本 API 定义（相当于设计里的 :api-docs 模块）：
 * - 为编辑器提供补全候选与签名提示；
 * - 生成 `script-js.d.ts` 声明文件，便于在编辑器/外部 IDE 中查阅类型与签名。
 * OCR 用法见 docs/README.md。
 */
object ApiDocs {

    private const val TAG = "ScriptJs"

    /** 一条 API：名称、签名、插入片段、说明 */
    data class Item(
        val name: String,
        val signature: String,
        val snippet: String,
        val doc: String,
        val declaration: String? = null
    )

    val items: List<Item> = listOf(
        // —— 基础工具 ——
        Item("sleep", "sleep(ms)", "sleep(1000);", "同步休眠"),
        Item("random", "random(min, max)", "random(1, 10)", "[min, max] 随机整数"),
        Item("exit", "exit()", "exit();", "结束当前脚本"),
        Item("log", "log(msg)", "log(\"\");", "控制台输出"),
        Item("toastLog", "toastLog(msg)", "toastLog(\"\");", "Toast + 控制台输出"),
        Item("click", "click(x, y[, duration])", "click(540, 1200);", "坐标点击（默认 30ms）"),
        Item("clickRandom", "clickRandom(x, y[, range, duration])", "clickRandom(540, 1200);", "随机偏移点击（防检测）"),
        Item("longClick", "longClick(x, y[, duration])", "longClick(540, 1200);", "长按（默认 600ms）"),
        Item("press", "press(x, y, duration)", "press(540, 1200, 800);", "定长按压"),
        Item("swipe", "swipe(x1, y1, x2, y2[, duration])", "swipe(540, 1800, 540, 600, 500);", "滑动（默认 500ms）"),
        Item("swipeRandom", "swipeRandom(x1, y1, x2, y2[, range, duration])", "swipeRandom(540, 1800, 540, 600);", "端点随机滑动"),
        Item(
            "gesture", "gesture(duration, [x,y]...[, delay])",
            "gesture(800, [500, 1500], [500, 500]);", "单指轨迹手势"
        ),
        Item(
            "gestures", "gestures([duration, [x,y]..., delay?], ...)",
            "gestures([500, [500, 800], [300, 900]], [500, [900, 800], [1100, 900]]);",
            "多指并动手势（同时起手）"
        ),
        Item("gesturesAvailable", "gesturesAvailable()", "gesturesAvailable()", "手势能力是否可用"),

        // —— 无障碍 ——
        Item("auto.waitFor", "auto.waitFor([timeoutMs])", "auto.waitFor(5000);", "等待无障碍服务开启"),
        Item("auto.service", "auto.service", "auto.service", "无障碍服务是否已连接"),

        // —— 截屏与找图 ——
        Item("requestScreenCapture", "requestScreenCapture([landscape])", "requestScreenCapture();", "申请屏幕录制授权"),
        Item("captureScreen", "captureScreen()", "captureScreen()", "截取当前屏幕（返回 Image）"),
        Item(
            "waitImage", "waitImage(tpl[, timeout, interval, options])",
            "waitImage(\"images/btn.png\", 10);", "轮询等图出现，返回 Point/null"
        ),
        Item(
            "waitImageVanish", "waitImageVanish(tpl[, timeout, interval, options])",
            "waitImageVanish(\"images/loading.png\", 5);", "轮询等图消失"
        ),
        Item(
            "images.findImage", "images.findImage(img, tpl, options?)",
            "images.findImage(captureScreen(), \"images/btn.png\", { threshold: 0.9 })",
            "模板匹配，返回匹配中心 Point"
        ),
        Item(
            "images.findAllImages", "images.findAllImages(img, tpl, options?)",
            "images.findAllImages(captureScreen(), \"images/btn.png\", { threshold: 0.9, max: 5 })",
            "全部匹配位置（相邻聚合）"
        ),
        Item(
            "images.existsImage", "images.existsImage(img, tpl, options?)",
            "images.existsImage(captureScreen(), \"images/btn.png\")", "模板是否在屏"
        ),
        Item(
            "images.findImageInRegion", "images.findImageInRegion(img, tpl, x, y[, w, h, threshold])",
            "images.findImageInRegion(captureScreen(), \"images/btn.png\", 0, 0, 540, 960)",
            "区域找图简写"
        ),
        Item(
            "images.findColor", "images.findColor(img, color, options?)",
            "images.findColor(captureScreen(), \"#ff4400\", { threshold: 16 })", "区域内找色"
        ),
        Item(
            "images.findMultiColors", "images.findMultiColors(img, firstColor, offsets, options?)",
            "images.findMultiColors(captureScreen(), \"#ff4400\", [[10, 0, \"#ffffff\"]])",
            "多点找色"
        ),
        Item("images.findImageWithText", "images.findImageWithText(img, tpl, options?)", "images.findImageWithText(captureScreen(), \"images/card.png\", { threshold: 0.9, padding: 8 })", "找图并对匹配区域做 OCR，返回 {x,y,bounds,text,items}"),
        Item("images.read", "images.read(path)", "images.read(\"images/btn.png\")", "读图（相对脚本目录）"),
        Item("images.save", "images.save(image, path[, format, quality])", "images.save(captureScreen(), \"shot.png\")", "保存图片"),
        Item("images.clip", "images.clip(img, x, y, w, h)", "images.clip(img, 0, 0, 200, 200)", "裁剪图片"),
        Item("images.pixel", "images.pixel(img, x, y)", "images.pixel(img, 10, 10)", "取像素色值"),

        // —— colors ——
        Item("colors.parseColor", "colors.parseColor(s)", "colors.parseColor(\"#ff4400\")", "解析颜色字符串"),
        Item("colors.rgb", "colors.rgb(r, g, b)", "colors.rgb(255, 0, 0)", "合成色值"),
        Item("colors.argb", "colors.argb(a, r, g, b)", "colors.argb(255, 255, 0, 0)", "合成带透明度色值"),
        Item("colors.red", "colors.red(c)", "colors.red(c)", "红色通道"),
        Item("colors.green", "colors.green(c)", "colors.green(c)", "绿色通道"),
        Item("colors.blue", "colors.blue(c)", "colors.blue(c)", "蓝色通道"),
        Item("colors.alpha", "colors.alpha(c)", "colors.alpha(c)", "透明度通道"),
        Item("colors.toString", "colors.toString(c)", "colors.toString(c)", "转 #AARRGGBB"),
        Item(
            "colors.isSimilar", "colors.isSimilar(c1, c2[, threshold, algorithm])",
            "colors.isSimilar(\"#ff0000\", \"#ff0101\", 16)", "颜色相似判断"
        ),

        // —— ocr ——
        Item("ocr.init", "ocr.init(engine?)", "ocr.init();", "初始化 OCR 引擎（默认 mlkit）",
            "init(engine?: string): boolean;"),
        Item("ocr.ready", "ocr.ready()", "ocr.ready()", "引擎是否已初始化",
            "ready(): boolean;"),
        Item("ocr.recognize", "ocr.recognize(image, options?)", "ocr.recognize(captureScreen())", "识别全部文字，返回 OcrItem[]；options.region 为 [x, y, width, height]",
            "recognize(image: any, options?: OcrOptions): OcrItem[];"),
        Item("ocr.recognizeText", "ocr.recognizeText(image, options?)", "ocr.recognizeText(captureScreen())", "仅返回按行拼接的原始文本；支持 options.region",
            "recognizeText(image: any, options?: OcrOptions): string;"),
        Item("ocr.findText", "ocr.findText(image, text, options?)", "ocr.findText(captureScreen(), \"立即购买\")", "找指定文字：精确匹配优先，否则包含匹配，忽略大小写；返回 OcrItem/null，支持 options.region",
            "findText(image: any, text: string, options?: OcrOptions): OcrItem | null;"),
        Item(
            "ocr.findTextContains", "ocr.findTextContains(image, text, options?)",
            "ocr.findTextContains(captureScreen(), \"立即购买\")",
            "忽略空格、换行、标点和符号，统一全角字符并忽略大小写后按行包含匹配。例如「立 即·购 买」可匹配「立即购买」。只保留中文、字母和数字；返回首个匹配行的原始 OcrItem，无匹配或清理后查询为空时返回 null，不跨行拼接。options.region 为 [x, y, width, height]。",
            "findTextContains(image: any, text: string, options?: OcrOptions): OcrItem | null;"
        ),
        Item("ocr.findAllText", "ocr.findAllText(image, text, options?)", "ocr.findAllText(captureScreen(), \"运行\")", "精确匹配优先，否则返回包含匹配的 OcrItem[]；支持 options.region",
            "findAllText(image: any, text: string, options?: OcrOptions): OcrItem[];"),
        Item(
            "ocr.findTextAny", "ocr.findTextAny(image, texts, options?)",
            "ocr.findTextAny(captureScreen(), [\"确定\", \"同意\"])",
            "一次 OCR 命中任意关键词：行顺序优先，同一行内取 texts 靠前的关键词。匹配规则同 findTextContains（忽略空格、标点、全角差异）。返回额外带 matched 字段的 OcrItem，无命中或关键词清理后全为空时返回 null。options.region 为 [x, y, width, height]。",
            "findTextAny(image: any, texts: string[], options?: OcrOptions): OcrItem | null;"
        ),

        // —— 等待与批量操作（新增）——
        Item(
            "waitImageClick", "waitImageClick(tpl, timeout, interval, options?)",
            "waitImageClick(\"images/btn.png\", 0, 1, { threshold: 0.88 });",
            "轮询等图出现并点击中心。timeout 单位秒：省略或负数=一直等到停止，0=只查一次，正数=最多等待秒数。options 支持 threshold、region、delay（点击后等待毫秒）、missLimit（连续未找到多少次后返回 false，0 不限）。只有成功截图却未匹配才计数；找到即清零，点击失败不推进。",
            null
        ),
        Item(
            "images.findImageAny", "images.findImageAny(img, templates, options?)",
            "images.findImageAny(captureScreen(), [{ tpl: a, name: 'a' }, b])",
            "单帧内按数组顺序查找多个模板，首个命中即返回，数组顺序即优先级（不比较分数）。条目支持 ImageRef、路径字符串或 { tpl, threshold, name }。返回 { x, y, index, name } 或 null。",
            null
        ),
        Item(
            "waitImageAny", "waitImageAny(templates, timeout, interval, options?)",
            "waitImageAny([{ tpl: reward, name: 'reward' }, quick], 0, 1)",
            "轮询等待多个模板中任意一个出现。timeout 秒：省略或负数=一直等到停止，0=只查一次。options 支持 threshold、region、missLimit。返回 { x, y, index, name } 或 null。截图失败不计入未找到。",
            null
        ),
        Item(
            "waitTextClick", "waitTextClick(texts, timeout, interval, options?)",
            "waitTextClick([\"确定\", \"同意\"], 0, 1, { region: [0, 0, 1920, 1080] })",
            "轮询截图 → OCR → 命中任意关键词后点击整行中心。timeout 秒：省略或负数=一直等到停止，0=只查一次。interval 默认 1 秒，按本轮开始补足周期。options 支持 region、delay（点击后等待毫秒）、missLimit。",
            null
        ),
        Item(
            "images.readAll", "images.readAll(source)",
            "images.readAll({ a: \"images/a.png\", b: \"images/b.png\" })",
            "批量读图。source 为路径数组或「键名→路径」对象。数组入参返回等长数组、对象入参返回同 key 对象，失败位置为 null；missing 为失败路径数组。",
            null
        ),
        Item(
            "images.releaseAll", "images.releaseAll(...targets)",
            "images.releaseAll(t.images, shot)",
            "批量释放图片句柄。参数可混用句柄、数组、对象和 null，递归展开并按内部 id 去重；返回实际释放数量。",
            null
        ),
        Item(
            "clickRetry", "clickRetry(x, y, times, interval, duration)",
            "clickRetry(1777, 24, 3, 500);",
            "固定坐标点击失败时重试。times 含首次、默认 3，0 表示一直重试直到停止；interval 为失败后等待毫秒（默认 500）；duration 透传给 click（默认 30ms）。",
            null
        ),

        // —— adapter ——
        Item("adapter.setDesignSize", "adapter.setDesignSize(w, h)", "adapter.setDesignSize(1080, 1920);", "声明设计基准分辨率"),
        Item("adapter.reset", "adapter.reset()", "adapter.reset();", "取消坐标缩放"),
        Item("adapter.scale", "adapter.scale(x, y)", "adapter.scale(540, 1200)", "返回换算后的真机坐标"),
        Item("adapter.enabled", "adapter.enabled()", "adapter.enabled()", "是否启用缩放")
    )

    /** 按前缀过滤补全候选（匹配名称末段，忽略大小写） */
    fun suggestions(prefix: String, limit: Int = 8): List<Item> {
        if (prefix.isBlank()) return items.take(limit)
        val p = prefix.lowercase()
        return items.filter { item ->
            val short = item.name.substringAfterLast('.')
            short.lowercase().startsWith(p) || item.name.lowercase().contains(p)
        }.take(limit)
    }

    /** 生成 .d.ts 声明文件（相当于 :api-docs 的产物） */
    fun writeDts(context: Context): File? = try {
        val dir = File(context.filesDir, "api").apply { mkdirs() }
        val file = File(dir, "script-js.d.ts")
        file.writeText(buildDts())
        Log.i(TAG, "已生成 API 声明文件: ${file.absolutePath}")
        file
    } catch (e: Exception) {
        Log.w(TAG, "生成 .d.ts 失败: ${e.message}")
        null
    }

    internal fun buildDts(): String = buildString {
        appendLine("// ddhelp 脚本 API 声明（自动生成，勿手改）")
        appendLine("// OCR 用法见 docs/README.md")
        appendLine()
        appendLine("interface Point { x: number; y: number; }")
        appendLine("interface Rect { left: number; top: number; right: number; bottom: number; }")
        appendLine("interface OcrItem extends Rect { text: string; bounds: Rect; confidence: number; matched?: string; centerX(): number; centerY(): number; }")
        appendLine("interface OcrOptions { region?: [number, number, number, number]; }")
        // 多模板匹配结果：index 为传入数组下标，name 为条目 name/路径
        appendLine("interface AnyMatch { x: number; y: number; index: number; name: string; }")
        // 模板条目：可直接传句柄/路径，也可用对象覆盖阈值与名称
        appendLine("interface TemplateEntry { tpl: any; threshold?: number; name?: string; }")
        // images.readAll 的结果：数组入参返回数组，对象入参返回同 key 对象
        appendLine("interface ReadAllResult { images: any; missing: string[]; }")
        appendLine()
        items.filter { '.' !in it.name }.forEach { item ->
            appendLine("/** ${item.doc} */")
            appendLine(dtsSignature(item))
        }
        appendLine()
        appendLine("declare const images: {")
        items.filter { it.name.startsWith("images.") }.forEach {
            appendLine("    /** ${it.doc} */")
            appendLine("    ${it.name.removePrefix("images.")}: (...args: any[]) => any;")
        }
        appendLine("};")
        appendLine("declare const colors: {")
        items.filter { it.name.startsWith("colors.") }.forEach {
            appendLine("    ${it.name.removePrefix("colors.")}: (...args: any[]) => any;")
        }
        appendLine("};")
        appendLine("declare const adapter: {")
        items.filter { it.name.startsWith("adapter.") }.forEach {
            appendLine("    ${it.name.removePrefix("adapter.")}: (...args: any[]) => any;")
        }
        appendLine("};")
        appendLine("declare const ocr: {")
        items.filter { it.name.startsWith("ocr.") }.forEach {
            appendLine("    /** ${it.doc} */")
            appendLine("    ${it.declaration ?: "${it.name.removePrefix("ocr.")}: (...args: any[]) => any;"}")
        }
        appendLine("};")
        appendLine("declare const auto: { service: boolean; waitFor: (timeoutMs?: number) => boolean; };")
    }

    /**
     * 把签名转成 TS 声明形式：参数名保留、类型统一 any、可选参数加 ?。
     * 支持文档里的可选组写法与嵌套数组，例如：
     * - `click(x, y[, duration])`        → `(x: any, y: any, duration?: any)`
     * - `waitImage(tpl[, timeout, ...])` → `(tpl: any, timeout?: any, ...)`
     */
    private fun dtsSignature(item: Item): String {
        val name = item.name
        val params = parseParams(item.signature)
        val tsParams = params.joinToString(", ") { (p, optional) ->
            if (optional) "$p?: any" else "$p: any"
        }
        return "declare function $name($tsParams): any;"
    }

    /** 解析参数列表：顶层方括号视为“可选组”，嵌套方括号属于数组字面量 */
    private fun parseParams(signature: String): List<Pair<String, Boolean>> {
        val open = signature.indexOf('(')
        if (open < 0) return emptyList()
        val inner = signature.substring(open + 1).substringBeforeLast(')')
        val out = ArrayList<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var depth = 0
        var index = 0

        fun flush() {
            var raw = sb.toString().trim()
            sb.setLength(0)
            if (raw.isEmpty()) return
            // 位于顶层可选组内（depth>=1）或自带 ? 的参数视为可选
            val opt = depth >= 1 || raw.endsWith("?")
            if (raw.endsWith("?")) raw = raw.dropLast(1).trim()
            raw = raw.trim(',', ' ')
            if (raw.isEmpty() || raw == "...") return
            index++
            // 非法标识符（嵌套数组等）用 argN 兜底，保证 .d.ts 合法
            val valid = raw[0].let { it.isLetter() || it == '_' || it == '$' } &&
                    raw.all { it.isLetterOrDigit() || it == '_' || it == '$' }
            out.add((if (valid) raw else "arg$index") to opt)
        }

        for (c in inner) {
            when (c) {
                '[' -> {
                    if (depth == 0) flush()      // 先结算可选组前的必填参数
                    depth++
                }
                ']' -> {
                    if (depth == 1) flush()      // 可选组结束，结算组内最后一个参数
                    if (depth > 0) depth--
                }
                ',' -> if (depth <= 1) flush() else sb.append(c)
                else -> sb.append(c)
            }
        }
        flush()
        return out
    }
}
