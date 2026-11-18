package com.forest.android.assistant.core.market

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

internal const val MARKET_BRANCH = "master"
internal val marketSlug = MARKET_REPO.removePrefix("https://github.com/")

internal data class MarketGitHubUser(val login: String)

internal enum class MarketPublishRoute(val wireName: String) {
    DIRECT("direct"), BRANCH("branch"), FORK("fork")
}

internal data class MarketGitHubHead(val sha: String, val route: MarketPublishRoute)

internal data class MarketWorkflowRun(val id: Long, val status: String, val conclusion: String)

internal class MarketGitHubError(val status: Int, private val detail: String) : IOException(
    when (status) {
        401 -> "GitHub 授权已失效，请重新授权"
        403 -> "GitHub 拒绝操作，请检查账号权限或稍后重试"
        404 -> "GitHub 找不到对应的仓库、分支或文件"
        409, 422 -> "GitHub 拒绝本次提交，请检查仓库中的版本或分支状态"
        429 -> "GitHub 请求过多，请稍后重试"
        else -> "GitHub 请求失败（HTTP $status）"
    }
) {
    val isBranchRuleFailure: Boolean
        get() = status in setOf(403, 422) && listOf("GH006", "GH013", "protected branch", "repository rule")
            .any { detail.contains(it, ignoreCase = true) }
}

internal class MarketGitHub(private val token: String? = null) {
    fun user(): MarketGitHubUser {
        val login = obj("GET", "/user").getString("login")
        require(login.matches(Regex("[A-Za-z0-9-]{1,39}"))) { "GitHub 账号无效" }
        return MarketGitHubUser(login)
    }

    fun head(): MarketGitHubHead {
        val repo = obj("GET", repoPath(marketSlug))
        val canPush = repo.optJSONObject("permissions")?.optBoolean("push") == true
        val branch = obj("GET", "${repoPath(marketSlug)}/branches/$MARKET_BRANCH")
        val route = when {
            !canPush -> MarketPublishRoute.FORK
            branch.optBoolean("protected") -> MarketPublishRoute.BRANCH
            else -> MarketPublishRoute.DIRECT
        }
        return MarketGitHubHead(branch.getJSONObject("commit").getString("sha"), route)
    }

    fun directoryExists(sha: String, directory: String): Boolean =
        optional { request("GET", contentsPath(directory, sha)) } != null

    fun releaseMetadata(sha: String, directory: String): JSONObject? = optional {
        val content = obj("GET", contentsPath("$directory/release.json", sha))
        require(content.getString("encoding") == "base64" && content.getInt("size") <= 64 * 1024) {
            "市场条目的发布信息无效"
        }
        JSONObject(Base64.getMimeDecoder().decode(content.getString("content")).toString(Charsets.UTF_8))
    }

    fun fork(login: String): String {
        val names = listOf(marketSlug.substringAfter('/'), "assistant-scripts-market")
        var available: String? = null
        for (name in names) {
            val slug = "$login/$name"
            val repo = optional { obj("GET", repoPath(slug)) }
            if (repo == null) {
                if (available == null) available = name
            } else if (repo.optBoolean("fork") && listOf("source", "parent").any {
                    repo.optJSONObject(it)?.optString("full_name").equals(marketSlug, ignoreCase = true)
                }) {
                return slug
            }
        }
        val name = requireNotNull(available) { "当前账号下的市场 Fork 名称已被其他仓库占用" }
        val response = obj("POST", "${repoPath(marketSlug)}/forks", JSONObject().put("name", name))
        val slug = response.getString("full_name")
        require(slug.equals("$login/$name", ignoreCase = true)) { "无法确认 GitHub Fork 所属账号" }
        return slug
    }

    fun branchSha(repo: String, branch: String): String? = optional {
        obj("GET", "${repoPath(repo)}/git/ref/heads/${encodePath(branch)}")
            .getJSONObject("object").getString("sha")
    }

    fun includesCommit(repo: String, head: String, commit: String): Boolean =
        head == commit || optional {
            obj("GET", "${repoPath(repo)}/compare/$commit...$head?per_page=1")
                .optString("status") in setOf("ahead", "identical")
        } == true

    fun baseTree(repo: String, sha: String): String? = optional {
        obj("GET", "${repoPath(repo)}/git/commits/$sha").getJSONObject("tree").getString("sha")
    }

