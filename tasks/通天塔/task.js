// 固定坐标按 1920 × 1080 的游戏界面编写。
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
    elder: "images/通天塔入口老头.png",
    open: "images/开启活动.png",
    sweep: "images/扫荡.png"
});
var templates = loaded.images;

function clickUntilAccepted(x, y, stepName) {
    // 新接口 clickRetry：times 传 0 表示持续重试直到点击完成，间隔 1 秒。
    clickRetry(x, y, 0, 1000);
    log(stepName + "：点击完成");
    sleep(2000); // 等待界面切换完成。
}

try {
    if (loaded.missing.length) {
        console.error("通天塔识别图片读取失败：" + loaded.missing.join("、"));
        exit();
    }

    // 新接口 retry：把“打开地图→前往寒潭→等待老人”当作一整组重试。
    // 每次未找到老人都重新打开地图并前往寒潭，最多尝试三次（含首次）。
    var found = retry(function (attempt) {
        log("第 " + attempt + "/3 次前往寒潭查找通天塔老人");
        clickUntilAccepted(1777, 24, "打开地图");
        clickUntilAccepted(586, 628, "前往寒潭");
        // “通天塔入口老头.png”的独立匹配阈值；每次最多轮询 8 秒。
        return waitImage(templates.elder, 8, 0.5, { threshold: 0.88 });
    }, {
        maxAttempts: 3,
        intervalMs: 1000,
        name: "前往寒潭查找通天塔老人",
        onRetry: function (reason, state) {
            console.warn("第 " + state.attempts + " 次未找到通天塔老人");
        }
    });
    if (!found.ok || !found.value) {
        console.warn("三次前往寒潭后仍未找到通天塔老人，结束任务");
        exit();
    }

    clickUntilAccepted(found.value.x, found.value.y, "点击通天塔老人");
    // “开启活动.png”的独立匹配阈值；等待出现并点击，点击后等待 2 秒。
    if (!waitImageClick(templates.open, -1, 1, { threshold: 0.88, delay: 2000 })) {
        console.warn("等待开启活动按钮失败，结束任务");
        exit();
    }

    // 新接口 clickImageUntilGone：持续点击扫荡，连续 5 次有效截图未找到后返回。
    // “扫荡.png”的独立匹配阈值；扫荡点击后不额外等待，内置检查间隔最小 50ms。
    var sweep = clickImageUntilGone(templates.sweep, {
        name: "通天塔扫荡",
        confirmCount: 5,
        intervalMs: 0,
        delayMs: 0,
        threshold: 0.88
    });
    log("扫荡结束：" + sweep.reason + "，共点击扫荡 " + sweep.clicks + " 次");

    clickUntilAccepted(1657, 102, "关闭通天塔");
    log("通天塔任务完成");
} finally {
    // 结束或异常时批量释放预加载的模板图片。
    images.releaseAll(templates);
}
