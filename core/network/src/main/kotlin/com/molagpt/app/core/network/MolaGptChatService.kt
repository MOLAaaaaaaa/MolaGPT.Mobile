package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.TitleRequest
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.network.dto.ChatRequestBody
import com.molagpt.app.core.network.dto.WireEnabledTools
import com.molagpt.app.core.network.sse.SsePayload
import com.molagpt.app.core.network.sse.sseFlow
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.math.min

/**
 * 真实 MolaGPT 服务实现（阶段 4）。
 * - 鉴权统一：每次请求前经 [ShortTokenManager.freshToken] 取 60 秒短 token（游客/登录同路径，含 ALTCHA）。
 * - SSE 流式：用共享 OkHttpClient 直接读 BufferedSource 逐行（最稳定）；解析止于本层只产 [StreamEvent]。
 * - stop / title：Ktor JSON。
 */
class MolaGptChatService(
    private val http: MolaHttp,
    private val registry: ModelRegistry,
    private val shortTokenManager: ShortTokenManager,
    private val dispatchers: DispatcherProvider,
) : ChatService {

    override fun sendMessage(request: ChatRequest): Flow<StreamEvent> = flow {
        val jwt = shortTokenManager.freshToken()
        var apiUrl = registry.apiUrlFor(request.modelId)
        var body = buildBody(request)
        if (isAutoRouteEndpoint(apiUrl, request.modelId)) {
            emit(StreamEvent.Pending("正在选择模型", "MolaGPT Routes", routes = true))
            val route = resolveAutoRoute(apiUrl, body, jwt)
            body = applyAutoRoute(body, route)
            apiUrl = route.apiUrl
            emit(StreamEvent.Pending("已选择模型", route.displayName ?: route.modelName, routes = true))
        }
        val url = MolaEndpoints.absolute(apiUrl)
        val bodyJson = http.json.encodeToString(ChatRequestBody.serializer(), body)
        val lastMessage = body.messages.lastOrNull()
        val lastPreview = lastMessage?.content?.let { content ->
            ((content as? JsonPrimitive)?.contentOrNull ?: content.toString())
                .replace('\n', ' ')
                .take(80)
        }
        Logger.d(
            "MolaChat",
            "POST $url model=${body.model} messages=${body.messages.size} " +
                "lastRole=${lastMessage?.role} lastPreview=$lastPreview",
        )
        val httpReq = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $jwt")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()
        val parser = StreamParser(http.json)
        val offset = ResumeOffset()
        try {
            val finished = collectSseRequest(httpReq, "对话请求", parser, offset)
            if (!finished) {
                throw IOException("流式连接提前结束")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: MolaAuthExpiredException) {
            throw e
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                val restored = resumeUntilFinished(apiUrl, body.sessionId, offset, parser, e)
                if (!restored) emit(StreamEvent.Failed("网络中断，续传失败"))
            }
        } catch (e: Exception) {
            // 用户停止会导致连接被取消并抛 IOException——此时协程已不活跃，安静收尾。
            if (currentCoroutineContext().isActive) {
                Logger.w("MolaChat", "stream error", e)
                emit(StreamEvent.Failed(e.message ?: "网络错误"))
            }
        }
    }.flowOn(dispatchers.io)

    override suspend fun stopGeneration(streamSessionId: String) {
        val jwt = runCatching { shortTokenManager.freshToken() }.getOrNull() ?: return
        runCatching {
            http.client.post(MolaEndpoints.absolute(MolaEndpoints.STOP_STREAM)) {
                header(HttpHeaders.Authorization, "Bearer $jwt")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("session_id", streamSessionId) })
            }
        }.onFailure { Logger.w("MolaChat", "stopGeneration failed", it) }
    }

    override suspend fun checkStreamStatus(streamSessionId: String): StreamStatus? = withContext(dispatchers.io) {
        val jwt = runCatching { shortTokenManager.freshToken() }.getOrNull() ?: return@withContext null
        runCatching {
            val resp = http.client.post(MolaEndpoints.absolute(MolaEndpoints.CHECK_STREAM_STATUS)) {
                header(HttpHeaders.Authorization, "Bearer $jwt")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { putJsonArray("session_ids") { add(streamSessionId) } })
            }
            val root = http.json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val entry = root["results"]?.jsonObject?.get(streamSessionId) as? JsonObject
                ?: return@runCatching null
            StreamStatus(
                status = entry["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                conversationId = entry["conversation_id"]?.jsonPrimitive?.contentOrNull,
                chunksCount = entry["chunks_count"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.getOrNull()
    }

    /**
     * 断点续传：回到原聊天代理，POST {action:resume, session_id, offset}。
     * fetch_completed_stream.php 只用于已完成后台任务补收，不能恢复进行中的流。
     */
    override fun resumeStream(apiUrl: String, streamSessionId: String, offset: Int): Flow<StreamEvent> = flow {
        val parser = StreamParser(http.json)
        val counter = ResumeOffset(offset)
        val finished = collectSseRequest(
            request = buildResumeRequest(apiUrl, streamSessionId, offset),
            operationName = "流恢复",
            parser = parser,
            offset = counter,
        )
        if (!finished) parser.finishTail(null).forEach { emit(it) }
    }.flowOn(dispatchers.io)

    override suspend fun uploadFile(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        conversationId: String,
    ): FileInfo = withContext(dispatchers.io) {
        val jwt = shortTokenManager.freshToken()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("conversation_id", conversationId)
            .addFormDataPart("files[]", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val req = Request.Builder()
            .url(MolaEndpoints.absolute(MolaEndpoints.BATCH_UPLOAD))
            .header("Authorization", "Bearer $jwt")
            .post(multipart)
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw MolaAuthExpiredException()
            val text = resp.body?.string().orEmpty()
            val fileObj = runCatching {
                http.json.parseToJsonElement(text).jsonObject["files"]?.jsonArray?.firstOrNull()?.jsonObject
            }.getOrNull()
            FileInfo(
                id = fileObj?.get("id")?.jsonPrimitive?.contentOrNull ?: Ids.newFragmentId(),
                name = fileObj?.get("filename")?.jsonPrimitive?.contentOrNull ?: fileName,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                url = fileObj?.get("url")?.jsonPrimitive?.contentOrNull,
                sandboxPath = fileObj?.get("filePathOnHost")?.jsonPrimitive?.contentOrNull,
                uploadStatus = if (resp.isSuccessful) UploadStatus.UPLOADED else UploadStatus.FAILED,
            )
        }
    }

    override suspend fun fetchFiles(conversationId: String): List<FileInfo> = emptyList()

    override suspend fun generateTitle(request: TitleRequest): String {
        val fallback = request.fallbackTitle()
        val jwt = runCatching { shortTokenManager.freshToken() }.getOrNull() ?: return fallback
        val user = request.firstUserText.orEmpty().trim()
        val assistantPreview = request.lastAssistantText.orEmpty().trim().take(1000)
        if (user.isBlank() || assistantPreview.isBlank()) return fallback
        return runCatching {
            val resp = http.client.post(MolaEndpoints.absolute(MolaEndpoints.GENERATE_TITLE)) {
                header(HttpHeaders.Authorization, "Bearer $jwt")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        putJsonArray("messages") {
                            addJsonObject {
                                put("role", "system")
                                put(
                                    "content",
                                    "你是一个专门生成对话标题的助手。请根据用户的问题和AI的回答，请根据对话内容生成中文标题，必须极度简短，标点符号省略，最长不得超过14个字符。只返回标题本身，不要有任何其他内容、标点符号、引号或解释。",
                                )
                            }
                            addJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    "用户问题：$user\n\nAI回答：$assistantPreview\n\n请为这段对话生成一个简洁的标题（不超过14个字）：",
                                )
                            }
                        }
                        put("temperature", 0.2)
                        put("max_tokens", 30)
                    },
                )
            }
            val root = http.json.parseToJsonElement(resp.bodyAsText()).jsonObject
            cleanGeneratedTitle(
                root["title"]?.jsonPrimitive?.contentOrNull
                    ?: (root["choices"] as? JsonArray)
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("message")
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonPrimitive
                        ?.contentOrNull,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun buildBody(req: ChatRequest): ChatRequestBody = ChatRequestBody(
        sessionId = req.streamSessionId,
        conversationId = req.conversationId,
        model = req.modelId,
        temperature = req.temperature,
        messages = req.messages.map { OpenAiMessageContentBuilder.build(it) },
        stream = req.stream,
        useThinking = req.useThinking,
        reasoningEffort = req.reasoningEffort,
        enabledTools = WireEnabledTools(
            network = req.enabledTools.network,
            steelBrowser = req.enabledTools.steelBrowser,
            codeExecution = req.enabledTools.codeExecution,
            deepResearch = false,
        ),
        privacyMode = req.privacyMode,
    )

    private fun resolveAutoRoute(apiUrl: String, body: ChatRequestBody, jwt: String): AutoRouteResult {
        val url = MolaEndpoints.absolute(apiUrl)
        val bodyJson = http.json.encodeToString(ChatRequestBody.serializer(), body)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $jwt")
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw MolaAuthExpiredException()
            val text = resp.body?.string().orEmpty().trimStart('\uFEFF')
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, "MolaGPT Routes 请求失败：HTTP ${resp.code} ${text.take(160)}")
            }
            val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw MolaApiException(resp.code, "MolaGPT Routes 返回无法解析：${text.take(160)}")
            }
            val modelName = root.readString("model_name")
                ?: throw MolaApiException(resp.code, "MolaGPT Routes 缺少 model_name")
            val routeApiUrl = root.readString("api_url")
                ?: throw MolaApiException(resp.code, "MolaGPT Routes 缺少 api_url")
            return AutoRouteResult(
                modelName = modelName,
                modelKey = root.readString("model_key"),
                displayName = root.readString("display_name"),
                apiUrl = routeApiUrl,
                reason = root.readString("reason"),
                routeSource = root.readString("route_source"),
                routeNote = root.readString("route_note"),
                routerModel = root.readString("router_model"),
                controlNote = root.readString("control_note"),
                confidence = root["confidence"]?.jsonPrimitive?.contentOrNull,
                controls = root["controls"] as? JsonObject,
            )
        }
    }

    private fun applyAutoRoute(body: ChatRequestBody, route: AutoRouteResult): ChatRequestBody {
        val controls = route.controls
        val useThinking = when (controls?.readString("thinking_mode")?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> body.useThinking
        }
        val effort = controls?.readString("reasoning_effort")
            ?.takeUnless { it.equals("inherit", ignoreCase = true) }
            ?: body.reasoningEffort
        var tools = body.enabledTools
        tools = when (controls?.readString("network_mode")?.lowercase()) {
            "on" -> tools.copy(network = true)
            "off" -> tools.copy(network = false)
            else -> tools
        }
        tools = when (controls?.readString("steel_browser_mode")?.lowercase()) {
            "on" -> tools.copy(steelBrowser = true)
            "off" -> tools.copy(steelBrowser = false)
            else -> tools
        }
        return body.copy(
            model = route.modelName,
            useThinking = useThinking,
            reasoningEffort = effort,
            enabledTools = tools,
            molaGptRoutes = buildJsonObject {
                route.modelKey?.let { put("model_key", it) }
                put("model_name", route.modelName)
                route.displayName?.let { put("display_name", it) }
                route.reason?.let { put("reason", it) }
                route.routeSource?.let { put("route_source", it) }
                route.confidence?.let { put("confidence", it) }
                route.routeNote?.let { put("route_note", it) }
                route.routerModel?.let { put("router_model", it) }
                route.controlNote?.let { put("control_note", it) }
            },
        )
    }

    private fun isAutoRouteEndpoint(apiUrl: String, modelId: String): Boolean =
        modelId.equals("autoLLM", ignoreCase = true) ||
            modelId.equals("auto", ignoreCase = true) ||
            apiUrl.contains("chatAuto.php", ignoreCase = true)

    private fun JsonObject.readString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private data class AutoRouteResult(
        val modelName: String,
        val modelKey: String?,
        val displayName: String?,
        val apiUrl: String,
        val reason: String?,
        val routeSource: String?,
        val routeNote: String?,
        val routerModel: String?,
        val controlNote: String?,
        val confidence: String?,
        val controls: JsonObject?,
    )

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESUME_ATTEMPTS = 8
        const val INITIAL_RESUME_DELAY_MS = 3_000L
        const val MAX_RESUME_DELAY_MS = 30_000L
    }

    private suspend fun FlowCollector<StreamEvent>.resumeUntilFinished(
        apiUrl: String,
        streamSessionId: String,
        offset: ResumeOffset,
        parser: StreamParser,
        firstError: IOException,
    ): Boolean {
        Logger.w("MolaChat", "stream interrupted, resume from offset=${offset.value}", firstError)
        var delayMs = INITIAL_RESUME_DELAY_MS
        repeat(MAX_RESUME_ATTEMPTS) { index ->
            currentCoroutineContext().ensureActive()
            val attempt = index + 1
            emit(StreamEvent.Pending("正在恢复连接", "第 $attempt 次重试"))
            delay(delayMs)
            try {
                val finished = collectSseRequest(
                    request = buildResumeRequest(apiUrl, streamSessionId, offset.value),
                    operationName = "流恢复",
                    parser = parser,
                    offset = offset,
                )
                if (finished) return true
                Logger.w("MolaChat", "resume stream ended before finish, offset=${offset.value}")
            } catch (e: IOException) {
                Logger.w("MolaChat", "resume attempt $attempt failed", e)
            }
            delayMs = min(delayMs * 2, MAX_RESUME_DELAY_MS)
        }
        return false
    }

    private suspend fun FlowCollector<StreamEvent>.collectSseRequest(
        request: Request,
        operationName: String,
        parser: StreamParser,
        offset: ResumeOffset,
    ): Boolean {
        val call = http.okHttp.newCall(request)
        try {
            call.execute().use { resp ->
                if (resp.code == 401) throw MolaAuthExpiredException()
                if (!resp.isSuccessful) throw MolaApiException(resp.code, "$operationName 失败：HTTP ${resp.code}")
                val source = resp.body?.source() ?: throw MolaApiException(resp.code, "$operationName 响应为空")
                var finished = false
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (payload.isDone) {
                        parser.finishTail("stop").forEach { emit(it) }
                        finished = true
                        return@collect
                    }
                    if (payload.countsForResumeOffset()) offset.value++
                    parser.parse(payload).forEach { ev ->
                        emit(ev)
                        if (ev is StreamEvent.Finish) finished = true
                    }
                }
                return finished
            }
        } finally {
            runCatching { if (!call.isCanceled()) call.cancel() }
        }
    }

    private suspend fun buildResumeRequest(apiUrl: String, streamSessionId: String, offset: Int): Request {
        val jwt = shortTokenManager.freshToken()
        val body = buildJsonObject {
            put("action", "resume")
            put("session_id", streamSessionId)
            put("offset", offset)
        }
        return Request.Builder()
            .url(MolaEndpoints.absolute(apiUrl))
            .header("Authorization", "Bearer $jwt")
            .header("Accept", "text/event-stream")
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun SsePayload.countsForResumeOffset(): Boolean =
        data.isNotBlank() &&
            !data.contains("\"type\":\"session_init\"") &&
            !data.contains("\"type\": \"session_init\"")

    private data class ResumeOffset(var value: Int = 0)
}
