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

首次使用时按需启用无障碍服务，并授权屏幕捕获。图片匹配和 OCR 需要截图权限；点击和滑动需要无障碍权限。

应用在「任务」中创建或导入脚本。脚本图片路径相对于任务目录。

任务页提供单任务 ZIP 导入和全部任务导出。导出时可在系统文件选择器中指定保存位置；全部任务 ZIP 以 `tasks/<任务名>/` 保存脚本、配置和素材。导入时可选择旧版单任务 ZIP 或包含 `tasks/` 目录的合集 ZIP。同名任务会跳过，不会覆盖。单个任务也可从任务菜单导出 ZIP。区域截图保存时也可在系统文件选择器中指定 PNG 保存位置。

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

## 发布

推送 Git tag 会自动构建 debug 与 release 两个 APK 并创建 GitHub Release，APK 文件名和制品名包含 tag：

- `<tag>-debug.apk`：debug 构建，使用 debug 签名。不同 CI 运行使用各自生成的签名密钥，覆盖安装前可能需要先卸载旧版本。
- `<tag>-release.apk`：release 构建（当前未开启代码混淆）。

release 包在仓库配置了 `RELEASE_KEYSTORE_PATH`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 这四个 secret/环境变量时使用正式签名，否则回退到 debug 签名以保证流水线始终产出可安装的 APK。本地同样可以配置这些环境变量后用 `gradle assembleRelease` 打正式签名包。

## 相关文档

- [设备认证（ECDSA 许可证）设计方案](device-auth-ecdsa.md)：一机一证的设备码、许可证格式、签发与校验流程。
- [性能优化方案与行为一致性验收](performance-optimization-plan.md)。
