# 启动四格漫画

`startup-comic.svg` 是手绘 SVG：肥宅雕从任务太多、一键执行、自动完成，到抱着可乐继续打游戏。画面包含四格，按左上、右上、左下、右下阅读。

应用启动时完整显示漫画约 3 秒，点击或返回可提前进入首页。横竖屏均按比例适配，不裁切格子。通知权限申请在漫画结束后显示。从录制流程返回任务页时跳过漫画。

本机重新绘制并导出 Android 使用的 1024 × 1024 PNG：

```powershell
node tools/create-startup-comic.mjs
```

绘图定义在 `tools/create-startup-comic.mjs`。脚本使用 Node.js 的内置 WebSocket 和本机 Chrome，通过离屏渲染导出图片，无需 npm 依赖。Chrome 路径可通过 `CHROME_PATH` 设置。

输出：

- SVG：`docs/branding/startup-comic.svg`
- Android 资源：`app/src/main/res/drawable-nodpi/startup_comic.png`

启动页使用导出的 PNG，保持 SVG 中中文字体、线条和配色的实际显示效果。

## 应用图标与悬浮窗 Logo

两处共用漫画中的肥宅雕形象：方框眼镜、交叠双翅和圆滚滚的大肚皮。标志为透明背景，主体放大，空闲悬浮球按圆形显示。任务运行时，悬浮球显示同一只雕雕的红色版本，头顶显示任务名称，双击雕雕停止任务。

- SVG：`docs/branding/brand-mark.svg`
- Android 共用资源：`app/src/main/res/drawable-nodpi/ic_brand_mark.png`（512 × 512）
- 运行态红色雕雕 SVG：`docs/branding/running-bird.svg`
- 运行态 Android 资源：`app/src/main/res/drawable-nodpi/ic_running_bird.png`（512 × 512）

只更新标志、不重新导出漫画：

```powershell
node tools/create-startup-comic.mjs --brand-only
```
