package com.forest.android.assistant.core.web

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.flow.FlowCompiler
import com.forest.android.assistant.core.flow.FlowTemplates
import com.forest.android.assistant.core.flow.FlowTemplateContent
import com.forest.android.assistant.core.image.AssetCropper
import com.forest.android.assistant.core.image.CropRegion
import com.forest.android.assistant.core.task.ImageHistory
import com.forest.android.assistant.core.task.TaskLoop
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.core.task.TaskGroups
import com.forest.android.assistant.core.task.TaskOrder
import com.forest.android.assistant.core.task.TaskZip
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Base64

data class LanWebState(
    val running: Boolean = false,
    val url: String? = null,
    val error: String? = null
)

object LanWebServer {
    private const val PORT = 8765

    private val _state = MutableStateFlow(LanWebState())
    val state = _state.asStateFlow()

    private var server: EditorHttpServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server?.wasStarted() == true) return
        val appContext = context.applicationContext
        runCatching {
            val instance = EditorHttpServer(appContext)
            instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = instance
            val address = localIpv4Address() ?: error("未检测到可用的局域网 IP，请先连接 Wi-Fi")
            _state.value = LanWebState(
                running = true,
                url = "http://$address:$PORT/"
            )
        }.onFailure {
            server?.stop()
            server = null
            _state.value = LanWebState(error = it.message ?: "局域网服务启动失败")
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        _state.value = LanWebState()
    }

    private fun localIpv4Address(): String? {
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { network -> network.inetAddresses.toList() }
        }.getOrDefault(emptyList())
        return addresses
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.address.size == 4 }
            .sortedByDescending { it.isSiteLocalAddress }
            .firstOrNull()
            ?.hostAddress
    }
}

