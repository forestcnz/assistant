# 雕雕助手任务脚本编写指南

本文根据竞技场、帮派宣言、通天塔、区域确认和天下五绝五个任务整理，说明如何把操作说明转换为可维护的任务脚本。接口签名、参数和返回值以[完整脚本 API 参考](script-api.md)为准。

文中的代码块按主题展示写法，需要结合完整任务说明使用。现有任务的坐标、阈值和等待时间是各自的配置，新任务应根据实际界面确定。

## 1. 先确定步骤，再编写代码

编写前先把需求整理清楚：

| 项目 | 需要明确的内容 |
| --- | --- |
| 初始界面 | 从游戏主界面、任务面板还是已打开的活动界面开始 |
| 操作顺序 | 每一步点击什么，下一步等待什么 |
| 识别方式 | 固定坐标、指定图片或指定区域内的文字 |
| 循环范围 | 哪些步骤重复、重复多少次，哪些步骤只执行一次 |
| 等待间隔 | 每次点击后等待多久，识别失败后多久再检查 |
| 失败处理 | 持续等待、重新导航、达到次数后结束，分别用于哪一步 |
| 收尾动作 | 什么情况下点击关闭，是否还要领取奖励 |
| 素材对应 | 每张实际使用的图片对应哪个步骤 |

按最新确认的要求执行。图片文件名与步骤名称不一致时，需要结合图片内容确认对应关系；无法确定时先澄清。目录里存在一张图片，并不代表流程必须增加一次点击。例如，帮派宣言任务不使用 `任务任务.png`，不能因为目录里有它就加入该步骤。

“不可跳过”意味着当前步骤尚未满足条件时，不能继续执行下一步。明确规定的停止条件要单独处理，例如“帮派宣言连续三次未找到就结束”，不能把这个条件自动套用到其他图片。

循环次数必须明确包含哪些动作。天下五绝的最终顺序是：

```text
打开＋号 → 打开技能 → 打开五绝
重复以下整组动作 5 轮：
    1 → 修炼 → 2 → 修炼 → 3 → 修炼 → 4 → 修炼 → 5 → 修炼
关闭
```

这里每轮包含 5 次修炼，五轮共 25 次；进入界面的三步和最后关闭各执行一次。

## 2. 现有任务的规则与参考用途

下表记录当前实现中的规则。其他任务不能直接继承这些次数和停止条件。

| 任务 | 当前规则 | 可参考的写法 |
| --- | --- | --- |
| 竞技场 | 最多尝试 15 场随机战；战败时关闭提示；次数耗尽时依次关闭提示、点击礼包、关闭竞技场 | 同一截图判断多个状态，弹窗优先，等待结果后再进入下一场 |
| 帮派宣言 | 最多 30 轮，每步点击后等待 3 秒；只有 `帮派宣言.png` 连续三次未找到时结束 | 每张图片独立匹配，只有指定步骤启用未找到次数限制 |
| 通天塔 | 最多三次前往寒潭，包含首次；每次到达后最多轮询找老人 8 秒；扫荡按钮连续五次未找到后关闭 | 有上限的重新导航、连续未找到计数、明确的收尾动作 |
| 区域确认 | 无限循环检查三个区域，包含“确定”或“同意”时点击；每轮最多尝试一次点击 | 区域 OCR、处理重叠区域、按目标间隔轮询 |
| 天下五绝 | 依次点击位置 1～5，每个位置后修炼一次，整组重复 5 轮，最后关闭 | 固定坐标、嵌套循环、只执行一次的入口和收尾 |

对应文件位于 `tasks/任务名/task.js`。这些任务是编写参考；模拟流程通过不等于已在实际游戏中验证识别效果。

## 3. 任务目录与配置

每个任务独立放在一个目录中，图片统一放在自己的 `images/` 子目录：

```text
tasks/
└── 任务名称/
    ├── task.js          # 任务入口
    ├── config.json      # 名称、设计尺寸和宿主执行次数
    ├── 任务名称.txt     # 可选：原始操作说明
    └── images/          # 图片素材；纯坐标或 OCR 任务可以没有
        ├── 入口.png
        └── 操作按钮.png
```

