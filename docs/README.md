# 雕雕助手

Android 自动化脚本工具，包名：`com.forest.android.assistant`。

## 功能

- 通过无障碍服务执行点击、滑动和手势。
- 使用截图进行图片匹配、找色和 OCR。
- 编写、运行和管理 JavaScript 自动化任务。
- 导入图片素材；支持单任务 ZIP 与多任务合集 ZIP 导入导出。

## 构建

需要 JDK 17 和 Gradle 9.6.1：

```bash
gradle assembleDebug
gradle assembleRelease
```

APK：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release.apk`。

## 使用

脚本开发请查阅[完整脚本 API 参考](script-api.md)：覆盖当前 63 个公开方法，以及参数类型、默认值、时间与坐标单位、返回值、任务配置和完整示例。

首次使用时按需启用无障碍服务，并授权屏幕捕获。图片匹配和 OCR 需要截图权限；点击和滑动需要无障碍权限。

应用在「任务」中创建或导入脚本。脚本图片路径相对于任务目录。

在「工具」页或悬浮菜单中打开「选择范围」，按住并拖动框选屏幕区域，点击「生成代码」即可复制 `var region = [x, y, width, height];`，结果也会写入运行日志。坐标为屏幕像素，`x/y` 是左上角位置，`width/height` 是宽高；可通过 `{ region: region }` 用于找图、找色和 OCR。支持反向拖动、重选和取消，只需悬浮窗权限。

任务页提供单任务 ZIP 导入和全部任务导出。导出时可在系统文件选择器中指定保存位置；全部任务 ZIP 以 `tasks/<任务名>/` 保存脚本、配置和素材。导入时可选择旧版单任务 ZIP 或包含 `tasks/` 目录的合集 ZIP。同名任务会跳过，不会覆盖。单个任务也可从任务菜单导出 ZIP。区域截图保存时也可在系统文件选择器中指定 PNG 保存位置。

### OCR 宽松包含查找

`ocr.findTextContains(image, text, options?)` 会将识别文字与查询文字统一为兼容形式（包括全角转半角），去除空格、换行、标点、符号及不可见字符，只保留 Unicode 字母（含中文）和数字，再忽略大小写做包含匹配。例如，识别到 `限时【立 即·购 买】` 时，查询 `立即购买` 可以命中。

```javascript
if (!requestScreenCapture()) exit();
var image = captureScreen();
try {
    var found = ocr.findTextContains(image, "立即购买", {
        region: [0, 0, 1080, 1920]
    });
    if (found) {
        log(found.text); // 原始识别文字，未被清理
        click(found.centerX(), found.centerY());
    }
} finally {
    images.release(image);
}
```

`options` 可省略；`region` 为 `[x, y, width, height]`，请按目标屏幕或 `adapter.setDesignSize` 的设计坐标调整。返回识别顺序中的首个匹配行 `OcrItem`（包含原始文字、边界、置信度和中心点方法），没有匹配或查询清理后为空时返回 `null`。每行独立匹配，不跨 OCR 行拼接；点击位置是整行中心。符号本身不参与匹配，例如 `9.90` 和 `990` 会视为相同文字。需要保留符号或精确匹配优先时继续使用 `ocr.findText` / `ocr.findAllText`，其行为不变。

在「任务 → 编辑脚本」顶部点击 **「示例」**，无需先输入代码，即可选择 OCR 忽略空格找字、OCR 读取屏幕文字、找图等待并点击、基础点击与滑动等完整脚本。预览后选择「光标插入」或「替换全文」；替换已有内容会先确认，且必须点击顶部「保存」才会写入文件。也可以切换到「API 片段」，按名称或功能搜索并插入单个方法示例。插入时如有选中文字，会替换选中部分。

编辑器输入 `ocr.` 或 `findTextC` 仍可补全新方法。点击候选插入片段，长按任意 API 候选查看签名、说明和示例。编辑器导出的 `script-js.d.ts` 也包含 OCR 参数、区域和返回值类型。

### 手动打包任务

可以先把一个或多个任务整理到同一个目录中，每个任务一个子目录，至少包含 `task.js`，并按需放入 `config.json` 和 `images/` 素材：

```text
tasks/
├── 任务甲/
│   ├── task.js
│   ├── config.json
│   └── images/
└── 任务乙/
    ├── task.js
    └── images/
```

使用仓库里的 Node.js 工具生成 APP 可导入的合集 ZIP。工具不需要安装第三方依赖：

```bash
node tools/package-tasks.js "C:\Users\Administrator\Desktop\tasks" "build\我的任务.zip"
```

任务目录参数必填，输出 ZIP 路径可以省略；省略时生成 `build/tasks-bundle.zip`。压缩包会使用 `tasks/<任务名>/...` 路径，并将中文文件名按 UTF-8 编码，避免 Windows 某些压缩工具写入 GBK 文件名后导致 APP 无法导入。生成 ZIP 后，在 APP「任务」页点击导入图标并选择该文件即可。

## 签名

正式签名密钥不入库（仓库公开）。CI 从 secrets 读取：

- `RELEASE_KEYSTORE_BASE64`：PKCS12 密钥库的 Base64 内容
- `RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`

workflow 解码出密钥库文件后传入 Gradle；本地无这些变量时 release 包回退到 debug 签名。本地打正式签名包：

```bash
RELEASE_KEYSTORE_PATH=/path/assistant-release.jks \
RELEASE_KEYSTORE_PASSWORD=... RELEASE_KEY_ALIAS=assistant RELEASE_KEY_PASSWORD=... \
  gradle assembleRelease
```

发布证书 SHA-256 指纹（`*-release.apk` 应为此值）：

```
F7:7A:A4:98:D5:8C:F2:29:68:5C:33:59:44:42:3A:CF:BD:E3:11:20:09:1D:C1:F3:6A:6C:02:3F:C9:7A:22:20
```

## 发布

推送 Git tag 会自动构建 debug 与 release 两个 APK 并创建 GitHub Release，APK 文件名和制品名包含 tag：

- `<tag>-debug.apk`：debug 构建，使用 debug 签名。不同 CI 运行使用各自生成的签名密钥，覆盖安装前可能需要先卸载旧版本。
- `<tag>-release.apk`：release 构建（当前未开启代码混淆），使用正式签名密钥。两者签名不同，互相覆盖安装前需先卸载。

## 相关文档

- [完整脚本 API 参考](script-api.md)：全部脚本方法及参数说明。
- [设备认证（ECDSA 许可证）设计方案](device-auth-ecdsa.md)：一机一证的设备码、许可证格式、签发与校验流程。
- [性能优化方案与行为一致性验收](performance-optimization-plan.md)。
