# 雕雕助手脚本 API 完整参考

本文按当前仓库的脚本引擎实现整理，包含 **81 个公开函数/方法**，另列公开属性、返回对象、配置字段及使用限制。

接口实现以 [`ScriptEngine.kt`](../app/src/main/java/com/forest/android/assistant/core/engine/ScriptEngine.kt) 为准；编辑器补全和自动导出的 `.d.ts` 提供签名与常用类型，本手册补充完整行为和边界。以 `__` 开头的桥接函数属于内部实现，不是供任务脚本调用的 API；公开的脚本路径变量 `__scriptPath__` 单独说明。

## 目录与方法索引

| 分类 | 方法/属性 |
| --- | --- |
| [运行约定与类型](#conventions) | 参数、时间、坐标、Image、Point、Rect、OcrItem、options |
| [基础与日志](#basics) | `sleep`、`random`、`exit`、`log`、`print`、`toastLog`、`console.log`、`console.info`、`console.warn`、`console.error` |
| [无障碍](#accessibility) | `auto`、`auto.waitFor`、`auto.service`（属性） |
| [点击与手势](#gestures) | `click`、`clickRandom`、`clickRetry`、`longClick`、`press`、`swipe`、`swipeRandom`、`gesture`、`gestures`、`gesturesAvailable` |
| [截图](#capture) | `requestScreenCapture`、`requestScreenCaptureAsync`、`captureScreen` |
| [图片基础操作](#image-basics) | `images.read`、`images.readAll`、`images.save`、`images.clip`、`images.resize`、`images.pixel`、`images.width`、`images.height`、`images.release`、`images.releaseAll` |
| [找图与找色](#image-search) | `images.findImage`、`images.findImageDetails`、`images.matchTemplate`、`images.findImageAny`、`images.findImagePair`、`images.findAllImages`、`images.existsImage`、`images.findImageInRegion`、`images.findImageWithText`、`images.findColor`、`images.findMultiColors` |
| [轮询找图](#wait-image) | `waitImage`、`waitImageVanish`、`waitImageClick`、`waitImageAny`、`clickImageUntilGone` |
| [颜色工具](#colors) | `colors.parseColor`、`colors.rgb`、`colors.argb`、`colors.red`、`colors.green`、`colors.blue`、`colors.alpha`、`colors.toString`、`colors.isSimilar` |
| [OCR](#ocr) | `ocr.init`、`ocr.ready`、`ocr.recognize`、`ocr.recognizeText`、`ocr.findText`、`ocr.findTextContains`、`ocr.findTextAny`、`ocr.findAllText`、`waitText`、`waitTextAny`、`waitTextClick`、`watchText`、`OcrItem.centerX`、`OcrItem.centerY` |
| [通用等待与重试](#polling) | `waitScreen`、`retry` |
| [坐标适配](#adapter) | `adapter.setDesignSize`、`adapter.reset`、`adapter.enabled`、`adapter.scale` |
| [模块与脚本路径](#modules) | `require`、`__scriptPath__`（变量） |
| [任务配置](#config) | `config.json` 的所有已读取字段 |
| [完整示例](#examples) | OCR 找字点击、预加载模板并轮询找图 |
| [支持边界](#limits) | 引擎环境、停止行为、失败返回值、常见误用 |

<a id="conventions"></a>

## 1. 运行约定与公共类型

### 1.1 参数与返回值记法

- `name?: Type`：可选参数；省略时使用参数表中的默认值。`—` 表示必填且没有默认值。
- `...args`：可变参数，可以传多个值。
- `number`：JavaScript 数字。坐标、尺寸、颜色和计时请使用有限数值；原生接口通常会将需要整数的参数截断。
- `void`：没有需要使用的返回值；不要依赖它判断执行成功。
- `Image | null`、`Point | null`：调用可能失败或没有匹配，使用前应检查 `null`。
- 可选参数的默认值一般只在省略或传 `undefined` 时应用；不要用 `null` 代替默认值。
- 方法示例中的 `img` 表示有效的截图/读图结果，`tpl` 表示模板图片或模板路径，`item` 表示非空 OCR 结果。独立运行时须先创建这些变量；组合示例见第 14 节。

### 1.2 时间单位

| 参数 | 单位 |
| --- | --- |
| `sleep(ms)`、`auto(timeoutMs)`、`auto.waitFor(timeoutMs)` | 毫秒 |
| 点击、长按、滑动、手势的 `duration` / `delay` | 毫秒 |
| `waitImage` / `waitImageVanish` 的 `timeout` / `interval` | **秒**，可用小数 |
| `waitImageClick` / `waitImageAny` / `waitTextClick` 的 `timeout` / `interval` | **秒**，可用小数 |
| `waitImageClick` / `waitImageAny` / `waitTextClick` 的 `options.delay` | 毫秒 |
| `clickRetry` 的 `interval` / `duration` | 毫秒 |
| `config.json` 的 `loop.interval` | 秒，整数 |

截图、OCR、找图和手势调用会等待当前操作完成。轮询超时是循环检查的截止时间，不能中断一次已经开始的截图或识别，因此总耗时可能超过设置值。

### 1.2.1 三个等待类接口的 timeout 约定

`waitImageClick`、`waitImageAny`、`waitTextClick` 使用统一的超时语义：

| 传入值 | 行为 |
| --- | --- |
| 省略、`null` | **不限时**，一直等到手动停止 |
| 负数（如 `-1`） | 与省略一致，兼容旧写法 |
| `0` | 只检查一次，立即返回本次结果 |
| 正数 `t` | 最多等待 `t` 秒 |
| `NaN` / 无法解析 | 按不限时处理 |

`options.missLimit` 表示“连续多少次未找到后结束”，`0` 或省略表示不限。计数口径：只有**成功截图（或成功识别）但没有匹配**才加一；截图失败不计；找到即清零，包括找到但点击失败的情况。`waitImage` / `waitImageVanish` 保持原有的默认 10 秒语义，不受此约定影响。

### 1.3 坐标与区域

坐标原点在左上角，X 向右，Y 向下。默认单位为输入图片/屏幕的真实像素。任务配置 `designSize` 或 `adapter.setDesignSize` 启用适配后，以下接口使用设计坐标：

| 接口或字段 | 启用 adapter 后的行为 |
| --- | --- |
| 点击、滑动、手势的位置 | 从设计坐标换算为屏幕坐标 |
| 找图、找色、OCR 的 `region`，`findImageInRegion` 的区域 | 从设计坐标换算为输入图片中的像素区域 |
| 找图、找色、OCR 的返回点和边界 | 从图片坐标换算回设计坐标，可直接传给 `click` |
| `images.clip` / `images.pixel` 的坐标 | 始终是输入图片的真实像素，不参与缩放 |
| 图片 `width` / `height` | 始终是实际像素宽高 |
| `findMultiColors` 的偏移量、`findImageWithText` 的 `padding` | 始终是实际像素，不参与缩放 |

`Region` 推荐使用 `[x, y, width, height]`，是“左上角 + 宽高”，不是两个角点。省略 `region` 表示整张图；也接受 `[x, y]` 或 `[x, y, width]`。宽/高省略或不大于 0 时延伸至对应图片边缘；坐标越界会裁到图片范围，不保证返回空结果。全为负数的区域按全图处理。

OCR 另有当前实现行为：裁剪后的宽或高小于 **8 个实际像素** 时改为识别全图，请避免使用过小的 OCR 区域。

区域识别的返回坐标相对于整张输入图片；对 `images.clip` 得到的图片识别时，坐标相对于裁剪图片，系统不会记住它在原屏幕的位置。处理独立素材图片时通常先 `adapter.reset()`，避免屏幕适配影响图片坐标。

### 1.4 Image 与 ImageRef

`captureScreen`、`images.read`、`images.clip` 返回下列句柄对象：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `__id` | `number` | 内部图片句柄，勿自行创建或修改 |
| `width` | `number` | 创建句柄时的实际像素宽度 |
| `height` | `number` | 创建句柄时的实际像素高度 |

本文 `ImageRef` 表示 `Image` 对象或已有的数字图片句柄，推荐始终传 `Image` 对象。普通 `img` / `image` 参数不接受路径；需要先 `images.read(path)`。只有模板参数 `tpl` 可以直接接收路径字符串。

图片对象没有 `recycle()` 或 `save()` 方法，分别使用 `images.release(img)` 和 `images.save(img, path)`。释放后对象上的宽高快照仍在，但句柄已失效；`images.width(img)` / `images.height(img)` 将返回 0。每轮脚本执行结束时，运行器会清理该轮全部图片句柄；循环内建议及时释放不再使用的图片。

### 1.5 Point、Rect、OcrItem 与图文匹配结果

| 类型 | 字段/方法 | 类型 | 含义 |
| --- | --- | --- | --- |
| `Point` | `x`、`y` | `number` | 坐标点；找图时为模板中心，找色时为命中的像素位置 |
| `Rect` | `left`、`top`、`right`、`bottom` | `number` | 左、上、右、下边界；宽高可用右减左、下减上计算 |
| `OcrItem` | `text` | `string` | 识别出的原始整行文字 |
| `OcrItem` | `left`、`top`、`right`、`bottom` | `number` | 整行文字边界，遵循当前 adapter 坐标 |
| `OcrItem` | `bounds` | `Rect` | 与上述四个边界字段一致 |
| `OcrItem` | `confidence` | `number` | 模型返回的置信度；模型给出负值表示无有效置信度时，当前实现填 1，因此 1 不一定表示识别完全正确 |
| `OcrItem` | `centerX()`、`centerY()` | `() => number` | 整行中心坐标，见第 10 节 |
| `OcrItem` | `matched` | `string`，可选 | 仅 `ocr.findTextAny` 返回，表示实际命中的关键词原文 |
| `OcrItem` | `regionIndex` | `number`，可选 | `options.regions` 中命中区域的下标，从 0 开始；未使用 `regions` 时为 -1 |
| `ImageMatch` | `x`、`y` | `number` | 模板中心，使用当前 adapter 坐标 |
| `ImageMatch` | `score` | `number` | 模板匹配分数，范围通常为 0～1；越大越相似 |
| `ImageMatch` | `bounds` | `Rect` | 匹配模板的边界，使用当前 adapter 坐标 |
| `WaitResult<T>` | `ok`、`reason` | `boolean`、`string` | 操作是否按目标条件结束，以及结束原因 |
| `WaitResult<T>` | `value` | `T` 或 `null` | 最近一次命中的值；重试超时等情况可能保留之前的命中 |
| `WaitResult<T>` | `attempts`、`misses`、`clicks`、`elapsedMs` | `number` | 截图/动作次数、连续未命中数、点击数、总耗时毫秒 |
| `WaitResult<T>` | `lastError` | `string` 或 `null` | 最近的截图、OCR、图片模板或点击错误 |
| `PollState` | `attempts`、`misses`、`clicks`、`elapsedMs`、`name` | `number`、`string` | 当前轮询/重试进度；`retry` 动作回调也会收到上次结果 `value` 和 `lastError` |
| `AnyMatch` | `x`、`y` | `number` | 命中模板中心，可直接传给 `click` |
| `AnyMatch` | `index` | `number` | 命中条目在传入数组中的下标（从 0 开始） |
| `AnyMatch` | `name` | `string` | 条目 `name`；未指定时取路径字符串，句柄条目为空串 |
| `ImageTextMatch` | `x`、`y` | `number` | 匹配模板中心 |
| `ImageTextMatch` | `bounds` | `Rect` | 模板匹配后、按 `padding` 扩展并裁到图片范围的 OCR 区域 |
| `ImageTextMatch` | `text` | `string` | 区域 OCR 按行用 `\n` 拼接的文字 |
| `ImageTextMatch` | `items` | `OcrItem[]` | 区域内逐行识别结果 |

`Point`、`AnyMatch` 和 `ImageTextMatch` 没有 `centerX()` / `centerY()` 方法；直接使用 `x` / `y`。

### 1.6 options 对象的全部支持字段

下表列出的字段均为可选。不同方法只读取其对应 options 类型中的字段；未列出的字段不会提供额外功能。

| 类型 | 字段 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- | --- |
| `FindImageOptions` | `threshold` | `number` | `0.9` | 模板匹配阈值，通常取 0～1，越高越严格；不是百分数 |
| `FindImageOptions` | `region` | `Region` | 全图 | 搜索区域 |
| `FindImageOptions` | `scales` | `number[]` | `[1]` | 按图片轮廓匹配时尝试的模板倍率；最多 8 个，单个倍率限 0.25～4 |
| `FindAllImagesOptions` | `threshold` | `number` | `0.9` | 同上 |
| `FindAllImagesOptions` | `region` | `Region` | 全图 | 同上 |
| `FindAllImagesOptions` | `max` | `number`，整数 | `5` | 最多返回的匹配点数；小于等于 0 返回空数组 |
| `FindAllImagesOptions` | `scales` | `number[]` | `[1]` | 同 `FindImageOptions` |
| `FindImageWithTextOptions` | `threshold` | `number` | `0.9` | 找图阈值，不是 OCR 置信度 |
| `FindImageWithTextOptions` | `region` | `Region` | 全图 | 找图范围 |
| `FindImageWithTextOptions` | `padding` | `number`，整数 | `0` | 找到模板后，OCR 区域四周外扩的实际像素数；限制到 0～200 |
| `FindImageWithTextOptions` | `scales` | `number[]` | `[1]` | 同 `FindImageOptions` |
| `FindColorOptions` | `threshold` | `number`，整数 | `16` | 每个 RGB 通道允许的绝对色差，建议 0～255；0 为精确色值，忽略 Alpha |
| `FindColorOptions` | `region` | `Region` | 全图 | 首色/主色点的搜索区域 |
| `OcrOptions` | `region` | `Region` | 全图 | OCR 区域 |
| `OcrOptions` | `regions` | `Region[]` | 未设置 | 不能与 `region` 同时设置；按数组顺序检查多个区域，结果带 `regionIndex` |
| `ScreenWaitOptions` | `timeoutMs` | `number`，毫秒 | `0` | 等待超时；`0` 表示不限时，使用 `maxAttempts` 可限制检查轮数 |
| `ScreenWaitOptions` | `intervalMs` | `number`，毫秒 | `500` | 轮询周期，最小 50ms；OCR 等操作占用的时间计入周期 |
| `ScreenWaitOptions` | `missLimit` | `number`，整数 | `0` | 连续未命中次数限制；`0` 不限，截图/OCR 失败不计入 |
| `ScreenWaitOptions` | `confirmCount` | `number`，整数 | `1` | 连续命中多少次才确认条件；截图失败会打断连续确认 |
| `ScreenWaitOptions` | `maxAttempts` | `number`，整数 | `0` | 最多检查次数；`0` 不限，至少检查一次 |
| `ScreenWaitOptions` | `name` | `string` | `""` | 附在返回结果和重试回调状态里的步骤名 |
| `ScreenWaitOptions` | `onRetry` | `function` | 未设置 | 每轮仍需重试时同步调用，接收原因和当前状态 |
| `WatchOptions` | `maxClicks` | `number`，整数 | `0` | 点击次数上限；`0` 表示持续运行 |
| `WatchOptions` | `delayMs` | `number`，毫秒 | `0` | 每次点击后等待时间 |
| `RetryOptions` | `maxAttempts` | `number`，整数 | `3` | 操作尝试次数，包含首次；`0` 表示不限次数 |
| `RetryOptions` | `timeoutMs`、`intervalMs`、`name`、`onRetry` | 同 `ScreenWaitOptions` | — | 整组操作的总超时、重试间隔与进度回调 |
| `ImageLoopOptions` | `threshold` / `region` | `number` / `Region` | `0.9` / 全图 | 单张模板的阈值与搜索区域 |
| `ImageLoopOptions` | `confirmCount` | `number`，整数 | `3` | 连续多少帧未找到时确认图片已消失 |
| `ImageLoopOptions` | `maxClicks` | `number`，整数 | `0` | 点击次数上限；`0` 表示不设上限 |
| `ImageLoopOptions` | `delayMs` | `number`，毫秒 | `0` | 每次点击后等待时间 |
| `MatchTemplateOptions` | `threshold` / `region` / `max` | `number` / `Region` / `number` | `0.9` / 全图 / `5` | 匹配阈值、搜索区域和最多返回结果数 |
| `MatchTemplateOptions` | `scales` | `number[]` | `[1]` | 同 `FindImageOptions` |
| `ResizeOptions` | `filter` | `boolean` | `true` | 是否使用滤镜平滑缩放 |
| `FindImageAnyOptions` | `threshold` | `number` | `0.9` | 条目未单独指定阈值时使用 |
| `FindImageAnyOptions` | `region` | `Region` | 全图 | 所有条目共用的搜索区域 |
| `FindImageAnyOptions` | `scales` | `number[]` | `[1]` | 所有条目共用的模板倍率 |
| `WaitImageClickOptions` | `threshold` / `region` | | | 同 `FindImageOptions` |
| `WaitImageClickOptions` | `delay` | `number`，毫秒 | `0` | 点击成功后额外等待 |
| `WaitImageClickOptions` | `missLimit` | `number`，整数 | `0` | 连续未找到多少次后返回 `false`；`0` 不限 |
| `WaitImageAnyOptions` | `threshold` / `region` | | | 同 `FindImageAnyOptions` |
| `WaitImageAnyOptions` | `missLimit` | `number`，整数 | `0` | 连续未找到多少次后返回 `null`；`0` 不限 |
| `WaitTextClickOptions` | `region` | `Region` | 全图 | OCR 区域 |
| `WaitTextClickOptions` | `regions` | `Region[]` | 未设置 | 按顺序检查多个 OCR 区域，命中后返回区域下标 |
| `WaitTextClickOptions` | `delay` | `number`，毫秒 | `0` | 点击成功后额外等待 |
| `WaitTextClickOptions` | `missLimit` | `number`，整数 | `0` | 连续未找到多少次后返回 `false`；`0` 不限 |
| `WaitTextClickOptions` | `confirmCount` / `maxAttempts` | `number`，整数 | `1` / `0` | 连续命中确认次数／检查轮数上限；`maxAttempts: 0` 不限 |

`ocr.*` 不读取 `threshold`、`ignoreCase`、`removeSpaces` 或 `engine` 等 options 字段。OCR 引擎用 `ocr.init(engine)` 指定；忽略空格及符号的查找使用 `ocr.findTextContains`，一次匹配多个关键词使用 `ocr.findTextAny`。

<a id="basics"></a>

## 2. 基础工具与日志

### `sleep`

签名：`sleep(ms: number): void`。阻塞当前脚本指定时长。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `ms` | `number` | 是 | — | 毫秒，按整数处理；小于等于 0 立即返回 |

返回：无可用返回值。休眠期间每隔最多 50ms 检查停止请求。

```javascript
sleep(1000); // 等待 1 秒
```

### `random`

签名：`random(min: number, max: number): number`。生成两端都包含的随机整数。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `min` | `number` | 是 | — | 整数下界 |
| `max` | `number` | 是 | — | 整数上界；`max <= min` 时直接返回 `min` |

返回：`[min, max]` 中的整数。参数经 32 位整数处理；正常随机区间的上界应小于 `2147483647`，避免内部 `max + 1` 溢出。

```javascript
sleep(random(500, 1200));
```

### `exit`

签名：`exit(): never`。参数：无。

返回：不正常返回，结束当前轮脚本执行，运行历史记录为退出。任务配置了多轮执行时，`exit()` 后仍可能进入下一轮；它不同于界面“停止任务”。不要用宽泛的 `try/catch` 吞掉退出异常。

```javascript
if (!requestScreenCapture()) exit();
```

### `log`

签名：`log(...args: any[]): void`。`print` 的别名，输出 I 级日志。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...args` | `any[]` | 否 | 无参数 | 各值转为文本后以一个空格连接；对象不会自动格式化为 JSON |

返回：无可用返回值。

```javascript
log("位置", 120, 360);
log(JSON.stringify({ success: true }));
```

### `print`

签名：`print(...args: any[]): void`。行为与 `log` 相同。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...args` | `any[]` | 否 | 无参数 | 输出内容，以空格拼接 |

返回：无可用返回值。

```javascript
print("第", 1, "次执行");
```

### `toastLog`

签名：`toastLog(message: string): void`。同时写入 I 级日志并显示短 Toast。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `message` | `string` | 是 | — | 一条提示文本；只使用第一个参数，其他值请先拼接 |

返回：无可用返回值。Toast 在主线程显示，不等待显示结束。

```javascript
toastLog("已完成 " + 3 + " 步");
```

### `console.log`

签名：`console.log(...args: any[]): void`。写入 I 级日志。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...args` | `any[]` | 否 | 无参数 | 以空格拼接的输出值，不做 `%s` 等格式占位符替换 |

返回：无可用返回值。

```javascript
console.log("开始", "OCR");
```

### `console.info`

签名：`console.info(...args: any[]): void`。写入 I 级日志。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...args` | `any[]` | 否 | 无参数 | 以空格拼接的输出值 |

返回：无可用返回值。

```javascript
console.info("图片已加载");
```

### `console.warn`

签名：`console.warn(...args: any[]): void`。写入 W 级日志。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...args` | `any[]` | 否 | 无参数 | 以空格拼接的输出值 |

返回：无可用返回值，不会中断脚本。

```javascript
console.warn("当前未找到按钮");
```

### `console.error`

签名：`console.error(...args: any[]): void`。写入 E 级日志。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...args` | `any[]` | 否 | 无参数 | 以空格拼接的输出值 |

返回：无可用返回值；仅记录错误，不会主动抛错或结束脚本。

```javascript
console.error("模板读取失败", "images/button.png");
```

<a id="accessibility"></a>

## 3. 无障碍服务

### `auto`

签名：`auto(timeoutMs?: number): boolean | undefined`。打开系统无障碍设置；指定时间时再等待服务连接。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `timeoutMs` | `number` | 否 | 不等待 | 最多等待的毫秒数；0 表示只检查当前状态 |

返回：省略参数时为 `undefined`；传入参数时，服务在等待期内连接返回 `true`，超时返回 `false`。不能自动代替用户在系统设置中开启服务。

```javascript
if (!auto(10000)) exit();
```

### `auto.waitFor`

签名：`auto.waitFor(timeoutMs?: number): boolean`。等待服务连接，不打开设置页。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `timeoutMs` | `number` | 否 | `0` | 最大等待毫秒数；默认立即检查，**不是无限等待** |

返回：服务已连接或在指定时间内连接为 `true`；未连接且超时为 `false`。等待期间约每 100ms 检查一次。

```javascript
if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
```

### `auto.service`（属性）

类型：只读 `boolean`。每次读取时检查无障碍服务是否连接。不是服务对象，也不是方法。

```javascript
log("服务已连接：", auto.service);
```

<a id="gestures"></a>

## 4. 点击、滑动与手势

以下位置参数均接受当前设计坐标。建议持续时间使用正整数、延迟使用非负整数、位置保持在屏幕内；非法手势参数可能抛出运行错误。返回 `true` 表示操作通道报告完成，不代表目标应用已经完成业务操作。

单指点击、长按、滑动及折线手势会优先使用无障碍服务；失败且 Root 通道可用时尝试 `input`。折线手势在 Root 兜底时退化为起点到终点的直线，起手延迟也不会保留。多指调用依赖无障碍服务。

### `click`

签名：`click(x: number, y: number, duration?: number): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x` | `number` | 是 | — | 点击 X 坐标 |
| `y` | `number` | 是 | — | 点击 Y 坐标 |
| `duration` | `number` | 否 | `30` | 按住时间，毫秒 |

返回：完成为 `true`，未受理、取消、超时或通道不可用等情况为 `false`。

```javascript
click(540, 1200, 50);
```

### `clickRandom`

签名：`clickRandom(x: number, y: number, range?: number, duration?: number): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x` | `number` | 是 | — | 基准 X 坐标 |
| `y` | `number` | 是 | — | 基准 Y 坐标 |
| `range` | `number` | 否 | `5` | X、Y 各自独立的随机偏移范围，约 `[-range, range)`；使用非负数，与基准坐标同单位 |
| `duration` | `number` | 否 | `100` | 按住时间，毫秒 |

返回：点击是否完成。先在设计坐标中偏移，再换算为屏幕位置；这是方形范围内随机取点，不是圆形半径。

```javascript
clickRandom(540, 1200, 8, 80);
```

### `clickRetry`

签名：`clickRetry(x: number, y: number, times?: number, interval?: number, duration?: number): boolean`。

固定坐标点击失败时自动重试。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x` | `number` | 是 | — | 点击 X 坐标，遵循 adapter |
| `y` | `number` | 是 | — | 点击 Y 坐标 |
| `times` | `number`，整数 | 否 | `3` | 最多尝试次数，**含首次**；`0` 表示一直重试直到手动停止 |
| `interval` | `number`，毫秒 | 否 | `500` | 每次失败后的等待 |
| `duration` | `number`，毫秒 | 否 | `30` | 按住时长，透传给 `click` |

返回：某次点击完成返回 `true`；`times` 耗尽返回 `false`。

只重试“点击通道未受理”，不判断业务是否成功。每次重试重新经过 adapter 换算，不会重复缩放；重试期间可被停止按钮打断。

```javascript
if (!clickRetry(1777, 24, 3, 500)) {
    toastLog("打开地图失败");
    exit();
}
```

### `longClick`

签名：`longClick(x: number, y: number, duration?: number): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x` | `number` | 是 | — | 长按 X 坐标 |
| `y` | `number` | 是 | — | 长按 Y 坐标 |
| `duration` | `number` | 否 | `600` | 按住时间，毫秒 |

返回：长按是否完成。

```javascript
longClick(300, 600, 1000);
```

### `press`

签名：`press(x: number, y: number, duration?: number): boolean`。定时按压，与长按使用同一底层操作。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x` | `number` | 是 | — | 按压 X 坐标 |
| `y` | `number` | 是 | — | 按压 Y 坐标 |
| `duration` | `number` | 否 | `500` | 按住时间，毫秒；当前实现允许省略 |

返回：按压是否完成。

```javascript
press(540, 1000, 800);
```

### `swipe`

签名：`swipe(x1: number, y1: number, x2: number, y2: number, duration?: number): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x1` | `number` | 是 | — | 起点 X |
| `y1` | `number` | 是 | — | 起点 Y |
| `x2` | `number` | 是 | — | 终点 X |
| `y2` | `number` | 是 | — | 终点 Y |
| `duration` | `number` | 否 | `500` | 整次滑动时长，毫秒 |

返回：滑动是否完成。

```javascript
swipe(540, 1600, 540, 500, 600);
```

### `swipeRandom`

签名：`swipeRandom(x1: number, y1: number, x2: number, y2: number, range?: number, duration?: number): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x1` | `number` | 是 | — | 基准起点 X |
| `y1` | `number` | 是 | — | 基准起点 Y |
| `x2` | `number` | 是 | — | 基准终点 X |
| `y2` | `number` | 是 | — | 基准终点 Y |
| `range` | `number` | 否 | `10` | 四个位置分量分别随机偏移，约 `[-range, range)`，使用非负数 |
| `duration` | `number` | 否 | `500` | 滑动时长，毫秒；时长本身不随机 |

返回：滑动是否完成。

```javascript
swipeRandom(540, 1600, 540, 500, 12, 600);
```

### `gesture`

调用格式：`gesture(duration, [x1, y1], [x2, y2], ..., delay?)`。一根手指沿多个点依次移动。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `duration` | `number` | 是 | — | 整条轨迹持续时间，毫秒，正整数 |
| 每个 `[x, y]` | `[number, number]` | 是 | — | 一个轨迹点，按独立参数传入，至少两个点 |
| 点中的 `x` | `number` | 是 | — | 当前点 X 坐标 |
| 点中的 `y` | `number` | 是 | — | 当前点 Y 坐标 |
| `delay` | `number` | 否 | `0` | 起手延迟，毫秒；建议放最后，只传一次 |

返回：`boolean`，手势是否完成；少于两个有效点返回 `false`。

```javascript
gesture(900, [200, 1400], [400, 1000], [650, 800], 100);
```

### `gestures`

调用格式：`gestures(stroke1, stroke2, ...)`，每条 `stroke` 为 `[duration, [x1, y1], [x2, y2], ..., delay?]`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...strokes` | 轨迹数组列表 | 是 | — | 每根手指一条轨迹，至少一条有效轨迹 |
| `stroke[0]` / `duration` | `number` | 是 | — | 该指轨迹的持续毫秒数，正整数 |
| 轨迹中的 `[x, y]` | `[number, number]` | 是 | — | 至少两个点；每个点的 X、Y 均为当前设计坐标 |
| 轨迹末尾 `delay` | `number` | 否 | `0` | 该指起手延迟，毫秒，非负整数 |

返回：`boolean`，手势是否完成。坐标经整数化再缩放。各指延迟相同则保留该值；不同则全部改为 0 并记警告日志。少于两个点的轨迹会被忽略；没有有效轨迹返回 `false`。请在调用前保证轨迹合法，不依赖此容错。

```javascript
gestures(
    [600, [450, 900], [250, 900]],
    [600, [630, 900], [830, 900]]
); // 双指同时向外滑动
```

### `gesturesAvailable`

签名：`gesturesAvailable(): boolean`。参数：无。

返回：无障碍服务当前是否连接；与 `auto.service` 判断一致，不检测 Root 兜底通道，也不保证某条具体轨迹一定能执行。

```javascript
log("无障碍手势可用：", gesturesAvailable());
```

<a id="capture"></a>

## 5. 截图

### `requestScreenCapture`

签名：`requestScreenCapture(landscape?: boolean): boolean`。请求当前截图通道可用，可能触发系统录屏授权。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `landscape` | `boolean` | 否 | 未指定 | 兼容参数；**当前实现忽略此参数**，不会据此切换横竖屏 |

返回：申请成功/通道可用为 `true`，取消或失败为 `false`。后台定时任务没有可用截图通道时不能弹出授权页，会返回 `false`；请事先在应用内授权，或配置可用的 Root 截图通道。

```javascript
if (!requestScreenCapture()) {
    toastLog("无法获取截图权限");
    exit();
}
```

### `requestScreenCaptureAsync`

签名：`requestScreenCaptureAsync(): Promise<boolean>`。参数：无。

返回：截图授权结果的 Promise。当前实现是先执行同一个同步授权过程，再将结果包装成 Promise；名称中的 Async 不表示授权过程在额外线程中并行执行。普通任务优先用 `requestScreenCapture()`。

```javascript
var capturePermission = requestScreenCaptureAsync(); // Promise<boolean>
```

### `captureScreen`

签名：`captureScreen(): Image | null`。参数：无。

返回：当前屏幕图片；未授权、暂无可用帧或截屏失败返回 `null`。授权失效时每轮脚本最多自动尝试重新申请一次，不保证重试成功。

截图时控制台与空闲悬浮球会暂时隐藏；运行条和停止按钮仍可能出现在图中，其覆盖位置会影响找图、找色及 OCR。

```javascript
var shot = captureScreen();
if (shot) {
    log("截图尺寸", shot.width, shot.height);
    images.release(shot);
}
```

<a id="image-basics"></a>

## 6. 图片基础操作

### `images.read`

签名：`images.read(path: string): Image | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `path` | `string` | 是 | — | 图片文件路径；相对路径基于当前 `task.js` 所在目录，也接受应用可访问的绝对文件路径 |

返回：图片句柄；文件不存在或解码失败为 `null`。不是网络下载接口，不接收 HTTP URL 或 `content://` URI。

```javascript
var template = images.read("images/button.png");
if (!template) exit();
images.release(template);
```

### `images.readAll`

签名：`images.readAll(source: string[] | Record<string, string>): ReadAllResult`。

批量读图，一次性得到全部句柄和失败清单。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `source` | `string[]` 或 `Object<string, string>` | 是 | — | 路径数组，或“键名 → 路径”对象；路径规则同 `images.read` |

返回：`{ images, missing }`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `images` | 数组或对象 | 与入参同形；失败位置为 `null` |
| `missing` | `string[]` | 读取失败的原始路径，顺序与输入一致 |

数组入参返回**同长度数组**，失败位填 `null`，保证下标对齐；对象入参返回同 key 对象。空输入返回 `{ images: [], missing: [] }` 或 `{ images: {}, missing: [] }`。成功读取的句柄需要自行释放。

```javascript
var t = images.readAll({
    elder: "images/云中易.png",
    enter: "images/进入恩仇录.png"
});
if (t.missing.length) {
    console.error("缺少图片：" + t.missing.join("、"));
    exit();
}
```

### `images.save`

签名：`images.save(img: ImageRef, path: string, format?: string, quality?: number): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 待保存图片 |
| `path` | `string` | 是 | — | 保存文件路径，相对脚本目录；尝试自动创建父目录 |
| `format` | `string` | 否 | 按扩展名推断 | 明确指定时使用 `"png"` 或 `"jpg"`，忽略大小写 |
| `quality` | `number`，整数 | 否 | `100` | 压缩质量，裁到 1～100；PNG 为无损格式，不用它降低图片质量 |

返回：保存成功为 `true`，无效图片或写入失败为 `false`。省略 `format` 时，`.jpg` / `.jpeg` 保存为 JPEG，其他扩展名保存为 PNG；明确传入 `"jpeg"`、`"webp"` 等非 `"jpg"` 值时，当前实现实际保存为 PNG。

```javascript
images.save(img, "output/screen.jpg", "jpg", 90);
```

### `images.clip`

签名：`images.clip(img: ImageRef, x: number, y: number, w: number, h: number): Image | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 原图 |
| `x` | `number`，整数 | 是 | — | 裁剪左上角 X，原图实际像素 |
| `y` | `number`，整数 | 是 | — | 裁剪左上角 Y，原图实际像素 |
| `w` | `number`，整数 | 是 | — | 裁剪宽度，实际像素 |
| `h` | `number`，整数 | 是 | — | 裁剪高度，实际像素 |

返回：新的图片句柄；无效图片或裁剪异常为 `null`。参数会裁到图内，宽高至少按 1 像素处理；不会因 adapter 自动缩放。原图和新图的句柄均需各自释放。

```javascript
var crop = images.clip(img, 100, 200, 300, 120);
if (crop) images.release(crop);
```

### `images.resize`

签名：`images.resize(img: ImageRef, width: number, height: number, options?: ResizeOptions): Image | null`。

按实际像素尺寸生成新的图片句柄。模板大小不会因 `adapter` 自动改变；找图时可使用 `options.scales` 尝试多个倍率，也可以先显式缩放再用返回句柄匹配。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 原图片 |
| `width`、`height` | `number`，正整数 | 是 | — | 输出实际像素尺寸，单边最多 8192 |
| `options.filter` | `boolean` | 否 | `true` | `true` 使用双线性滤镜，`false` 使用邻近像素缩放 |

返回：新图片句柄；原图和缩放图须分别释放。尺寸无效、图片无效或内存不足时返回 `null`。

```javascript
var scaled = images.resize(template, 400, 120, { filter: true });
if (scaled) {
    log("缩放模板：", images.width(scaled), images.height(scaled));
    images.release(scaled);
}
```

### `images.pixel`

签名：`images.pixel(img: ImageRef, x: number, y: number): number | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 输入图片 |
| `x` | `number`，整数 | 是 | — | 实际像素 X，范围 `0 <= x < width` |
| `y` | `number`，整数 | 是 | — | 实际像素 Y，范围 `0 <= y < height` |

返回：无符号 32 位 ARGB 数值；坐标越界或图片无效返回 `null`。不应用 adapter。

```javascript
var color = images.pixel(img, 10, 20);
if (color !== null) log(colors.toString(color));
```

### `images.width`

签名：`images.width(img: ImageRef): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 输入图片 |

返回：实际像素宽度，图片无效/已释放返回 `0`。

```javascript
log(images.width(img));
```

### `images.height`

签名：`images.height(img: ImageRef): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 输入图片 |

返回：实际像素高度，图片无效/已释放返回 `0`。

```javascript
log(images.height(img));
```

### `images.release`

签名：`images.release(img: ImageRef): void`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 不再使用的图片句柄 |

返回：无可用返回值。移除句柄及其缓存引用；重复释放或传入 `null` 不执行实际操作。释放后不要继续使用图片，也不要依靠对象仍保留的宽高判断句柄有效性。

```javascript
images.release(img);
```

### `images.releaseAll`

签名：`images.releaseAll(...targets: any[]): number`。

批量释放图片句柄，用于替换任务脚本里手写的 `for...in` 清理循环。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `...targets` | `ImageRef`、数组、对象、`null` | 否 | 无参数 | 可混用；数组和对象会递归展开，对象只遍历 value |

返回：实际释放的句柄数量。按内部 `__id` 去重，因此同一句柄重复传入只计一次；`null`、非图片值和无参数都返回 `0`，不抛错。

```javascript
try {
    // 业务步骤
} finally {
    images.releaseAll(t.images, shot);
}
```

<a id="image-search"></a>

## 7. 找图与找色

找图使用灰度边缘轮廓匹配，默认阈值为 `0.9`。默认按模板实际像素尺寸匹配；可通过 `scales` 显式尝试其他倍率，坐标适配不会自动缩放模板，不支持任意旋转。`findImage` 成功返回模板中心；返回结果不包含匹配分数。

`tpl` 接受有效图片句柄或文件路径。反复查找时建议先 `images.read()` 一次并复用句柄；传路径会在每次调用时重新读取并登记图片句柄。

### `images.findImage`

签名：`images.findImage(img: ImageRef, tpl: ImageRef | string, options?: FindImageOptions): Point | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 被搜索的大图 |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板图片或相对脚本目录的路径 |
| `options` | `FindImageOptions` | 否 | `{}` | 支持 `threshold = 0.9`、`region = 全图`，见 1.6 节 |

返回：达到阈值的最高分候选的中心 `Point`；无匹配、图片无效或模板大于搜索区域时返回 `null`。

```javascript
var point = images.findImage(img, tpl, { threshold: 0.92, region: [0, 500, 1080, 800] });
if (point) click(point.x, point.y);
```

### `images.matchTemplate`

签名：`images.matchTemplate(img: ImageRef, tpl: ImageRef | string, options?: MatchTemplateOptions): ImageMatch[]`。

返回达到阈值的匹配明细，按画面位置从上到下、从左到右排列。与 `findAllImages` 不同，结果保留每个匹配的分数和矩形边界。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 被搜索的大图 |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板句柄或相对脚本目录的路径；路径只在本次调用内读取并释放 |
| `options.threshold` | `number` | 否 | `0.9` | 最低相似度分数 |
| `options.region` | `Region` | 否 | 全图 | 搜索区域 |
| `options.max` | `number`，整数 | 否 | `5` | 最多返回的候选数；小于等于 0 返回空数组 |

返回：`ImageMatch[]`，每项包含 `x`、`y`、`score` 和 `bounds`。无匹配或参数无效时返回空数组。多个结果按位置排序，不按分数排序；要取最高分结果请用 `images.findImageDetails`。分数用于比较模板相似度，不等同于识别正确概率。

```javascript
var matches = images.matchTemplate(shot, template, { threshold: 0.88, max: 3 });
if (matches.length) {
    log("最佳分数：", matches[0].score, JSON.stringify(matches[0].bounds));
}
```

### `images.findImageDetails`

签名：`images.findImageDetails(img: ImageRef, tpl: ImageRef | string, options?: MatchTemplateOptions): ImageMatch | null`。

`images.matchTemplate` 的单结果简写，返回通过阈值的最高分匹配；无匹配时返回 `null`。

```javascript
var match = images.findImageDetails(shot, template, { threshold: 0.9 });
if (match) log(match.x, match.y, match.score, JSON.stringify(match.bounds));
```

### `images.findImageAny`

签名：`images.findImageAny(img: ImageRef, templates: TemplateEntry[], options?: FindImageAnyOptions): AnyMatch | null`。

在**一帧**图片内按数组顺序查找多个模板，首个命中立即返回。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 被搜索的大图，通常是 `captureScreen()` |
| `templates` | `TemplateEntry[]` | 是 | — | 按优先级排列；空数组返回 `null` |
| `options` | `FindImageAnyOptions` | 否 | `{}` | `threshold = 0.9`、`region = 全图` |

`TemplateEntry` 支持三种写法：

| 写法 | 示例 | 说明 |
| --- | --- | --- |
| 对象 | `{ tpl: btn, threshold: 0.88, name: "quick" }` | `tpl` 必填；`threshold`、`name` 可选 |
| 句柄 | `btn` | 使用 `options.threshold` |
| 路径 | `"images/关闭.png"` | 使用 `options.threshold`，`name` 取该路径 |

返回：`{ x, y, index, name }`，字段见 1.5 节。无效条目（句柄失效、路径读取失败）会被跳过并记 W 日志，不影响后续条目。

**数组顺序即优先级，不比较匹配分数**，用于表达“弹窗优先于背景按钮”这类业务顺序。

```javascript
var state = images.findImageAny(shot, [
    { tpl: exhausted, threshold: 0.86, name: "exhausted" },
    { tpl: defeated,  threshold: 0.88, name: "defeated" },
    { tpl: random,    threshold: 0.88, name: "random" }
]);
if (state && state.name === "exhausted") {
    log("次数耗尽，命中下标：", state.index);
}
```

### `images.findImagePair`

签名：`images.findImagePair(img: ImageRef, tplA: ImageRef | string, tplB: ImageRef | string, options?: FindImagePairOptions): PairMatch | null`。

在同一帧画面中查找两个模板，判定它们是否位于同一行，并返回两者**中心的中点**。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 待搜索的画面 |
| `tplA` | `ImageRef` 或 `string` | 是 | — | 第一个模板 |
| `tplB` | `ImageRef` 或 `string` | 是 | — | 第二个模板 |
| `options.threshold` | `number` | 否 | `0.9` | 两个模板共用阈值 |
| `options.thresholdA` | `number` | 否 | — | 单独覆盖 A 的阈值 |
| `options.thresholdB` | `number` | 否 | — | 单独覆盖 B 的阈值 |
| `options.region` | `Region` | 否 | 全图 | 两个模板共用的搜索区域 |
| `options.rowTolerance` | `number`，整数 | 否 | 自动 | 同行判定的像素容差；`<= 0` 使用自动值 |
| `options.scales` | `number[]` | 否 | `[1]` | 两个模板共用的倍率，最多 8 个，每个在 0.25～4 范围内 |

**同行判定**：两个匹配框中心的 Y 差值不超过容差。容差默认按较小模板高度的 30% 自动计算（最小 4px），大小不同的控件用同一把尺子会出错。

返回：`PairMatch`，字段见 1.5 节 `PairMatch`。

任一模板未找到、模板无效或路径读取失败时返回 `null`。两个参数是**同一份素材**（同一句柄或同一路径）时，取画面中按 X 排序的前两个互不重叠的位置配对，用于两个外观相同的控件；只有一个匹配时返回 `null`。

性能说明：A 先全区域搜索；B 优先在 A 所在的行带内搜索，找不到时回退一次全区域搜索，保证「找到了但不同行」仍能返回 `sameRow: false`。

所有找图 API（包括单模板、多模板、轮询、双模板和图文匹配）现在都按灰度边缘轮廓比较图片内容；`findColor` / `findMultiColors` 仍按颜色找点。形状匹配对明暗反转不敏感，但字体结构、笔画粗细和模板周围背景边缘仍会影响得分。`scales` 用来匹配不同尺寸，结果边界使用命中倍率的实际尺寸，区域与结果坐标仍遵循 adapter。纯色、无轮廓的模板不会命中。

```javascript
var pair = images.findImagePair(shot, loaded.images.confirmFirst, loaded.images.confirmLast, {
    scales: [0.75, 1, 1.25, 1.5],
    thresholdA: 0.8, thresholdB: 0.8, rowTolerance: 20
});
if (pair && pair.sameRow) click(pair.midX, pair.midY);
```

```javascript
var pair = images.findImagePair(shot, "images/装备.png", "images/技能.png", {
    thresholdA: 0.88, thresholdB: 0.88
});
if (!pair) {
    log("至少有一个模板没找到");
} else if (pair.sameRow) {
    log("同一行，Y 差 " + pair.rowDelta + "px（容差 " + pair.tolerance + "）");
    click(pair.midX, pair.midY);          // 两个模板之间的坐标
} else {
    log("不在同一行：Y 差 " + pair.rowDelta + "，容差 " + pair.tolerance);
}
```

### `images.findAllImages`

签名：`images.findAllImages(img: ImageRef, tpl: ImageRef | string, options?: FindAllImagesOptions): Point[]`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 被搜索的大图 |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板图片或路径 |
| `options` | `FindAllImagesOptions` | 否 | `{}` | `threshold = 0.9`、`region = 全图`、`max = 5` |

返回：相邻匹配聚合后的中心点数组，无匹配返回 `[]`。`max > 1` 时按 Y 从上到下、同 Y 按 X 从左到右排序并截取；`max = 1` 时返回最高分候选。方法名中的 All 仍受 `max` 和相邻去重限制。

```javascript
var points = images.findAllImages(img, tpl, { threshold: 0.9, max: 10 });
points.forEach(function(point) { log(point.x, point.y); });
```

### `images.existsImage`

签名：`images.existsImage(img: ImageRef, tpl: ImageRef | string, options?: FindImageOptions): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 被搜索的大图 |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板图片或路径 |
| `options` | `FindImageOptions` | 否 | `{}` | `threshold = 0.9`、`region = 全图` |

返回：存在匹配为 `true`，无匹配或无效图片为 `false`。

```javascript
if (images.existsImage(img, tpl, { threshold: 0.95 })) log("按钮存在");
```

### `images.findImageInRegion`

签名：`images.findImageInRegion(img: ImageRef, tpl: ImageRef | string, x: number, y: number, w?: number, h?: number, threshold?: number): Point | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 被搜索的大图 |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板图片或路径 |
| `x` | `number` | 是 | — | 搜索区域左上角 X，遵循 adapter |
| `y` | `number` | 是 | — | 搜索区域左上角 Y，遵循 adapter |
| `w` | `number` | 否 | `-1` | 搜索宽度；非正数延伸至图片右边缘 |
| `h` | `number` | 否 | `-1` | 搜索高度；非正数延伸至图片下边缘 |
| `threshold` | `number` | 否 | `0.9` | 匹配阈值，通常 0～1 |

返回：匹配中心或 `null`。与传 `region` 的 `images.findImage` 使用同一查找逻辑。

```javascript
var point = images.findImageInRegion(img, tpl, 0, 600, 1080, 800, 0.92);
```

### `images.findImageWithText`

签名：`images.findImageWithText(img: ImageRef, tpl: ImageRef | string, options?: FindImageWithTextOptions): ImageTextMatch | null`。先匹配图片，再识别匹配区域中的文字。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 大图 |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板图片或路径 |
| `options` | `FindImageWithTextOptions` | 否 | `{}` | `threshold = 0.9`、`region = 全图`、`padding = 0`；padding 使用实际像素，限制到 0～200 |

返回：`{ x, y, bounds, text, items }`，字段见 1.5 节；找图失败返回 `null`。找到图但 OCR 无文字/失败时，仍返回匹配对象，其 `text` 为 `""`、`items` 为 `[]`。OCR 外扩区域可能超出原找图 `region`，但会限制在整张图片内。

```javascript
var match = images.findImageWithText(img, "images/card.png", { threshold: 0.9, padding: 8 });
if (match) log(match.text);
```

### `images.findColor`

签名：`images.findColor(img: ImageRef, color: number | string, options?: FindColorOptions): Point | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 输入图片 |
| `color` | `number` 或 `string` | 是 | — | ARGB 数字，或 `"#RRGGBB"` / `"#AARRGGBB"`；不支持颜色英文名 |
| `options` | `FindColorOptions` | 否 | `{}` | `threshold = 16`，RGB 各通道允许差值；`region = 全图` |

返回：首个匹配像素的 `Point`；无匹配返回 `null`。按从上到下、每行从左到右扫描；只比较 RGB，不比较透明度。

```javascript
var point = images.findColor(img, "#ff4400", { threshold: 8 });
```

### `images.findMultiColors`

签名：`images.findMultiColors(img: ImageRef, firstColor: number | string, offsets: ColorOffset[], options?: FindColorOptions): Point | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `img` | `ImageRef` | 是 | — | 输入图片 |
| `firstColor` | `number` 或 `string` | 是 | — | 首色/主色，格式同 `findColor` |
| `offsets` | `ColorOffset[]` | 是 | — | 相对首色点需要同时满足的颜色列表；可为 `[]` |
| `offsets[i][0]` / `dx` | `number`，整数 | 是 | — | 相对首色点的 X 偏移，实际像素，可为负数 |
| `offsets[i][1]` / `dy` | `number`，整数 | 是 | — | 相对首色点的 Y 偏移，实际像素，可为负数 |
| `offsets[i][2]` / `color` | `number` 或 `string` | 是 | — | 此偏移点的期望颜色 |
| `options` | `FindColorOptions` | 否 | `{}` | `threshold = 16`，作用于所有颜色；`region = 全图`，限制主色点搜索范围 |

`ColorOffset` 格式为 `[dx, dy, color]`。返回：所有颜色同时满足时的主色点坐标，否则 `null`。偏移点可在 `region` 外，但必须在整张图片内。偏移量不随 adapter 缩放；空偏移数组等同于只查找首色。

```javascript
var point = images.findMultiColors(img, "#ff4400", [
    [10, 0, "#ffffff"],
    [0, 20, "#000000"]
], { threshold: 10, region: [0, 300, 1080, 1200] });
```

<a id="wait-image"></a>

## 8. 轮询找图

两个方法会在截图通道未就绪时先尝试申请截图。`timeout` 和 `interval` 都使用**秒**；间隔实际为 `max(50, round(interval * 1000))` 毫秒，最小 50ms。即使 `timeout = 0`，也会至少尝试检查一次。

当前内置轮询不会逐轮释放截图句柄，它们在本轮脚本结束时统一清理；高频或长时间轮询可参照第 14 节手动循环，逐次释放图片。

### `waitImage`

签名：`waitImage(tpl: ImageRef | string, timeout?: number, interval?: number, options?: FindImageOptions): Point | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板图片或路径；多次查找建议预读图片 |
| `timeout` | `number` | 否 | `10` | 最长轮询时间，秒，建议非负数 |
| `interval` | `number` | 否 | `0.5` | 两次未命中检查之间的休眠，秒 |
| `options` | `FindImageOptions` | 否 | `{}` | `threshold = 0.9`、`region = 全图` |

返回：出现时的模板中心；超时、授权失败等情况下返回 `null`。一次匹配本身所需的时间额外计入总耗时，匹配期间不由此 timeout 强制打断。

```javascript
var point = waitImage("images/button.png", 10, 0.5, { threshold: 0.92 });
if (point) click(point.x, point.y);
```

### `waitImageVanish`

签名：`waitImageVanish(tpl: ImageRef | string, timeout?: number, interval?: number, options?: FindImageOptions): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 需要等待消失的模板图片或路径 |
| `timeout` | `number` | 否 | `10` | 最长轮询时间，秒，建议非负数 |
| `interval` | `number` | 否 | `0.5` | 两次仍存在检查之间的休眠，秒 |
| `options` | `FindImageOptions` | 否 | `{}` | `threshold = 0.9`、`region = 全图` |

返回：某次检查未找到图时为 `true`；授权失败或到截止时间仍找到图时为 `false`。无效模板/截图也会导致底层查找返回 `false`，继而使本方法返回 `true`，因此 `true` 不区分“确实消失”和“本次无法找到”。需要严格确认时应先验证模板和截图有效，再手动轮询。

```javascript
var gone = waitImageVanish("images/loading.png", 15, 0.5);
log("加载图不再匹配：", gone);
```

### `waitImageClick`

签名：`waitImageClick(tpl: ImageRef | string, timeout?: number, interval?: number, options?: WaitImageClickOptions): boolean`。

轮询等待模板出现并点击其中心。每轮重新截图、重新定位、及时释放截图。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板句柄或相对脚本目录的路径 |
| `timeout` | `number` | 否 | **不限时** | 秒，规则见 1.2.1：省略/负数不限，`0` 只查一次，正数限时 |
| `interval` | `number` | 否 | `0.5` | 秒，未命中后等待，实际取 `max(50, round(interval*1000))` 毫秒 |
| `options.threshold` | `number` | 否 | `0.9` | 匹配阈值 |
| `options.region` | `Region` | 否 | 全图 | 搜索区域 |
| `options.delay` | `number`，毫秒 | 否 | `0` | 点击成功后额外等待 |
| `options.missLimit` | `number`，整数 | 否 | `0` | 连续未找到多少次后返回 `false`；`0` 不限 |

返回：点击完成返回 `true`；超时、达到 `missLimit`、模板读取失败或截图通道不可用返回 `false`。

```javascript
// 帮派宣言：点完等 3 秒，连续 3 次找不到就结束
if (!waitImageClick(declarationTask, 0, 1, {
    threshold: 0.88, delay: 3000, missLimit: 3
})) {
    exit();
}
```

### `waitImageAny`

签名：`waitImageAny(templates: TemplateEntry[], timeout?: number, interval?: number, options?: WaitImageAnyOptions): AnyMatch | null`。

`images.findImageAny` 的轮询版本：每轮重新截图，返回首个命中的模板。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `templates` | `TemplateEntry[]` | 是 | — | 同 `images.findImageAny`，数组顺序即优先级 |
| `timeout` | `number` | 否 | **不限时** | 秒，规则见 1.2.1 |
| `interval` | `number` | 否 | `0.5` | 秒 |
| `options.threshold` | `number` | 否 | `0.9` | 条目默认阈值 |
| `options.region` | `Region` | 否 | 全图 | 所有条目共用 |
| `options.missLimit` | `number`，整数 | 否 | `0` | 连续未找到多少次后返回 `null` |

返回：`{ x, y, index, name }` 或 `null`。

```javascript
// 恩仇：奖励弹窗和快速通关谁先出现是本次运行的关键分支
var hit = waitImageAny([
    { tpl: reward, threshold: 0.88, name: "reward" },
    { tpl: quick,  threshold: 0.88, name: "quick" }
], 0, 1);
if (hit && hit.name === "reward") {
    waitImageClick(claim, 0, 1, { threshold: 0.88, delay: 3000 });
}
```

### `clickImageUntilGone`

签名：`clickImageUntilGone(tpl: ImageRef | string, options?: ImageLoopOptions): WaitResult<Point>`。

反复截图定位并点击模板，连续 `confirmCount` 次有效截图未找到后返回 `reason: "gone"`。适合反复扫荡或领取，模板路径只在调用期间加载一次。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `tpl` | `ImageRef` 或 `string` | 是 | — | 模板句柄或相对脚本目录的路径 |
| `options.timeoutMs` | `number`，毫秒 | 否 | `0` | 最长等待时间；`0` 表示不限时 |
| `options.intervalMs` | `number`，毫秒 | 否 | `500` | 检查周期，最小 50ms |
| `options.confirmCount` | `number`，整数 | 否 | `3` | 连续多少次未匹配后确认已消失 |
| `options.threshold` / `options.region` | `number` / `Region` | 否 | `0.9` / 全图 | 模板阈值与搜索区域 |
| `options.maxClicks` | `number`，整数 | 否 | `0` | 点击上限；达到后以 `reason: "max_clicks"` 返回；`0` 不限 |
| `options.delayMs` | `number`，毫秒 | 否 | `0` | 每次点击后等待 |
| `options.maxAttempts` | `number`，整数 | 否 | `0` | 检查轮数上限；`0` 不限 |

成功消失返回 `ok: true`；结果中的 `clicks` 是点击成功次数。截图失败不计为未命中。超时、轮数到达上限和截图授权失败会返回对应 `reason`。

```javascript
var result = clickImageUntilGone(sweep, {
    name: "通天塔扫荡", confirmCount: 5, delayMs: 3000
});
log(result.reason, "扫荡次数：", result.clicks);
```

<a id="colors"></a>

## 9. 颜色工具

颜色数值按无符号 32 位 `0xAARRGGBB` 表示，范围为 `0`～`4294967295`；A 为透明度，RGB 为红绿蓝，每通道 0～255。字符格式使用 `#RRGGBB` 或 `#AARRGGBB`，不是 `#RRGGBBAA`。

### `colors.parseColor`

签名：`colors.parseColor(value: number | string): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `value` | `number` 或 `string` | 是 | — | ARGB 数字或 6/8 位十六进制字符串，可带 `#`；6 位补 Alpha `FF` |

返回：无符号 ARGB 数值。没有严格字符串格式校验；不应传 `red` 等颜色名或 `#RGB` 简写。无法解析的字符串可能因位运算转换得到 0，而不是抛出错误。

```javascript
var red = colors.parseColor("#ff0000"); // 4294901760，即 0xffff0000
```

### `colors.rgb`

签名：`colors.rgb(r: number, g: number, b: number): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `r` | `number`，整数 | 是 | — | 红色，0～255 |
| `g` | `number`，整数 | 是 | — | 绿色，0～255 |
| `b` | `number`，整数 | 是 | — | 蓝色，0～255 |

返回：Alpha 固定为 255 的 ARGB 数字。输入不会自动裁到 0～255，请自行保证范围。

```javascript
var orange = colors.rgb(255, 68, 0);
```

### `colors.argb`

签名：`colors.argb(a: number, r: number, g: number, b: number): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `a` | `number`，整数 | 是 | — | 透明度，0 全透明，255 不透明 |
| `r` | `number`，整数 | 是 | — | 红色，0～255 |
| `g` | `number`，整数 | 是 | — | 绿色，0～255 |
| `b` | `number`，整数 | 是 | — | 蓝色，0～255 |

返回：合成的 ARGB 数字；通道不自动限幅。

```javascript
var translucentRed = colors.argb(128, 255, 0, 0);
```

### `colors.red`

签名：`colors.red(color: number): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `color` | `number` | 是 | — | 数字 ARGB 色值；字符串先经 `parseColor` 转换 |

返回：红色通道，整数 0～255。

```javascript
log(colors.red(colors.parseColor("#ff4400"))); // 255
```

### `colors.green`

签名：`colors.green(color: number): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `color` | `number` | 是 | — | 数字 ARGB 色值 |

返回：绿色通道，整数 0～255。

```javascript
log(colors.green(colors.parseColor("#ff4400"))); // 68
```

### `colors.blue`

签名：`colors.blue(color: number): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `color` | `number` | 是 | — | 数字 ARGB 色值 |

返回：蓝色通道，整数 0～255。

```javascript
log(colors.blue(colors.parseColor("#ff4400"))); // 0
```

### `colors.alpha`

签名：`colors.alpha(color: number): number`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `color` | `number` | 是 | — | 数字 ARGB 色值 |

返回：透明度通道，整数 0～255。

```javascript
log(colors.alpha(colors.parseColor("#80ff4400"))); // 128
```

### `colors.toString`

签名：`colors.toString(color: number): string`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `color` | `number` | 是 | — | 数字 ARGB 色值 |

返回：`#` 加 8 位小写十六进制字符串，总长 9，格式 `#aarrggbb`。

```javascript
log(colors.toString(colors.rgb(255, 68, 0))); // #ffff4400
```

### `colors.isSimilar`

签名：`colors.isSimilar(c1: number | string, c2: number | string, threshold?: number, algorithm?: string): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `c1` | `number` 或 `string` | 是 | — | 第一种颜色，格式同 `parseColor` |
| `c2` | `number` 或 `string` | 是 | — | 第二种颜色 |
| `threshold` | `number` | 否 | `4` | 允许差异，使用非负数；不同算法含义见下表 |
| `algorithm` | `string` | 否 | `"diff"` | `"diff"`、`"rgb"`、`"hsv"`，区分大小写；其他值走 diff 分支 |

| 算法 | 判断规则 | Alpha |
| --- | --- | --- |
| `diff` | A、R、G、B 每个通道的绝对差均不大于 threshold；通常阈值 0～255 | 比较 |
| `rgb` | `sqrt(ΔR² + ΔG² + ΔB²) <= threshold`，最大距离约 441.67 | 忽略 |
| `hsv` | 色相最短环形距离除以 360、饱和度差、明度差，分别乘 255；三项均不大于 threshold | 忽略 |

返回：满足所选规则为 `true`，否则 `false`。这里的默认阈值为 4，找色方法的默认阈值则为 16。

```javascript
log(colors.isSimilar("#ff0000", "#ff0101", 4, "diff"));
```

<a id="ocr"></a>

## 10. OCR 文字识别

当前内置 MLKit 中文识别模型，可识别图片中的中文及英文等文字。第一次识别会自动初始化，无需每次 `ocr.init()`。结果按模型的文本块和行顺序返回，不额外按屏幕位置重新排序。

每个识别调用独立执行 OCR，不复用上次识别结果。原生识别等待上限为 20 秒；初始化、识别失败或没有文字时通常返回空数组、空字符串或 `null`，具体见各方法，三种情况不一定能从返回值区分。

### `ocr.init`

签名：`ocr.init(engine?: string): boolean`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `engine` | `string` | 否 | `"mlkit"` | 当前实际只有 MLKit；传 `"paddle"` 会提示并回退至 MLKit，其他值也不会加载其他引擎 |

返回：初始化成功为 `true`，失败为 `false`。并不执行图片识别。

```javascript
log("OCR 初始化：", ocr.init());
```

### `ocr.ready`

签名：`ocr.ready(): boolean`。参数：无。

返回：OCR 引擎是否已经初始化。不表示截图授权状态，也不表示某张图片一定可识别。

```javascript
if (!ocr.ready()) ocr.init();
```

### `ocr.recognize`

签名：`ocr.recognize(image: ImageRef, options?: OcrOptions): OcrItem[]`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `image` | `ImageRef` | 是 | — | 截图或读入的图片，不直接接受路径 |
| `options` | `OcrOptions` | 否 | `{}` | 仅支持 `region`，默认全图 |

返回：每行一项的 `OcrItem[]`，包含原始文字和整行边界；无结果/失败为 `[]`。裁剪区域的偏移已加回输入图片坐标，再应用 adapter。

```javascript
var lines = ocr.recognize(img, { region: [0, 0, 1080, 800] });
lines.forEach(function(line) { log(line.text, line.centerX(), line.centerY()); });
```

### `ocr.recognizeText`

签名：`ocr.recognizeText(image: ImageRef, options?: OcrOptions): string`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `image` | `ImageRef` | 是 | — | 输入图片 |
| `options` | `OcrOptions` | 否 | `{}` | `region = 全图` |

返回：原始识别行按 `\n` 拼接的字符串；无结果/失败返回 `""`。不自动删除空格、符号或换行，也不返回坐标。

```javascript
var text = ocr.recognizeText(img);
log(text);
```

### `ocr.findText`

签名：`ocr.findText(image: ImageRef, text: string, options?: OcrOptions): OcrItem | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `image` | `ImageRef` | 是 | — | 输入图片 |
| `text` | `string` | 是 | — | 查询文字；会去掉首尾空白，保留中间空白和符号 |
| `options` | `OcrOptions` | 否 | `{}` | `region = 全图` |

返回：首个匹配行，或 `null`。先在全部 OCR 行中找忽略大小写、首尾空白后的精确相等项；存在精确项时只在精确项中选首个，否则退化为忽略大小写的包含查找。空查询不匹配；不是正则匹配，也不校正错别字。

```javascript
var found = ocr.findText(img, "立即购买");
if (found) click(found.centerX(), found.centerY());
```

### `ocr.findTextContains`

签名：`ocr.findTextContains(image: ImageRef, text: string, options?: OcrOptions): OcrItem | null`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `image` | `ImageRef` | 是 | — | 输入图片 |
| `text` | `string` | 是 | — | 查询文字；与识别文字同时进行兼容规范化及字符清理 |
| `options` | `OcrOptions` | 否 | `{}` | 仅支持 `region`，默认全图 |

处理步骤：对两侧文字进行 Unicode NFKC 规范化（包含全角转半角），只保留 Unicode 字母和数字类别（含中文），再忽略大小写判断包含关系。空格、换行、标点、符号、Emoji 及不可见字符等非字母数字字符会被删除。

返回：按 OCR 顺序找到的首个匹配行，保留其**原始** `text`、边界及置信度，不优先选择后面出现的精确相等行。未匹配、图片无效、查询清理后为空时返回 `null`；省略查询或传 `null` 也返回 `null`。

| 识别行 | 查询 | 结果 |
| --- | --- | --- |
| `限时【立 即·购 买】` | `立即购买` | 命中 |
| `ＶＩＰ－１２３` | `vip123` | 命中 |
| `价格：9.90元` | `990` | 命中，符号被删除 |
| `立即购买` | `★！` | 不命中，查询清理后为空 |
| 两个独立 OCR 行分别为 `立即`、`购买` | `立即购买` | 不命中，不跨行拼接 |

方法不能修正 OCR 识别成的其他字，也不对命中的子串单独定位；返回和点击坐标都是整行边界及中心。

```javascript
var found = ocr.findTextContains(img, "立即购买", { region: [0, 500, 1080, 900] });
if (found) {
    log("原始文字：", found.text);
    click(found.centerX(), found.centerY());
}
```

### `ocr.findAllText`

签名：`ocr.findAllText(image: ImageRef, text: string, options?: OcrOptions): OcrItem[]`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `image` | `ImageRef` | 是 | — | 输入图片 |
| `text` | `string` | 是 | — | 查询文字，首尾去空白，匹配忽略大小写 |
| `options` | `OcrOptions` | 否 | `{}` | `region = 全图` |

返回：全部精确匹配行；**只有没有任何精确匹配行时**才返回全部包含匹配行。无结果/空查询返回 `[]`。仍保留内部空格和符号，不是 `findTextContains` 的批量版本，不提供 `max` 参数。

```javascript
var matches = ocr.findAllText(img, "领取");
log("匹配数量：", matches.length);
```

### `ocr.findTextAny`

签名：`ocr.findTextAny(image: ImageRef, texts: string[], options?: OcrOptions): OcrItem | null`。

在同一张输入图片中判断每行是否包含关键词数组中的任意一个；设置 `regions` 时按顺序检查区域，命中即返回。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `image` | `ImageRef` | 是 | — | 输入图片，不直接接受路径 |
| `texts` | `string[]` | 是 | — | 关键词数组；行顺序优先，同一行内取数组顺序的第一个命中词 |
| `options` | `OcrOptions` | 否 | `{}` | 可指定一个 `region`，或以 `regions` 按顺序指定多个区域 |

返回：在 `OcrItem` 基础上多一个 `matched` 字段，表示实际命中的关键词**原文**；设置 `regions` 时还会带 `regionIndex`。匹配规则与 `ocr.findTextContains` 完全一致：NFKC 规范化、只保留 Unicode 字母和数字、忽略大小写、逐行包含匹配、不跨行拼接。每个区域各运行一次 OCR，但共用传入的同一图片，不会重复截图。

清理后为空的关键词会被忽略；关键词数组为空或全部清理后为空时返回 `null`，**且不触发 OCR**。

```javascript
var line = ocr.findTextAny(shot, ["确定", "同意"], { region: [1702, 425, 192, 92] });
if (line) {
    log("命中：", line.matched, "原文：", line.text);
    click(line.centerX(), line.centerY());
}
```

多个区域按优先级依次检查，返回命中区域在数组中的下标：

```javascript
var line = ocr.findTextAny(shot, ["确定", "同意"], {
    regions: [[1702, 425, 192, 92], [601, 570, 324, 130]]
});
if (line) log("命中区域：", line.regionIndex, line.text);
```

### `waitText` / `waitTextAny`

签名：`waitText(text: string, options?: ScreenWaitOptions & OcrOptions): WaitResult<OcrItem>`、`waitTextAny(texts: string[], options?: ScreenWaitOptions & OcrOptions): WaitResult<OcrItem>`。

轮询截图与 OCR，出现匹配文字后返回结构化结果；不自动点击。两者均支持 `region` 与 `regions`，其中多区域按顺序检查。

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `text` / `texts` | `string` / `string[]` | — | 单个或多个关键词，匹配规则同 `ocr.findTextAny` |
| `options.timeoutMs` | `number`，毫秒 | `0` | `0` 表示不限时 |
| `options.intervalMs` | `number`，毫秒 | `500` | 轮询周期，最小 50ms |
| `options.confirmCount` | `number`，整数 | `1` | 连续识别到多少次后才确认；截图或识别失败打断确认 |
| `options.missLimit` | `number`，整数 | `0` | 连续成功 OCR 但无匹配的上限；`0` 不限 |
| `options.maxAttempts` | `number`，整数 | `0` | 轮询次数上限；`0` 不限 |
| `options.region` / `options.regions` | `Region` / `Region[]` | 全图 | 限制 OCR 范围；两个字段不能同时指定 |

返回的 `WaitResult.value` 为 `OcrItem`。`reason` 为 `matched`、`timeout`、`miss_limit`、`attempt_limit`、`capture_error`、`ocr_error` 或 `invalid_argument`；失败时查看 `lastError`。

```javascript
var result = waitTextAny(["确定", "同意"], {
    regions: regions, timeoutMs: 10000, intervalMs: 1000, confirmCount: 2
});
if (result.ok) click(result.value.centerX(), result.value.centerY());
```

### `watchText`

签名：`watchText(texts: string[], options?: ScreenWaitOptions & OcrOptions & WatchOptions): WaitResult<OcrItem>`。

持续轮询 OCR，匹配后点击文字整行中心，然后继续监听。默认无限运行，用户停止任务时退出；`maxClicks`、`missLimit`、`maxAttempts` 或 `timeoutMs` 可设定结束条件。每次只点击当前命中的一行，下一轮会重新截图。

```javascript
var result = watchText(["确定", "同意"], {
    regions: regions, intervalMs: 500, delayMs: 300, maxClicks: 10
});
log("监听结束：", result.reason, "点击次数：", result.clicks);
```

### `waitTextClick`

签名：`waitTextClick(texts: string[], timeout?: number, interval?: number, options?: WaitTextClickOptions): boolean`。

轮询截图 → OCR → 命中任意关键词后点击整行中心。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `texts` | `string[]` | 是 | — | 关键词数组，规则同 `ocr.findTextAny` |
| `timeout` | `number` | 否 | **不限时** | 秒，规则见 1.2.1 |
| `interval` | `number` | 否 | `1` | 秒，目标轮询周期；从本轮开始计时补足，OCR 超过 1 秒则立即进入下一轮 |
| `options.region` | `Region` | 否 | 全图 | OCR 区域 |
| `options.delay` | `number`，毫秒 | 否 | `0` | 点击成功后额外等待 |
| `options.missLimit` | `number`，整数 | 否 | `0` | 连续未找到多少次后返回 `false`；`0` 不限 |

返回：点击成功返回 `true`；超时、达到 `missLimit`、OCR 初始化失败或截图不可用返回 `false`。

截图失败或识别到文字但没有命中关键词之外的空白结果不累加“连续未找到”计数；点击位置是整行中心，命中子串不单独定位。

```javascript
// 击败它们：常驻监听，OCR 命中即点
waitTextClick(["击败它们"], 0, 1, {
    region: [37, 593, 801, 160], delay: 1000
});
```

### `OcrItem.centerX`

签名：`item.centerX(): number`。参数：无，须通过某个非空 `OcrItem` 调用。

返回：`Math.round((item.left + item.right) / 2)`，即当前坐标系中的整行中心 X。方法依赖 `this`，不要脱离 item 单独调用。

```javascript
log(item.centerX());
```

### `OcrItem.centerY`

签名：`item.centerY(): number`。参数：无，须通过某个非空 `OcrItem` 调用。

返回：`Math.round((item.top + item.bottom) / 2)`，即当前坐标系中的整行中心 Y。

```javascript
log(item.centerY());
```

<a id="polling"></a>

## 通用等待与整组重试

新轮询接口的 `timeoutMs`、`intervalMs`、`delayMs` 均使用毫秒；`timeoutMs: 0` 表示不限时，次数限制通过 `maxAttempts`、`missLimit` 设置。等待会在脚本停止时立即退出。图片句柄只在截图检查期间有效；`waitScreen` 回调应返回普通数据，不应返回当前截图对象。

### `waitScreen`

签名：`waitScreen<T>(check: (image: ImageRef, state: PollState) => T | null | false | undefined, options?: ScreenWaitOptions): WaitResult<T>`。

每轮获取一张截图并传给同步 `check` 函数，自动释放截图。回调返回真值表示命中，返回 `false`、`null` 或 `undefined` 表示继续等待。适合组合检查多张图片或实现业务自己的跨帧状态判断。

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `check` | 同步函数 | — | 接收截图和本轮状态，返回命中值或未命中 |
| `options.timeoutMs` | `number`，毫秒 | `0` | 超时；`0` 表示不限时 |
| `options.intervalMs` | `number`，毫秒 | `500` | 检查周期，最小 50ms |
| `options.confirmCount` | `number`，整数 | `1` | 连续命中次数；截图失败打断连续确认 |
| `options.missLimit` | `number`，整数 | `0` | 连续有效截图未命中的次数上限；`0` 不限 |
| `options.maxAttempts` | `number`，整数 | `0` | 截图检查次数上限；`0` 不限，至少检查一次 |
| `options.name` | `string` | `""` | 附在结果及回调状态中的步骤名称 |
| `options.onRetry` | 同步函数 | 未设置 | 每轮继续等待时调用，参数为原因与 `PollState` |

返回 `WaitResult<T>`。`reason` 可能是 `matched`、`timeout`、`miss_limit`、`attempt_limit` 或 `capture_error`；授权失败会立即返回 `capture_error`。`onRetry` 的原因包括 `miss`、`capture_error` 和 `confirming`。

```javascript
var state = waitScreen(function(shot) {
    var exhausted = images.findImage(shot, exhaustedTemplate, { threshold: 0.86 });
    if (exhausted) return { name: "exhausted", point: exhausted };
    var random = images.findImage(shot, randomTemplate, { threshold: 0.88 });
    return random ? { name: "random", point: random } : null;
}, { timeoutMs: 30000, intervalMs: 500, confirmCount: 2, name: "竞技场状态" });
```

### `retry`

签名：`retry<T>(action: (attempt: number, state: PollState) => T | null | false | undefined, options?: RetryOptions): WaitResult<T>`。

按顺序重复执行一个同步动作。动作返回真值即视为成功；返回 `false`、`null` 或 `undefined` 时等待后重试。适合重试“打开地图 → 前往地点 → 点击目标”等由脚本函数组成的一整步。抛出的异常会直接交给脚本运行器处理。

`options.maxAttempts` 默认 `3`，包含首次执行；`0` 表示不限次数。`timeoutMs` 默认 `0`（不限时），`intervalMs` 默认 `500` 毫秒。返回的 `reason` 为 `completed`、`attempt_limit` 或 `timeout`。

```javascript
var result = retry(function(attempt) {
    log("第 " + attempt + " 次尝试导航");
    return click(1777, 24) && click(1103, 760);
}, { maxAttempts: 5, intervalMs: 1000, name: "前往临安" });
```

<a id="adapter"></a>

## 11. 分辨率与坐标适配

宿主会在任务启动时设置真实屏幕尺寸。若任务 `config.json` 含有效 `designSize`，会自动启用该设计尺寸；脚本还可以用下列方法覆盖或关闭。

换算公式：`realX = designX * screenWidth / designWidth`，Y 同理。X、Y 独立缩放，不补偿状态栏偏移、居中留白或目标控件布局变化。模板图片本身不会随 adapter 缩放。

### `adapter.setDesignSize`

签名：`adapter.setDesignSize(w: number, h: number): void`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `w` | `number`，整数 | 是 | — | 编写脚本时的基准屏幕宽度，需大于 0 |
| `h` | `number`，整数 | 是 | — | 编写脚本时的基准屏幕高度，需大于 0 |

返回：无可用返回值。任一设计尺寸不大于 0 时不启用缩放；还需宿主提供有效的真实尺寸。设置持续到再次修改/重置或整个任务结束。同一任务配置的多轮执行不会在每轮开始时重新应用 designSize，建议各轮脚本明确设置需要的坐标系。

```javascript
adapter.setDesignSize(1080, 1920);
click(540, 960); // 点击按设计分辨率定义的屏幕中心
```

### `adapter.reset`

签名：`adapter.reset(): void`。参数：无。

返回：无可用返回值。关闭设计坐标缩放，此后按实际图片/屏幕像素操作。

```javascript
adapter.reset();
```

### `adapter.enabled`

签名：`adapter.enabled(): boolean`。参数：无。

返回：设计宽高及真实屏幕宽高都有效且当前启用适配时为 `true`。

```javascript
log("坐标适配已启用：", adapter.enabled());
```

### `adapter.scale`

签名：`adapter.scale(x: number, y: number): [number, number]`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `x` | `number` | 是 | — | 设计 X 坐标，按整数处理 |
| `y` | `number` | 是 | — | 设计 Y 坐标，按整数处理 |

返回：换算后的实际像素坐标数组 `[realX, realY]`，不是 `{ x, y }`。未启用适配时返回输入的整数坐标。适配开启时，`click` 本身会再做换算，不要把 `adapter.scale()` 的结果再次直接传给 `click`；该方法适合向不自动适配的 `images.pixel` 等接口传坐标。

```javascript
var real = adapter.scale(540, 960);
var color = images.pixel(img, real[0], real[1]);
```

<a id="modules"></a>

## 12. 模块引用与脚本路径

### `require`

签名：`require(name: string): object`。

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | `string` | 是 | — | 仅接受 `"images"`、`"colors"`、`"ocr"`、`"adapter"`，区分大小写 |

返回：对应的全局模块对象，与直接使用该全局变量相同。其他名称会抛出 `Error("模块未实现: ...")`。不支持加载本地 JS 文件、npm 包、`fs` 或 `http` 等 Node 模块。

```javascript
var textRecognition = require("ocr");
log(textRecognition === ocr); // true
```

### `__scriptPath__`（变量）

类型：`string`，值为当前入口脚本 `task.js` 的绝对路径。由引擎初始化，脚本应只读使用。图片相对路径的解析基于该脚本原始所在目录，修改这个 JS 变量不会改变图片读写目录。

```javascript
log("当前脚本：", __scriptPath__);
```

<a id="config"></a>

## 13. 任务配置文件

每个任务目录至少包含 `task.js`，可以附带 `config.json` 和 `images/`。配置由宿主读取，不会自动注入名为 `config` 的 JavaScript 变量。

```json
{
  "name": "OCR 查找示例",
  "desc": "识别文字并点击",
  "target": "",
  "designSize": [1080, 1920],
  "loop": {
    "count": 1,
    "interval": 0
  }
}
```

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | `string` | 否 | 任务目录名 | 配置中的任务名称 |
| `desc` | `string` | 否 | `""` | 任务描述 |
| `target` | `string` | 否 | 无 | 目标 Android 包名；非空时每轮脚本前尝试启动该应用，空字符串不自动启动 |
| `designSize` | `[number, number]` | 否 | 不启用 | 设计基准宽高，正整数；缺失、`null` 或格式不正确时不启用 |
| `designSize[0]` | `number`，整数 | 条件必填 | `0` | 基准宽度，需大于 0 才有效 |
| `designSize[1]` | `number`，整数 | 条件必填 | `0` | 基准高度，需大于 0 才有效 |
| `loop` | `object` | 否 | `{}` | 多轮执行设置 |
| `loop.count` | `number`，整数 | 否 | `1` | 执行总轮数，最小为 1；0 不表示无限循环 |
| `loop.interval` | `number`，整数 | 否 | `0` | 两轮之间的间隔秒数，最小为 0 |

每轮建立新的 JS 上下文，普通脚本变量不跨轮保留；该轮结束时清理图片句柄。正常结束和 `exit()` 均可继续下一轮；脚本出错或收到停止请求时结束多轮执行。配置文件不存在或解析失败时采用默认配置。

<a id="examples"></a>

## 14. 完整组合示例

示例中的文字、坐标和模板需按目标界面调整。不要把多个独立示例原样拼接后执行。

### 14.1 OCR 忽略空格和符号后查找并点击

```javascript
// 以设备实际像素工作，避免继承任务配置中的设计坐标。
adapter.reset();

if (!auto.waitFor(5000)) {
    toastLog("请先开启无障碍服务");
    exit();
}
if (!requestScreenCapture()) {
    toastLog("没有可用的截图通道");
    exit();
}

var shot = captureScreen();
if (!shot) {
    toastLog("暂时无法获取截图，请重试");
    exit();
}

try {
    // 整屏查找。若已知按钮范围，可传 { region: [x, y, w, h] }。
    var found = ocr.findTextContains(shot, "立即购买");
    if (found) {
        log("识别原文：", found.text);
        log("边界：", JSON.stringify(found.bounds));
        if (!click(found.centerX(), found.centerY())) {
            console.warn("点击未完成");
        }
    } else {
        log("没有识别到目标文字");
    }
} finally {
    images.release(shot);
}
```

### 14.2 预加载模板并及时释放每次截图

准备包含目标内容轮廓的 `images/button.png`。该示例不会把截图失败误认为目标消失，并且每次只保留当前截图；需要适配其他字号时可添加 `scales`。

```javascript
adapter.reset();
if (!auto.waitFor(5000)) exit();
if (!requestScreenCapture()) exit();

var template = images.read("images/button.png");
if (!template) {
    console.error("找不到模板 images/button.png");
    exit();
}

var deadline = Date.now() + 10000;
var found = null;
try {
    while (Date.now() < deadline) {
        var shot = captureScreen();
        if (shot) {
            try {
                found = images.findImage(shot, template, { threshold: 0.92 });
            } finally {
                images.release(shot);
            }
            if (found) break;
        }
        sleep(500);
    }
    if (found) {
        log("点击结果：", click(found.x, found.y));
    } else {
        console.warn("等待按钮超时");
    }
} finally {
    images.release(template);
}
```

<a id="limits"></a>

## 15. 支持边界与常见误用

### 15.1 JavaScript 环境

脚本通过 QuickJS 运行。可使用引擎提供的标准 JavaScript 语法及对象，例如变量、函数、条件/循环、数组与字符串操作、`Math`、`Date`、`JSON`、`RegExp`、`Promise`。这些语言标准库的方法不属于本应用的 81 个脚本 API。

当前应用没有注入浏览器 DOM、`window`、`document`、`fetch`、`XMLHttpRequest`、Node.js `process` / `Buffer` / 文件系统、`setTimeout` / `setInterval`、Auto.js 的控件选择器、`app.launchPackage`、通用 `shell` 等能力。目标应用启动可使用任务的 `target` 配置；Root 兜底属于内部实现，不代表脚本可以调用任意 shell 命令。

只有本文列出的 `console` 方法已提供；没有 `console.debug`、`console.clear`、`console.show` 等方法。只有本文列出的 `require` 模块名可用。不要将其他自动化框架的同名 API 文档直接套用到本应用。

### 15.2 停止与运行生命周期

停止入口会先请求中断 QuickJS 运行时，再取消引擎协程：纯 JavaScript 忙循环（例如 `while (true) {}`）也会被立即打断，不必等到某个 API 调用返回。已经进入的 OCR、手势、截屏等原生操作无法从中间撤回，需要等它返回；返回后不会继续执行脚本的下一步。已派发给系统的触摸操作同样不能撤回。

`sleep` 和部分图像计算另有周期性中断检查，停止响应通常在几十毫秒内完成。脚本本身不需要为停止编写额外配合代码。

`exit()` 退出当前轮；要结束整个正在运行的多轮任务，使用应用提供的停止入口。调用 `console.error()` 只写日志，既不抛错也不退出。

### 15.3 常见差异速查

| 容易误解的用法 | 当前实际行为 |
| --- | --- |
| `auto.waitFor()` 会一直等 | 默认超时为 0，立即检查 |
| `waitImage(tpl, 1000)` 等 1 秒 | 第二个参数是秒，1 秒应传 `1` |
| `requestScreenCapture(true)` 会转横屏 | 当前忽略 landscape 参数 |
| `adapter.scale()` 返回 Point 对象 | 返回 `[x, y]` 数组 |
| 所有图片坐标都会随 adapter 缩放 | `pixel`、`clip`、图片宽高、颜色偏移、OCR padding 使用实际像素 |
| `images.findImage()` 返回左上角 | 返回模板中心 |
| `images.findAllImages()` 不限数量 | 默认最多 5 个，且会聚合相邻命中 |
| 模板会随 designSize 自动放大缩小 | 模板像素尺寸不变 |
| `image.recycle()` 能释放图片 | 应调用 `images.release(image)` |
| `ocr.findAllText()` 一定返回所有包含项 | 有精确项时只返回精确项 |
| `findTextContains()` 返回清理后的字符串 | 返回原始 `OcrItem`；清理仅用于匹配 |
| `findTextContains()` 能跨两行匹配 | 每行独立匹配，不拼接不同 OCR 行 |
| 命中文字子串就能精确点击那个词 | 返回的是整行中心 |
| `waitImageVanish()` 的 true 证明截图正常且图已消失 | 图片无效等失败也可能导致 true |
| `waitImageClick()` 省略 timeout 会超时失败 | 省略或负数表示不限时，一直等到手动停止 |
| 新等待接口的 timeout 用毫秒 | `waitImageClick` / `waitImageAny` / `waitTextClick` 的 timeout 是秒，正数才限时 |
| `timeout: 0` 表示不限时 | `0` 表示只检查一次；不限时要省略或传负数 |
| `images.findImageAny()` 返回匹配分最高的那个 | 返回**数组顺序**中第一个命中的条目，不比较分数 |
| `waitImageClick()` 找到图就一定返回 true | 点击通道失败仍返回 false，但不会推进步骤 |
| `images.readAll()` 失败时抛错 | 不抛错，失败项在返回值的 `missing` 里，`images` 对应位置为 null |
| `images.releaseAll()` 重复传同一句柄会重复计数 | 按内部 id 去重，同一句柄只算一次 |
| `ocr.findTextAny()` 返回清理后的关键词 | `matched` 是命中的原始关键词，`text` 是原始整行文字 |
| `colors.isSimilar` 默认与找色一样严格 | 默认阈值为 4，找色默认阈值为 16；默认 diff 还会比较 Alpha |
| 为 OCR options 设置 threshold 能过滤置信度 | OCR 只读取 region；可自己对 recognize 的结果按 confidence 过滤 |

### 15.4 维护与源码对应

| 内容 | 实现来源 |
| --- | --- |
| 对外方法、JS 默认参数与返回值组装 | [`ScriptEngine.kt`](../app/src/main/java/com/forest/android/assistant/core/engine/ScriptEngine.kt) |
| 手势和 Root 兜底 | [`GestureClient.kt`](../app/src/main/java/com/forest/android/assistant/core/gesture/GestureClient.kt)、[`InputFallback.kt`](../app/src/main/java/com/forest/android/assistant/core/gesture/InputFallback.kt) |
| 图片、找色及匹配 | [`ImageApi.kt`](../app/src/main/java/com/forest/android/assistant/core/image/ImageApi.kt)、[`TemplateMatcher.kt`](../app/src/main/java/com/forest/android/assistant/core/image/TemplateMatcher.kt)、[`ColorMatcher.kt`](../app/src/main/java/com/forest/android/assistant/core/image/ColorMatcher.kt) |
| OCR 与宽松包含匹配 | [`OcrApi.kt`](../app/src/main/java/com/forest/android/assistant/core/ocr/OcrApi.kt)、[`OcrTextMatcher.kt`](../app/src/main/java/com/forest/android/assistant/core/ocr/OcrTextMatcher.kt) |
| 坐标换算 | [`ScriptAdapter.kt`](../app/src/main/java/com/forest/android/assistant/core/engine/ScriptAdapter.kt) |
| 任务循环、权限和截图回调 | [`ScriptRunner.kt`](../app/src/main/java/com/forest/android/assistant/core/engine/ScriptRunner.kt) |
| 配置读取 | [`TaskManager.kt`](../app/src/main/java/com/forest/android/assistant/core/task/TaskManager.kt) |

维护此文档时应同时核对原生绑定和 JS 包装层。新增公开方法需添加独立条目，并列出参数类型、是否必填、默认值、单位、返回值和示例；仅修改编辑器补全列表不足以保证文档覆盖完整。
