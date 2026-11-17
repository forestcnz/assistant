# 无效代码清理记录

本次仅清理静态引用核对后确认无调用的实现、资源和依赖，不调整代码压缩配置。

| 清理项 | 引用核对结果 |
| --- | --- |
| `core/flow/FlowDocument.kt` | `FlowDocument`、`FlowNode`、`InsertTarget`、`NodeLocation` 只在该文件内部相互引用；Web 编辑器使用 JavaScript 模型，Android 编译器直接读取 JSON。 |
| `ui/PermissionStatusCard.kt` | 无调用；当前权限页使用 `PermissionsPage.kt` 中的状态行。 |
| `ui/CaptureModeCard.kt` 及三个 `ic_capture_*` 图标 | 无调用；当前截屏设置使用 `ChoiceCard`，相关图标只被旧卡片引用。 |
| `MainActions.toggleConsole/showPicker`、`PageComponents.ToolRow`、`ConsoleOverlay.toggle` | 无调用；保留悬浮工具面板里的实际功能及控制台显示、隐藏实现。 |
| `TemplateCaptureActivity.toBitmapRect/fitScale/lastStart/viewSize/backRequestedWhileSaving` | 旧坐标换算链无调用；当前框选由 `CropCanvas` 处理。返回标记只有写入，没有读取，保存期间仍拦截返回。 |
| `FlowCompiler.saveDraft` | 无调用；保留现有草稿文件的读取、转换和清理兼容。 |
| `FloatyManager.showTaskGroupPanel/requestedGroupId/panelGradient` | 无调用；保留悬浮面板内的分组选择、返回和执行逻辑。 |
| `AppUpdatePage.statusStyle/statusHeight`、两个色板常量 | 计算结果或常量没有使用者。 |
| `ui-tooling-preview` 的正式依赖 | 没有 `@Preview` 或预览 API 调用；调试工具仍只在 debug 中引入。 |
| `ic_launcher_bird.xml`、`ic_pause.xml`、`ic_nav_tools.xml` | 前两项主代码、清单、资源和测试均无引用；工具图标随唯一引用者 `ToolRow` 一并清理。 |
| `ic_running_bird.png` | 已无运行时引用；移到 `docs/branding/running-bird.png` 保存设计素材，生成工具同步改为输出到文档目录。 |

## 原有注释存档

以下是随无效实现移出代码目录的原始注释，保留供历史设计查阅。

```kotlin
/** 权限状态用底色与文字共同表达；整张卡片可点击，适配纵向列表和大字体。 */
// 纵向列表中的每张卡保留一个点击区域，图标、用途和状态不再依赖方卡宽度裁切。
/** 等宽方形选择卡，整张卡片可点击，文字在窄屏下使用紧凑尺寸。 */
/** 开关悬浮控制台（可滚动日志面板，需悬浮窗权限） */
/** 开关控制台；返回操作后是否处于显示状态 */
/** 打开屏幕拾取工具（需要悬浮窗权限；取色和截图还需截屏授权） */
// 取色依赖截屏：先弹授权，成功后再打开拾取层
/** 工具行：名称 + 用途 + 尾部箭头 */
/** 记录截图里“点击任务运行按钮”等元素位置无关；仅用于坐标系换算 */
/** 拖动起点（用于 onDrag 时计算矩形） */
/** 分组详情的执行入口直接进入对应分组，沿用保存顺序并默认全选。 */
/** 与录制悬浮窗共用的视觉语言：暖白卡片、深绿灰标题栏、深蓝主按钮。 */
```

```xml
<!-- 向右的侧脸剪影；大钩喙是雕的主要识别点。 -->
<!-- 三撮向后扬起的头羽和胸羽，保持简笔画的清爽轮廓。 -->
<!-- 向右倾斜的动漫眼眉。 -->
<!-- 金色弯钩喙在悬浮球尺寸下仍保留完整外形。 -->
<!-- 暂停图标与文字共同标识录制状态。 -->
```
