// 自动使用：每秒在同一截图中查找“使”和“用”，同行且间距合理时点击中心的中点。
// 坐标基准为 1920 × 1080；持续运行至手动停止，每轮最多点击一次。
adapter.setDesignSize(1920, 1080);
if (images.supportsShapeMatching !== true) {
    toastLog("此任务需要支持形状匹配的新版助手，请先更新 APP");
    exit();
}
if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
}

// 两字中心间距的合理范围（实际截图像素）：上限按约 4 个字符宽度（36～40px 字宽）留出余量。
var MIN_CHAR_GAP = 10;
var MAX_CHAR_GAP = 160;

// 同行且两字紧邻才算有效配对，避免“使”与同一行别处的“用”误配后点到空白处。
function isAdjacentPair(pair) {
    if (!pair || !pair.sameRow) return false;
    var gap = Math.abs(pair.dx);
    return gap >= MIN_CHAR_GAP && gap <= MAX_CHAR_GAP;
}

var loaded = images.readAll({ useFirst: "images/使.png", useLast: "images/用.png" });
try {
    if (loaded.missing.length) {
        console.error("缺少图片：" + loaded.missing.join("、"));
        exit();
    }
    log("自动使用已启动，每秒检查一次，手动停止结束");
    while (true) {
        var startedAt = Date.now();
        var shot = captureScreen();
        if (shot) {
            try {
                // 两张图片各自设置阈值；模板未设置 scales，按 1920 × 1080 的实机像素尺寸匹配。
                var pair = images.findImagePair(shot, loaded.images.useFirst, loaded.images.useLast, {
                    thresholdA: 0.9, // 使.png
                    thresholdB: 0.9  // 用.png
                });
                // 找到两张图仍可能不同行或间距过远，必须校验后才点击。
                if (isAdjacentPair(pair)) {
                    if (click(pair.midX, pair.midY)) {
                        log("使用：中点点击完成");
                    } else {
                        console.warn("使用：点击失败，下轮重新定位");
                    }
                }
            } finally {
                images.release(shot);
            }
        }
        // 截图失败也等待，避免空转；识别超时后短暂让出执行时间。
        sleep(Math.max(1, 1000 - (Date.now() - startedAt)));
    }
} finally {
    images.releaseAll(loaded.images);
}
