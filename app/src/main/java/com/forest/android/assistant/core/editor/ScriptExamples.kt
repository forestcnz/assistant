package com.forest.android.assistant.core.editor

/** 编辑器可直接写入的完整脚本，示例只会修改编辑内容，不会自动运行。 */
object ScriptExamples {
    data class Item(val title: String, val description: String, val code: String)

    val items = listOf(
        Item(
            "OCR 忽略空格找字",
            "清理空格、标点和符号，找到文字后点击整行中心。写入后修改 query。",
            """
            // OCR 忽略空格和符号后查找并点击
            var query = "立即购买";
            adapter.reset();
            if (!auto.waitFor(5000)) { toastLog("请先开启无障碍服务"); exit(); }
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }

            var shot = captureScreen();
            if (!shot) { toastLog("截图失败，请重试"); exit(); }
            try {
                // 可增加第三个参数 { region: [x, y, width, height] } 限定范围。
                var found = ocr.findTextContains(shot, query);
                if (found) {
                    log("原始文字：", found.text);
                    log("点击结果：", click(found.centerX(), found.centerY()));
                } else {
                    log("没有找到文字：", query);
                }
            } finally {
                images.release(shot);
            }
            """.trimIndent()
        ),
        Item(
            "OCR 读取屏幕文字",
            "识别整屏文字并输出文字、边界和中心坐标，不点击屏幕。",
            """
            // OCR 读取屏幕文字
            adapter.reset();
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            var shot = captureScreen();
            if (!shot) { toastLog("截图失败，请重试"); exit(); }
            try {
                var lines = ocr.recognize(shot);
                log("识别行数：", lines.length);
                lines.forEach(function(line) {
                    log(line.text, JSON.stringify(line.bounds), line.centerX(), line.centerY());
                });
            } finally {
                images.release(shot);
            }
            """.trimIndent()
        ),
        Item(
            "找图等待并点击",
            "预加载模板，最多等待 10 秒，找到后点击。需先添加 images/button.png 素材。",
            """
            // 找图等待并点击：先将模板添加到任务的 images/button.png。
            var templatePath = "images/button.png";
            adapter.reset();
            if (!auto.waitFor(5000)) { toastLog("请先开启无障碍服务"); exit(); }
            if (!requestScreenCapture()) { toastLog("截图授权失败"); exit(); }
            var template = images.read(templatePath);
            if (!template) { toastLog("请先添加模板：" + templatePath); exit(); }

            var deadline = Date.now() + 10000;
            var found = null;
            try {
                while (Date.now() < deadline) {
                    var shot = captureScreen();
                    if (shot) {
                        try {
                            found = images.findImage(shot, template, { threshold: 0.92 });
                        } finally {
                            images.release(shot);
                        }
                        if (found) break;
                    }
                    sleep(500);
                }
                if (found) {
                    log("点击结果：", click(found.x, found.y));
                } else {
                    log("等待目标图片超时");
                }
            } finally {
                images.release(template);
            }
            """.trimIndent()
        ),
        Item(
            "基础点击与滑动",
            "按 1080×1920 设计坐标点击和滑动。写入后将坐标改为目标位置。",
            """
            // 基础点击与滑动：运行前修改下方坐标。
            adapter.setDesignSize(1080, 1920);
            if (!auto.waitFor(5000)) { toastLog("请先开启无障碍服务"); exit(); }

            log("点击结果：", click(540, 960));
            sleep(1000);
            log("滑动结果：", swipe(540, 1600, 540, 600, 500));
            toastLog("示例执行结束");
            """.trimIndent()
        )
    )
}
