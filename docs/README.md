# 雕雕助手使用文档

- [使用指南](../README.md)：安装授权、任务编辑、流程编排、素材管理、运行与备份。
- [脚本 API](script-api.md)：函数、参数、返回值和示例。
- [任务脚本编写指南](script-writing-guide.md)：从识别、点击和等待开始编写自动化任务。

## 维护参考

- [局域网 Web 编辑器](lan-web-editor-design.md)：服务结构、任务文件和接口。
- [品牌素材](branding/startup-comic.md)：启动漫画、应用图标和运行图标的源文件与生成方式。

### Android 应用更新

正式版由 `vX.Y.Z` 标签触发。应用从 GitHub Releases 的 Latest 读取 `update.json` 并下载正式签名 APK。检查更新、下载和安装入口位于「设置 → 版本」的应用更新卡片。已有正式版用户首次手动覆盖安装支持此功能的版本，后续可通过应用更新。

发布流程从上一正式版生成 HDiffPatch 差分，合成结果与新版 APK 字节一致且差分小于全包一半时，才写入 `update.json` 的 `patches`。设备上已安装的 APK 与差分记录的版本和 SHA-256 一致时，应用优先下载增量包，合成失败后可选择「下载完整包」。首次升级到支持增量更新的版本会下载完整包。

手机端的 `libhpatchz.so` 来自 [HDiffPatch v5.1.3 Android SDK](https://github.com/sisong/HDiffPatch/releases/tag/v5.1.3) 的 `android_hpatchz/libs`，包括 `arm64-v8a`、`armeabi-v7a`、`x86` 和 `x86_64`。SDK ZIP 的 SHA-256 为 `d7b98bcd3efb05436e08bcf525136c81e7dc6713bc58260770a2793818167fb7`。
