package com.forest.android.assistant.core.web

import android.content.Context
import android.os.SystemClock
import com.forest.android.assistant.BuildConfig
import com.forest.android.assistant.core.market.MarketGitHub
import com.forest.android.assistant.core.market.MarketGitHubError
import com.forest.android.assistant.core.market.MarketGitHubUser
import com.forest.android.assistant.core.market.MarketPublishConflict
import com.forest.android.assistant.core.market.MarketPublishSnapshot
import com.forest.android.assistant.core.market.MarketRelease
import com.forest.android.assistant.core.market.MarketRepository
import com.forest.android.assistant.core.market.MarketSubmission
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** GitHub 授权和提交仅存在于本次 Web 服务进程，各浏览器会话互相隔离。 */
internal class MarketWebRoutes(private val context: Context) {
    private val market = MarketRepository(context)
    private val cache = File(context.cacheDir, "market-web-${UUID.randomUUID()}")
    private val sessions = linkedMapOf<String, Browser>()
    private val random = SecureRandom()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO.limitedParallelism(3))
    private val statusScope = CoroutineScope(supervisor + Dispatchers.IO.limitedParallelism(4))
    private val catalogLock = Mutex()
    private var checkedCatalog: CheckedCatalog? = null
    @Volatile private var closed = false

    fun close() {
        closed = true
        synchronized(sessions) { sessions.clear() }
        supervisor.invokeOnCompletion { cache.deleteRecursively() }
        scope.cancel()
    }

    fun route(session: IHTTPSession, path: List<String>, readBody: () -> JSONObject): Response {
        val origin = browserOrigin(session) ?: return problem(Status.FORBIDDEN,
            "市场发布需要可信 HTTPS，或从本机打开 http://127.0.0.1:8765/")
        val method = session.method
        val imageRequest = method == Method.GET && path.size == 4 && path[0] == "preview" && path[2] == "image"
        val sentOrigin = session.headers["origin"]
        if ((!imageRequest && session.headers["x-assistant-market"] != "1") ||
            (sentOrigin != null && sentOrigin != origin) || (method == Method.POST && sentOrigin != origin) ||
            session.headers["sec-fetch-site"]?.let { it !in setOf("same-origin", "none") } == true) {
            return problem(Status.FORBIDDEN, "请在当前编辑器页面操作")
        }
        if (method !in setOf(Method.GET, Method.POST)) return problem(Status.METHOD_NOT_ALLOWED, "请求方法不支持")
        return try {
            val body = if (method == Method.POST) {
                require((session.headers["content-length"]?.toIntOrNull() ?: 0) in 1..64 * 1024 &&
                    session.headers["content-type"]?.substringBefore(';') == "application/json") { "请求格式或大小不合法" }
                readBody()
            } else JSONObject()
            val cookie = sessionCookieName(origin)
            val key = session.cookies.read(cookie)
            val browser = findBrowser(key, origin)
            when {
                path == listOf("session") && method == Method.GET -> if (browser == null) answer(sessionInfo(null))
                    else withBrowser(browser) { answer(sessionInfo(it)) }
                path == listOf("device") && method == Method.POST -> startDevice(origin, browser, body)
                path == listOf("device", "poll") && method == Method.POST -> withBrowser(browser) { pollDevice(it, key!!) }
                path == listOf("logout") && method == Method.POST -> {
                    if (browser != null) withBrowser(browser) {
                        requireNoSubmission(it)
                        synchronized(sessions) { sessions.remove(key) }
                        dispose(it)
                    }
                    answer(JSONObject().put("ok", true)).withCookie(origin, "", 0)
                }
                path.firstOrNull() == "submissions" && path.size <= 2 && method == Method.GET ->
                    submissions(browser, path.getOrNull(1), session.parameters["refresh"]?.firstOrNull() == "1")
                else -> withBrowser(browser) {
                    requireAccount(it)
                    when {
                        path == listOf("catalog") && method == Method.GET -> catalog()
                        path == listOf("preview") && method == Method.POST -> preview(it, body)
                        path.size == 2 && path[0] == "preview" && method == Method.GET -> textPreview(it, path[1])
                        imageRequest -> previewImage(it, path[1], path[3])
                        path == listOf("submit") && method == Method.POST -> submit(it, body.getString("id"))
                        path.size == 3 && path[0] == "submissions" && path[2] == "retry" && method == Method.POST -> retry(it, path[1])
                        else -> problem(Status.NOT_FOUND, "市场接口不存在")
                    }
                }
            }
        } catch (error: WebError) {
            problem(error.status, error.message.orEmpty())
        } catch (error: MarketPublishConflict) {
            problem(Status.CONFLICT, error.message.orEmpty())
        } catch (error: MarketGitHubError) {
            val status = if (error.status == 401) Status.UNAUTHORIZED else Status.SERVICE_UNAVAILABLE
            problem(status, error.message.orEmpty())
        } catch (error: IllegalStateException) {
            problem(Status.BAD_REQUEST, error.message.orEmpty())
        } catch (error: IllegalArgumentException) {
            problem(Status.BAD_REQUEST, error.message.orEmpty())
        } catch (_: JSONException) {
            problem(Status.BAD_REQUEST, "读取发布资料失败，请检查表单或任务文件")
        } catch (_: IOException) {
            problem(Status.SERVICE_UNAVAILABLE, "暂时无法读取文件或连接 GitHub，请稍后重试")
        }
    }

    private fun startDevice(origin: String, browser: Browser?, body: JSONObject): Response {
        val clientId = body.getString("clientId").trim()
        require(clientId.matches(Regex("[A-Za-z0-9_]{10,80}"))) { "请输入有效的 GitHub OAuth Client ID" }
        if (browser != null) return withBrowser(browser) {
            check(it.account == null) { "请先在 Web 退出当前 GitHub 账号" }
            val pending = it.pending
            if (pending != null && now() < pending.expires && clientId == pending.clientId) return@withBrowser answer(pending.summary())
            answer(beginDevice(it, clientId).summary())
        }
        val fresh = Browser(origin)
        val key = newKey()
        synchronized(sessions) {
            if (sessions.size >= 8) throw WebError(Status.SERVICE_UNAVAILABLE, "发布会话已满，请在其他浏览器退出后再试")
            sessions[key] = fresh
        }
        return try {
            synchronized(fresh) { answer(beginDevice(fresh, clientId).summary()).withCookie(origin, key, SESSION_SECONDS) }
        } catch (error: Exception) {
            synchronized(sessions) { sessions.remove(key) }
            throw error
        }
    }

    private fun beginDevice(browser: Browser, clientId: String): PendingDevice {
        val result = MarketGitHub.deviceCode(clientId)
        if (!result.has("device_code")) throw WebError(Status.BAD_REQUEST, "无法获取设备码，请检查 Client ID 和 OAuth App 的 Device Flow 设置")
        val interval = result.optLong("interval", 5).coerceAtLeast(1) * 1000
        val expires = now() + result.getLong("expires_in").coerceIn(1, 900) * 1000
        return PendingDevice(clientId, result.getString("device_code"), result.getString("user_code"), interval, expires, now() + interval)
            .also { browser.pending = it; browser.expires = expires }
    }

    private fun pollDevice(browser: Browser, key: String): Response {
        val pending = browser.pending ?: return answer(sessionInfo(browser))
        if (now() >= pending.expires) {
            browser.pending = null
            return answer(JSONObject().put("deviceState", "expired"))
        }
        if (now() < pending.nextPoll) return answer(pending.summary())
        pending.nextPoll = now() + pending.interval
        val result = MarketGitHub.deviceToken(pending.clientId, pending.deviceCode)
        when (result.optString("error")) {
            "authorization_pending" -> return answer(pending.summary())
            "slow_down" -> {
                pending.interval = maxOf(pending.interval + 5000, result.optLong("interval") * 1000)
                pending.nextPoll = now() + pending.interval
                return answer(pending.summary())
            }
            "expired_token", "token_expired" -> {
                browser.pending = null
                return answer(JSONObject().put("deviceState", "expired"))
            }
            "access_denied" -> {
                browser.pending = null
                return answer(JSONObject().put("deviceState", "denied"))
            }
        }
        val token = result.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw WebError(Status.BAD_REQUEST, "GitHub 授权失败，请重新获取设备码")
        browser.pending = null
        val scopes = result.optString("scope").split(',').map(String::trim)
        require("public_repo" in scopes || "repo" in scopes) { "当前 OAuth 授权缺少公开仓库权限，请重新授权" }
        browser.account = MarketGitHub(token).user()
        browser.token = token
        browser.expires = now() + result.optLong("expires_in", SESSION_SECONDS).coerceIn(1, SESSION_SECONDS) * 1000
        val newKey = newKey()
        synchronized(sessions) {
            sessions.remove(key)
            if (!closed) sessions[newKey] = browser
        }
        return answer(sessionInfo(browser)).withCookie(browser.origin, newKey, SESSION_SECONDS)
    }

    private fun preview(browser: Browser, body: JSONObject): Response {
        requireNoSubmission(browser)
        val previous = browser.preview
        if (previous != null && browser.submissions[previous.id] == null) previous.discard()
        browser.preview = null
        val snapshot = MarketPublishSnapshot.preview(context, cache, body, requireAccount(browser), github(browser))
        if (closed) {
            snapshot.discard()
            throw WebError(Status.SERVICE_UNAVAILABLE, "Web 服务已停止")
        }
        browser.preview = snapshot
        return answer(snapshot.summary())
    }

    private fun textPreview(browser: Browser, id: String): Response {
        val snapshot = currentPreview(browser, id)
        val contents = JSONObject()
        snapshot.files.filterKeys { !it.startsWith("images/") }.forEach { (name, file) -> contents.put(name, file.readText()) }
        return answer(contents)
    }

    private fun previewImage(browser: Browser, id: String, name: String): Response {
        val snapshot = currentPreview(browser, id)
        val file = snapshot.files["images/$name"] ?: return problem(Status.NOT_FOUND, "素材不存在")
        return newFixedLengthResponse(Status.OK, MarketPublishSnapshot.imageMime(name), file.inputStream(), file.length())
            .apply { protectResponse() }
    }

    private fun submit(browser: Browser, id: String): Response {
        browser.submissions[id]?.let { return answer(it.summary()) }
        requireNoSubmission(browser)
        val snapshot = currentPreview(browser, id)
        val job = MarketSubmission(snapshot)
        browser.submissions[id] = job
        browser.preview = null
        while (browser.submissions.size > 8) {
            val oldest = browser.submissions.entries.first()
            oldest.value.snapshot.discard()
            browser.submissions.remove(oldest.key)
        }
        launchSubmission(browser, job)
        return answer(job.summary(), Status.ACCEPTED)
    }

    private fun retry(browser: Browser, id: String): Response {
        val job = findSubmission(browser, id)
        requireNoSubmission(browser)
        if (!job.beginRetry()) throw WebError(Status.CONFLICT, "当前状态无需重试，刷新后查看结果")
        launchSubmission(browser, job)
        return answer(job.summary(), Status.ACCEPTED)
    }

    private fun launchSubmission(browser: Browser, job: MarketSubmission) {
        val github = github(browser)
        val account = requireAccount(browser)
        browser.submitting = true
        scope.launch { job.submit(github, account) }.invokeOnCompletion { browser.submitting = false }
    }

    private fun submissions(browser: Browser?, id: String?, force: Boolean): Response {
        val (github, records) = withBrowser(browser) {
            requireAccount(it)
            github(it) to (if (id == null) it.submissions.values.toList().asReversed() else listOf(findSubmission(it, id)))
        }
        val requestedAt = now()
        // 短暂等待本轮查询。遇到慢响应时先返回已更新的记录，其余查询继续在后台执行。
        val checks = records.mapNotNull { record ->
            record.refresh(statusScope, github, requestedAt, force) { catalogForStatus(requestedAt, force) }
        }
        if (checks.isNotEmpty()) runBlocking { withTimeoutOrNull(1200) { checks.joinAll() } }
        val checking = checks.any { it.isActive }
        return withBrowser(browser) {
            val result = if (id != null) findSubmission(it, id).summary()
            else JSONObject().put("submissions", JSONArray(it.submissions.values.toList().asReversed().map(MarketSubmission::summary)))
            answer(result.put("checking", checking))
        }
    }

    private suspend fun catalogForStatus(requestedAt: Long, force: Boolean): List<MarketRelease> = catalogLock.withLock {
        // 一轮可能有多条打包记录，同用一份目录，查询失败时也避免重复请求。
        checkedCatalog?.takeIf {
            it.checkedAt >= requestedAt || (!force && requestedAt - it.checkedAt < MarketSubmission.REFRESH_INTERVAL_MS)
        }?.let { return@withLock it.result.getOrThrow() }
        val result = try {
            Result.success(market.load(fresh = true))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        checkedCatalog = CheckedCatalog(now(), result)
        result.getOrThrow()
    }

    private fun catalog(): Response {
        val entries = runBlocking { market.load() }.map { release ->
            JSONObject().put("scriptId", release.source.scriptId).put("title", release.title).put("version", release.version)
                .put("description", release.description).put("usage", release.usage).put("environment", release.environment)
                .put("capabilities", JSONArray(release.capabilities)).put("targetApp", release.targetApp)
                .put("targetPackage", release.targetPackage).put("minAppVersionCode", release.minAppVersionCode)
        }
        return answer(JSONObject().put("entries", JSONArray(entries)))
    }

    private fun sessionInfo(browser: Browser?): JSONObject = JSONObject().put("minAppVersionCode", BuildConfig.VERSION_CODE)
        .put("login", browser?.account?.login).put("device", browser?.pending?.summary())
        .put("submissions", JSONArray(browser?.submissions?.values?.toList().orEmpty().asReversed().map(MarketSubmission::summary)))

    private fun requireNoSubmission(browser: Browser) {
        if (browser.submitting) throw WebError(Status.CONFLICT, "有任务正在提交，请先等待结果")
    }

    private fun currentPreview(browser: Browser, id: String): MarketPublishSnapshot = browser.preview?.takeIf { it.id == id }
        ?: throw WebError(Status.CONFLICT, "预览已失效，请重新生成发布快照")

    private fun findSubmission(browser: Browser, id: String): MarketSubmission = browser.submissions[id]
        ?: throw WebError(Status.NOT_FOUND, "找不到本次发布记录")

    private fun requireAccount(browser: Browser): MarketGitHubUser = browser.account
        ?: throw WebError(Status.UNAUTHORIZED, "请先在 Web 授权 GitHub")

    private fun github(browser: Browser): MarketGitHub = MarketGitHub(browser.token)

    private inline fun <T> withBrowser(browser: Browser?, action: (Browser) -> T): T {
        val active = browser ?: throw WebError(Status.UNAUTHORIZED, "发布会话已过期，请重新授权 GitHub")
        return synchronized(active) {
            if (closed || active.expires <= now()) throw WebError(Status.UNAUTHORIZED, "发布会话已过期，请重新授权 GitHub")
            action(active)
        }
    }

    private fun findBrowser(key: String?, origin: String): Browser? {
        val expired = mutableListOf<Browser>()
        val browser = synchronized(sessions) {
            val iterator = sessions.values.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.expires <= now() && !item.submitting) {
                    iterator.remove()
                    expired.add(item)
                }
            }
            sessions[key]?.takeIf { it.origin == origin && it.expires > now() }
        }
        expired.forEach { synchronized(it) { dispose(it) } }
        return browser
    }

    private fun dispose(browser: Browser) {
        browser.expires = 0
        browser.preview?.discard()
        browser.submissions.values.forEach { it.snapshot.discard() }
        browser.preview = null
        browser.token = null
        browser.account = null
        browser.pending = null
        browser.submissions.clear()
    }

    // 只信任回环连接上的 HTTPS 反向代理，避免局域网请求伪造转发头。
    private fun browserOrigin(session: IHTTPSession): String? {
        if (session.remoteIpAddress != "127.0.0.1") return null
        val host = session.headers["host"]?.lowercase() ?: return null
        val https = session.headers["x-forwarded-proto"] == "https"
        val scheme = if (https) "https" else "http"
        val uri = runCatching { URI("$scheme://$host") }.getOrNull() ?: return null
        if (uri.host == null || uri.rawAuthority != host || uri.rawUserInfo != null || uri.port > 65535) return null
        if (!https && uri.host !in setOf("127.0.0.1", "localhost", "[::1]")) return null
        val port = uri.port.takeIf { it != -1 && it != if (https) 443 else 80 }
        return "$scheme://${uri.host}${port?.let { ":$it" }.orEmpty()}"
    }

    private fun answer(json: JSONObject, status: Status = Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString()).apply { protectResponse() }

    private fun problem(status: Status, message: String) = answer(JSONObject().put("error", message), status)
        .apply { addHeader("Connection", "close") }

    private fun Response.protectResponse() {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun Response.withCookie(origin: String, key: String, age: Long): Response = apply {
        val secure = origin.startsWith("https://")
        val path = if (secure) "/" else "/api/market/"
        addHeader("Set-Cookie", "${sessionCookieName(origin)}=$key; Path=$path; Max-Age=$age; HttpOnly; SameSite=Strict${if (secure) "; Secure" else ""}")
    }

    private fun sessionCookieName(origin: String) = if (origin.startsWith("https://")) "__Host-assistant-market" else "assistant-market-local"

    private fun newKey(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).apply(random::nextBytes))

    private class Browser(val origin: String) {
        @Volatile var expires = now() + 900_000
        @Volatile var submitting = false
        var pending: PendingDevice? = null
        var account: MarketGitHubUser? = null
        var token: String? = null
        var preview: MarketPublishSnapshot? = null
        val submissions = linkedMapOf<String, MarketSubmission>()
    }

    private class PendingDevice(val clientId: String, val deviceCode: String, val userCode: String,
                                var interval: Long, val expires: Long, var nextPoll: Long) {
        fun summary() = JSONObject().put("deviceState", "pending").put("userCode", userCode)
            .put("verificationUrl", "https://github.com/login/device").put("pollAfterMs", (nextPoll - now()).coerceAtLeast(250))
            .put("expiresInMs", (expires - now()).coerceAtLeast(0))
    }

    private class WebError(val status: Status, message: String) : Exception(message)

    private data class CheckedCatalog(val checkedAt: Long, val result: Result<List<MarketRelease>>)

    companion object {
        private const val SESSION_SECONDS = 8 * 60 * 60L
        private fun now() = SystemClock.elapsedRealtime()
    }
}
