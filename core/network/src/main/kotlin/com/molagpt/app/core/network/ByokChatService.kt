package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.ImageGenerationConfig
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ThinkingConfig
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.model.WebSearchOptions
import com.molagpt.app.core.model.WebSearchProvider
import com.molagpt.app.core.network.sse.sseFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ByokChatService(
    private val http: MolaHttp,
    private val providerResolver: suspend (String) -> ByokProvider?,
    private val mcpServersProvider: () -> List<ByokMcpServer> = { emptyList() },
    private val webSearchOptionsProvider: () -> WebSearchOptions = { WebSearchOptions() },
    /** 用于在聊天内 generate_image 工具中查找图像用途 provider（purpose=IMAGE）。 */
    private val imageProviderResolver: suspend () -> ByokProvider? = { null },
    /** 聊天内 generate_image 出图参数（来自 BYOK 工具设置的「图像生成」卡）。 */
    private val imageGenConfigProvider: suspend () -> ImageGenerationConfig = { ImageGenerationConfig() },
    /** 复用工作台同一出图路径（按 imageFormat 分派，OpenRouter 走 chat/completions）。 */
    private val byokImageApi: ByokImageApi = ByokImageApi(http),
    /** 把出图字节存为本地文件，返回 Coil 可加载的 url（file://...）；返回 null 表示存盘失败（回退内联 data URI）。 */
    private val imageFileSaver: suspend (bytes: ByteArray, ext: String) -> String? = { _, _ -> null },
    private val dispatchers: DispatcherProvider,
) {
    fun sendMessage(request: ChatRequest): Flow<StreamEvent> = flow {
        val provider = providerResolver(request.providerId)
        if (provider == null || !provider.enabled) {
            emit(StreamEvent.Failed("BYOK 服务不可用"))
            return@flow
        }
        when (provider.type) {
            ByokProviderType.OPENAI_COMPAT -> streamOpenAiCompatible(provider, request).collect { emit(it) }
            ByokProviderType.OPENAI_RESPONSE -> streamOpenAiResponse(provider, request).collect { emit(it) }
            ByokProviderType.ANTHROPIC -> streamAnthropic(provider, request).collect { emit(it) }
            ByokProviderType.GEMINI -> streamGemini(provider, request).collect { emit(it) }
        }
    }.flowOn(dispatchers.io)

    suspend fun stopGeneration(streamSessionId: String) {
        // BYOK provider protocols do not share MolaGPT's stream cache stop API.
    }

    fun resumeStream(apiUrl: String, streamSessionId: String, offset: Int): Flow<StreamEvent> = flow {
        emit(StreamEvent.Failed("BYOK 流暂不支持进程恢复"))
    }.flowOn(dispatchers.io)

    suspend fun checkStreamStatus(streamSessionId: String): StreamStatus? = null

    suspend fun uploadFile(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        conversationId: String,
    ): FileInfo = FileInfo(
        id = Ids.newFragmentId(),
        name = fileName,
        mimeType = mimeType,
        sizeBytes = bytes.size.toLong(),
        uploadStatus = UploadStatus.FAILED,
    )

    suspend fun fetchFiles(conversationId: String): List<FileInfo> = emptyList()

    private fun streamOpenAiCompatible(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val baseMessages = buildMessages(provider, request)
        if (request.enabledTools.hasByokTools) {
            var messages = baseMessages
            var rounds = 0
            while (rounds < MAX_TOOL_ROUNDS) {
                val toolRound = runToolRound(provider, request, messages) ?: break
                rounds += 1
                toolRound.events.forEach { emit(it) }
                messages = toolRound.messages
            }
            streamOpenAiCompatible(provider, request, messages, includeTools = false).collect { emit(it) }
            return@flow
        }
        streamOpenAiCompatible(provider, request, baseMessages, includeTools = false)
            .collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamAnthropic(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val toolRound = if (request.enabledTools.hasByokTools) runAnthropicToolRound(provider, request) else null
        toolRound?.events?.forEach { emit(it) }
        val body = buildAnthropicBody(provider, request, extraUserText = toolRound?.summary)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .header("Accept", "text/event-stream")
            .header("anthropic-version", "2023-06-01")
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val call = http.okHttp.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(StreamEvent.Failed("BYOK 请求失败：HTTP ${resp.code} ${resp.body?.string().orEmpty().take(160)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed("BYOK 响应为空"))
                    return@flow
                }
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (payload.isDone) {
                        emit(StreamEvent.Finish("stop"))
                        return@collect
                    }
                    parseAnthropicEvent(payload.data)?.let { emit(it) }
                }
            }
        } finally {
            runCatching { if (!call.isCanceled()) call.cancel() }
        }
    }.flowOn(dispatchers.io)

    private fun streamGemini(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val toolRound = if (request.enabledTools.hasByokTools) runGeminiToolRound(provider, request) else null
        toolRound?.events?.forEach { emit(it) }
        val body = buildGeminiBody(provider, request, extraUserText = toolRound?.summary)
        val req = Request.Builder()
            .url(geminiEndpoint(provider, request.modelId, stream = true))
            .header("Accept", "text/event-stream")
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val call = http.okHttp.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(StreamEvent.Failed("BYOK 请求失败：HTTP ${resp.code} ${resp.body?.string().orEmpty().take(160)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed("BYOK 响应为空"))
                    return@flow
                }
                var emittedFinish = false
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (payload.isDone) {
                        emit(StreamEvent.Finish("stop"))
                        emittedFinish = true
                        return@collect
                    }
                    parseGeminiEvent(payload.data).forEach { event ->
                        emit(event)
                        if (event is StreamEvent.Finish) emittedFinish = true
                    }
                }
                if (!emittedFinish) emit(StreamEvent.Finish("stop"))
            }
        } finally {
            runCatching { if (!call.isCanceled()) call.cancel() }
        }
    }.flowOn(dispatchers.io)

    private fun streamOpenAiCompatible(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        includeTools: Boolean,
    ): Flow<StreamEvent> = flow {
        val body = buildOpenAiBody(provider, request, messages, stream = true, includeTools = includeTools)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .header("Accept", "text/event-stream")
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val parser = StreamParser(http.json)
        val call = http.okHttp.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    emit(StreamEvent.Failed("BYOK 请求失败：HTTP ${resp.code} ${text.take(160)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed("BYOK 响应为空"))
                    return@flow
                }
                var finished = false
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (payload.isDone) {
                        parser.finishTail("stop").forEach { emit(it) }
                        finished = true
                        return@collect
                    }
                    parser.parse(payload).forEach { event ->
                        emit(event)
                        if (event is StreamEvent.Finish) finished = true
                    }
                }
                if (!finished) parser.finishTail(null).forEach { emit(it) }
            }
        } finally {
            runCatching { if (!call.isCanceled()) call.cancel() }
        }
    }.flowOn(dispatchers.io)

    /**
     * OpenAI Responses API (/v1/responses)。请求体用 `input` 而非 `messages`，
     * system 消息抽到 `instructions`；SSE 事件（response.output_text.delta 等）
     * 复用 [StreamParser]（已内置 response.* 解析）。
     */
    private fun streamOpenAiResponse(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val baseMessages = buildMessages(provider, request)
        if (request.enabledTools.hasByokTools) {
            var input = baseMessages
            var rounds = 0
            while (rounds < MAX_TOOL_ROUNDS) {
                val toolRound = runResponseToolRound(provider, request, input) ?: break
                rounds += 1
                toolRound.events.forEach { emit(it) }
                input = toolRound.messages
            }
            streamResponseStream(provider, request, input, includeTools = false).collect { emit(it) }
            return@flow
        }
        streamResponseStream(provider, request, baseMessages, includeTools = false).collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamResponseStream(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        includeTools: Boolean,
    ): Flow<StreamEvent> = flow {
        val body = buildOpenAiResponseBody(provider, request, messages, stream = true, includeTools = includeTools)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .header("Accept", "text/event-stream")
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val parser = StreamParser(http.json)
        val call = http.okHttp.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    emit(StreamEvent.Failed("BYOK 请求失败：HTTP ${resp.code} ${text.take(160)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed("BYOK 响应为空"))
                    return@flow
                }
                var finished = false
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (payload.isDone) {
                        parser.finishTail("stop").forEach { emit(it) }
                        finished = true
                        return@collect
                    }
                    parser.parse(payload).forEach { event ->
                        emit(event)
                        if (event is StreamEvent.Finish) finished = true
                    }
                }
                if (!finished) parser.finishTail(null).forEach { emit(it) }
            }
        } finally {
            runCatching { if (!call.isCanceled()) call.cancel() }
        }
    }.flowOn(dispatchers.io)

    /** 构建 Responses API 请求体：input 数组 + instructions + reasoning + tools。 */
    private fun buildOpenAiResponseBody(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        stream: Boolean,
        includeTools: Boolean,
    ): JsonObject {
        // system 角色消息抽到 instructions，其余进 input。
        val systemText = messages
            .filter { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
            .mapNotNull { it["content"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
        val inputItems = messages
            .filter { it["role"]?.jsonPrimitive?.contentOrNull != "system" }
            .map { msg ->
                buildJsonObject {
                    put("role", msg["role"]?.jsonPrimitive?.contentOrNull ?: "user")
                    put("content", msg["content"] ?: JsonPrimitive(""))
                }
            }
        return buildJsonObject {
            put("model", request.modelId)
            put("stream", stream)
            if (systemText != null) put("instructions", systemText)
            putJsonArray("input") { inputItems.forEach { add(it) } }
            // Responses API（OpenAI 官方 /v1/responses）推理：reasoning:{effort}，按 kind 门控。
            if (request.useThinking && effectiveThinkingKind(provider, request.modelId) != ThinkingParamKind.NONE) {
                putJsonObject("reasoning") {
                    put("effort", request.reasoningEffort.ifBlank { ThinkingKinds.MEDIUM })
                }
            }
            if (includeTools && request.enabledTools.hasByokTools) {
                putJsonArray("tools") {
                    toolSpecs(provider, request).forEach { spec -> add(buildResponseTool(spec)) }
                }
            }
        }
    }

    /** Responses API 工具定义：扁平结构 {type:function, name, description, parameters}。 */
    private fun buildResponseTool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", spec.name)
        put("description", spec.description)
        put("parameters", buildToolParameters(spec, uppercaseTypes = false))
    }

    /** Responses API 非流式工具轮：解析 output[] 中的 function_call，执行后回填 function_call_output。 */
    private suspend fun runResponseToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
    ): ToolRoundResult? {
        val body = buildOpenAiResponseBody(provider, request, messages, stream = false, includeTools = true)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null
            val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val output = root["output"] as? JsonArray ?: return null
            val calls = output.mapNotNull { parseResponseToolCall(it) }.takeIf { it.isNotEmpty() } ?: return null
            val events = ArrayList<StreamEvent>()
            // 先把助手前导文本/推理发给 UI（output 里的 message 项）。
            output.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                if (o["type"]?.jsonPrimitive?.contentOrNull == "message") {
                    val contentArr = o["content"] as? JsonArray ?: return@forEach
                    val textParts = contentArr.mapNotNull { c ->
                        val co = c as? JsonObject ?: return@mapNotNull null
                        if (co["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                            co["text"]?.jsonPrimitive?.contentOrNull
                        } else null
                    }
                    val preamble = textParts.joinToString("").takeIf { it.isNotBlank() }
                    if (!preamble.isNullOrBlank()) events.add(StreamEvent.Delta(text = preamble))
                }
            }
            val newInput = messages.toMutableList()
            for (call in calls) {
                events.add(StreamEvent.Tool(call.id, call.name, ToolStatus.RUNNING, labelForTool(call.name), call.arguments))
                val result = executeTool(provider, request, call)
                val preview = if (shouldShowToolPreview(call.name)) result.take(1200) else null
                events.add(StreamEvent.Tool(call.id, call.name, statusForToolResult(result), labelForTool(call.name), call.arguments, preview))
                // 把 function_call 与其输出作为 input 历史回填，供下一轮。
                newInput.add(buildJsonObject {
                    put("type", "function_call")
                    put("id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                })
                newInput.add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", call.id)
                    put("output", result)
                })
            }
            return ToolRoundResult(events = events, messages = newInput, summary = null)
        }
    }

    private fun parseResponseToolCall(element: JsonElement): ToolCall? {
        val obj = element as? JsonObject ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "function_call") return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: Ids.newFragmentId()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
        return ToolCall(id, name, arguments)
    }

    private suspend fun runToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        baseMessages: List<JsonObject>,
    ): ToolRoundResult? {
        val body = buildOpenAiBody(provider, request, baseMessages, stream = false, includeTools = true)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null
            val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val message = (root["choices"] as? JsonArray)
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?: return null
            val calls = (message["tool_calls"] as? JsonArray)
                ?.mapNotNull { parseToolCall(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val events = ArrayList<StreamEvent>()
            // 先把工具调用前的助手前导文本/推理发给 UI（如「让我测试一下工具」），
            // 与 Desktop 一致——非流式工具轮也要展示 content/reasoning，而不是直接蹦出工具卡片。
            val preamble = message["content"]?.jsonPrimitive?.contentOrNull
            val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
            if (!reasoning.isNullOrBlank()) events.add(StreamEvent.Delta(thinking = reasoning))
            if (!preamble.isNullOrBlank()) events.add(StreamEvent.Delta(text = preamble))
            val messages = baseMessages.toMutableList()
            messages.add(message)
            for (call in calls) {
                events.add(StreamEvent.Tool(call.id, call.name, ToolStatus.RUNNING, labelForTool(call.name), call.arguments))
                val rawResult = executeTool(provider, request, call)
                // 出图工具：把 base64/图片转本地文件 + Image 事件，回给模型的只留占位文本（绝不回灌 base64）。
                val result = processImageToolResult(call, rawResult, events)
                val preview = if (shouldShowToolPreview(call.name)) result.take(1200) else null
                events.add(StreamEvent.Tool(call.id, call.name, statusForToolResult(result), labelForTool(call.name), call.arguments, preview))
                messages.add(toolResultMessage(call.id, result))
            }
            return ToolRoundResult(events, messages)
        }
    }

    private suspend fun runAnthropicToolRound(
        provider: ByokProvider,
        request: ChatRequest,
    ): ToolRoundResult? {
        val body = buildAnthropicBody(provider, request, stream = false, includeTools = true)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null
            val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val contentBlocks = root["content"] as? JsonArray
            val calls = contentBlocks
                ?.mapNotNull { parseAnthropicToolCall(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            // 工具调用前的文本/思考块（type=text / type=thinking）先发给 UI。
            val preamble = contentBlocks
                .filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text" }
                .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                .joinToString("").takeIf { it.isNotBlank() }
            val reasoning = contentBlocks
                .filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "thinking" }
                .mapNotNull { (it as? JsonObject)?.get("thinking")?.jsonPrimitive?.contentOrNull }
                .joinToString("").takeIf { it.isNotBlank() }
            return executeToolCalls(provider, request, calls, preamble, reasoning)
        }
    }

    private suspend fun runGeminiToolRound(
        provider: ByokProvider,
        request: ChatRequest,
    ): ToolRoundResult? {
        val body = buildGeminiBody(provider, request, includeTools = true)
        val req = Request.Builder()
            .url(geminiEndpoint(provider, request.modelId, stream = false))
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null
            val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val calls = (root["candidates"] as? JsonArray)
                ?.firstOrNull()
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("parts") as? JsonArray
                ?: return null
            val parsed = calls.mapNotNull { parseGeminiToolCall(it) }.takeIf { it.isNotEmpty() } ?: return null
            // functionCall 之前的 text part 作为前导文本发给 UI。
            val preamble = calls
                .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                .joinToString("").takeIf { it.isNotBlank() }
            return executeToolCalls(provider, request, parsed, preamble)
        }
    }

    private suspend fun executeToolCalls(
        provider: ByokProvider,
        request: ChatRequest,
        calls: List<ToolCall>,
        preamble: String? = null,
        reasoning: String? = null,
    ): ToolRoundResult {
        val events = ArrayList<StreamEvent>()
        // 工具卡片前先发助手前导思考/文本（与 Desktop 一致）。
        if (!reasoning.isNullOrBlank()) events.add(StreamEvent.Delta(thinking = reasoning))
        if (!preamble.isNullOrBlank()) events.add(StreamEvent.Delta(text = preamble))
        val results = ArrayList<ToolCallResult>()
        calls.forEach { call ->
            events.add(StreamEvent.Tool(call.id, call.name, ToolStatus.RUNNING, labelForTool(call.name), call.arguments))
            val rawResult = executeTool(provider, request, call)
            // 出图工具：把 base64/图片转本地文件 + Image 事件，回给模型的只留占位文本（绝不回灌 base64）。
            val result = processImageToolResult(call, rawResult, events)
            val preview = if (shouldShowToolPreview(call.name)) result.take(1200) else null
            events.add(StreamEvent.Tool(call.id, call.name, statusForToolResult(result), labelForTool(call.name), call.arguments, preview))
            results.add(ToolCallResult(call, result))
        }
        return ToolRoundResult(events = events, messages = emptyList(), summary = summarizeToolResults(results))
    }

    private fun buildOpenAiBody(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        stream: Boolean,
        includeTools: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", request.modelId)
        put("temperature", request.temperature)
        put("stream", stream)
        putJsonArray("messages") {
            messages.forEach { add(it) }
        }
        if (includeTools && request.enabledTools.hasByokTools) put("tools", toolDefinitions(provider, request))
        addOpenAiThinking(provider, request)
    }

    /**
     * 有效推理 kind：模型显式配置（含 NONE=关闭）优先；否则按 OpenRouter→host→模型 ID 兜底。
     * OpenRouter 对所有模型统一走 reasoning:{effort}，忽略家族差异。
     */
    private fun effectiveThinkingKind(provider: ByokProvider, modelId: String): ThinkingParamKind {
        val cfg = provider.models.firstOrNull { it.id == modelId }?.thinkingConfig
        if (cfg != null) return cfg.kind
        if (ThinkingKinds.isOpenRouter(provider.baseUrl)) return ThinkingParamKind.OPENAI_REASONING_EFFORT
        return ThinkingKinds.hostInferredKind(provider.baseUrl)
            ?: ThinkingKinds.inferFromModelId(modelId)
    }

    /** 向 OpenAI-compat 请求体追加推理参数（top-level 字段，按 kind 分派）。 */
    private fun JsonObjectBuilder.addOpenAiThinking(
        provider: ByokProvider,
        request: ChatRequest,
    ) {
        val kind = effectiveThinkingKind(provider, request.modelId)
        if (kind == ThinkingParamKind.NONE) return
        if (!request.useThinking) {
            // 关闭：仅对需要显式禁用的 kind 发禁用参数，其余省略（更安全）。
            when (kind) {
                ThinkingParamKind.DEEPSEEK_THINKING, ThinkingParamKind.KIMI ->
                    putJsonObject("thinking") { put("type", "disabled") }
                ThinkingParamKind.QWEN_THINKING_BUDGET -> put("enable_thinking", false)
                else -> {}
            }
            return
        }
        val effort = request.reasoningEffort.ifBlank { ThinkingKinds.MEDIUM }
        when (kind) {
            ThinkingParamKind.OPENAI_REASONING_EFFORT -> {
                if (ThinkingKinds.isOpenRouter(provider.baseUrl)) {
                    putJsonObject("reasoning") { put("effort", effort) }
                } else {
                    put("reasoning_effort", effort)
                }
            }
            ThinkingParamKind.DEEPSEEK_THINKING -> {
                putJsonObject("thinking") { put("type", "enabled") }
                put("reasoning_effort", effort)
            }
            ThinkingParamKind.KIMI -> putJsonObject("thinking") { put("type", "enabled") }
            ThinkingParamKind.QWEN_THINKING_BUDGET -> {
                put("enable_thinking", true)
                put("thinking_budget", ThinkingKinds.budgetFor(kind, effort))
            }
            ThinkingParamKind.GEMINI -> put("reasoning_effort", effort)
            else -> {}
        }
    }

    private fun buildMessages(provider: ByokProvider, request: ChatRequest): List<JsonObject> {
        val replaceImages = replaceImagesWithText(provider, request)
        val imageOrdinal = AtomicInteger(0)
        return request.messages.map { message ->
            val wire = OpenAiMessageContentBuilder.build(
                message,
                includeFileParts = true,
                replaceImagesWithText = replaceImages,
                imageOrdinal = imageOrdinal,
            )
            buildJsonObject {
                put("role", wire.role)
                put("content", wire.content)
            }
        }
    }

    private fun replaceImagesWithText(provider: ByokProvider, request: ChatRequest): Boolean =
        !modelSupportsVision(provider, request) && request.enabledTools.vision

    private fun modelSupportsVision(provider: ByokProvider, request: ChatRequest): Boolean =
        provider.models.firstOrNull { it.id == request.modelId }?.supportsVision == true

    private fun toolDefinitions(provider: ByokProvider, request: ChatRequest): JsonArray =
        JsonArray(toolSpecs(provider, request).map { buildOpenAiTool(it) })

    private fun anthropicToolDefinitions(provider: ByokProvider, request: ChatRequest): JsonArray =
        JsonArray(toolSpecs(provider, request).map { spec ->
            buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("input_schema", buildToolParameters(spec, uppercaseTypes = false))
            }
        })

    private fun geminiToolDefinitions(provider: ByokProvider, request: ChatRequest): JsonArray = buildJsonArray {
        val specs = toolSpecs(provider, request)
        if (specs.isEmpty()) return@buildJsonArray
        addJsonObject {
            putJsonArray("functionDeclarations") {
                specs.forEach { spec ->
                    addJsonObject {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("parameters", buildToolParameters(spec, uppercaseTypes = true))
                    }
                }
            }
        }
    }

    private fun toolSpecs(provider: ByokProvider, request: ChatRequest): List<ToolSpec> = buildList {
        if (request.enabledTools.network) {
            add(
                ToolSpec(
                    name = "search_web",
                    description = "Search the web for current information.",
                    properties = mapOf("query" to "Search query", "max_results" to "Number of results"),
                    required = listOf("query"),
                ),
            )
        }
        if (request.enabledTools.steelBrowser) {
            add(
                ToolSpec(
                    name = "fetch_url",
                    description = "Fetch and read a web page by URL.",
                    properties = mapOf("url" to "URL to fetch"),
                    required = listOf("url"),
                ),
            )
        }
        if (request.enabledTools.vision && !modelSupportsVision(provider, request)) {
            add(
                ToolSpec(
                    name = "view_image",
                    description = "Inspect a user-attached image through a configured vision model. " +
                        "Images are numbered globally across the whole conversation in upload order, " +
                        "matching the [图片#N] markers shown inline in the messages.",
                    properties = mapOf(
                        "image_index" to "1-based global index of the image, matching the [图片#N] marker. Defaults to the most recent image.",
                        "query" to "What to inspect or answer about the image.",
                    ),
                    required = listOf("image_index"),
                ),
            )
        }
        if (request.enabledTools.imageGeneration) {
            add(
                ToolSpec(
                    name = "generate_image",
                    description = "Create an image from a prompt.",
                    properties = mapOf("prompt" to "Image prompt"),
                    required = listOf("prompt"),
                ),
            )
        }
        if (request.enabledTools.mcp) {
            add(
                ToolSpec(
                    name = "mcp_list_tools",
                    description = "List tools exposed by enabled MCP servers.",
                    properties = mapOf("server" to "Optional MCP server name"),
                    required = emptyList(),
                ),
            )
            add(
                ToolSpec(
                    name = "mcp_call",
                    description = "Call an enabled MCP server tool.",
                    properties = mapOf("server" to "MCP server name", "tool" to "Tool name", "arguments" to "Tool arguments JSON"),
                    required = listOf("server", "tool"),
                ),
            )
        }
    }

    private fun buildOpenAiTool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", buildToolParameters(spec, uppercaseTypes = false))
        })
    }

    private fun buildToolParameters(spec: ToolSpec, uppercaseTypes: Boolean): JsonObject = buildJsonObject {
        put("type", if (uppercaseTypes) "OBJECT" else "object")
        put("properties", buildJsonObject {
            spec.properties.forEach { (key, desc) ->
                put(key, buildJsonObject {
                    val type = if (key == "max_results") {
                        if (uppercaseTypes) "INTEGER" else "integer"
                    } else {
                        if (uppercaseTypes) "STRING" else "string"
                    }
                    put("type", type)
                    put("description", desc)
                })
            }
        })
        putJsonArray("required") { spec.required.forEach { add(it) } }
    }

    private fun parseToolCall(element: JsonElement): ToolCall? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val function = obj["function"] as? JsonObject ?: return null
        val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
        return ToolCall(id, name, arguments)
    }

    private fun parseAnthropicToolCall(element: JsonElement): ToolCall? {
        val obj = element as? JsonObject ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: Ids.newFragmentId()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val input = obj["input"]?.let { http.json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
        return ToolCall(id, name, input)
    }

    private fun parseGeminiToolCall(element: JsonElement): ToolCall? {
        val functionCall = (element as? JsonObject)?.get("functionCall")?.jsonObject ?: return null
        val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val args = functionCall["args"]?.let { http.json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
        return ToolCall(Ids.newFragmentId(), name, args)
    }

    private suspend fun executeTool(provider: ByokProvider, request: ChatRequest, call: ToolCall): String = when (call.name) {
        "search_web" -> searchWeb(call.arg("query"), call.arg("max_results")?.toIntOrNull())
        "fetch_url" -> fetchUrl(call.arg("url"))
        "view_image" -> viewImage(provider, request, call)
        "generate_image" -> generateImage(provider, request, call.arg("prompt"))
        "mcp_list_tools" -> listMcpTools(call.arg("server"))
        "mcp_call" -> callMcpServer(call)
        else -> "Unsupported tool: ${call.name}"
    }

    private fun viewImage(provider: ByokProvider, request: ChatRequest, call: ToolCall): String {
        val index = call.arg("image_index")?.toIntOrNull() ?: return "Missing image_index"
        val query = call.arg("query")
        val allImages = request.messages
            .filter { it.role == com.molagpt.app.core.model.Role.USER }
            .flatMap { it.attachments }
            .filter { it.mimeType.startsWith("image/") && !it.remoteUrl.isNullOrBlank() }
        val image = allImages.getOrNull(index - 1)
            ?: return "Image index $index out of range (1-${allImages.size})"
        return analyzeImage(provider, request, image.remoteUrl!!, query)
    }

    private suspend fun generateImage(provider: ByokProvider, request: ChatRequest, prompt: String?): String {
        val imagePrompt = prompt?.takeIf { it.isNotBlank() } ?: return "Missing image prompt"
        // 优先在当前 provider 找图像模型；找不到则回退到首个图像用途 provider（purpose=IMAGE）。
        val imageProvider = provider.takeIf { it.models.any { m -> m.supportsImageGeneration } }
            ?: imageProviderResolver() ?: return "未配置图像服务"
        val imageModelId = imageProvider.models.firstOrNull { it.supportsImageGeneration }?.id
            ?: return "未配置图像模型"
        // 复用工作台同一出图路径：按 imageProvider.imageFormat 分派（OpenRouter 自动走 chat/completions，
        // 不再硬编码 v1/images/generations，修复 OpenRouter 出图 404）。出图参数取 BYOK 工具设置。
        return runCatching {
            val cfg = imageGenConfigProvider()
            val result = byokImageApi.generate(imageProvider, imageModelId, imagePrompt, size = "", imageConfig = cfg)
            result.url?.takeIf { it.isNotBlank() } ?: result.raw ?: "图像生成未返回结果"
        }.getOrElse { "Image generation failed: ${it.message}" }
    }

    /**
     * 出图工具结果后处理：把 [rawResult]（data URI / 图片 url）转成本地文件 + [StreamEvent.Image]，
     * 让图片走已有的 Image 渲染管线展示给用户；回给模型的只是占位文本，**绝不回灌 base64**（否则撑爆上下文）。
     * 非 generate_image 工具、或非图片结果（错误串等）原样返回。
     */
    private suspend fun processImageToolResult(
        call: ToolCall,
        rawResult: String,
        events: MutableList<StreamEvent>,
    ): String {
        if (call.name != "generate_image") return rawResult
        val prompt = call.arg("prompt")
        val url = when {
            rawResult.startsWith("data:image/", ignoreCase = true) -> {
                // data:[mime];base64,xxx → 解码存盘 → file://；存盘失败回退原 data URI（UI 仍可渲染）。
                val comma = rawResult.indexOf(',')
                val meta = if (comma > 0) rawResult.substring(0, comma) else ""
                val ext = when {
                    meta.contains("jpeg", true) || meta.contains("jpg", true) -> "jpg"
                    meta.contains("webp", true) -> "webp"
                    else -> "png"
                }
                val saved = if (comma > 0 && meta.contains("base64", true)) {
                    runCatching {
                        val bytes = android.util.Base64.decode(rawResult.substring(comma + 1), android.util.Base64.DEFAULT)
                        imageFileSaver(bytes, ext)
                    }.getOrNull()
                } else null
                saved ?: rawResult
            }
            rawResult.startsWith("http://", true) || rawResult.startsWith("https://", true) -> rawResult.trim()
            else -> return rawResult // 错误串 / 占位等，原样回给模型。
        }
        events.add(StreamEvent.Image(url, prompt))
        return "[图像已生成并展示给用户]"
    }

    private fun analyzeImage(provider: ByokProvider, request: ChatRequest, imageUrl: String?, question: String?): String {
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: return "Missing image URL"
        val prompt = question?.takeIf { it.isNotBlank() } ?: "请分析这张图片。"
        val visionModelId = selectByokVisionModel(provider, request.modelId)
        return when (provider.type) {
            ByokProviderType.OPENAI_COMPAT -> analyzeOpenAiImage(provider, visionModelId, url, prompt)
            ByokProviderType.OPENAI_RESPONSE -> analyzeResponseImage(provider, visionModelId, url, prompt)
            ByokProviderType.ANTHROPIC -> analyzeAnthropicImage(provider, visionModelId, url, prompt)
            ByokProviderType.GEMINI -> analyzeGeminiImage(provider, visionModelId, url, prompt)
        }
    }

    private fun analyzeOpenAiImage(provider: ByokProvider, modelId: String, url: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", modelId)
            put("stream", false)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", url) })
                        }
                    }
                }
            }
        }
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Vision failed: HTTP ${resp.code} ${text.take(800)}"
                parseOpenAiTextResult(text)
            }
        }.getOrElse { "Vision failed: ${it.message}" }
    }

    private fun analyzeResponseImage(provider: ByokProvider, modelId: String, url: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", modelId)
            put("stream", false)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            put("text", prompt)
                        }
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", url)
                        }
                    }
                }
            }
        }
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Vision failed: HTTP ${resp.code} ${text.take(800)}"
                parseResponseTextResult(text)
            }
        }.getOrElse { "Vision failed: ${it.message}" }
    }

    /** 从 Responses API 非流式结果中抽取 output[].content[].text。 */
    private fun parseResponseTextResult(text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text.take(800)
        val output = root["output"] as? JsonArray ?: return root["status"]?.jsonPrimitive?.contentOrNull ?: text.take(800)
        return output.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o["type"]?.jsonPrimitive?.contentOrNull != "message") return@mapNotNull null
            (o["content"] as? JsonArray)?.mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                if (co["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    co["text"]?.jsonPrimitive?.contentOrNull
                } else null
            }?.joinToString("")
        }.joinToString("").ifBlank { text.take(800) }
    }

    private fun analyzeAnthropicImage(provider: ByokProvider, modelId: String, url: String, prompt: String): String {
        val data = parseDataUrl(url)
        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 2048)
            put("stream", false)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        }
                        addJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                if (data != null) {
                                    put("type", "base64")
                                    put("media_type", data.mimeType)
                                    put("data", data.base64)
                                } else {
                                    put("type", "url")
                                    put("url", url)
                                }
                            })
                        }
                    }
                }
            }
        }
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Vision failed: HTTP ${resp.code} ${text.take(800)}"
                parseAnthropicTextResult(text)
            }
        }.getOrElse { "Vision failed: ${it.message}" }
    }

    private fun analyzeGeminiImage(provider: ByokProvider, modelId: String, url: String, prompt: String): String {
        val data = parseDataUrl(url)
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                        addJsonObject {
                            if (data != null) {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", data.mimeType)
                                    put("data", data.base64)
                                })
                            } else {
                                put("fileData", buildJsonObject {
                                    put("mimeType", "image/*")
                                    put("fileUri", url)
                                })
                            }
                        }
                    }
                }
            }
        }
        val req = Request.Builder()
            .url(geminiEndpoint(provider, modelId, stream = false))
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Vision failed: HTTP ${resp.code} ${text.take(800)}"
                parseGeminiTextResult(text)
            }
        }.getOrElse { "Vision failed: ${it.message}" }
    }

    private fun parseOpenAiTextResult(text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return text.take(4000)
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            return "Vision failed: $message"
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return text.take(4000)
        val message = choice["message"] as? JsonObject ?: return text.take(4000)
        val content = message["content"] ?: return text.take(4000)
        if (content is JsonArray) {
            return content.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("\n").ifBlank { text.take(4000) }
        }
        return content.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: text.take(4000)
    }

    private fun parseAnthropicTextResult(text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return text.take(4000)
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            return "Vision failed: $message"
        }
        val content = root["content"] as? JsonArray ?: return text.take(4000)
        return content.mapNotNull { part ->
            (part as? JsonObject)
                ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        }.joinToString("\n").ifBlank { text.take(4000) }
    }

    private fun parseGeminiTextResult(text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return text.take(4000)
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            return "Vision failed: $message"
        }
        val parts = (root["candidates"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts") as? JsonArray
            ?: return text.take(4000)
        return parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .ifBlank { text.take(4000) }
    }

    private fun callMcpServer(call: ToolCall): String {
        val serverName = call.arg("server")?.takeIf { it.isNotBlank() } ?: return "Missing MCP server"
        val toolName = call.arg("tool")?.takeIf { it.isNotBlank() } ?: return "Missing MCP tool"
        val server = mcpServersProvider()
            .filter { it.enabled }
            .firstOrNull { it.name.equals(serverName, ignoreCase = true) || it.id.equals(serverName, ignoreCase = true) }
            ?: return "MCP server not found: $serverName"
        if (toolName in server.disabledTools) return "MCP tool disabled: $toolName"
        val arguments = call.argJsonObject("arguments") ?: buildJsonObject {}
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", call.id)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            })
        }
        val req = Request.Builder()
            .url(server.endpoint)
            .header("Accept", "application/json")
            .apply {
                server.token?.takeIf { it.isNotBlank() }?.let { header(server.headerName.ifBlank { "Authorization" }, "Bearer $it") }
            }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "MCP failed: HTTP ${resp.code} ${text.take(800)}"
                formatMcpResult(text)
            }
        }.getOrElse { "MCP failed: ${it.message}" }
    }

    private fun listMcpTools(serverName: String?): String {
        val servers = mcpServersProvider()
            .filter { it.enabled }
            .filter {
                serverName.isNullOrBlank() ||
                    it.name.equals(serverName, ignoreCase = true) ||
                    it.id.equals(serverName, ignoreCase = true)
            }
        if (servers.isEmpty()) return "No enabled MCP servers"
        return servers.joinToString("\n\n") { server ->
            val disabled = server.disabledTools.toSet()
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", Ids.newFragmentId())
                put("method", "tools/list")
            }
            val req = Request.Builder()
                .url(server.endpoint)
                .header("Accept", "application/json")
                .apply {
                    server.token?.takeIf { it.isNotBlank() }?.let { header(server.headerName.ifBlank { "Authorization" }, "Bearer $it") }
                }
                .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
                .build()
            val tools = runCatching {
                http.okHttp.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use "tools/list failed: HTTP ${resp.code} ${text.take(400)}"
                    formatMcpTools(text, disabled)
                }
            }.getOrElse { "tools/list failed: ${it.message}" }
            "${server.name}\n$tools"
        }
    }

    private fun searchWeb(query: String?, requestedMax: Int?): String {
        if (query.isNullOrBlank()) return "Missing query"
        val options = webSearchOptionsProvider()
        val maxResults = (requestedMax ?: options.maxResults).coerceIn(1, options.maxResults.coerceIn(1, 10))
        return when (options.provider) {
            WebSearchProvider.TAVILY -> searchTavily(query, options.apiKey, maxResults)
            WebSearchProvider.EXA -> searchExa(query, options.apiKey, maxResults)
            WebSearchProvider.DUCKDUCKGO -> searchDuckDuckGo(query, maxResults)
        }
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val req = Request.Builder()
            .url("https://duckduckgo.com/html/?q=$encoded")
            .header("Accept", "text/html")
            .header("User-Agent", "Mozilla/5.0 (compatible; MolaGPT/1.0)")
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "Search failed: HTTP ${resp.code}"
                val html = resp.body?.string().orEmpty()
                // DDG HTML 结构多变：href 与 title 顺序/属性可能调换，故用更宽松的两段匹配。
                val regex = Regex(
                    """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
                val results = regex.findAll(html).take(maxResults).mapIndexed { index, match ->
                    val url = decodeDuckDuckGoHref(htmlDecode(match.groupValues[1]))
                    val title = stripHtml(htmlDecode(match.groupValues[2])).ifBlank { url }
                    "${index + 1}. $title\n$url"
                }.toList()
                results.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                    ?: "未从 DuckDuckGo 获取到结果（可能被反爬限制），可在设置改用 Tavily/Exa。"
            }
        }.getOrElse { "Search failed: ${it.message}" }
    }

    /** DDG 的 result__a href 常是 /l/?uddg=<encoded-real-url> 跳转链接，解出真实 URL。 */
    private fun decodeDuckDuckGoHref(href: String): String {
        val marker = "uddg="
        val idx = href.indexOf(marker)
        if (idx < 0) return href
        val raw = href.substring(idx + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(href)
    }

    private fun searchTavily(query: String, apiKey: String?, maxResults: Int): String {
        if (apiKey.isNullOrBlank()) return "未配置 Tavily API Key，请在设置填写"
        val body = buildJsonObject {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", "basic")
        }
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Accept", "application/json")
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Search failed: HTTP ${resp.code} ${text.take(300)}"
                val root = http.json.parseToJsonElement(text).jsonObject
                val results = (root["results"] as? JsonArray).orEmpty().mapIndexedNotNull { index, item ->
                    val obj = item as? JsonObject ?: return@mapIndexedNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull?.take(500) ?: ""
                    "${index + 1}. $title\n$url\n$content"
                }
                val answer = root["answer"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                listOfNotNull(answer?.let { "摘要：$it" }, results.joinToString("\n\n").takeIf { it.isNotBlank() })
                    .joinToString("\n\n")
                    .ifBlank { "Tavily 未返回结果" }
            }
        }.getOrElse { "Search failed: ${it.message}" }
    }

    private fun searchExa(query: String, apiKey: String?, maxResults: Int): String {
        if (apiKey.isNullOrBlank()) return "未配置 Exa API Key，请在设置填写"
        val body = buildJsonObject {
            put("query", query)
            put("numResults", maxResults)
            put("contents", buildJsonObject {
                put("text", buildJsonObject { put("maxCharacters", 500) })
            })
        }
        val req = Request.Builder()
            .url("https://api.exa.ai/search")
            .header("Accept", "application/json")
            .header("x-api-key", apiKey)
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Search failed: HTTP ${resp.code} ${text.take(300)}"
                val root = http.json.parseToJsonElement(text).jsonObject
                val results = (root["results"] as? JsonArray).orEmpty().mapIndexedNotNull { index, item ->
                    val obj = item as? JsonObject ?: return@mapIndexedNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = obj["text"]?.jsonPrimitive?.contentOrNull?.take(500) ?: ""
                    "${index + 1}. $title\n$url\n$content"
                }
                results.joinToString("\n\n").ifBlank { "Exa 未返回结果" }
            }
        }.getOrElse { "Search failed: ${it.message}" }
    }

    private fun fetchUrl(url: String?): String {
        if (url.isNullOrBlank()) return "Missing url"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/html,text/plain")
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "Fetch failed: HTTP ${resp.code}"
                stripHtml(resp.body?.string().orEmpty()).take(12000)
            }
        }.getOrElse { "Fetch failed: ${it.message}" }
    }

    private fun toolResultMessage(toolCallId: String, result: String): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        put("content", result)
    }

    private fun ToolCall.arg(name: String): String? =
        runCatching { http.json.parseToJsonElement(arguments).jsonObject[name]?.jsonPrimitive?.contentOrNull }
            .getOrNull()

    private fun ToolCall.argJsonObject(name: String): JsonObject? =
        runCatching {
            val element = http.json.parseToJsonElement(arguments).jsonObject[name] ?: return@runCatching null
            when (element) {
                is JsonObject -> element
                else -> http.json.parseToJsonElement(element.jsonPrimitive.content).jsonObject
            }
        }.getOrNull()

    private fun formatMcpResult(text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return text.take(4000)
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            return "MCP failed: $message"
        }
        val result = root["result"] ?: return text.take(4000)
        val content = result.jsonObject["content"] as? JsonArray
        val parts = content?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["data"]?.jsonPrimitive?.contentOrNull
                ?: obj["url"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: result.toString().take(4000)
    }

    private fun formatMcpTools(text: String, disabledTools: Set<String> = emptySet()): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return text.take(4000)
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            return "tools/list failed: $message"
        }
        val tools = root["result"]?.jsonObject?.get("tools") as? JsonArray
            ?: return root["result"]?.toString()?.take(4000) ?: text.take(4000)
        return tools.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (name in disabledTools) return@mapNotNull null
            val description = obj["description"]?.jsonPrimitive?.contentOrNull
            if (description.isNullOrBlank()) name else "$name - $description"
        }.joinToString("\n").ifBlank { "No tools" }
    }

    private fun labelForTool(name: String): String = when (name) {
        "search_web" -> "联网搜索"
        "fetch_url" -> "阅读网页"
        "view_image" -> "视觉理解"
        "generate_image" -> "图像生成"
        "mcp_list_tools" -> "MCP 工具列表"
        "mcp_call" -> "MCP 服务器"
        else -> "工具调用"
    }

    /**
     * 判断工具调用结果状态：通过解析 JSON 中的 success 字段（参照桌面端实现）。
     * 只有明确 `success: false` 时才判定为失败；JSON 解析失败时默认成功（宽容容错）。
     */
    private fun statusForToolResult(result: String): ToolStatus {
        return try {
            val json = http.json.parseToJsonElement(result).jsonObject
            val success = json["success"]?.jsonPrimitive?.booleanOrNull
            when {
                success == false -> ToolStatus.FAILED
                else -> ToolStatus.SUCCESS
            }
        } catch (e: Exception) {
            // JSON 解析失败，默认成功（避免误判）
            ToolStatus.SUCCESS
        }
    }

    /**
     * 判断是否应向前端展示工具结果预览。
     * 外挂视觉工具的结果仅发给模型，不向用户展示。
     */
    private fun shouldShowToolPreview(toolName: String): Boolean {
        return toolName !in setOf("view_image", "vision_proxy")
    }

    private fun summarizeToolResults(results: List<ToolCallResult>): String =
        buildString {
            append("工具结果：")
            results.forEachIndexed { index, item ->
                append("\n\n")
                append(index + 1)
                append(". ")
                append(labelForTool(item.call.name))
                append("\n")
                append(item.result.take(4000))
            }
        }

    private fun buildAnthropicBody(
        provider: ByokProvider,
        request: ChatRequest,
        stream: Boolean = true,
        includeTools: Boolean = false,
        extraUserText: String? = null,
    ): JsonObject = buildJsonObject {
        put("model", request.modelId)
        put("max_tokens", 4096)
        put("temperature", request.temperature)
        put("stream", stream)
        val replaceImages = replaceImagesWithText(provider, request)
        val imageOrdinal = AtomicInteger(0)
        putJsonArray("messages") {
            request.messages.filter { it.role != com.molagpt.app.core.model.Role.SYSTEM }.forEach { message ->
                addJsonObject {
                    put("role", if (message.role == com.molagpt.app.core.model.Role.ASSISTANT) "assistant" else "user")
                    put("content", ByokMessageContentBuilder.anthropicContent(message, replaceImages, imageOrdinal))
                }
            }
            extraUserText?.takeIf { it.isNotBlank() }?.let { text ->
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                }
            }
        }
        val systemText = request.messages
            .filter { it.role == com.molagpt.app.core.model.Role.SYSTEM }
            .joinToString("\n") { it.rawText.orEmpty() }
            .trim()
        if (systemText.isNotBlank()) put("system", systemText)
        // Anthropic 推理：adaptive（Claude 3.7/4.x）/ budget_tokens（预算式），按 kind 分派。
        val kind = effectiveThinkingKind(provider, request.modelId)
        if (request.useThinking && kind != ThinkingParamKind.NONE) {
            val effort = request.reasoningEffort.ifBlank { ThinkingKinds.MEDIUM }
            when (kind) {
                ThinkingParamKind.CLAUDE_BUDGET -> {
                    val budget = ThinkingKinds.budgetFor(kind, effort)
                    // budget_tokens 必须 < max_tokens，按需抬高 max_tokens。
                    if (budget >= 4096) put("max_tokens", budget + 1024)
                    putJsonObject("thinking") {
                        put("type", "enabled")
                        put("budget_tokens", budget)
                    }
                }
                else -> putJsonObject("thinking") {
                    put("type", "adaptive")
                    put("effort", effort)
                }
            }
        }
        if (includeTools) put("tools", anthropicToolDefinitions(provider, request))
    }

    private fun parseAnthropicEvent(data: String): StreamEvent? {
        val root = runCatching { http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "content_block_delta" -> {
                val delta = root["delta"]?.jsonObject
                val text = delta?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: delta?.get("thinking")?.jsonPrimitive?.contentOrNull
                if (text.isNullOrEmpty()) null else StreamEvent.Delta(text = text)
            }
            "message_stop" -> StreamEvent.Finish("stop")
            "error" -> StreamEvent.Failed(root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic 请求失败")
            else -> null
        }
    }

    private fun buildGeminiBody(
        provider: ByokProvider,
        request: ChatRequest,
        includeTools: Boolean = false,
        extraUserText: String? = null,
    ): JsonObject = buildJsonObject {
        val replaceImages = replaceImagesWithText(provider, request)
        val imageOrdinal = AtomicInteger(0)
        putJsonArray("contents") {
            request.messages.filter { it.role != com.molagpt.app.core.model.Role.SYSTEM }.forEach { message ->
                addJsonObject {
                    put("role", if (message.role == com.molagpt.app.core.model.Role.ASSISTANT) "model" else "user")
                    put("parts", ByokMessageContentBuilder.geminiParts(message, replaceImages, imageOrdinal))
                }
            }
            extraUserText?.takeIf { it.isNotBlank() }?.let { text ->
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", text) }
                    }
                }
            }
        }
        val systemText = request.messages
            .filter { it.role == com.molagpt.app.core.model.Role.SYSTEM }
            .joinToString("\n") { it.rawText.orEmpty() }
            .trim()
        if (systemText.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    addJsonObject { put("text", systemText) }
                }
            })
        }
        put("generationConfig", buildJsonObject {
            put("temperature", request.temperature)
            // Gemini 2.5/3 推理：thinkingConfig.thinkingBudget（按档位映射）。
            val kind = effectiveThinkingKind(provider, request.modelId)
            if (request.useThinking && kind == ThinkingParamKind.GEMINI) {
                val effort = request.reasoningEffort.ifBlank { ThinkingKinds.MEDIUM }
                putJsonObject("thinkingConfig") {
                    put("thinkingBudget", ThinkingKinds.budgetFor(kind, effort))
                }
            }
        })
        if (includeTools) put("tools", geminiToolDefinitions(provider, request))
    }

    private fun parseGeminiEvent(data: String): List<StreamEvent> {
        val root = runCatching { http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let {
            return listOf(StreamEvent.Failed(it))
        }
        val candidates = root["candidates"] as? JsonArray ?: return emptyList()
        return candidates.flatMap { candidateElement ->
            val candidate = candidateElement.jsonObject
            val parts = candidate["content"]?.jsonObject?.get("parts") as? JsonArray
            val events = parts?.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    StreamEvent.Delta(text = it)
                }
            }.orEmpty().toMutableList<StreamEvent>()
            val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            if (!finish.isNullOrBlank()) events += StreamEvent.Finish(finish)
            events
        }
    }

    private fun stripHtml(input: String): String =
        htmlDecode(input)
            .replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun htmlDecode(input: String): String =
        input.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun parseDataUrl(value: String): DataUrl? {
        if (!value.startsWith("data:", ignoreCase = true)) return null
        val comma = value.indexOf(',')
        if (comma <= 5) return null
        val meta = value.substring(5, comma)
        if (!meta.contains(";base64", ignoreCase = true)) return null
        val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
        val data = value.substring(comma + 1).takeIf { it.isNotBlank() } ?: return null
        return DataUrl(mime, data)
    }

    private fun geminiEndpoint(provider: ByokProvider, modelId: String, stream: Boolean): String {
        val path = provider.chatPath
            .replace("{model}", modelId)
            .let { if (stream) it else it.replace(":streamGenerateContent", ":generateContent") }
        val url = provider.endpoint(path)
        val params = buildList {
            if (stream && !url.contains("alt=")) add("alt=sse")
            provider.apiKey?.takeIf { it.isNotBlank() }?.let {
                add("key=${URLEncoder.encode(it, Charsets.UTF_8.name())}")
            }
        }
        if (params.isEmpty()) return url
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + params.joinToString("&")
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MAX_TOOL_ROUNDS = 3
    }

    private data class ToolCall(val id: String, val name: String, val arguments: String)
    private data class ToolCallResult(val call: ToolCall, val result: String)
    private data class ToolRoundResult(
        val events: List<StreamEvent>,
        val messages: List<JsonObject>,
        val summary: String? = null,
    )
    private data class ToolSpec(
        val name: String,
        val description: String,
        val properties: Map<String, String>,
        val required: List<String>,
    )
    private data class DataUrl(val mimeType: String, val base64: String)
}

private val com.molagpt.app.core.model.EnabledTools.hasByokTools: Boolean
    get() = network || steelBrowser || vision || imageGeneration || mcp

internal fun selectByokVisionModel(provider: ByokProvider, fallbackModelId: String): String {
    val current = provider.models.firstOrNull { it.id == fallbackModelId }
    if (current?.supportsVision == true) return fallbackModelId
    return provider.models.firstOrNull { it.supportsVision }?.id ?: fallbackModelId
}
