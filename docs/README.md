# 雕雕助手使用文档

- [使用指南](../README.md)：安装授权、任务编辑、流程编排、素材管理、运行与备份。
- [脚本 API](script-api.md)：函数、参数、返回值和示例。
- [任务脚本编写指南](script-writing-guide.md)：从识别、点击和等待开始编写自动化任务。

## 维护参考

- [局域网 Web 编辑器](lan-web-editor-design.md)：服务结构、任务文件和接口。
- [品牌素材](branding/startup-comic.md)：启动漫画、应用图标和运行图标的源文件与生成方式。

### Android 应用更新

正式版由 `vX.Y.Z` 标签触发。GitHub Release 附带 `update.json` 和正式签名 APK，自动检查从 Latest 读取清单。应用内安装入口位于「设置 → 应用更新」。已有正式版用户首次手动覆盖安装支持此功能的版本，后续可通过应用更新。

Gitee 下载仓库由 `gradle.properties` 中的 `appUpdateGiteeRepository` 指定。Release 标签基于 Gitee 仓库已有的默认分支。将有该仓库写入权限的 `GITEE_ACCESS_TOKEN` 添加到 GitHub Actions secrets，正式发布后 CI 会上传同一份 APK 和应用读取的更新清单。补传时从 `master` 手动运行 Android build and release，在 `mirror_tag` 填写最新正式版的标签。
