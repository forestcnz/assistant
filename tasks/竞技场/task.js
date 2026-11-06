// 任务流程：打开地图 → 前往临安 → 进入竞技场 → 最多进行十五场随机战。
// 每场都等待明确结果；战败时关闭提示，次数耗尽时按说明领取礼包并退出。
// 下方固定点击坐标来自 1920 × 1080 的竞技场操作说明。
adapter.setDesignSize(1920, 1080);

// 点击依赖无障碍服务，找图依赖截图通道；缺少任一条件就停止本轮任务。
if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
}

// 新接口 images.readAll：预先批量读取状态图片，避免每次轮询都从磁盘重新加载。
var loaded = images.readAll({
    random: "images/随机战.png",
    exhausted: "images/次数耗尽.png",
    defeated: "images/战败.png",
    fighting: "images/战斗中.png"
});
var templates = loaded.images;

function clickUntilAccepted(x, y, name) {
    // 新接口 clickRetry：click 返回 false 表示操作通道没有完成本次点击。
    // times 传 0 表示持续重试直到受理；这里沿用 500ms 的重试间隔。
    clickRetry(x, y, 0, 500);
    log(name + "：点击完成");
    sleep(800); // 给游戏界面留出切换时间。
}

function waitForRandom() {
    // 新接口 waitImageClick：入口按钮没有出现就持续等待，不跳过本场挑战。
    // “随机战.png”单独设置 0.88 阈值；点击后等待 800ms 观察界面变化。
    if (!waitImageClick(templates.random, -1, 0.5, { threshold: 0.88, delay: 800 })) {
        console.warn("等待随机战按钮失败，结束任务");
        exit();
    }
}

function waitForBattleResult() {
    var leftRandomScreen = false;
    var otherFrames = 0;
    // 新接口 waitScreen：每轮自动截图并交给回调判断，结束前自动释放截图。
    // images.findImageAny 在同一帧内按数组顺序匹配，弹窗优先于背景中的“随机战”按钮。
    // 截图偶尔失败时不会进入回调，下一轮重新截图，不推断状态。
    var result = waitScreen(function (shot) {
        var hit = images.findImageAny(shot, [
            // “次数耗尽.png”弹窗背景较大，单独使用 0.86 阈值以容忍画面轻微变化。
            { tpl: templates.exhausted, threshold: 0.86, name: "exhausted" },
            // “战败.png”单独设置 0.88 阈值。
            { tpl: templates.defeated, threshold: 0.88, name: "defeated" },
            // “战斗中.png”单独设置 0.88 阈值。
            { tpl: templates.fighting, threshold: 0.88, name: "fighting" },
            // “随机战.png”单独设置 0.88 阈值。
            { tpl: templates.random, threshold: 0.88, name: "random" }
        ]);
        if (!hit) {
            // 连续两次看不到任何已知状态，也视为已经离开竞技场入口画面。
            otherFrames++;
            if (otherFrames >= 2) leftRandomScreen = true;
            return null;
        }
        otherFrames = 0;
        // 次数耗尽或战败是明确结果，可以立即进入对应处理分支。
        if (hit.name === "exhausted" || hit.name === "defeated") return { state: hit.name };
        if (hit.name === "fighting") leftRandomScreen = true;
        // 点击后的旧画面不能直接算作胜利；必须先确认界面进入战斗，
        // 然后再次看到“随机战”按钮，才说明本场已结束并获胜。
        if (hit.name === "random" && leftRandomScreen) return { state: "victory" };
        // 没有出现明确结果时继续等待，不用超时跳过当前步骤。
        return null;
    }, {
        intervalMs: 500,
        name: "竞技场战斗结果"
    });
    if (!result.ok || !result.value) {
        // 截图通道不可用等异常导致无法确认结果时，结束任务，避免误记为战胜。
        console.warn("未能确认战斗结果：" + result.reason);
        exit();
    }
    return result.value.state;
}

try {
    if (loaded.missing.length) {
        console.error("竞技场识别图片读取失败：" + loaded.missing.join("、"));
        exit();
    }

    // 按任务说明依次导航，前一步点击完成后再执行下一步。
    clickUntilAccepted(1777, 24, "打开地图");
    clickUntilAccepted(1103, 760, "前往临安");
    clickUntilAccepted(1265, 42, "进入竞技场");

    for (var round = 1; round <= 15; round++) {
        waitForRandom();
        log("开始第 " + round + " 场随机战");
        var result = waitForBattleResult();
        if (result === "exhausted") {
            // 按说明关闭购买提示、点击首胜礼包、关闭竞技场，然后结束任务。
            log("竞技场次数耗尽，领取礼包并关闭竞技场");
            clickUntilAccepted(1198, 656, "关闭次数耗尽提示");
            clickUntilAccepted(445, 738, "领取首胜礼包");
            clickUntilAccepted(1661, 90, "关闭竞技场");
            break;
        }
        if (result === "defeated") {
            // 关闭战败界面后，下一轮仍会等待随机战按钮重新出现。
            log("第 " + round + " 场战败");
            clickUntilAccepted(1409, 341, "关闭战败提示");
        } else {
            log("第 " + round + " 场战胜");
        }
    }
} finally {
    // 正常结束或提前退出时，都释放预加载的模板图片（新接口支持对象批量释放）。
    images.releaseAll(templates);
}
