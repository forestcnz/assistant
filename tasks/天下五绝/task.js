// 坐标按 1920 × 1080 游戏界面设置。
adapter.setDesignSize(1920, 1080);

function clickStep(x, y, name) {
    // 这里按固定坐标点击，不做找图或 OCR，因此没有图像匹配阈值。
    // 新接口 clickRetry：times 传 0 表示持续重试直到点击完成，间隔 500ms。
    clickRetry(x, y, 0, 500);
    log(name + "点击完成");
    sleep(800);
}

// 依次打开菜单、技能和天下五绝界面。
clickStep(1856, 993, "打开菜单");
clickStep(1180, 1003, "打开技能");
clickStep(1667, 821, "打开五绝");

var positions = [
    { x: 760, y: 255, name: "位置1" },
    { x: 512, y: 414, name: "位置2" },
    { x: 613, y: 669, name: "位置3" },
    { x: 871, y: 658, name: "位置4" },
    { x: 950, y: 413, name: "位置5" }
];

// 每个位置点击后都点击一次“修炼”，按 1、修炼、2、修炼……5、修炼执行五轮。
for (var round = 1; round <= 5; round++) {
    log("开始第 " + round + " 轮");
    for (var i = 0; i < positions.length; i++) {
        var position = positions[i];
        clickStep(position.x, position.y, position.name);
        clickStep(1244, 829, "修炼");
    }
}

// 五轮完成后关闭天下五绝界面。
clickStep(1660, 96, "关闭");