private class EditorHttpServer(
    private val context: Context
) : NanoHTTPD(LanWebServerPort.value) {
    override fun serve(session: IHTTPSession): Response {
        return runCatching { route(session) }
            .getOrElse { error ->
                json(
                    Response.Status.INTERNAL_ERROR,
                    JSONObject().put("error", error.message ?: "服务器内部错误")
                )
            }
    }

    private fun route(session: IHTTPSession): Response {
        // NanoHTTPD already decodes the URI. Decoding twice corrupts literal % and + in names.
        val segments = session.uri.trim('/').split('/').filter(String::isNotBlank)

        if (segments.isEmpty() || (segments.size == 1 && segments[0] == "index.html")) {
            return asset("web/index.html", "text/html; charset=utf-8")
        }
        if (segments == listOf("app.js")) return asset("web/app.js", "text/javascript; charset=utf-8")
        if (segments == listOf("styles.css")) return asset("web/styles.css", "text/css; charset=utf-8")
        if (segments == listOf("app-icon.png")) return asset("web/app-icon.png", "image/png")
        if (segments == listOf("api", "health")) {
            return json(Response.Status.OK, JSONObject().put("ok", true))
        }
        if (segments.size < 2 || segments[0] != "api") {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "接口不存在"))
        }

        return when {
            segments == listOf("api", "task-bundle") -> exportTaskBundle(session)
            segments == listOf("api", "tasks") -> tasks(session)
            segments == listOf("api", "tasks", "order") -> taskOrder(session)
            segments[1] == "task-groups" && segments.size <= 3 -> groupRoute(session, segments.getOrNull(2))
            segments[1] == "flow-templates" -> templateRoute(session, segments.getOrNull(2), segments.drop(3))
            segments.size >= 3 && segments[1] == "tasks" -> taskRoute(
                session,
                segments[2],
                segments.drop(3)
            )
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "接口不存在"))
        }
    }

    private fun exportTaskBundle(session: IHTTPSession): Response {
        if (session.method != Method.GET) return methodNotAllowed()
        // 与 APP 的“导出全部”共用打包逻辑；锁住任务目录及分组引用，避免重命名/删除混入备份。
        val result = synchronized(TaskManager) {
            if (TaskManager.listTasks(context).isEmpty()) {
                return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "暂无可导出的任务"))
            }
            TaskZip.exportAll(context)
        } ?: return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "导出全部任务失败，请重试"))
        val filename = URLEncoder.encode(result.file.name, "UTF-8").replace("+", "%20")
        return newFixedLengthResponse(Response.Status.OK, "application/zip", FileInputStream(result.file), result.sizeBytes).apply {
            addHeader("Content-Disposition", "attachment; filename=\"tasks.zip\"; filename*=UTF-8''$filename")
            addHeader("Cache-Control", "no-store")
        }
    }

    @Synchronized
    private fun tasks(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> {
            val items = JSONArray()
            TaskManager.listTasks(context).forEach { task ->
                val flowFile = FlowCompiler.sourceFile(context, task.name)
                val visual = flowFile.isFile
                val config = TaskManager.readConfig(context, task.name)
                items.put(
                    JSONObject()
                        .put("name", task.name)
                        .put("type", if (visual) "flow" else "script")
                        .put("description", config.desc)
                        .put("target", config.target ?: "")
                        .put("globalCount", config.loop.count)
                        .put("imageCount", TaskManager.listImages(context, task.name).size)
                )
            }
            json(Response.Status.OK, JSONObject().put("tasks", items)
                .put("orderRevision", TaskOrder.read(context).revision)
                .put("groups", JSONArray(TaskGroups.list(context).map(TaskGroups::toJson)))
                .put("templates", JSONArray(FlowTemplates.list(context).map(FlowTemplates::toJson))))
        }
        Method.POST -> {
            requireIdle()
            val request = jsonBody(session)
            val name = request.optString("name").trim()
            val type = request.optString("type", "script")
            val scaffold = if (type == "flow") TaskManager.Scaffold.VISUAL else TaskManager.Scaffold.BLANK
            val task = TaskManager.createTask(context, name, scaffold)
                ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "名称非法或任务已存在"))
            json(Response.Status.CREATED, JSONObject().put("name", task.name).put("type", type))
        }
        else -> methodNotAllowed()
    }

    /** 任务列表顺序：GET 返回当前顺序与版本，PUT 携带 If-Match 保存拖拽结果。 */
    @Synchronized
    private fun taskOrder(session: IHTTPSession): Response = try {
        when (session.method) {
            Method.GET -> {
                val snapshot = TaskOrder.read(context)
                json(Response.Status.OK, JSONObject()
                    .put("taskNames", JSONArray(TaskManager.listTasks(context).map { it.name }))
                    .put("revision", snapshot.revision))
            }
            Method.PUT -> {
                val revision = session.headers["if-match"]?.toLongOrNull()
                    ?: throw IllegalArgumentException("缺少任务顺序版本，请刷新后重试")
                val names = jsonBody(session).getJSONArray("taskNames")
                val snapshot = TaskOrder.save(context, revision,
                    (0 until names.length()).map(names::getString))
                json(Response.Status.OK, JSONObject().put("ok", true).put("revision", snapshot.revision))
            }
            else -> methodNotAllowed()
        }
    } catch (error: ConcurrentModificationException) {
        json(Response.Status.CONFLICT, JSONObject().put("error", error.message))
    } catch (error: IllegalArgumentException) {
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", error.message))
    } catch (error: org.json.JSONException) {
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", "任务顺序数据不合法"))
    }

    private fun groupRoute(session: IHTTPSession, id: String?): Response = try {
        when {
            id == null && session.method == Method.GET -> json(Response.Status.OK,
                JSONObject().put("groups", JSONArray(TaskGroups.list(context).map(TaskGroups::toJson))))
            id == null && session.method == Method.POST -> {
                val request = jsonBody(session)
                val members = request.getJSONArray("taskNames")
                val group = TaskGroups.create(context, request.getString("name"),
                    (0 until members.length()).map(members::getString))
                json(Response.Status.CREATED, JSONObject().put("group", TaskGroups.toJson(group)))
            }
            id != null && session.method == Method.PUT -> {
                val revision = session.headers["if-match"]?.toLongOrNull()
                require(revision != null) { "缺少分组版本，请刷新后重试" }
                val request = jsonBody(session)
                val members = request.getJSONArray("taskNames")
                val group = TaskGroups.update(context, id, revision, request.getString("name"),
                    (0 until members.length()).map(members::getString))
                json(Response.Status.OK, JSONObject().put("group", TaskGroups.toJson(group)))
            }
            id != null && session.method == Method.DELETE -> {
                val revision = session.headers["if-match"]?.toLongOrNull()
                require(revision != null) { "缺少分组版本，请刷新后重试" }
                TaskGroups.delete(context, id, revision)
                json(Response.Status.OK, JSONObject().put("ok", true))
            }
            else -> methodNotAllowed()
        }
    } catch (error: ConcurrentModificationException) {
        json(Response.Status.CONFLICT, JSONObject().put("error", error.message))
    } catch (error: NoSuchElementException) {
        json(Response.Status.NOT_FOUND, JSONObject().put("error", error.message))
    } catch (error: IllegalArgumentException) {
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", error.message))
    } catch (error: org.json.JSONException) {
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", "分组数据不合法"))
    }

    @Synchronized
    private fun templateRoute(session: IHTTPSession, id: String?, rest: List<String>): Response = try {
        when {
            id == null && session.method == Method.GET -> json(Response.Status.OK,
                JSONObject().put("templates", JSONArray(FlowTemplates.list(context).map(FlowTemplates::toJson))))
            id == null && session.method == Method.POST -> {
                val request = jsonBody(session)
                val info = FlowTemplates.create(context, request.getString("name"), request.optString("description"),
                    request.optString("source", FlowCompiler.initialDocument()),
                    request.optString("sourceTask").takeIf(String::isNotBlank),
                    request.optString("sourceTemplate").takeIf(String::isNotBlank))
                json(Response.Status.CREATED, JSONObject().put("template", FlowTemplates.toJson(info)))
            }
            id != null -> {
                val info = FlowTemplates.get(context, id)
                val resource = FlowTemplates.resourceName(id)
                when {
                    rest.isEmpty() && session.method == Method.GET -> taskDocument(resource, info)
                    rest.isEmpty() && session.method == Method.PUT -> {
                        val request = jsonBody(session)
                        val updated = FlowTemplates.update(context, id, templateRevision(session),
                            request.getString("name"), request.optString("description"))
                        json(Response.Status.OK, JSONObject().put("template", FlowTemplates.toJson(updated)))
                    }
                    rest.isEmpty() && session.method == Method.DELETE -> {
                        FlowTemplates.delete(context, id, templateRevision(session))
                        json(Response.Status.OK, JSONObject().put("ok", true))
                    }
                    rest == listOf("flow") && session.method == Method.PUT -> {
                        val updated = FlowTemplates.save(context, id, templateRevision(session), requestSource(session))
                        json(Response.Status.OK, JSONObject().put("ok", true).put("template", FlowTemplates.toJson(updated))
                            .put("scriptSource", TaskManager.scriptFile(context, resource).readText()))
                    }
                    rest == listOf("validate") && session.method == Method.POST -> {
                        val document = FlowTemplateContent.parse(requestSource(session))
                        json(Response.Status.OK, JSONObject().put("ok", true).put("code", FlowTemplateContent.preview(document)))
                    }
                    rest == listOf("insert") && session.method == Method.POST -> {
                        val request = jsonBody(session)
                        val targetTemplate = request.optString("targetTemplate").takeIf(String::isNotBlank)
                        if (targetTemplate != null) FlowTemplates.checked(context, targetTemplate, templateRevision(session))
                        val document = FlowTemplates.insert(context, id,
                            request.optString("targetTask").takeIf(String::isNotBlank), targetTemplate)
                        json(Response.Status.OK, JSONObject().put("document", document))
                    }
                    rest.firstOrNull() in listOf("images", "image-history") -> {
                        val mutation = session.method != Method.GET && rest.lastOrNull() != "rename-preview"
                        if (mutation) FlowTemplates.checked(context, id, templateRevision(session))
                        val response = taskRoute(session, resource, rest, templateResource = true)
                        if (mutation && response.status.requestStatus in 200..299) {
                            response.addHeader("X-Template-Revision", FlowTemplates.touch(context, id).revision.toString())
                        }
                        response
                    }
                    else -> methodNotAllowed()
                }
            }
            else -> methodNotAllowed()
        }
    } catch (error: ConcurrentModificationException) {
        json(Response.Status.CONFLICT, JSONObject().put("error", error.message))
    } catch (error: NoSuchElementException) {
        json(Response.Status.NOT_FOUND, JSONObject().put("error", error.message))
    } catch (error: IllegalArgumentException) {
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", error.message))
    } catch (error: org.json.JSONException) {
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", "模板数据不合法"))
    }

    private fun templateRevision(session: IHTTPSession): Long =
        session.headers["if-match"]?.toLongOrNull() ?: throw IllegalArgumentException("缺少模板版本，请重新打开模板")

    @Synchronized
    private fun taskRoute(
        session: IHTTPSession,
        taskName: String,
        rest: List<String>,
        templateResource: Boolean = false
    ): Response {
        if (!templateResource && TaskManager.listTasks(context).none { it.name == taskName }) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "任务不存在"))
        }
        return when {
            rest.isEmpty() && session.method == Method.GET -> taskDocument(taskName)
            !templateResource && rest == listOf("copy") && session.method == Method.POST -> {
                val request = jsonBody(session)
                val result = TaskManager.copyTask(context, taskName, request.getString("name"))
                result.fold(onSuccess = { copied ->
                    json(Response.Status.CREATED, JSONObject().put("name", copied.name)
                        .put("type", if (FlowCompiler.isVisual(context, copied.name)) "flow" else "script"))
                }, onFailure = { error ->
                    val status = if (error is IllegalArgumentException) Response.Status.BAD_REQUEST else Response.Status.INTERNAL_ERROR
                    json(status, JSONObject().put("error", error.message ?: "复制任务失败"))
                })
            }
            rest.isEmpty() && session.method == Method.PUT -> saveTaskSettings(session, taskName)
            rest.isEmpty() && session.method == Method.DELETE -> {
                TaskManager.deleteTask(context, taskName).getOrThrow()
                json(Response.Status.OK, JSONObject().put("ok", true))
            }
            rest == listOf("script") && session.method == Method.GET ->
                textFile(TaskManager.scriptFile(context, taskName))
            rest == listOf("script") && session.method == Method.PUT -> saveScript(session, taskName)
            rest == listOf("flow") && session.method == Method.GET ->
                flowDocument(taskName)
            rest == listOf("flow") && session.method == Method.PUT -> saveFlow(session, taskName)
            rest == listOf("validate") && session.method == Method.POST -> validateFlow(session)
            rest == listOf("images") && session.method == Method.GET -> listImages(taskName)
            rest == listOf("images") && session.method == Method.POST -> uploadImage(session, taskName)
            rest.size == 3 && rest[0] == "images" && rest[2] == "crop" && session.method == Method.GET ->
                cropPreview(taskName, rest[1])
            rest.size == 3 && rest[0] == "images" && rest[2] == "crop" && session.method == Method.POST ->
                cropImage(session, taskName, rest[1])
            rest.size == 3 && rest[0] == "images" && rest[2] == "rename-preview" && session.method == Method.POST ->
                previewImageRename(session, taskName, rest[1])
            rest == listOf("image-history") && session.method == Method.GET -> listImageHistory(taskName)
            rest.size == 2 && rest[0] == "image-history" && session.method == Method.GET ->
                serveHistoryImage(taskName, rest[1])
            rest.size == 3 && rest[0] == "image-history" && rest[2] == "restore" && session.method == Method.POST ->
                restoreHistoryImage(taskName, rest[1])
            rest.size == 2 && rest[0] == "images" && session.method == Method.GET ->
                serveImage(taskName, rest[1])
            rest.size == 2 && rest[0] == "images" && session.method == Method.PUT ->
                renameImage(session, taskName, rest[1])
            rest.size == 2 && rest[0] == "images" && session.method == Method.DELETE ->
                deleteImage(taskName, rest[1])
            else -> methodNotAllowed()
        }
    }

    private fun taskDocument(taskName: String, template: FlowTemplates.Info? = null): Response {
        val flowFile = FlowCompiler.sourceFile(context, taskName)
        val draftFile = FlowCompiler.draftFile(context, taskName)
        val visual = flowFile.isFile
        val sourceFile = when {
            visual && draftFile.isFile -> draftFile
            visual -> flowFile
            else -> TaskManager.scriptFile(context, taskName)
        }
        val source = sourceFile.readText()
        val editableScript = if (visual) TaskManager.scriptFile(context, taskName).readText() else source
        val settings = taskSettings(taskName)
        if (template != null) settings.put("name", template.name).put("description", template.description)
            .put("templateId", template.id).put("revision", template.revision)
        return json(
            Response.Status.OK,
            settings
                .put("type", if (visual) "flow" else "script")
                .put("source", source)
                .put("scriptSource", editableScript)
                .put("draft", visual && draftFile.isFile)
        )
    }

    private fun taskSettings(taskName: String): JSONObject {
        val config = TaskManager.readConfig(context, taskName)
        return JSONObject()
            .put("name", taskName)
            .put("description", config.desc)
            .put("target", config.target.orEmpty())
            .put("globalCount", config.loop.count)
            .put("loopMode", config.loop.mode.key)
            .put("durationSec", config.loop.durationSec)
            .put("intervalSec", config.loop.intervalSec)
            .put("designWidth", config.designWidth)
            .put("designHeight", config.designHeight)
    }

    private fun saveTaskSettings(session: IHTTPSession, taskName: String): Response {
        val request = jsonBody(session)
        val loopMode = TaskLoop.Mode.entries.firstOrNull { it.key == request.getString("loopMode") }
            ?: error("运行方式不合法")
        val settings = TaskManager.TaskConfig(
            desc = request.getString("description").trim(),
            target = request.getString("target").trim(),
            loop = TaskLoop(loopMode, request.getInt("globalCount"), request.getLong("durationSec"), request.getInt("intervalSec")),
            designWidth = request.getInt("designWidth"),
            designHeight = request.getInt("designHeight")
        )
        val saved = TaskManager.updateTask(context, taskName, request.getString("name"), settings).getOrThrow()
        return json(Response.Status.OK, taskSettings(saved.name))
    }

    private fun flowDocument(taskName: String): Response {
        val flowFile = FlowCompiler.sourceFile(context, taskName)
        if (!flowFile.isFile) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "当前任务不是流程任务"))
        }
        val draft = FlowCompiler.draftFile(context, taskName)
        val source = if (draft.isFile) draft else flowFile
        return json(
            Response.Status.OK,
            JSONObject()
                .put("source", source.readText())
                .put("draft", draft.isFile)
        )
    }

    private fun saveScript(session: IHTTPSession, taskName: String): Response {
        requireIdle()
        val source = requestSource(session)
        require(source.toByteArray().size <= 2_000_000) { "脚本不能超过 2 MB" }
        writeAtomic(TaskManager.scriptFile(context, taskName), source)
        FlowCompiler.convertToScript(context, taskName)
        return json(Response.Status.OK, JSONObject().put("ok", true).put("type", "script"))
    }

    private fun saveFlow(session: IHTTPSession, taskName: String): Response {
        requireIdle()
        val source = requestSource(session)
        val document = JSONObject(source)
        check(FlowCompiler.isVisual(context, taskName)) { "当前任务不是流程任务" }
        document.put("globalCount", TaskManager.readConfig(context, taskName).loop.count)
        FlowCompiler.save(context, taskName, document.toString(2))
        val script = TaskManager.scriptFile(context, taskName).readText()
        return json(Response.Status.OK, JSONObject().put("ok", true).put("scriptSource", script))
    }

    private fun validateFlow(session: IHTTPSession): Response {
        return runCatching {
            val source = requestSource(session)
            val code = FlowCompiler.compile(JSONObject(source))
            json(
                Response.Status.OK,
                JSONObject().put("ok", true).put("code", code)
            )
        }.getOrElse {
            json(
                Response.Status.OK,
                JSONObject().put("ok", false).put("error", it.message ?: "流程校验失败")
            )
        }
    }

    private fun listImages(taskName: String): Response {
        val items = JSONArray()
        TaskManager.imageInfos(context, taskName).forEach { info ->
            items.put(
                JSONObject()
                    .put("name", info.file.name)
                    .put("size", info.sizeBytes)
                    .put("width", info.width)
                    .put("height", info.height)
                    .put("version", imageVersion(info.file))
            )
        }
        return json(Response.Status.OK, JSONObject().put("images", items))
    }

    private fun uploadImage(session: IHTTPSession, taskName: String): Response {
        requireIdle()
        val request = jsonBody(session)
        val rawName = request.optString("name", "image.png")
        val encoded = request.optString("data")
        require(encoded.isNotBlank()) { "图片内容为空" }
        require(encoded.length <= 21_000_000) { "图片不能超过 15 MB" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.isNotEmpty()) { "图片内容为空" }
        val directory = TaskManager.imagesDir(context, taskName)
        val safeName = uniqueImageName(directory, rawName)
        writeAtomic(File(directory, safeName), bytes)
        return json(Response.Status.CREATED, JSONObject().put("name", safeName))
    }

    private fun serveImage(taskName: String, fileName: String): Response {
        val file = imageFile(taskName, fileName)
        if (file == null) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "图片不存在"))
        }
        return imageResponse(file)
    }

    private fun serveHistoryImage(taskName: String, id: String): Response = synchronized(ImageHistory) {
        val entry = ImageHistory.find(context, taskName, id)
            ?: return@synchronized json(Response.Status.NOT_FOUND, JSONObject().put("error", "历史素材不存在或已被移回"))
        imageResponse(entry.file)
    }

    private fun imageResponse(file: File): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            mimeType(file.extension),
            FileInputStream(file),
            file.length()
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun imageVersion(file: File): String = "${file.lastModified()}-${file.length()}"

    private fun cropPreview(taskName: String, fileName: String): Response {
        val file = ImageHistory.source(context, taskName, fileName)
        val version = imageVersion(file)
        val preview = AssetCropper.load(context, taskName, fileName)
        val bytes = try {
            val output = ByteArrayOutputStream()
            check(preview.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法生成裁剪预览" }
            output.toByteArray()
        } finally {
            preview.bitmap.recycle()
        }
        check(file.isFile && imageVersion(file) == version) { "图片已变化，请重新打开裁剪" }
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Image-Width", preview.width.toString())
                addHeader("X-Image-Height", preview.height.toString())
                addHeader("X-Image-Version", version)
            }
    }

    private fun cropImage(session: IHTTPSession, taskName: String, fileName: String): Response {
        requireIdle()
        val request = jsonBody(session)
        val region = CropRegion(request.getInt("left"), request.getInt("top"), request.getInt("right"), request.getInt("bottom"))
        require(region.right > region.left && region.bottom > region.top) { "裁剪区域无效" }
        synchronized(ImageHistory) {
            val file = ImageHistory.source(context, taskName, fileName)
            check(imageVersion(file) == request.getString("version")) { "图片已变化，请重新打开裁剪" }
            AssetCropper.save(context, taskName, fileName, region)
        }
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun listImageHistory(taskName: String): Response {
        val entries = JSONArray()
        ImageHistory.list(context, taskName).forEach { entry ->
            entries.put(JSONObject()
                .put("id", entry.file.name)
                .put("name", entry.originalName)
                .put("archivedAt", entry.archivedAt))
        }
        return json(Response.Status.OK, JSONObject().put("entries", entries))
    }

    private fun restoreHistoryImage(taskName: String, id: String): Response {
        requireIdle()
        val restored = synchronized(ImageHistory) {
            val entry = ImageHistory.find(context, taskName, id)
                ?: error("历史素材不存在或已被移回")
            ImageHistory.moveBack(context, taskName, entry)
        }
        return json(Response.Status.OK, JSONObject().put("name", restored.name))
    }

    private fun imageFile(taskName: String, fileName: String): File? {
        val directory = TaskManager.imagesDir(context, taskName).canonicalFile
        val file = File(directory, fileName)
        return file.takeIf { it.isFile && it.canonicalFile.parentFile == directory && it.name == fileName }
    }

    private fun renameImage(session: IHTTPSession, taskName: String, fileName: String): Response {
        requireIdle()
        val preview = imageRenamePlan(session, taskName, fileName)
        val renamed = TaskManager.renameImageWithScript(preview) ?: error("图片重命名失败")
        return json(Response.Status.OK, imageRenameSummary(preview).put("name", renamed.name))
    }

    private fun previewImageRename(session: IHTTPSession, taskName: String, fileName: String): Response {
        val preview = imageRenamePlan(session, taskName, fileName)
        val lines = JSONArray()
        preview.plan?.changes?.take(20)?.forEach { change ->
            lines.put(JSONObject()
                .put("line", change.lineNumber)
                .put("before", change.before.take(800))
                .put("after", change.after.take(800)))
        }
        return json(Response.Status.OK, imageRenameSummary(preview).put("lines", lines))
    }

    private fun imageRenamePlan(session: IHTTPSession, taskName: String, fileName: String): TaskManager.ImageRenamePreview {
        val file = imageFile(taskName, fileName) ?: error("图片不存在")
        val name = jsonBody(session).getString("name").trim()
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it in "/\\:*?\"<>|'`" || it.isISOControl() }) { "图片文件名不合法" }
        return TaskManager.previewImageRename(context, taskName, file, name)
            ?: error("名称未修改、不合法或已有同名图片")
    }

    private fun imageRenameSummary(preview: TaskManager.ImageRenamePreview): JSONObject = JSONObject()
        .put("name", preview.newName)
        .put("scriptReferences", preview.plan?.replacementCount ?: 0)
        .put("scriptLines", preview.plan?.changedLineCount ?: 0)
        .put("flowFiles", JSONArray(preview.flowChanges.map { it.file.name }))

    private fun deleteImage(taskName: String, fileName: String): Response {
        requireIdle()
        val file = imageFile(taskName, fileName)
            ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "图片不存在"))
        require(TaskManager.deleteImage(file)) { "图片删除失败" }
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun requestSource(session: IHTTPSession): String {
        val body = bodyText(session)
        if (body.isBlank()) return ""
        return runCatching { JSONObject(body).getString("source") }.getOrDefault(body)
    }

    private fun jsonBody(session: IHTTPSession): JSONObject {
        val body = bodyText(session)
        require(body.isNotBlank()) { "请求内容为空" }
        return JSONObject(body)
    }

    private fun bodyText(session: IHTTPSession): String {
        // NanoHTTPD treats JSON POST bodies without an explicit charset as ASCII.
        if (session.headers["content-type"]?.substringBefore(';')?.trim()
                ?.equals("application/json", ignoreCase = true) == true) {
            session.headers["content-type"] = "application/json; charset=UTF-8"
        }
        val body = HashMap<String, String>()
        session.parseBody(body)
        body["postData"]?.let { return it }
        val temporaryBody = body["content"] ?: return ""
        return File(temporaryBody).readText()
    }

    private fun requireIdle() {
        check(!ScriptRunner.state.value.running && !com.forest.android.assistant.core.recording.RecordingService.active) {
            "请先完成录制或停止正在运行的任务"
        }
    }

    private fun asset(path: String, mime: String): Response = runCatching {
        val input = context.assets.open(path)
        newChunkedResponse(Response.Status.OK, mime, input).apply { addHeader("Cache-Control", "no-cache") }
    }.getOrElse {
        json(Response.Status.NOT_FOUND, JSONObject().put("error", "页面资源不存在"))
    }

    private fun textFile(file: File): Response {
        if (!file.isFile) return json(Response.Status.NOT_FOUND, JSONObject().put("error", "文件不存在"))
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/plain; charset=utf-8",
            ByteArrayInputStream(file.readBytes()),
            file.length()
        )
    }

    private fun json(status: Response.Status, content: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", content.toString())
            .apply { addHeader("Cache-Control", "no-store") }

    private fun methodNotAllowed(): Response =
        json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "请求方法不支持"))

    private fun writeAtomic(file: File, text: String) = writeAtomic(file, text.toByteArray(Charsets.UTF_8))

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun uniqueImageName(directory: File, rawName: String): String {
        val fileName = File(rawName.substringAfterLast('/').substringAfterLast('\\')).name
        val dot = fileName.lastIndexOf('.')
        val rawBase = if (dot > 0) fileName.substring(0, dot) else fileName
        val base = rawBase.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim()
            .take(100)
            .ifBlank { "image" }
        val rawExtension = if (dot > 0) fileName.substring(dot + 1) else "png"
        val extension = rawExtension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }?.lowercase() ?: "png"
        var candidate = "$base.$extension"
        var index = 1
        while (File(directory, candidate).exists()) {
            candidate = "${base}_$index.$extension"
            index++
        }
        return candidate
    }

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }
}

private object LanWebServerPort {
    const val value = 8765
}
