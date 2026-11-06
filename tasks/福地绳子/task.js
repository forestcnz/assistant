// 福地绳子：从能看到福地老头的界面开始，按 1920 × 1080 设计坐标执行。
// 按“绳子.txt”整组持续循环，直到用户停止；任何步骤未找到目标都等待，不跳过。
// 每次点击完成后等待 2 秒，未找到或点击失败后等待 1 秒重新截图。
adapter.setDesignSize(1920, 1080);

if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
}

// 新接口 images.readAll：一次性读取全部模板，images 与入参同形，missing 为失败路径。
var loaded = images.readAll({
    elder: "images/福地老头.png",
    blessedLand: "images/进入福地洞天.png",
    entrance: "images/绳子入口.png",
    enter: "images/进入副本.png",
    close: "images/关闭.png",
    stage1: "images/绳子关卡1.png",
    stage2: "images/绳子关卡2.png",
    stage3: "images/绳子关卡3.png",
    stage4: "images/绳子关卡4.png",
    reposition: "images/出来调整身位.png"
});
var templates = loaded.images;

// 新接口 waitImageClick：负数超时表示一直等到手动停止；找到即点击并清零计数，
// 点击失败会重新截图定位，截图失败不计为未找到。
// 以下阈值为初始设置，尚需实机核对；每张图片独立传入，便于分别调整。
function waitAndClick(tpl, stepName, threshold) {
    var ok = waitImageClick(tpl, -1, 1, {
        threshold: threshold,
        delay: 2000 // 毫秒，等待移动、弹窗或界面切换。
    });
    if (!ok) {
        console.warn(stepName + "：等待失败，结束任务");
        exit();
    }
    log(stepName + "：点击完成");
}

try {
    // 只加载操作说明实际使用的图片；缺少任何一张都在点击前结束。
    if (loaded.missing.length) {
        console.error("福地绳子图片读取失败：" + loaded.missing.join("、"));
        exit();
    }

    var round = 0;
    while (true) {
        round++;
        log("开始第 " + round + " 轮福地绳子");
        // “福地老头.png”的独立匹配阈值。
        waitAndClick(templates.elder, "点击福地老头", 0.88);
        // “进入福地洞天.png”的独立匹配阈值。
        waitAndClick(templates.blessedLand, "点击进入福地洞天", 0.88);
        // “绳子入口.png”的独立匹配阈值。
        waitAndClick(templates.entrance, "点击绳子入口", 0.88);
        // “进入副本.png”的独立匹配阈值。
        waitAndClick(templates.enter, "点击进入副本", 0.88);
        // “关闭.png”的独立匹配阈值。
        waitAndClick(templates.close, "进入副本后关闭", 0.88);
        // “绳子关卡1.png”的独立匹配阈值。
        waitAndClick(templates.stage1, "点击绳子关卡1", 0.88);
        waitAndClick(templates.close, "关卡1后关闭", 0.88);
        // “绳子关卡2.png”的独立匹配阈值。
        waitAndClick(templates.stage2, "点击绳子关卡2", 0.88);
        waitAndClick(templates.close, "关卡2后关闭", 0.88);
        // “绳子关卡3.png”的独立匹配阈值。
        waitAndClick(templates.stage3, "点击绳子关卡3", 0.88);
        waitAndClick(templates.close, "关卡3后关闭", 0.88);
        // “绳子关卡4.png”的独立匹配阈值。
        waitAndClick(templates.stage4, "点击绳子关卡4", 0.88);
        waitAndClick(templates.close, "关卡4后关闭", 0.88);
        // “出来调整身位.png”的独立匹配阈值。
        waitAndClick(templates.reposition, "点击出来调整身位", 0.88);
        log("第 " + round + " 轮福地绳子点击流程执行完毕");
    }
} finally {
    // 停止、退出或异常时释放已加载的模板，不额外点击界面（新接口支持对象批量释放）。
    images.releaseAll(templates);
}
