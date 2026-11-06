// 按 1920 × 1080 的游戏界面坐标执行；一轮包含领取、使用和交付宣言。
// 每步点击后等待 2 秒；只有“帮派宣言”图片连续三次找不到时才结束任务。
// 其他步骤找不到目标时持续等待，不跳过任何一步。
adapter.setDesignSize(1920, 1080);

if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
}

// 相对路径以当前 task.js 所在目录为基准。
// 新接口 images.readAll：一次性读取全部模板，images 与入参同形，missing 为失败路径。
var loaded = images.readAll({
    start: "images/进行帮派任务.png",
    declarationTask: "images/帮派宣言.png",
    goPublish: "images/去临安发布宣言.png",
    declarationItem: "images/宣言.png",
    use: "images/使用宣言.png",
    goDeliver: "images/去帮派交付任务.png",
    deliver: "images/交付任务.png"
});
var templates = loaded.images;

// 新接口 waitImageClick：截图、找图、点击、连续未找到计数都由内置实现完成。
// 负数超时表示一直等到手动停止；找到图片即清零计数，截图失败不计为未找到。
// 每张图片的阈值在自己的调用点单独传入，便于分别调整。
function clickImageWhenVisible(tpl, stepName, threshold, maxConsecutiveMisses) {
    // interval 使用 2 秒，对应“每步点击后等待 2 秒”的原流程节奏。
    var ok = waitImageClick(tpl, -1, 2, {
        threshold: threshold,
        delay: 2000,
        missLimit: maxConsecutiveMisses || 0
    });
    if (ok) {
        log(stepName + "：点击完成");
    } else {
        console.warn(stepName + "：连续 " + maxConsecutiveMisses + " 次未找到，结束任务");
    }
    return ok;
}

function clickCoordinateWhenAccepted(x, y, stepName) {
    // 新接口 clickRetry：times 传 0 表示持续重试直到点击完成，间隔 2 秒。
    clickRetry(x, y, 0, 2000);
    log(stepName + "：点击完成");
    sleep(2000);
}

try {
    // 缺少任何一张必需图片时，在点击前报告完整路径并结束。
    if (loaded.missing.length) {
        console.error("帮派宣言图片读取失败：" + loaded.missing.join("、"));
        exit();
    }

    for (var round = 1; round <= 30; round++) {
        log("开始第 " + round + " 轮帮派宣言任务");

        // 到帮派任务 NPC，然后点击“帮派宣言”。
        // “进行帮派任务.png”单独使用 0.88。
        clickImageWhenVisible(templates.start, "进行帮派任务", 0.88);
        // “帮派宣言.png”单独使用 0.88；仅在选择“帮派宣言”时计数，
        // 连续三次未找到就结束，不执行后续步骤。
        if (!clickImageWhenVisible(templates.declarationTask, "选择帮派宣言", 0.88, 3)) break;
        clickCoordinateWhenAccepted(1442, 830, "领取任务");
        clickCoordinateWhenAccepted(1043, 555, "结束对话");

        // 前往临安，找到宣言道具并使用。
        // “去临安发布宣言.png”单独使用 0.88。
        clickImageWhenVisible(templates.goPublish, "前往临安发布宣言", 0.88);
        // “宣言.png”单独使用 0.88。
        clickImageWhenVisible(templates.declarationItem, "选择宣言道具", 0.88);
        // “使用宣言.png”单独使用 0.88。
        clickImageWhenVisible(templates.use, "使用宣言", 0.88);

        // 返回帮派交付任务；交付完成后才开始下一轮。
        // “去帮派交付任务.png”单独使用 0.88。
        clickImageWhenVisible(templates.goDeliver, "前往帮派交付任务", 0.88);
        // “交付任务.png”单独使用 0.88。
        clickImageWhenVisible(templates.deliver, "交付任务", 0.88);
        log("第 " + round + " 轮帮派宣言任务完成");
    }
} finally {
    // 结束或退出时释放预加载图片（新接口支持对象批量释放）。
    images.releaseAll(templates);
}
