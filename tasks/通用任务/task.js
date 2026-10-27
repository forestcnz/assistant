// 通用任务：以一秒为目标周期持续找图并点击，手动停止后结束。
// 坐标基准为 1920 × 1080；模板使用原始像素尺寸，不随坐标适配缩放。
adapter.setDesignSize(1920, 1080);
if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
}

var loaded = images.readAll({ common: "images/通用.png" });
try {
    if (loaded.missing.length) {
        console.error("缺少图片：" + loaded.missing.join("、"));
        exit();
    }
    log("通用任务已启动，每秒检查一次，手动停止结束");
    while (true) {
        var startedAt = Date.now();
        var shot = captureScreen();
        if (shot) {
            try {
                // 通用.png 的独立匹配阈值；需结合实机截图调整。
                var point = images.findImage(shot, loaded.images.common, { threshold: 0.9 });
                if (point) {
                    if (click(point.x, point.y)) {
                        log("通用：点击完成");
                    } else {
                        console.warn("通用：点击失败，下轮重新定位");
                    }
                }
            } finally {
                images.release(shot);
            }
        }
        // 扣除识别耗时；单轮超过一秒时，短暂让出执行时间后继续。
        sleep(Math.max(1, 1000 - (Date.now() - startedAt)));
    }
} finally {
    images.releaseAll(loaded.images);
}