使用 `images.read("images/入口.png")` 读取图片。相对路径以当前 `task.js` 所在目录为基准，移动素材后要同步修改引用。读取返回 `null` 时应说明缺少哪张图片并结束，不能带着无效模板继续等待。

配置示例：

```json
{
  "name": "任务名称",
  "desc": "说明主要流程、循环次数和结束条件",
  "designSize": [1920, 1080],
  "loop": {
    "count": 1,
    "interval": 0
  }
}
```

当前五个任务使用 1920 × 1080 设计坐标；新任务应按坐标采集时的分辨率设置。`config.json` 由宿主读取，不会自动生成脚本可访问的 `config` 变量。需要自动启动应用时，另行填写已知的 `target` 包名。

脚本内部控制 15 次、30 轮或无限循环时，通常将宿主的 `loop.count` 保持为 `1`。它表示整份脚本的执行次数，不能与内部循环混淆；`0` 也不表示无限循环。

## 4. 初始化、坐标和点击

使用无障碍点击和截图识别的任务，可以按需要组合下面的初始化代码：

```javascript
adapter.setDesignSize(1920, 1080);

if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}

// 找图和 OCR 需要截图通道。
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
}
```

`auto.waitFor(5000)` 最多等待服务 5 秒，不会自动替用户开启服务。纯坐标任务按实际点击通道准备条件，不必为坐标点击额外申请截图或初始化 OCR。

启用适配后，点击、找图及 OCR 的区域参数使用设计坐标；识别返回的位置也已转换为设计坐标，可以直接点击。不要再次用 `adapter.scale()` 转换后传给 `click`。

区域格式为 `[x, y, width, height]`，最后两个值是宽高。例如 `[1702, 425, 192, 92]` 表示从 `(1702, 425)` 开始、宽 192、高 92 的区域。

坐标适配不会缩放模板图片。模板应与实际截图中的目标像素尺寸相符；更换分辨率、游戏缩放或界面布局后，需要重新核对素材。

双图匹配 `images.findImagePair` 默认按轮廓查找，可通过 `scales` 尝试多个模板尺寸。使用前检查 `images.supportsShapeMatching === true`，具体参数见 API 参考。轮廓匹配弱化颜色和平滑背景差异，会增加识别耗时；应使用正反样本重新确定阈值，不能照搬旧版像素匹配的阈值。

固定坐标点击的可复用片段：

```javascript
function clickStep(x, y, stepName) {
    while (!click(x, y)) {
        console.warn(stepName + "：点击未完成，重试");
        sleep(500);
    }
    log(stepName + "：点击已完成");
    sleep(800); // 示例间隔，按该任务的界面切换时间调整。
}
```

`click` 返回 `true` 只表示操作通道报告点击完成，不代表已领取奖励或已经进入新界面。需要确认业务结果的步骤，要继续识别对应状态。图片点击失败后重新截图定位，通常比一直重复旧坐标更合适。

时间单位：`sleep` 和 `auto.waitFor` 使用毫秒；`waitImage`、`waitImageVanish` 的超时与间隔使用秒；配置的 `loop.interval` 也使用秒。

## 5. 每张图片独立写匹配阈值

**每张用于识别的图片都要在自己的 `images.findImage` 调用中直接写阈值数字。** 即使两张图片目前都取 `0.88`，也要分开写，方便之后单独调整。

```javascript
var templates = {
    start: images.read("images/进行帮派任务.png"),
    declarationTask: images.read("images/帮派宣言.png")
};

// “进行帮派任务.png”的阈值独立设置。
function findStart(shot) {
    return images.findImage(shot, templates.start, { threshold: 0.88 });
}

// “帮派宣言.png”的阈值独立设置。
function findDeclarationTask(shot) {
    return images.findImage(shot, templates.declarationTask, { threshold: 0.88 });
}
```

不要用一个公共 `threshold` 变量控制全部图片，也不要让所有图片共用唯一一处硬编码阈值的通用找图函数。可以复用截图、等待和点击逻辑，把每张图片的专用匹配函数传进去。

阈值通常取 0～1，越高越严格；`0.88` 不代表 88% 的识别正确率。当前任务中的 `0.86`、`0.88` 是已有设置，不是适用于所有图片的统一标准。调整时应结合实际截图判断漏识别和误识别，不能仅靠不断降低阈值解决问题。

