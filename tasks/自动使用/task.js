// 自动使用：每秒在同一截图中查找“使”和“用”，同行时点击中心的中点。
// 坐标基准为 1920 × 1080；持续运行至手动停止，每轮最多点击一次。
adapter.setDesignSize(1920, 1080);
if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
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
                // 两张图片各自设置阈值，同行容差采用 API 按模板高度计算的默认值。
                var pair = images.findImagePair(shot, loaded.images.useFirst, loaded.images.useLast, {
                    thresholdA: 0.9, // 使.png
                    thresholdB: 0.9  // 用.png
                });
                // 找到两张图仍可能不同行，必须检查 sameRow。
                if (pair && pair.sameRow) {
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
