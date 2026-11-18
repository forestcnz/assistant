package com.forest.android.assistant.core.market

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class MarketSubmission(val snapshot: MarketPublishSnapshot) {
    @Volatile var status = "submitting"
        private set
    @Volatile var error: String? = null
        private set
    @Volatile private var route = snapshot.head.route
    @Volatile private var pullNumber: Int? = null
    @Volatile private var mergedCommit: String? = null
    @Volatile private var runId: Long? = null
    @Volatile private var sourceUrl: String? = null
    @Volatile private var checkError: String? = null
    private var repository: String? = null
    private var commit: String? = null
    private var refreshJob: Job? = null
    @Volatile private var lastCheck: Long? = null
    private val blobs = mutableMapOf<String, String>()
    private val branch = "dd-market-${snapshot.id}"

    fun summary(): JSONObject = JSONObject().put("id", snapshot.id).put("task", snapshot.task)
        .put("title", snapshot.title).put("scriptId", snapshot.scriptId).put("version", snapshot.version)
        .put("status", status).put("route", route.wireName).put("error", error).put("checkError", checkError)
        .put("pullUrl", pullNumber?.let { "$MARKET_REPO/pull/$it" })
        .put("runUrl", runId?.let { "$MARKET_REPO/actions/runs/$it" })
        .put("sourceUrl", sourceUrl)

    fun beginRetry(): Boolean {
        if (status != "failed") return false
        error = null
        status = "submitting"
        return true
    }

    fun submit(github: MarketGitHub, account: MarketGitHubUser) {
        try {
            submitFiles(github, account)
            snapshot.discard()
        } catch (conflict: MarketPublishConflict) {
            snapshot.discard()
            error = conflict.message
            status = "conflict"
        } catch (failure: Exception) {
            error = when (failure) {
                is MarketGitHubError, is IllegalStateException, is IllegalArgumentException -> failure.message
                else -> "提交到 GitHub 时连接中断，可以重试本次发布"
            }
            status = "failed"
        }
    }

    private fun submitFiles(github: MarketGitHub, account: MarketGitHubUser) {
        // 重试先核对上一次远端写入，避免网络断开后重复提交或重复创建 PR。
        repository?.takeIf { route != MarketPublishRoute.DIRECT }?.let { repo ->
            val existing = github.pullNumber(repo, branch)
            if (existing != null) {
                pullNumber = existing
                status = "review"
                return
            }
            val existingBranch = github.branchSha(repo, branch)
            if (existingBranch != null) {
                if (existingBranch != commit) throw MarketPublishConflict("投稿分支已被其他操作修改，请重新预览")
                openPull(github, repo)
                return
            }
        }
        val head = github.head()
        if (route == MarketPublishRoute.DIRECT && commit?.let { github.includesCommit(marketSlug, head.sha, it) } == true) {
            mergedCommit = commit
            status = "building"
            return
        }
        if (head.sha != snapshot.head.sha) throw MarketPublishConflict("市场仓库已有新提交，请重新预览后发布")
        if (route == MarketPublishRoute.DIRECT && head.route == MarketPublishRoute.BRANCH) route = MarketPublishRoute.BRANCH
        if (route != MarketPublishRoute.FORK && head.route == MarketPublishRoute.FORK) {
            throw MarketPublishConflict("仓库写权限已变化，请重新预览以选择投稿方式")
        }
        val repo = repository ?: (if (route == MarketPublishRoute.FORK) github.fork(account.login) else marketSlug)
            .also { repository = it }
        val newCommit = commit ?: buildCommit(github, repo).also { commit = it }
        if (route == MarketPublishRoute.DIRECT) {
            try {
                github.updateMaster(newCommit)
                mergedCommit = newCommit
                status = "building"
                return
            } catch (failure: MarketGitHubError) {
                if (!failure.isBranchRuleFailure) throw failure
                route = MarketPublishRoute.BRANCH
            }
        }
        github.createBranch(repo, branch, newCommit)
        openPull(github, repo)
    }

    private fun buildCommit(github: MarketGitHub, repo: String): String {
        var base = github.baseTree(repo, snapshot.head.sha)
        if (repo != marketSlug) {
            // GitHub 创建 Fork 后会异步准备 Git 对象。
            for (attempt in 1..12) {
                if (base != null) break
                Thread.sleep(1500)
                base = github.baseTree(repo, snapshot.head.sha)
            }
        }
        val baseTree = checkNotNull(base) { "GitHub 正在准备 Fork，稍后可重试本次投稿" }
        val entries = JSONArray()
        // 同一个新树整体替换任务目录，更新时也会移除本次取消公开的旧素材。
        val images = JSONArray()
        snapshot.files.forEach { (path, file) ->
            val sha = blobs.getOrPut(path) { github.createBlob(repo, file.readBytes()) }
            if (path.startsWith("images/")) images.put(treeEntry(path.substringAfter('/'), sha))
            else entries.put(treeEntry(path, sha))
        }
        if (images.length() != 0) entries.put(treeEntry("images", github.createTree(repo, images), directory = true))
        val taskTree = github.createTree(repo, entries)
        val tree = github.createTree(repo, JSONArray().put(treeEntry(snapshot.directory, taskTree, directory = true)), baseTree)
        return github.createCommit(repo, snapshot.head.sha, tree, "发布 ${snapshot.title} v${snapshot.version}\n\nMarket-Submission: ${snapshot.id}")
    }

    private fun openPull(github: MarketGitHub, repo: String) {
        pullNumber = github.pullNumber(repo, branch) ?: github.createPull(repo, branch,
            "发布 ${snapshot.title} v${snapshot.version}",
            "${snapshot.metadata.getString("description")}\n\n${snapshot.metadata.getString("changes")}")
        status = "review"
    }

    @Synchronized
    fun refresh(scope: CoroutineScope, github: MarketGitHub, requestedAt: Long, force: Boolean,
                loadCatalog: suspend () -> List<MarketRelease>): Job? {
        // 合并列表和详情的重叠查询，手动刷新可以跳过轮询间隔。
        refreshJob?.takeIf { it.isActive }?.let { return it }
        if (status !in REFRESHABLE ||
            lastCheck?.let { it >= requestedAt || (!force && requestedAt - it < REFRESH_INTERVAL_MS) } == true) return null
        return scope.launch {
            try {
                checkError = refreshStatus(github, loadCatalog)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                checkError = "暂时无法查询 GitHub 状态，请稍后刷新"
            } finally {
                lastCheck = SystemClock.elapsedRealtime()
            }
        }.also { refreshJob = it }
    }

    private suspend fun refreshStatus(github: MarketGitHub, loadCatalog: suspend () -> List<MarketRelease>): String? {
        if (status == "review") {
            val number = pullNumber ?: return null
            val pull = github.pull(number)
            if (pull.optBoolean("merged")) {
                mergedCommit = pull.getString("merge_commit_sha")
                status = "building"
            } else if (pull.optString("state") == "closed") status = "closed"
        }
        val expected = mergedCommit ?: return null
        val release = try {
            loadCatalog().firstOrNull { it.source.scriptId == snapshot.scriptId && it.version >= snapshot.version }
                ?.takeIf { github.includesCommit(marketSlug, it.revision, expected) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 市场目录暂不可读时仍可显示工作流进度，下一轮重新确认上架结果。
            updateRun(github, expected)
            return "暂时无法核对市场目录，请稍后刷新"
        }
        if (release != null) {
            sourceUrl = release.sourceUrl
            status = if (release.version == snapshot.version) "published" else "superseded"
            return null
        }
        updateRun(github, expected)
        return null
    }

    private fun updateRun(github: MarketGitHub, commit: String) {
        val run = github.publishRun(commit) ?: return
        runId = run.id
        status = when {
            run.conclusion in setOf("failure", "timed_out", "action_required", "startup_failure") -> "build_failed"
            run.status == "completed" -> "awaiting_catalog"
            run.status in setOf("queued", "requested", "waiting", "pending") -> "queued"
            else -> "building"
        }
    }

    private fun treeEntry(path: String, sha: String, directory: Boolean = false) = JSONObject().put("path", path)
        .put("mode", if (directory) "040000" else "100644").put("type", if (directory) "tree" else "blob").put("sha", sha)

    companion object {
        internal const val REFRESH_INTERVAL_MS = 4_000L
        private val REFRESHABLE = setOf("review", "queued", "building", "awaiting_catalog", "build_failed")
    }
}