`images.findImage` 返回模板中心点，使用 `point.x`、`point.y` 点击。图片匹配结果没有 OCR 对象的 `centerX()`、`centerY()` 方法。

## 6. 等待、重试与连续未找到次数

**优先使用内置等待接口**：`waitImageClick`、`waitImageAny`、`waitTextClick` 已经内置了“截图失败不计数、找到即清零、点击失败不推进、逐帧释放截图”这些规则，直接调用比手写帮助函数更可靠。

```javascript
// 默认不限时，一直等到出现或手动停止
waitImageClick(declarationTask, 0, 1, {
    threshold: 0.88, delay: 3000, missLimit: 3
});

// 一帧内判断多个状态，数组顺序即优先级
var hit = waitImageAny([
    { tpl: reward, threshold: 0.88, name: "reward" },
    { tpl: quick,  threshold: 0.88, name: "quick" }
], 0, 1);
```

只有需要自定义判断逻辑（例如同帧做多次额外校验）时，才手写下面的帮助函数。它接收专用匹配函数，例如 `findStart`，只负责截图和等待，不持有公共图片阈值：

```javascript
function scanScreen(findPoint) {
    var shot = captureScreen();
    if (!shot) return { captured: false, point: null };
    try {
        return { captured: true, point: findPoint(shot) };
    } finally {
        images.release(shot);
    }
}

// missLimit 省略时持续等待；正整数表示连续未找到多少次后返回 false。
// 此例按帮派宣言的规则，每次点击后或重试前等待 3 秒。
function waitAndClick(findPoint, stepName, missLimit) {
    var consecutiveMisses = 0;
    while (true) {
        var result = scanScreen(findPoint);
        if (!result.captured) {
            sleep(3000); // 无有效截图，不计为一次未找到。
            continue;
        }

        if (result.point) {
            consecutiveMisses = 0;
            if (click(result.point.x, result.point.y)) {
                log(stepName + "：点击已完成");
                sleep(3000);
                return true;
            }
            console.warn(stepName + "：点击未完成，重新定位");
        } else {
            consecutiveMisses++;
            if (missLimit && consecutiveMisses >= missLimit) {
                console.warn(stepName + "：连续 " + consecutiveMisses + " 次未找到");
                return false;
            }
        }
        sleep(3000);
    }
}
```

以下片段展示两个步骤各自的等待规则，后续业务动作仍需按任务说明实现：

```javascript
// “进行帮派任务”持续等待。
waitAndClick(findStart, "进行帮派任务");

// 只有“帮派宣言”启用连续三次未找到的停止条件。
if (!waitAndClick(findDeclarationTask, "选择帮派宣言", 3)) {
    exit();
}
```

“连续 N 次找不到”的计数规则：成功截图却没有匹配才加一；找到图片就清零，包括找到后点击失败的情况；截图失败不增加次数。截图失败本身不能证明图片已经消失。内置的 `waitImageClick` / `waitImageAny` / `waitTextClick` 的 `options.missLimit` 就是按这个规则实现的。

批量素材用 `images.readAll` 加载、`images.releaseAll` 释放，避免每个任务重复写 `for...in`：

```javascript
var t = images.readAll({ start: "images/进行帮派任务.png", task: "images/帮派宣言.png" });
if (t.missing.length) {
    console.error("缺少图片：" + t.missing.join("、"));
    exit();
}
try {
    // 业务步骤
} finally {
    images.releaseAll(t.images);
}
```

在业务循环中也可以用 `break` 结束循环，但达到停止条件后不能继续执行领取、使用或交付步骤。`exit()` 结束当前宿主执行轮次；宿主配置了多轮时仍可能运行下一轮。因此内部控制完整流程的任务通常配合 `loop.count: 1`。

重新导航次数与内部找图次数要分开描述。通天塔当前实现最多前往寒潭三次，包含首次，每次到达后再轮询找老人。如果新任务要求“首次之外再重试三次”，总尝试数就是四次，代码和注释都应体现。

## 7. 状态切换与收尾

