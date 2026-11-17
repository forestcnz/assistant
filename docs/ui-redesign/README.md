# 雕雕助手 APP 全页面 UI 设计稿

直接打开 [完整 HTML 设计稿](../app-ui-redesign.html)。文件已经内嵌样式、JavaScript、图标和现有品牌 SVG，无需安装依赖、启动服务或连接网络。

## 如何审阅

- **界面总览**：按九个功能域浏览 53 个页面／浮层，支持按名称搜索和分类筛选。
- **交互预览**：点击任意画板进入；在手机中点击按钮、输入表单、切换选项，右侧可查看设计说明、关联页面和状态。
- **设计规范**：查看颜色、排版、组件、导航结构与主要用户路径。
- 手机浏览器可直接打开，桌面建议使用 1440 px 及以上窗口；窄屏会自动调整。
- 可使用 `app-ui-redesign.html#editor` 等 hash 链接直达指定页面。
- “重置演示数据”恢复初始内容。示例数据只存在当前浏览器页面内，刷新后还原。

## 设计方向

以暖灰页面、白色卡片、深墨色品牌区域和青绿色操作按钮组成统一的界面语言。保留雕雕品牌形象；任务、日志、权限、设置沿用四个底部主入口。

任务列表、详情、表单、图片工作区、代码编辑器和悬浮工具采用不同的信息结构。主操作固定在底部；危险操作进入确认弹窗；缺失、加载、失败等状态与正常页面使用同一套组件。

稿内包含 78 个页面状态以及额外确认弹窗。小号文字和预览缩放服务于设计审阅；Android 实现时应以 sp/dp 落地、支持系统字体缩放，并确保触控热区至少 48 dp。

## 功能覆盖与代码依据

| 功能域 | 设计页面／浮层 | 当前实现依据 |
| --- | --- | --- |
| 任务工作台（7） | 任务首页、新建任务、任务详情、循环执行、定时运行、启动页、操作确认与反馈 | `MainActivity.kt` 的 `TaskWorkspace`、`TaskMenuDialog`、`CreateTaskDialog`、`LoopDialog`、`ScheduleDialog`；`ui/StartupComic.kt` |
| 任务与分组（4） | 分组列表、新建分组、分组详情、添加成员 | `ui/TaskGroupsPage.kt` |
| 脚本编辑（3） | 脚本编辑器、示例列表、API 示例详情 | `ScriptEditorActivity.kt`；`core/editor/ApiDocs.kt` |
| 图片与素材（8） | 素材列表、图片详情、裁剪、精确选区、重命名、引用变更确认、历史素材、截取模板 | `MainActivity.kt` 素材相关页面；`AssetCropActivity.kt`；`TemplateCaptureActivity.kt` |
| 市场与备份（7） | 市场列表、脚本说明、批量选择、导入确认、导入进度、导入结果、导入与导出 | `ui/ScriptMarket.kt`；`ui/MarketBatchImport.kt`；`MainActivity.kt` 任务 ZIP 导入／导出 |
| 运行与日志（4） | 实时日志、运行历史、结果／错误详情、任务执行浮层 | `MainActivity.kt` 的 `LogsTab`、`RunHistoryDialog`；`core/floaty/FloatyManager.kt` |
| 权限与截屏（4） | 运行准备、截屏通道选择、测试过程／结果、后台保活指引 | `MainActivity.kt` 的 `PermissionsTab`、`CaptureChannelDialog`、`KeepAliveDialog`；`PickerCapturePermissionActivity.kt` |
| 设置与更新（4） | 设置首页、队列间隔、局域网 Web 编辑、应用更新 | `MainActivity.kt` 的 `SettingsTab`、`LanWebDialog`、`TaskQueueIntervalRow`；`ui/AppUpdateCard.kt` |
| 悬浮工具与录制（12） | 悬浮任务列表、悬浮分组列表／详情、快捷工具、取点、取色、范围、截图、录制准备、录制控制条、完成保存、悬浮日志 | `core/floaty/FloatyManager.kt`、`PickerOverlay.kt`、`ConsoleOverlay.kt`；`core/recording/RecordingSetupOverlay.kt`、`RecordingOverlay.kt`；`ScreenshotExportActivity.kt` |

上述路径相对于 `app/src/main/java/com/forest/android/assistant/`。

## 状态与关键交互

- 任务、分组、素材、历史记录和日志的空白状态。
- 市场加载中、加载失败、搜索无结果、已导入、版本不兼容与部分导入失败。
- 指定次数、指定时长、直到手动停止三种循环方式。
- 延时一次、固定间隔、每天固定时刻三种定时方式；固定间隔至少 15 分钟。
- 截屏通道测试中、成功、失败；失败保留原设置，不自动切换通道。
- 更新检查中、已是最新、下载中、中断、合成、校验、准备安装和非正式签名提示。
- 运行成功、失败、错误定位与任务间等待。
- 删除任务、清空任务、删除分组、删除素材、历史素材覆盖恢复、清空代码、清空日志／历史、放弃录制等确认。
- 编辑器草稿保留、未保存离开确认、流程任务保存为脚本的转换确认。
- 表单名称／范围校验、批量勾选、分组顺序调整、任务拖动排序、裁剪选区拖动与精确调整。

## 功能边界

本稿只设计 Android APP 范围，未修改 Android 源码。

- 手机端新建任务仍只有空白、签到、找图三个脚本模板。
- 流程编排、共享模板管理继续通过局域网 Web 编辑器进行，本稿提供入口及连接流程，不将其虚构为现有原生页面。
- 分组删除保留任务；分组成员移除不删除任务；素材改名先展示引用差异；裁剪原图保留到历史素材。
- 系统权限、文件选择器、截图保存及 APK 安装由 Android 提供，不重新设计系统界面。HTML 展示交接提示和模拟反馈。
- 原型中的任务、市场脚本、运行历史、更新版本、网络地址和进度均为演示数据。不会执行脚本、实际开放网络服务、授权设备、下载 APK 或读写真实任务。
- 日志级别筛选、运行结果独立详情页和集中式更新详情是本次 UI 方案的展示建议，不能据此认为原 APP 已具备相同界面结构。
- 设计稿中的复制操作会尝试复制示例文本；源码和反馈链接由用户点击后打开现有脚本市场仓库。

## 源文件与重新生成

| 文件 | 用途 |
| --- | --- |
| `prototype.css` | APP 组件、设计稿画板与响应式样式 |
| `prototype.js` | 页面、演示数据、导航和交互 |
| `build.mjs` | 把代码、样式和品牌素材内嵌成单文件 HTML |
| `overview.png` | 桌面总览截图 |
| `task-preview.png` | 桌面交互预览截图 |
| `mobile-preview.png` | 手机宽度预览截图 |
| `qa-report.json` | 逐页状态及横向溢出检查结果 |
| `interaction-report.json` | 关键交互检查结果 |

在仓库根目录运行：

```powershell
node docs/ui-redesign/build.mjs
```

产物：`docs/app-ui-redesign.html`。Node.js 仅用于重新生成，打开成品不需要 Node.js。

## 验证方式

使用本机 Edge 无头浏览器打开本地 HTML，逐页切换全部状态，检查运行异常、横向溢出与图标按钮可访问名称。检查桌面 1440 px、手机 390 px，并验证主要表单、创建、分组、素材、导入、截屏测试、录制、更新和确认弹窗流程。

没有构建 APK；本次改动仅为可独立审阅的 HTML 设计稿。
