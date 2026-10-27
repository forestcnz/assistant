package com.forest.android.assistant.core.editor

/** 从桌面 tasks 的共通写法提炼关键用法；完整 API 示例单独展示。 */
object ScriptExamples {
    data class Item(
        val title: String,
        val description: String,
        val category: String,
        val code: String,
        val completeScript: Boolean = false
    )

    val items = listOf(
        // 各任务共用的初始化与图片生命周期。
        Item("横屏坐标", "以 1920×1080 为基准填写点击坐标，结束时取消适配。", "准备", """
            adapter.setDesignSize(1920, 1080);
            try {
                click(960, 540); // 按 1920×1080 填写的设计坐标
                sleep(500);
            } finally {
                adapter.reset();
            }
        """.trimIndent()),
        Item("申请操作权限", "先等无障碍服务，再申请截图授权；任一失败直接结束脚本。", "准备", """
            if (!auto.waitFor(5000)) {
                toastLog("无障碍服务未开启");
                exit();
            }
            if (!requestScreenCapture()) {
                toastLog("截图授权失败");
                exit();
            }
        """.trimIndent()),
        Item("截取当前画面", "先申请截图授权；shot 可能为空，用完必须释放。", "准备", """
            var shot = captureScreen();
            if (!shot) {
                toastLog("截图失败");
                exit();
            }
            try {
                log("截图：", images.width(shot), "×", images.height(shot));
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("预加载找图素材", "放在循环前读取一次；template 为空表示素材读取失败，结束时释放。", "准备", """
            var template = images.read("images/按钮.png");
            if (!template) {
                toastLog("素材读取失败：images/按钮.png");
                exit();
            }
            try {
                log("素材尺寸：", images.width(template), "×", images.height(template));
            } finally {
                images.release(template);
            }
        """.trimIndent()),
        Item("释放截图与素材", "截图每轮用完释放；素材在整个任务结束时释放，也要覆盖异常退出。", "准备", """
            var shot = null, template = null;
            try {
                template = images.read("images/按钮.png");
                shot = captureScreen();
                if (template && shot) log("准备完成");
            } finally {
                images.release(template);
                images.release(shot);
            }
        """.trimIndent()),

        // 天下五绝、福地绳子、恩仇、通天塔中的循环控制。
        Item("循环指定次数", "执行 5 次，每次点击后等 800 毫秒让界面切换。", "循环", """
            for (var i = 0; i < 5; i++) {
                log("第 " + (i + 1) + " 次");
                click(760, 255);
                sleep(800);
            }
        """.trimIndent()),
        Item("持续循环", "每秒执行一次，直到手动停止脚本；建议在循环内先判断状态。", "循环", """
            while (true) {
                click(760, 255);
                sleep(1000);
            }
        """.trimIndent()),
        Item("遍历多个坐标", "按数组顺序依次点击各位置，中间留出等待时间。", "循环", """
            var positions = [[760, 255], [512, 414]];
            for (var p of positions) {
                log("点击：", p[0], p[1]);
                click(p[0], p[1]);
                sleep(800);
            }
        """.trimIndent()),
        Item("超时后结束循环", "最多循环 8 秒，超时自动结束；把 log 换成实际轮询操作。", "循环", """
            var deadline = Date.now() + 8000;
            while (Date.now() < deadline) {
                log("等待中，剩余 " + Math.ceil((deadline - Date.now()) / 1000) + " 秒");
                sleep(1000);
            }
            log("超时，结束循环");
        """.trimIndent()),
        Item("连续未找到后结束", "完整的找图循环：连续 5 次没找到就退出，找到则重置计数。", "循环", """
            var template = images.read("images/按钮.png");
            if (!template) exit();
            var misses = 0;
            try {
                while (true) {
                    var shot = captureScreen();
                    try {
                        if (!shot) { sleep(1000); continue; } // 截图失败不计次数
                        var point = images.findImage(shot, template, { threshold: 0.88 });
                        misses = point ? 0 : misses + 1;
                        if (point) {
                            click(point.x, point.y);
                            sleep(1000);
                        }
                    } finally {
                        images.release(shot);
                    }
                    if (misses >= 5) {
                        log("连续 " + misses + " 次未找到，结束");
                        break;
                    }
                    sleep(500);
                }
            } finally {
                images.release(template);
            }
        """.trimIndent()),
        Item("保持每秒轮询", "每轮从循环开头计时，结尾补足到 1 秒，避免截屏耗时导致频率漂移。", "循环", """
            while (true) {
                var startedAt = Date.now();
                log("本轮开始");
                var remaining = 1000 - (Date.now() - startedAt);
                if (remaining > 0) sleep(remaining);
            }
        """.trimIndent()),

        // 固定坐标点击、重试来自天下五绝和各任务的导航步骤。
        Item("点击后等待", "点击完成后留出 3 秒切换界面，再继续下一步。", "点击", """
            if (click(1777, 24)) {
                sleep(3000);
                log("第 1 步完成");
            } else {
                log("第 1 步点击失败");
            }
        """.trimIndent()),
        Item("点击失败就重试", "固定坐标点击未完成时，隔 1 秒重试，超过 5 次放弃。", "点击", """
            var tries = 0;
            while (!click(1777, 24)) {
                tries++;
                if (tries >= 5) {
                    toastLog("连续 5 次点击失败");
                    exit();
                }
                sleep(1000);
            }
            log("点击成功，重试 " + tries + " 次");
        """.trimIndent()),

        // 通天塔、帮派宣言、福地绳子、恩仇的等图与独立阈值。
        Item("找图并点击", "截屏、加载素材、找图并点击，最后释放图片。素材放到任务的 images/ 下。", "找图", """
            var template = images.read("images/按钮.png");
            if (!template) { toastLog("素材读取失败"); exit(); }
            var shot = null;
            try {
                shot = captureScreen();
                if (!shot) { toastLog("截图失败"); exit(); }
                var point = images.findImage(shot, template, { threshold: 0.88 });
                if (point) {
                    click(point.x, point.y);
                    log("已点击：", point.x, point.y);
                } else {
                    log("未找到按钮");
                }
            } finally {
                images.release(shot);
                images.release(template);
            }
        """.trimIndent()),
        Item("只在指定区域找图", "只在 [x, y, 宽, 高] 范围内搜索，可避开背景里的相似图标。", "找图", """
            var region = [37, 593, 801, 160];
            var template = images.read("images/按钮.png");
            if (!template) { toastLog("素材读取失败"); exit(); }
            var shot = null;
            try {
                shot = captureScreen();
                if (!shot) { toastLog("截图失败"); exit(); }
                var point = images.findImage(shot, template, { region: region, threshold: 0.88 });
                if (point) {
                    click(point.x, point.y);
                    log("区域内已点击：", point.x, point.y);
                } else {
                    log("区域内未找到按钮");
                }
            } finally {
                images.release(shot);
                images.release(template);
            }
        """.trimIndent()),
        Item("等待图片出现", "先授权截图；最多等 8 秒，每秒检查一次，找到后点击。", "找图", """
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            var point = waitImage("images/按钮.png", 8, 1, { threshold: 0.88 });
            if (point) {
                click(point.x, point.y);
                log("按钮已出现并点击");
            } else {
                log("等待 8 秒仍未出现");
            }
        """.trimIndent()),
        Item("等待图片消失", "常用于等加载遮罩消失；超时返回 false 时再继续后续操作。", "找图", """
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            if (waitImageVanish("images/加载中.png", 5, 0.5)) {
                log("加载完成");
                click(960, 540);
            } else {
                log("加载仍存在，稍后重试");
            }
        """.trimIndent()),

        // 击败它们、区域确认中的 OCR、文字匹配与区域遍历。
        Item("初始化 OCR", "首次识字前调用一次；初始化失败直接结束脚本。", "OCR", """
            if (!ocr.init()) {
                toastLog("OCR 初始化失败");
                exit();
            }
            log("OCR 已就绪：", ocr.ready());
        """.trimIndent()),
        Item("读取区域文字", "先初始化 OCR 并授权截图，再读取指定区域的文字。", "OCR", """
            if (!ocr.init()) { toastLog("OCR 初始化失败"); exit(); }
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                var text = ocr.recognizeText(shot, { region: [37, 593, 801, 160] });
                log("识别结果：" + text);
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("识字后点击", "在截图中忽略空格和符号查找文字，找到后点击整行中心。", "OCR", """
            if (!ocr.init()) { toastLog("OCR 初始化失败"); exit(); }
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                var line = ocr.findTextContains(shot, "击败它们");
                if (line) {
                    click(line.centerX(), line.centerY());
                    log("已点击：" + line.text);
                } else {
                    log("未找到「击败它们」");
                }
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("遍历区域识别结果", "先初始化 OCR 并授权截图，逐行输出识别到的文字及其中心坐标。", "OCR", """
            if (!ocr.init()) { toastLog("OCR 初始化失败"); exit(); }
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                var lines = ocr.recognize(shot, { region: [601, 570, 324, 130] });
                for (var line of lines) {
                    log(line.text, line.centerX(), line.centerY());
                }
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("匹配确定或同意", "放在 OCR 结果遍历中：去掉空白后匹配「确定」或「同意」，只点一次后跳出。", "OCR", """
            var lines = ocr.recognize(shot, { region: [601, 570, 324, 130] });
            for (var line of lines) {
                var text = line.text.replace(/\s+/g, "");
                if (/确定|同意/.test(text)) {
                    click(line.centerX(), line.centerY());
                    break;
                }
            }
        """.trimIndent()),

        // 失败不推进、单帧最多点击一次、弹窗优先等任务判断。
        Item("截图失败跳过本轮", "放在截图循环中；截图失败不计入连续未找到次数。", "判断", """
            while (true) {
                var shot = captureScreen();
                if (!shot) {
                    sleep(1000);
                    continue;
                }
                try {
                    log("本轮截图正常");
                } finally {
                    images.release(shot);
                }
                sleep(1000);
            }
        """.trimIndent()),
        Item("每轮只点击一次", "放在 OCR 遍历中匹配成功的分支，点完立刻 break，避免同帧重复点击。", "判断", """
            var lines = ocr.recognize(shot, { region: [601, 570, 324, 130] });
            for (var line of lines) {
                var text = line.text.replace(/\s+/g, "");
                if (/确定|同意/.test(text)) {
                    click(line.centerX(), line.centerY());
                    break;
                }
            }
        """.trimIndent()),
        Item("优先处理结束弹窗", "先判断结束弹窗，再判断背景按钮，避免误点背景内容。", "判断", """
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                if (images.existsImage(shot, "images/次数耗尽.png")) {
                    log("次数已耗尽，结束脚本");
                    exit();
                }
                if (images.existsImage(shot, "images/开始挑战.png")) {
                    log("可以挑战");
                }
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("输出运行状态", "普通进度用 log，异常提示用 console.warn/error，均输出到控制台。", "判断", """
            log("开始执行");
            console.info("进度：第 1 步");
            console.warn("未找到目标，等待重试");
            console.error("示例错误日志，不会结束脚本");
        """.trimIndent()),

        // 新增 API：等图点击、多状态识别、批量读写、多关键词 OCR。
        Item("等图点击", "轮询等待图片出现并点击。timeout 省略表示一直等到停止，missLimit 控制连续未找到次数。", "找图", """
            // timeout 省略 = 一直等到停止；delay 为点击后等待的毫秒数
            if (!waitImageClick("images/按钮.png", 0, 1, {
                threshold: 0.88, delay: 3000, missLimit: 3
            })) {
                toastLog("连续 3 次未找到按钮");
                exit();
            }
            log("按钮已点击");
        """.trimIndent()),
        Item("多状态识别", "一帧内按数组顺序匹配多个模板，首个命中即返回；数组顺序就是优先级。", "找图", """
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                var hit = images.findImageAny(shot, [
                    { tpl: "images/次数耗尽.png", threshold: 0.86, name: "exhausted" },
                    { tpl: "images/战败.png", threshold: 0.88, name: "defeated" },
                    { tpl: "images/随机战.png", threshold: 0.88, name: "random" }
                ]);
                if (hit) log("命中：" + hit.name + " 下标 " + hit.index);
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("等任一状态出现", "轮询多个模板，谁先出现就返回谁；适合弹窗优先于背景按钮的分支判断。", "找图", """
            var hit = waitImageAny([
                { tpl: "images/排名奖励.png", threshold: 0.88, name: "reward" },
                { tpl: "images/快速通关.png", threshold: 0.88, name: "quick" }
            ], 0, 1);
            if (hit && hit.name === "reward") {
                log("需要先领奖");
            } else if (hit) {
                log("可直接进入下一步");
            }
        """.trimIndent()),
        Item("批量读图与释放", "一次性加载全部素材并检查缺失；结束时统一释放，重复句柄只算一次。", "准备", """
            var t = images.readAll({
                elder: "images/云中易.png",
                enter: "images/进入恩仇录.png"
            });
            if (t.missing.length) {
                console.error("缺少图片：" + t.missing.join("、"));
                exit();
            }
            try {
                log("素材已加载");
            } finally {
                images.releaseAll(t.images);
            }
        """.trimIndent()),
        Item("多关键词 OCR 点击", "一次识别匹配多个关键词，命中后点击整行中心。", "OCR", """
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                var line = ocr.findTextAny(shot, ["确定", "同意"], {
                    region: [1702, 425, 192, 92]
                });
                if (line) {
                    log("命中关键词：" + line.matched);
                    click(line.centerX(), line.centerY());
                }
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("常驻监听文字", "轮询 OCR，命中任意关键词就点击；timeout 省略表示一直等到停止。", "OCR", """
            waitTextClick(["击败它们"], 0, 1, {
                region: [37, 593, 801, 160],
                delay: 1000
            });
        """.trimIndent()),
        Item("OCR 多区域", "在同一张截图里按顺序检查多个区域，返回命中区域下标。", "OCR", """
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                var result = ocr.findTextAny(shot, ["确定", "同意"], {
                    regions: [[1702, 425, 192, 92], [601, 570, 324, 130]]
                });
                if (result) log("区域", result.regionIndex, result.text);
            } finally {
                images.release(shot);
            }
        """.trimIndent()),
        Item("等待文字", "等待文字命中但不自动点击；WaitResult 提供命中和超时原因。", "OCR", """
            var result = waitTextAny(["确定", "同意"], {
                regions: [[1702, 425, 192, 92], [601, 570, 324, 130]],
                timeoutMs: 10000, intervalMs: 500, confirmCount: 2
            });
            if (result.ok) click(result.value.centerX(), result.value.centerY());
            else log("等待结束：", result.reason, result.lastError);
        """.trimIndent()),
        Item("持续文字监听", "命中后点击并继续监听，可用 maxClicks 或 maxAttempts 限制运行。", "OCR", """
            var result = watchText(["击败它们"], {
                region: [37, 593, 801, 160], intervalMs: 500,
                delayMs: 1000, maxClicks: 5, maxAttempts: 100
            });
            log("监听结束：", result.reason, "点击次数：", result.clicks);
        """.trimIndent()),
        Item("组合屏幕等待", "回调拿到本轮截图，可组合多个模板或自定义业务状态。", "判断", """
            var result = waitScreen(function(shot) {
                return images.findImageAny(shot, [
                    { tpl: "images/次数耗尽.png", threshold: 0.86, name: "exhausted" },
                    { tpl: "images/随机战.png", threshold: 0.88, name: "random" }
                ]);
            }, { timeoutMs: 30000, intervalMs: 500, confirmCount: 2 });
            if (result.ok) log("状态：", result.value.name);
            else log("等待结束：", result.reason);
        """.trimIndent()),
        Item("整组动作重试", "重试函数里的整组导航步骤；maxAttempts 包含第一次执行。", "判断", """
            var result = retry(function(attempt) {
                log("第 " + attempt + " 次导航");
                return click(1777, 24) && click(1103, 760);
            }, { maxAttempts: 3, intervalMs: 1000, name: "前往临安" });
            log("导航重试结果：", result.reason);
        """.trimIndent()),
        Item("重复点击直到图片消失", "每轮重新定位和点击，连续多帧未找到后返回点击次数。", "找图", """
            var result = clickImageUntilGone("images/sweep.png", {
                threshold: 0.88, confirmCount: 5, delayMs: 1000,
                maxClicks: 20, timeoutMs: 120000, name: "扫荡"
            });
            log("结束原因：", result.reason, "成功点击：", result.clicks);
        """.trimIndent()),
        Item("缩放与匹配分数", "显式缩放模板，并读取匹配分数和边界；缩放图需要单独释放。", "找图", """
            var shot = captureScreen();
            var template = images.read("images/button.png");
            var scaled = null;
            try {
                if (!shot || !template) exit();
                scaled = images.resize(template, 320, 100, { filter: true });
                var matches = images.matchTemplate(shot, scaled, {
                    threshold: 0.88, region: [0, 0, 1920, 1080], max: 3
                });
                if (matches.length) log(matches[0].score, JSON.stringify(matches[0].bounds));
            } finally {
                images.releaseAll(scaled, template, shot);
            }
        """.trimIndent()),
        Item("固定坐标重试", "点击通道未受理时自动重试；times 含首次，0 表示一直重试到停止。", "点击", """
            if (!clickRetry(1777, 24, 3, 500)) {
                toastLog("打开地图失败");
                exit();
            }
            log("地图已打开");
        """.trimIndent()),
        Item("双模板同行校验", "两个模板是否在同一行，并返回两者中心的中点；不同行仍返回坐标，sameRow 标记结论。", "找图", """
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败"); exit(); }
            try {
                var pair = images.findImagePair(shot, "images/装备.png", "images/技能.png", {
                    thresholdA: 0.88, thresholdB: 0.88
                });
                if (!pair) { log("至少有一个模板没找到"); }
                else if (pair.sameRow) {
                    log("同一行，Y 差 " + pair.rowDelta + "px");
                    click(pair.midX, pair.midY);   // 两个模板之间的坐标
                } else {
                    log("不在同一行：Y 差 " + pair.rowDelta + "，容差 " + pair.tolerance);
                }
            } finally {
                images.release(shot);
            }
        """.trimIndent())
    )

    val completeExample = Item(
        title = "完整示例：全部 API",
        description = "展示全部 81 个公开方法。会截图、OCR、保存演示图片及执行手势；运行前修改文字与点击坐标。",
        category = "完整示例",
        completeScript = true,
        code = """
            // 完整 API 示例：全部 81 个公开函数/方法及公开属性。
            // 素材从本次截图生成，无需提前导入；图片保存到任务的 output/ 目录。
            // 会实际执行点击、滑动和多指手势，请先修改下方坐标与查询文字。
            var query = "确定";
            var region = [0, 0, 1920, 1080];
            var x = 960, y = 540;
            var output = "output/api-demo-" + Date.now();

            // 1. 坐标适配、模块与路径。
            adapter.setDesignSize(1920, 1080);
            log("坐标适配：", adapter.enabled());
            var real = adapter.scale(x, y); // [真实像素X, 真实像素Y]，用于取色，不再传入 click。
            var imageModule = require("images");
            print("图片模块：", imageModule === images, "脚本：", __scriptPath__);

            // 2. 权限：auto() 打开设置，auto.waitFor 等待服务，auto.service 读取状态。
            if (!auto.service) auto();
            if (!auto.waitFor(5000)) { toastLog("请先开启无障碍服务"); exit(); }
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            // 当前 Async 接口返回同步授权结果的 Promise，不需用它组织任务流程。
            var permissionPromise = requestScreenCaptureAsync();
            console.log("异步接口返回 Promise：", typeof permissionPromise.then === "function");

            // 3. 基础工具与各级日志。
            sleep(random(300, 500));
            console.info("开始 API 示例");
            console.warn("以下为演示日志：未找到目标时可以重试");
            console.error("以下为演示日志：error 仅记录错误，不会结束脚本");

            // 4. 颜色工具：颜色构造、各通道、字符串与相似判断。
            var red = colors.parseColor("#ff0000");
            var rgb = colors.rgb(255, 0, 0);
            var argb = colors.argb(255, 255, 0, 0);
            log("颜色：", colors.toString(red), colors.isSimilar(red, rgb, 16, "diff"));
            log("ARGB：", colors.alpha(argb), colors.red(argb), colors.green(argb), colors.blue(argb));

            var shot = null, crop = null, template = null, resized = null;
            try {
                // 5. 截图、尺寸、裁剪、保存、读取。clip/pixel 均使用真实像素。
                shot = captureScreen();
                if (!shot) { toastLog("截图失败"); exit(); }
                var width = images.width(shot), height = images.height(shot);
                log("截图尺寸：", width, height);
                var cropX = Math.floor(width / 4), cropY = Math.floor(height / 4);
                crop = images.clip(shot, cropX, cropY, Math.min(160, width - cropX), Math.min(80, height - cropY));
                if (!crop || !images.save(crop, output + "-template.png", "png", 100)) {
                    toastLog("生成演示素材失败"); exit();
                }
                template = images.read(output + "-template.png");
                if (!template) { toastLog("演示素材读取失败"); exit(); }
                resized = images.resize(template,
                    Math.max(1, Math.floor(images.width(template) / 2)),
                    Math.max(1, Math.floor(images.height(template) / 2)));
                log("缩放图片：", resized ? images.width(resized) + "x" + images.height(resized) : null);
                log("保存截图：", images.save(shot, output + "-screen.png", "png", 100));
                // 批量读图：一次加载多个素材并检查缺失
                var batch = images.readAll({ template: output + "-template.png" });
                log("批量读图缺失：", batch.missing.length);
                images.releaseAll(batch.images);

                // 6. 找图：单个、多模板、多个、是否存在、指定范围。
                var options = { region: region, threshold: 0.88 };
                var point = images.findImage(shot, template, options);
                log("匹配中心：", JSON.stringify(point));
                log("匹配明细：", JSON.stringify(images.findImageDetails(shot, template, options)));
                log("全部匹配明细：", JSON.stringify(images.matchTemplate(shot, template, { threshold: 0.88, max: 3 })));
                // 多模板：数组顺序即优先级，首个命中即返回
                log("多模板匹配：", JSON.stringify(images.findImageAny(shot, [
                    { tpl: template, threshold: 0.88, name: "demo" }
                ])));
                log("全部匹配：", JSON.stringify(images.findAllImages(shot, template, { threshold: 0.88, max: 3 })));
                // 双模板：同行判定 + 两者中心的中点
                var pair = images.findImagePair(shot, template, template, { threshold: 0.88 });
                if (pair) {
                    log("双模板：", "sameRow=" + pair.sameRow, "中点=(" + pair.midX + "," + pair.midY + ")",
                        "Y差=" + pair.rowDelta + "/" + pair.tolerance);
                }
                log("是否存在：", JSON.stringify(images.existsImage(shot, template, options)));
                log("范围找图：", JSON.stringify(images.findImageInRegion(shot, template, 0, 0, 1920, 1080, 0.88)));

                // 7. 取色、单色查找、多点找色；偏移量是实际像素。
                var pixelX = Math.max(0, Math.min(width - 1, real[0]));
                var pixelY = Math.max(0, Math.min(height - 1, real[1]));
                var nextX = Math.min(width - 1, pixelX + 10);
                var firstColor = images.pixel(shot, pixelX, pixelY);
                var nextColor = images.pixel(shot, nextX, pixelY);
                if (firstColor !== null && nextColor !== null) {
                    log("像素颜色：", colors.toString(firstColor));
                    log("找色：", JSON.stringify(images.findColor(shot, firstColor, { threshold: 16 })));
                    var offsets = [[nextX - pixelX, 0, nextColor]];
                    log("多点找色：", JSON.stringify(images.findMultiColors(shot, firstColor, offsets, { threshold: 16 })));
                }

                // 8. OCR：初始化、状态、逐行、全文、普通查找、宽松包含、全部匹配。
                if (!ocr.init("mlkit")) { toastLog("OCR 初始化失败"); exit(); }
                log("OCR 已就绪：", ocr.ready());
                var lines = ocr.recognize(shot, { region: region });
                for (var line of lines) {
                    log(line.text, line.centerX(), line.centerY(), JSON.stringify(line.bounds));
                }
                log("区域全文：", ocr.recognizeText(shot, { region: region }));
                log("普通查找：", JSON.stringify(ocr.findText(shot, query, { region: region })));
                var foundText = ocr.findTextContains(shot, query, { region: region });
                log("忽略空格和符号：", JSON.stringify(foundText));
                log("全部文字匹配：", JSON.stringify(ocr.findAllText(shot, query, { region: region })));
                // 多关键词：一次识别命中任意关键词，返回带 matched 的 OcrItem
                var anyText = ocr.findTextAny(shot, [query, "取消"], { region: region });
                log("多关键词 OCR：", anyText ? anyText.matched : null);
                var anyRegionText = ocr.findTextAny(shot, [query, "取消"], { regions: [region] });
                log("多区域 OCR：", anyRegionText ? anyRegionText.regionIndex : null);
                log("找图后识字：", JSON.stringify(images.findImageWithText(shot, template, { threshold: 0.88, padding: 8 })));

                // 9. 等图出现/消失：超时和间隔单位均为秒。目标不消失时返回 false。
                log("等待出现：", JSON.stringify(waitImage(template, 2, 0.5, { threshold: 0.88 })));
                log("等待消失：", waitImageVanish(template, 2, 0.5, { threshold: 0.88 }));
                // 新增等待接口：timeout 省略=不限时，0=只查一次，正数=限时秒数
                log("等图点击：", waitImageClick(template, 0, 0.5, { threshold: 0.88 }));
                log("等任一图：", JSON.stringify(waitImageAny([
                    { tpl: template, threshold: 0.88, name: "demo" }
                ], 0, 0.5)));
                log("等文字点击：", waitTextClick([query], 0, 0.5, { region: region }));
                log("等待文字：", JSON.stringify(waitText(query, { region: region, maxAttempts: 1 })));
                log("等待任一文字：", JSON.stringify(waitTextAny([query, "取消"], { regions: [region], maxAttempts: 1 })));
                log("通用屏幕等待：", JSON.stringify(waitScreen(function(frame) {
                    return images.findImage(frame, template, options);
                }, { maxAttempts: 1 })));
                log("文字监听：", JSON.stringify(watchText([query], {
                    region: region, maxAttempts: 1, maxClicks: 1
                })));
                log("重复点击直到图片消失：", JSON.stringify(clickImageUntilGone(template, {
                    threshold: 0.88, region: region, confirmCount: 1, maxAttempts: 1
                })));
                log("整组动作重试：", JSON.stringify(retry(function(attempt) {
                    return attempt === 1;
                }, { maxAttempts: 1 })));

                // 10. 点击与手势：这里会实际操作屏幕，坐标按上方设计分辨率。
                log("点击：", click(x, y));
                sleep(500);
                log("随机偏移点击：", clickRandom(x, y, 5, 100));
                log("固定坐标重试：", clickRetry(x, y, 2, 200));
                log("长按：", longClick(x, y, 600));
                log("定时按压：", press(x, y, 800));
                log("滑动：", swipe(960, 800, 960, 300, 500));
                log("随机偏移滑动：", swipeRandom(960, 300, 960, 800, 5, 500));
                log("单指轨迹：", gesture(800, [600, 600], [960, 300], [1200, 600]));
                if (gesturesAvailable()) {
                    log("多指手势：", gestures([500, [800, 540], [600, 540]], [500, [1120, 540], [1320, 540]]));
                }
            } finally {
                // 11. 正常结束或提前退出时释放本示例持有的图片，再取消坐标适配。
                // releaseAll 接受句柄、数组和对象，按内部 id 去重。
                images.releaseAll(resized, template, crop, shot);
                adapter.reset();
            }
            toastLog("完整 API 示例结束");
            exit();
        """.trimIndent()
    )
}