有限次数用 `for` 控制；持续监听用 `while (true)`，并在每轮进行截图、识别或 `sleep`。停止入口会中断 JS 运行时空转循环，所以纯计算循环不会再卡死停止流程；但仍应保留每轮等待，避免无意义地占满 CPU、拖慢同一进程内的截屏与识别。

状态判断时先识别遮挡按钮的弹窗，再识别背景按钮。竞技场的次数耗尽和战败应优先于随机战。点击随机战后旧界面可能短暂保留，不能立刻把仍显示的随机战按钮当作本场获胜。

当前竞技场实现通过战斗中标记，或连续两次正常截图未匹配到已知状态，记录界面已离开随机战状态，再等待随机战重新出现。后者是界面变化的判断依据，不是战斗开始的绝对证明；实机调试时应留意模板漏识别造成的误判。

收尾动作只放在要求的路径上。通天塔在扫荡按钮连续五次未找到后关闭；竞技场的礼包和关闭操作位于次数耗尽分支；天下五绝完成五轮后关闭。不要自动给所有任务增加同样的结束动作。

## 8. 区域 OCR 与每秒轮询

区域确认任务使用下面三个区域：

```javascript
var regions = [
    [1702, 425, 192, 92],
    [1688, 426, 198, 78],
    [601, 570, 324, 130]
];
```

多个区域放进数组，反复声明 `var region = ...` 会覆盖前面的值。

在同一区域查找多个关键词时，可以一次调用 `ocr.recognize` 获取各行，再判断文字是否包含任意目标词。以下片段需要先取得有效的 `shot`，并在外层释放截图：

```javascript
var lines = ocr.recognize(shot, { region: regions[0] });
for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    var text = String(line.text || "").replace(/\s+/g, "");
    if (text.indexOf("确定") >= 0 || text.indexOf("同意") >= 0) {
        click(line.centerX(), line.centerY());
        break;
    }
}
```

空白清理可以匹配“确 定”，不会纠正 OCR 错字，也不会把不同行合并匹配。需要忽略标点等符号时，可以查阅 `ocr.findTextContains`；每次调用它都会独立执行 OCR。

`ocr.recognize` 的选项只支持区域，不接受图像匹配 `threshold`。图片阈值规则不适用于纯 OCR 或纯坐标任务。OCR 点击使用整行中心，不能把包含查询误认为精确定位到该行中的某一个词。

前两个区域相互重叠，因此区域确认每轮找到匹配后只尝试一次点击，再重新截图开始下一轮。不要在界面已经变化后，继续点击同一张旧截图上的其他位置。

以每秒一次为目标轮询时，从本轮开始时间计算剩余等待时间：

```javascript
var startedAt = Date.now();
// 在这里完成本轮截图、OCR 和必要的点击。
var remainingMs = 1000 - (Date.now() - startedAt);
if (remainingMs > 0) sleep(remainingMs);
```

OCR 和截图会阻塞当前调用，耗时可能超过一秒。超过时下一轮立即开始，不能保证固定一秒内完成所有识别。如果每轮末尾固定 `sleep(1000)`，周期就会成为“本轮执行耗时＋一秒”。

## 9. 图片释放、中文注释和日志

每次截图都用 `try/finally` 配对 `images.release(shot)`。预加载模板在任务外层统一释放，例如接在第 5 节模板加载代码之后：

```javascript
try {
    if (!templates.start || !templates.declarationTask) {
        console.error("任务模板读取失败");
        exit();
    }
    // 在这里执行已经确认的完整任务流程。
} finally {
    images.release(templates.start);
    images.release(templates.declarationTask);
}
```

图片对象没有 `recycle()` 方法。不要把每轮截图留在数组里无限积累，也不要提前释放仍需使用的模板。

中文注释应说明：

- 文件开头：任务范围、坐标基准、循环次数和停止条件。
- 每张图片的匹配函数：图片名称和独立阈值。
- 特殊判断：为什么等待、何时清零计数、何时结束任务。
- 延时：单位和用途，例如等待弹窗关闭或界面切换。
- 收尾与释放：在什么条件下执行。

注释必须与当前代码一致。循环从 4 次改成 15 次时，同步更新注释和配置描述；天下五绝已经是“每个位置后修炼”，就不能仍写成“只在中间修炼”。未实测的阈值不要描述为已经验证的最佳值。

