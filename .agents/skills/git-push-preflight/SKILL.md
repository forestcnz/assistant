---
name: git-push-preflight
description: 本项目在 git push 或提交后推送前检查远端分支是否已有新提交；若远端仅自动修改 Android 版本号，先拉取并衔接本地提交再推送；若远端还有其他代码改动，停止推送并说明差异。用户要求推送、提交并推送或推送被拒绝时使用。
---

# 推送前检查远端代码

本仓库的 `dev` 推送会触发自动 patch 升版；`dev` 合并进 `master` 后还会自动升 minor 版并同步回 `dev`。因此本地提交后，远端可能只多了版本号提交。每次推送分支前都要实际抓取远端，不能仅凭本地缓存的 `origin/dev`、提交标题或上次推送结果判断。

## 操作流程

1. 从仓库根目录确认当前分支及其上游，然后抓取该上游的远端更新。示例命令适用于本项目使用的 `origin`：

   ```bash
   git branch --show-current
   git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}'
   git fetch origin
   git merge-base --is-ancestor '@{upstream}' HEAD
   ```

   先检查前两条命令的结果；处于游离 HEAD、没有上游或目标远端不是 `origin` 时，先确定实际要推送的分支和远端，不要猜测 `dev`。最后一条命令退出码为 0，表示本地已包含远端最新代码，可直接正常 `git push`。非 0 时继续检查；若抓取失败，不要依据旧的远端跟踪引用推送。

2. 远端有本地尚未包含的提交时，找共同祖先并查看**远端独有**的变更，不能用 `git diff HEAD origin/dev` 直接比较两个分支的最终文件（会把本地未推送的改动混在一起）：

   ```bash
   upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}')
   base=$(git merge-base HEAD "$upstream")
   git log --oneline HEAD.."$upstream"
   git diff --name-status "$base" "$upstream"
   git diff --unified=0 "$base" "$upstream" -- gradle.properties app/build.gradle.kts
   ```

   找不到共同祖先时停止推送并说明情况。**只有同时满足以下条件**才判定为“远端仅版本号变化”：远端独有的变更文件都属于 `gradle.properties`、`app/build.gradle.kts`；逐行核对后，前者仅修改 `appVersionCode=`、`appVersionName=` 的值，后者仅修改 `versionName = providers.gradleProperty("appVersionName").orElse("...").get()` 中 `orElse` 的默认版本号。不能只看文件名或提交消息；同文件中的其他属性、构建逻辑和注释变动都属于非版本变动。

3. 确认远端确实只改版本号后，若工作区干净，执行 `git pull --rebase` 衔接本地提交，再核对本地版本号没有把远端更新倒退。若有未提交改动，先按用户已授权的范围处理它们，再拉取；不要用会丢失改动的重置命令。若仅版本字段发生 rebase 冲突，核对远端新版本号并解决冲突，确保 `gradle.properties` 与 `app/build.gradle.kts` 的默认版本名一致；不要盲目选 `--ours` 或 `--theirs`。其他冲突不能当作“仅版本号变化”自动处理。

4. 推送前再 `git fetch origin`，用 `git merge-base --is-ancestor '@{upstream}' HEAD` 确认已经包含远端最新提交，然后 `git push`。如果远端又前进或推送再次被拒绝，重新从第 2 步检查新变化；不要强推（包括 `--force-with-lease`），也不要跳过检查直接重试。

远端独有变更包含其他文件或版本文件中的其他行时，列出提交与变动路径，停止自动拉取及推送，向用户说明需要处理的代码差异。此检查针对要推送的实际目标分支；显式推送其他分支时，先核实其对应远端分支，不能拿当前分支的上游检查结果代替。
