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
        """.trimIndent())
    )

    val completeExample = Item(
        title = "完整示例：全部 API",
        description = "展示全部 63 个公开方法。会截图、OCR、保存演示图片及执行手势；运行前修改文字与点击坐标。",
        category = "完整示例",
        completeScript = true,
        code = """
            // 完整 API 示例：全部 63 个公开函数/方法及公开属性。
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

            var shot = null, crop = null, template = null;
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
                log("保存截图：", images.save(shot, output + "-screen.png", "png", 100));

                // 6. 找图：单个、多个、是否存在、指定范围。
                var options = { region: region, threshold: 0.88 };
                var point = images.findImage(shot, template, options);
                log("匹配中心：", JSON.stringify(point));
                log("全部匹配：", JSON.stringify(images.findAllImages(shot, template, { threshold: 0.88, max: 3 })));
                log("是否存在：", images.existsImage(shot, template, options));
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
                log("找图后识字：", JSON.stringify(images.findImageWithText(shot, template, { threshold: 0.88, padding: 8 })));

                // 9. 等图出现/消失：超时和间隔单位均为秒。目标不消失时返回 false。
                log("等待出现：", JSON.stringify(waitImage(template, 2, 0.5, { threshold: 0.88 })));
                log("等待消失：", waitImageVanish(template, 2, 0.5, { threshold: 0.88 }));

                // 10. 点击与手势：这里会实际操作屏幕，坐标按上方设计分辨率。
                log("点击：", click(x, y));
                sleep(500);
                log("随机偏移点击：", clickRandom(x, y, 5, 100));
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
                images.release(template);
                images.release(crop);
                images.release(shot);
                adapter.reset();
            }
            toastLog("完整 API 示例结束");
            exit();
        """.trimIndent()
    )
}