日志记录轮次、步骤、点击返回结果、连续未找到次数和停止原因。把“点击完成”和“任务已完成”区分清楚，只有业务状态足以支持时才记录后者。长时间等待可以适度输出进度，避免每轮刷大量重复日志。

## 10. 运行环境与常见问题

任务脚本运行于 QuickJS，可以使用标准 JavaScript 的函数、数组、条件、循环、`Date` 和 `JSON`，以及 API 参考列出的全局接口。

| 问题 | 处理方式 |
| --- | --- |
| 试图 `require("./common.js")` 共享函数 | 当前不支持加载本地 JS，把必要函数保留在任务入口中 |
| 使用 `fs`、`process`、`fetch` 或 `setInterval` | 这些能力未注入任务引擎，按支持的 API 和轮询方式实现 |
| `requestScreenCapture(true)` 后方向没变化 | 该参数当前不控制屏幕方向，运行前需处于正确界面和方向 |
| 找到图片却点击位置偏移 | 核对设计尺寸、目标布局及是否重复做坐标缩放 |
| 更换屏幕尺寸后一直匹配不到 | 模板不会随 adapter 缩放，核对实际像素尺寸和素材 |
| 连续未找到次数一直增加 | 核对路径、素材、画面和该图阈值，不能无依据地当成次数耗尽 |
| 打了错误日志但脚本继续执行 | `console.error` 只输出日志，需要明确退出或结束对应循环 |
| 点击停止后仍短暂等待 | 已进入的 OCR 或手势需要先返回；JS 忙循环会被立即中断，不要吞掉停止或退出异常 |

## 11. 检查与单任务 ZIP 打包

交付前核对步骤顺序、循环范围、每张图的独立阈值、停止条件的适用对象、图片路径和配置。修改脚本后应重新生成需要交付的 ZIP，旧 ZIP 不会自动同步源码。

可以用 Node 做 JavaScript 语法检查：

```powershell
node --check "tasks/帮派宣言/task.js"
```

这不会执行游戏脚本，也不能确认权限、命中率或坐标正确。复杂流程可以通过模拟检查“找不到是否还会继续”“找到后是否清零”“循环是否越界”；实际识别效果仍需在设备上确认。交付时明确说明已经检查的范围。

使用仓库打包工具，将**单个任务目录**作为输入：

```powershell
node tools/package-tasks.js "tasks/帮派宣言" "build/帮派宣言.zip"
```

其他任务同样单独生成：

```powershell
node tools/package-tasks.js "tasks/竞技场" "build/竞技场.zip"
node tools/package-tasks.js "tasks/通天塔" "build/通天塔.zip"
node tools/package-tasks.js "tasks/区域确认" "build/区域确认.zip"
node tools/package-tasks.js "tasks/天下五绝" "build/天下五绝.zip"
```

输入 `tasks/帮派宣言` 得到独立任务 ZIP；输入整个 `tasks` 会收集其下的任务并合并。当前约定按任务单独打包，只有明确要求合集时才传入整个 `tasks` 目录。

单任务 ZIP 内仍使用以下路径，顶层的 `tasks/` 不代表包含多个任务：

```text
tasks/帮派宣言/task.js
tasks/帮派宣言/config.json
tasks/帮派宣言/images/帮派宣言.png
...
```

工具会递归打包任务目录中的全部文件，包括说明文本和未引用图片，并用 UTF-8 写入中文文件名；它不会自动删除或筛选未用素材。输出 ZIP 必须放在输入目录之外，通常使用 `build/任务名.zip`。

打包后核对 ZIP 中的任务名称、脚本和配置、图片路径及完整性。在应用任务页选择导入；当前同名任务会跳过，不会覆盖，因此更新包导入前需要按实际需求处理已有任务。

如果任务目录被本地 `.gitignore` 忽略，新文件可能不显示在普通 `git status` 中。写入文件、打包和纳入 Git 版本控制是不同操作，需要提交时另行确认跟踪状态。

相关参考：[完整脚本 API](script-api.md)、[应用使用与打包说明](README.md)、[打包工具源码](../tools/package-tasks.js)。