    fun createBlob(repo: String, bytes: ByteArray): String = obj("POST", "${repoPath(repo)}/git/blobs", JSONObject()
        .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(bytes))).getString("sha")

    fun createTree(repo: String, entries: JSONArray, base: String? = null): String {
        val body = JSONObject().put("tree", entries)
        base?.let { body.put("base_tree", it) }
        return obj("POST", "${repoPath(repo)}/git/trees", body).getString("sha")
    }

    fun createCommit(repo: String, base: String, tree: String, message: String): String =
        obj("POST", "${repoPath(repo)}/git/commits", JSONObject()
            .put("message", message).put("tree", tree).put("parents", JSONArray(listOf(base)))).getString("sha")

    fun updateMaster(sha: String) {
        obj("PATCH", "${repoPath(marketSlug)}/git/refs/heads/$MARKET_BRANCH", JSONObject()
            .put("sha", sha).put("force", false))
    }

    fun createBranch(repo: String, branch: String, sha: String) {
        obj("POST", "${repoPath(repo)}/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
    }

    fun pullNumber(repo: String, branch: String): Int? {
        val head = encode("${repo.substringBefore('/')}:$branch")
        val pulls = request("GET", "${repoPath(marketSlug)}/pulls?head=$head&base=$MARKET_BRANCH&state=all") as JSONArray
        return (0 until pulls.length()).map(pulls::getJSONObject).firstOrNull { pull ->
            pull.getJSONObject("head").optJSONObject("repo")?.optString("full_name").equals(repo, ignoreCase = true)
        }?.getInt("number")
    }

    fun createPull(repo: String, branch: String, title: String, body: String): Int {
        val params = JSONObject().put("title", title).put("body", body).put("base", MARKET_BRANCH)
            .put("head", if (repo == marketSlug) branch else "${repo.substringBefore('/')}:$branch")
        return obj("POST", "${repoPath(marketSlug)}/pulls", params).getInt("number")
    }

    fun pull(number: Int): JSONObject = obj("GET", "${repoPath(marketSlug)}/pulls/$number")

    fun publishRun(commit: String): MarketWorkflowRun? {
        val path = "${repoPath(marketSlug)}/actions/workflows/publish.yml/runs?branch=$MARKET_BRANCH&head_sha=$commit&per_page=1"
        val run = obj("GET", path).optJSONArray("workflow_runs")?.optJSONObject(0) ?: return null
        return MarketWorkflowRun(run.getLong("id"), run.optString("status"), run.optString("conclusion"))
    }

    private fun obj(method: String, path: String, body: JSONObject? = null): JSONObject =
        request(method, path, body) as JSONObject

    private fun request(method: String, path: String, body: JSONObject? = null): Any =
        send("https://api.github.com$path", method, token, body?.toString(), "application/json; charset=utf-8")

    private fun contentsPath(path: String, sha: String): String = "${repoPath(marketSlug)}/contents/${encodePath(path)}?ref=$sha"

    private fun repoPath(repo: String): String = "/repos/${encodePath(repo)}"

    private inline fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (error: MarketGitHubError) {
        if (error.status == 404) null else throw error
    }

    companion object {
        fun deviceCode(clientId: String): JSONObject = oauth("/login/device/code", "client_id" to clientId, "scope" to "public_repo")

        fun deviceToken(clientId: String, code: String): JSONObject = oauth("/login/oauth/access_token",
            "client_id" to clientId, "device_code" to code, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")

        private fun oauth(path: String, vararg params: Pair<String, String>): JSONObject = send(
            "https://github.com$path", "POST", null, params.joinToString("&") { "${it.first}=${encode(it.second)}" },
            "application/x-www-form-urlencoded; charset=utf-8"
        ) as JSONObject

        private fun encodePath(path: String): String = path.split('/').joinToString("/", transform = ::encode)

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun send(address: String, method: String, token: String?, body: String?, contentType: String): Any {
            val connection = URI(address).toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", "DiaoDiaoAssistant-Android")
                connection.setRequestProperty("Accept", "application/json")
                token?.let {
                    connection.setRequestProperty("Authorization", "Bearer $it")
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                body?.toByteArray(Charsets.UTF_8)?.let { bytes ->
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status !in 200..299 && !address.startsWith("https://github.com/login/")) {
                    val detail = connection.errorStream?.use { it.readAtMost(16 * 1024).toString(Charsets.UTF_8) }.orEmpty()
                    throw MarketGitHubError(status, detail)
                }
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                    ?: throw MarketGitHubError(status, "")
                val result = input.use { it.readAtMost(4 * 1024 * 1024 + 1) }
                if (result.size > 4 * 1024 * 1024) throw IOException("GitHub 返回的数据过大")
                return JSONTokener(result.toString(Charsets.UTF_8)).nextValue()
            } finally {
                connection.disconnect()
            }
        }

        // 兼容最低支持的 Android 版本，并限制外部响应占用内存。
        private fun InputStream.readAtMost(limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
