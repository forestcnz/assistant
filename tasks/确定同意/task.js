// 确定同意：按轮廓识别“确/定”、“确/认”和“同/意”，配对同行且间距合理时点击中点。
// 坐标基准为 1920 × 1080；依次优先确定、确认、同意，每轮最多尝试一次点击，手动停止结束。
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

// 同行且两字紧邻才算有效配对，避免“确”与同一行别处的“定/认”误配后点到空白处。
function isAdjacentPair(pair) {
    if (!pair || !pair.sameRow) return false;
    var gap = Math.abs(pair.dx);
    return gap >= MIN_CHAR_GAP && gap <= MAX_CHAR_GAP;
}

var loaded = images.readAll({
    confirmFirst: "images/确.png",
    confirmLast: "images/定.png",
    acknowledgeLast: "images/认.png",
    agreeFirst: "images/同.png",
    agreeLast: "images/意.png"
});
try {
    if (loaded.missing.length) {
        console.error("缺少图片：" + loaded.missing.join("、"));
        exit();
    }
    log("确定同意已启动，每秒检查一次，手动停止结束");
    while (true) {
        var startedAt = Date.now();
        var shot = captureScreen();
        if (shot) {
            try {
                // 每个字符独立设置阈值；模板按 1 倍原始像素匹配；中心 Y 差不超过 20 个实际截图像素视为同行。
                var pair = images.findImagePair(shot, loaded.images.confirmFirst, loaded.images.confirmLast, {
                    thresholdA: 0.82, // 确.png：两种字号共用此轮廓模板。
                    thresholdB: 0.82, // 定.png
                    rowTolerance: 20
                });
                var label = "确定";
                if (!isAdjacentPair(pair)) {
                    pair = images.findImagePair(shot, loaded.images.confirmFirst, loaded.images.acknowledgeLast, {
                        thresholdA: 0.82, // 确.png
                        thresholdB: 0.82, // 认.png
                        rowTolerance: 20
                    });
                    label = "确认";
                }
                if (!isAdjacentPair(pair)) {
                    pair = images.findImagePair(shot, loaded.images.agreeFirst, loaded.images.agreeLast, {
                        thresholdA: 0.82, // 同.png
                        thresholdB: 0.82, // 意.png
                        rowTolerance: 20
                    });
                    label = "同意";
                }
                if (isAdjacentPair(pair)) {
                    // 点击后不再使用旧截图查找另一按钮，下轮重新截图。
                    if (click(pair.midX, pair.midY)) {
                        log(label + "：中点点击完成");
                    } else {
                        console.warn(label + "：点击失败，下轮重新定位");
                    }
                }
            } finally {
                images.release(shot);
            }
        }
        sleep(Math.max(1, 1000 - (Date.now() - startedAt)));
    }
} finally {
    images.releaseAll(loaded.images);
}
