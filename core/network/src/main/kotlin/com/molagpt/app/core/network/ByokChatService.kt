package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.CustomBodyParam
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.ImageGenerationConfig
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ThinkingConfig
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import com.molagpt.app.core.model.TitleRequest
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.model.WebSearchOptions
import com.molagpt.app.core.model.WebSearchProvider
import com.molagpt.app.core.network.sse.sseFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
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
    /**
     * 外挂视觉目标解析：读「BYOK 工具 → 视觉理解」配置的 `<providerId>::<modelId>`，
     * 解析成 (目标 provider, 目标 modelId)。**可跨 provider**——视觉模型不必与当前聊天模型同属一个 provider。
     * 返回 null 表示未配置/解析不到（[analyzeImage] 会明确报错，绝不把图片发给不支持视觉的模型）。
     */
    private val visionProviderResolver: suspend () -> Pair<ByokProvider, String>? = { null },
    /** 聊天内 generate_image 出图参数（来自 BYOK 工具设置的「图像生成」卡）。 */
    private val imageGenConfigProvider: suspend () -> ImageGenerationConfig = { ImageGenerationConfig() },
    /** 「BYOK 工具 → 会话标题」总开关；关闭时 [generateTitle] 直接回退占位标题，不打任何请求。 */
    private val autoTitleEnabled: () -> Boolean = { true },
    /**
     * 标题模型目标解析：读设置里的 `<providerId>::<modelId>`（**可跨 provider**，通常挂个便宜小模型）。
     * 返回 null 表示未配置，[generateTitle] 回退到会话自身的 provider/模型。
     */
    private val titleProviderResolver: suspend () -> Pair<ByokProvider, String>? = { null },
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

    // ── 会话标题 ────────────────────────────────────────────────────────────────
    //
    // 用**用户自己的 provider** 生成，绝不经 MolaGPT 服务器：BYOK 用户可能压根没登录账号，
    // 把对话内容送去我们服务器也违背 BYOK 的隐私预期。
    // 任何失败（未配置/HTTP 错/解析不出/清洗后为空）都静默回退到占位标题——标题是锦上添花，
    // 绝不能因此打扰用户，更不能把错误报文写成会话名。

    suspend fun generateTitle(request: TitleRequest): String {
        val fallback = request.fallbackTitle()
        if (!autoTitleEnabled() || request.messages.isEmpty()) return fallback
        val (provider, modelId) = resolveTitleTarget(request) ?: return fallback
        val prompt = buildTitlePrompt(request.messages, Locale.getDefault().displayName)
        val raw = runCatching {
            withContext(dispatchers.io) { requestTitle(provider, modelId, prompt) }
        }.getOrNull()
        return cleanGeneratedTitle(raw) ?: fallback
    }

    /** 目标优先级：设置里显式指定的标题模型 → 会话自身的 provider/模型（零配置可用）。 */
    private suspend fun resolveTitleTarget(request: TitleRequest): Pair<ByokProvider, String>? {
        titleProviderResolver()?.takeIf { it.first.enabled }?.let { return it }
        val provider = providerResolver(request.providerId)?.takeIf { it.enabled } ?: return null
        val modelId = request.modelId.trim().ifBlank { return null }
        return provider to modelId
    }

    private fun requestTitle(provider: ByokProvider, modelId: String, prompt: String): String? {
        val body = buildTitleBody(provider, modelId, prompt)
        val url = if (provider.type == ByokProviderType.GEMINI) {
            geminiEndpoint(provider, modelId, stream = false)
        } else {
            provider.endpoint(provider.chatPath)
        }
        val req = Request.Builder()
            .url(url)
            .apply {
                // Gemini 的 key 走 URL query（见 geminiEndpoint），其余协议走鉴权头。
                if (provider.type != ByokProviderType.GEMINI) {
                    provider.applyAuthHeaders { name, value -> header(name, value) }
                }
                if (provider.type == ByokProviderType.ANTHROPIC) header("anthropic-version", "2023-06-01")
            }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        return http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) null else parseTitleResponse(provider.type, http.json, text)
        }
    }

    /**
     * 标题请求体：单条 user 消息、非流式、不带工具、不带角色系统提示（否则猫娘人格会把标题写成「喵～」）。
     *
     * **不设 max_tokens**：给推理模型设小上限会让预算全花在思考上、正文返回空。
     * 控成本靠下面显式关思考，不靠截断。
     */
    private fun buildTitleBody(provider: ByokProvider, modelId: String, prompt: String): JsonObject =
        when (provider.type) {
            ByokProviderType.OPENAI_COMPAT -> buildJsonObject {
                put("model", modelId)
                put("stream", false)
                put("temperature", TITLE_TEMPERATURE)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
                // DeepSeek / Kimi / Qwen 默认开思考，必须显式发禁用参数（复用聊天同一套 kind 分派）。
                addOpenAiThinking(provider, modelId, requestedThinking = false, reasoningEffort = "")
                applyModelCustomBody(provider, modelId)
            }

            ByokProviderType.OPENAI_RESPONSE -> buildJsonObject {
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
                        }
                    }
                }
                applyModelCustomBody(provider, modelId)
            }

            // max_tokens 是 Anthropic 必填字段；不发 thinking 即为关闭。
            ByokProviderType.ANTHROPIC -> buildJsonObject {
                put("model", modelId)
                put("max_tokens", ANTHROPIC_TITLE_MAX_TOKENS)
                put("temperature", TITLE_TEMPERATURE)
                put("stream", false)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
                applyModelCustomBody(provider, modelId)
            }

            // Gemini 不发 thinkingConfig：thinkingBudget=0 在部分模型（Gemini 3 Pro）上会 400，
            // 与其冒 400 的险不如让它思考，反正解析时会跳过 thought 分片。
            ByokProviderType.GEMINI -> buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", prompt) }
                        }
                    }
                }
                applyModelCustomBody(provider, modelId)
            }
        }

    private fun streamOpenAiCompatible(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val baseMessages = buildMessages(provider, request)
        if (request.enabledTools.hasByokTools) {
            var messages = baseMessages
            var rounds = 0
            while (rounds < MAX_TOOL_ROUNDS) {
                val toolRound = runToolRound(provider, request, messages) { emit(it) } ?: break
                rounds += 1
                messages = toolRound.messages
            }
            streamOpenAiCompatible(provider, request, messages, includeTools = false).collect { emit(it) }
            return@flow
        }
        streamOpenAiCompatible(provider, request, baseMessages, includeTools = false)
            .collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamAnthropic(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val toolRound = if (request.enabledTools.hasByokTools) {
            runAnthropicToolRound(provider, request) { emit(it) }
        } else {
            null
        }
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
        val toolRound = if (request.enabledTools.hasByokTools) {
            runGeminiToolRound(provider, request) { emit(it) }
        } else {
            null
        }
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
                val toolRound = runResponseToolRound(provider, request, input) { emit(it) } ?: break
                rounds += 1
                input = toolRound.messages
            }
            streamResponseStream(provider, request, input).collect { emit(it) }
            return@flow
        }
        streamResponseStream(provider, request, baseMessages).collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamResponseStream(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
    ): Flow<StreamEvent> = flow {
        val body = buildOpenAiResponseBody(provider, request, messages, stream = true, includeTools = false)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .header("Accept", "text/event-stream")
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val parser = StreamParser(http.json, responseFinalAnswerOnly = true)
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
            .map(::toOpenAiResponseInputItem)
        return buildJsonObject {
            put("model", request.modelId)
            put("stream", stream)
            if (systemText != null) put("instructions", systemText)
            putJsonArray("input") { inputItems.forEach { add(it) } }
            // Responses API（OpenAI 官方 /v1/responses）推理：reasoning:{effort}，按 kind 门控。
            val thinkingOn = request.useThinking || isAlwaysOnThinking(provider, request.modelId)
            if (thinkingOn && effectiveThinkingKind(provider, request.modelId) != ThinkingParamKind.NONE) {
                putJsonObject("reasoning") {
                    put("effort", request.reasoningEffort.ifBlank { ThinkingKinds.MEDIUM })
                }
            }
            if (includeTools && request.enabledTools.hasByokTools) {
                putJsonArray("tools") {
                    toolSpecs(provider, request).forEach { spec -> add(buildResponseTool(spec)) }
                }
            }
            applyModelCustomBody(provider, request.modelId)
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
        emitEvent: suspend (StreamEvent) -> Unit,
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
            val output = (root["output"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?: return null
            val parsedOutput = parseResponseOutputItems(output)
            if (parsedOutput.none { it is ParsedResponseOutputItem.FunctionCall }) return null
            val functionOutputs = ArrayList<ResponseFunctionOutput>()

            // output[] 本身就是有序协议：reasoning / message / function_call 必须逐项消费。
            // 特别是工具前导句（message）必须紧挨它后面的工具卡，不能先收集所有文本再执行所有调用。
            parsedOutput.forEach { item ->
                when (item) {
                    is ParsedResponseOutputItem.Reasoning -> {
                        emitEvent(StreamEvent.Delta(thinking = item.text))
                    }
                    is ParsedResponseOutputItem.Message -> {
                        emitEvent(StreamEvent.Delta(text = item.text))
                    }
                    is ParsedResponseOutputItem.FunctionCall -> {
                        val call = ToolCall(
                            id = item.id ?: Ids.newFragmentId(),
                            name = item.name,
                            arguments = item.arguments,
                            responseCallId = item.callId,
                        )
                        val result = executeAndEmitTool(provider, request, call, emitEvent)
                        functionOutputs.add(
                            ResponseFunctionOutput(
                                callId = call.responseCallId ?: call.id,
                                output = result.output,
                            ),
                        )
                    }
                }
            }
            // 官方手动上下文模式要求先原样回放整个 response.output（含 reasoning 的加密内容），
            // 再附加与 call_id 对应的 function_call_output；不能把这些 item 转成空 user message。
            val newInput = buildResponseReplayInput(messages, output, functionOutputs)
            return ToolRoundResult(messages = newInput, summary = null)
        }
    }

    private suspend fun runToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        baseMessages: List<JsonObject>,
        emitEvent: suspend (StreamEvent) -> Unit,
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

            // 先把工具调用前的助手前导文本/推理发给 UI（如「让我测试一下工具」），
            // 与 Desktop 一致——非流式工具轮也要展示 content/reasoning，而不是直接蹦出工具卡片。
            val preamble = message["content"]?.jsonPrimitive?.contentOrNull
            val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
            if (!reasoning.isNullOrBlank()) emitEvent(StreamEvent.Delta(thinking = reasoning))
            if (!preamble.isNullOrBlank()) emitEvent(StreamEvent.Delta(text = preamble))
            val messages = baseMessages.toMutableList()
            messages.add(message)
            for (call in calls) {
                val result = executeAndEmitTool(provider, request, call, emitEvent)
                messages.add(toolResultMessage(call.id, result.output))
            }
            return ToolRoundResult(messages)
        }
    }

    private suspend fun runAnthropicToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        emitEvent: suspend (StreamEvent) -> Unit,
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
            return executeToolCalls(provider, request, calls, preamble, reasoning, emitEvent)
        }
    }

    private suspend fun runGeminiToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        emitEvent: suspend (StreamEvent) -> Unit,
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
            return executeToolCalls(provider, request, parsed, preamble, emitEvent = emitEvent)
        }
    }

    private suspend fun executeToolCalls(
        provider: ByokProvider,
        request: ChatRequest,
        calls: List<ToolCall>,
        preamble: String? = null,
        reasoning: String? = null,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolRoundResult {
        // 工具卡片前先发助手前导思考/文本（与 Desktop 一致）。
        if (!reasoning.isNullOrBlank()) emitEvent(StreamEvent.Delta(thinking = reasoning))
        if (!preamble.isNullOrBlank()) emitEvent(StreamEvent.Delta(text = preamble))
        val results = ArrayList<ToolCallResult>()
        calls.forEach { call ->
            val result = executeAndEmitTool(provider, request, call, emitEvent)
            results.add(ToolCallResult(call, result.output))
        }
        return ToolRoundResult(messages = emptyList(), summary = summarizeToolResults(results))
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
        addOpenAiThinking(provider, request.modelId, request.useThinking, request.reasoningEffort)
        applyModelCustomBody(provider, request.modelId)
    }

    /**
     * 有效推理 kind：模型显式配置（含 NONE=关闭）优先；否则按 OpenRouter→host→模型 ID 兜底。
     * 聚合网关下预算类通过 [ThinkingKinds.wireKind] 折算为 OPENAI_REASONING_EFFORT，
     * 避免把 thinking_budget 等家族私有参数发到 OpenRouter。
     */
    private fun effectiveThinkingKind(provider: ByokProvider, modelId: String): ThinkingParamKind {
        val cfg = provider.models.firstOrNull { it.id == modelId }?.thinkingConfig
        val raw = when {
            cfg != null -> cfg.kind
            ThinkingKinds.isOpenRouter(provider.baseUrl) -> ThinkingParamKind.OPENAI_REASONING_EFFORT
            else -> ThinkingKinds.hostInferredKind(provider.baseUrl)
                ?: ThinkingKinds.inferFromModelId(modelId)
        }
        return ThinkingKinds.wireKind(raw, provider.baseUrl)
    }

    /** 模型是否强制开启推理（如 Kimi K3）。 */
    private fun isAlwaysOnThinking(provider: ByokProvider, modelId: String): Boolean =
        provider.models.firstOrNull { it.id == modelId }?.thinkingConfig?.alwaysOn == true ||
            ThinkingKinds.isKimiK3(modelId)

    /**
     * 向 OpenAI-compat 请求体追加推理参数（top-level 字段，按 kind 分派）。
     * 聊天与后台任务（标题生成）共用：后者传 [requestedThinking] = false 拿到显式禁用参数。
     */
    private fun JsonObjectBuilder.addOpenAiThinking(
        provider: ByokProvider,
        modelId: String,
        requestedThinking: Boolean,
        reasoningEffort: String,
    ) {
        val kind = effectiveThinkingKind(provider, modelId)
        if (kind == ThinkingParamKind.NONE) return
        val alwaysOn = isAlwaysOnThinking(provider, modelId)
        val useThinking = requestedThinking || alwaysOn
        if (!useThinking) {
            // 关闭：仅对需要显式禁用的 kind 发禁用参数，其余省略（更安全）。
            when (kind) {
                ThinkingParamKind.DEEPSEEK_THINKING, ThinkingParamKind.KIMI ->
                    putJsonObject("thinking") { put("type", "disabled") }
                ThinkingParamKind.QWEN_THINKING_BUDGET -> put("enable_thinking", false)
                else -> {}
            }
            return
        }
        val effort = reasoningEffort.ifBlank {
            provider.models.firstOrNull { it.id == modelId }?.thinkingConfig
                ?.let { ThinkingKinds.resolveDefaultEffort(it) }
                ?: ThinkingKinds.MEDIUM
        }
        when (kind) {
            ThinkingParamKind.OPENAI_REASONING_EFFORT -> {
                if (ThinkingKinds.isAggregatingGateway(provider.baseUrl)) {
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
            ThinkingParamKind.GEMINI -> {
                // OpenAI-compat Gemini：符号档位；聚合网关已在 wireKind 折算走 effort 分支。
                if (ThinkingKinds.isAggregatingGateway(provider.baseUrl)) {
                    putJsonObject("reasoning") { put("effort", effort) }
                } else {
                    put("reasoning_effort", effort)
                }
            }
            else -> {}
        }
    }

    /**
     * 把当前模型的自定义 body 覆写项叠加到请求体（作为 builder 的最后一步，覆盖前面所有字段）。
     * 保护键（承载消息/流/工具结构的字段）永不可覆盖，避免破坏请求。与 Desktop CustomRequestParams 对齐。
     */
    private fun JsonObjectBuilder.applyModelCustomBody(provider: ByokProvider, modelId: String) {
        val custom = provider.models.firstOrNull { it.id == modelId }?.customBody ?: return
        custom.forEach { param ->
            val key = param.key.trim()
            if (key.isBlank() || isProtectedBodyKey(key)) return@forEach
            put(key, customBodyValueToJson(param))
        }
    }

    private fun isProtectedBodyKey(key: String): Boolean =
        key == "messages" || key == "input" || key == "contents" || key == "stream" ||
            key == "tools" || key == "tool_choice" || key == "functionDeclarations"

    private fun customBodyValueToJson(param: CustomBodyParam): JsonElement =
        when (param.type.trim().lowercase()) {
            "number" -> param.value.toLongOrNull()?.let { JsonPrimitive(it) }
                ?: param.value.toDoubleOrNull()?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(param.value)
            "boolean" -> JsonPrimitive(param.value.trim().toBooleanStrictOrNull() ?: false)
            "json" -> runCatching { http.json.parseToJsonElement(param.value) }.getOrDefault(JsonPrimitive(param.value))
            else -> JsonPrimitive(param.value)
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

    private suspend fun executeAndEmitTool(
        provider: ByokProvider,
        request: ChatRequest,
        call: ToolCall,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolExecutionResult = emitByokToolLifecycle(
        id = call.id,
        name = call.name,
        label = labelForTool(call.name),
        argsJson = call.arguments,
        execute = {
            val execution = executeTool(provider, request, call)
            // 出图工具：把 base64/图片转本地文件 + Image 事件，回给模型的只留占位文本（绝不回灌 base64）。
            execution.copy(output = processImageToolResult(call, execution.output, emitEvent))
        },
        resultPreview = { result ->
            if (shouldShowToolPreview(call.name)) result.output.take(1200) else null
        },
        emitEvent = emitEvent,
    )

    private suspend fun executeTool(
        provider: ByokProvider,
        request: ChatRequest,
        call: ToolCall,
    ): ToolExecutionResult {
        val output = try {
            when (call.name) {
                "search_web" -> searchWeb(call.arg("query"), call.arg("max_results")?.toIntOrNull())
                "fetch_url" -> fetchUrl(call.arg("url"))
                "view_image" -> viewImage(provider, request, call)
                "generate_image" -> generateImage(provider, request, call.arg("prompt"))
                "mcp_list_tools" -> listMcpTools(call.arg("server"))
                "mcp_call" -> callMcpServer(call)
                else -> return ToolExecutionResult.failure("Unsupported tool: ${call.name}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ToolExecutionResult.failure("${labelForTool(call.name)} failed: ${error.message ?: error::class.simpleName}")
        }
        return classifyByokToolResult(call.name, output)
    }

    private suspend fun viewImage(provider: ByokProvider, request: ChatRequest, call: ToolCall): String {
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
        emitEvent: suspend (StreamEvent) -> Unit,
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
        emitEvent(StreamEvent.Image(url, prompt))
        return "[图像已生成并展示给用户]"
    }

    private suspend fun analyzeImage(provider: ByokProvider, request: ChatRequest, imageUrl: String?, question: String?): String {
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: return "Missing image URL"
        val prompt = question?.takeIf { it.isNotBlank() } ?: "请分析这张图片。"
        // 解析外挂视觉目标：优先用「BYOK 工具 → 视觉理解」里配置的目标模型（**可跨 provider**），
        // 未配置时回退当前 provider 自带的视觉模型。解析不到则明确报错——绝不把图片发给不支持视觉的模型
        // （否则上游会以 "unknown variant image_url, expected text" 拒收）。
        val target = resolveVisionTarget(provider, request.modelId)
            ?: return "外挂视觉未配置可用的视觉模型：请在「设置 → BYOK 工具 → 视觉理解」中选择一个支持视觉的模型。"
        val (visionProvider, visionModelId) = target
        return when (visionProvider.type) {
            ByokProviderType.OPENAI_COMPAT -> analyzeOpenAiImage(visionProvider, visionModelId, url, prompt)
            ByokProviderType.OPENAI_RESPONSE -> analyzeResponseImage(visionProvider, visionModelId, url, prompt)
            ByokProviderType.ANTHROPIC -> analyzeAnthropicImage(visionProvider, visionModelId, url, prompt)
            ByokProviderType.GEMINI -> analyzeGeminiImage(visionProvider, visionModelId, url, prompt)
        }
    }

    /**
     * 解析外挂视觉目标 `(provider, modelId)`：
     * 1. 用户在设置里显式配置的目标（`visionProviderResolver`，可跨 provider）优先；
     * 2. 否则在**当前 provider** 内挑视觉模型（[selectByokVisionModel]），并**校验挑中的确实支持视觉**——
     *    修掉旧逻辑「找不到就静默退回当前文本模型」导致把图发给文本模型的坑；
     * 3. 都没有 → null（调用方据此报错）。
     */
    private suspend fun resolveVisionTarget(current: ByokProvider, currentModelId: String): Pair<ByokProvider, String>? {
        visionProviderResolver()?.let { return it }
        val picked = selectByokVisionModel(current, currentModelId)
        if (current.models.any { it.id == picked && it.supportsVision }) return current to picked
        return null
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
        if (root["status"]?.jsonPrimitive?.contentOrNull.equals("failed", ignoreCase = true)) {
            val message = (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "Responses API returned failed"
            return "Vision failed: $message"
        }
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
        // UA 不在此处设置：MolaHttp 的拦截器会统一覆盖为固定 UA。实测 DDG 恰好对固定 UA 返回正常
        // 结果，而浏览器 UA 会触发 202 反爬页，故不要在这里改写 UA。
        val req = Request.Builder()
            .url("https://duckduckgo.com/html/?q=$encoded")
            .header("Accept", "text/html")
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
        val resultObject = result.jsonObject
        val content = resultObject["content"] as? JsonArray
        val parts = content?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["data"]?.jsonPrimitive?.contentOrNull
                ?: obj["url"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val formatted = parts.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: result.toString().take(4000)
        return if (resultObject["isError"]?.jsonPrimitive?.booleanOrNull == true) {
            "MCP failed: $formatted"
        } else {
            formatted
        }
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
        val useThinking = request.useThinking || isAlwaysOnThinking(provider, request.modelId)
        if (useThinking && kind != ThinkingParamKind.NONE) {
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
        applyModelCustomBody(provider, request.modelId)
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
            val thinkingOn = request.useThinking || isAlwaysOnThinking(provider, request.modelId)
            if (thinkingOn && kind == ThinkingParamKind.GEMINI) {
                val effort = request.reasoningEffort.ifBlank { ThinkingKinds.MEDIUM }
                putJsonObject("thinkingConfig") {
                    put("thinkingBudget", ThinkingKinds.budgetFor(kind, effort))
                }
            }
        })
        if (includeTools) put("tools", geminiToolDefinitions(provider, request))
        applyModelCustomBody(provider, request.modelId)
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
        const val TITLE_TEMPERATURE = 0.3
        const val ANTHROPIC_TITLE_MAX_TOKENS = 2048
    }

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
        val responseCallId: String? = null,
    )
    private data class ToolCallResult(val call: ToolCall, val result: String)
    private data class ToolRoundResult(
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

/** BYOK 工具的结构化执行结果；状态不再依赖 UI 猜测一段自由文本。 */
internal data class ToolExecutionResult(
    val output: String,
    val status: ToolStatus,
) {
    companion object {
        fun success(output: String) = ToolExecutionResult(output, ToolStatus.SUCCESS)
        fun failure(output: String) = ToolExecutionResult(output, ToolStatus.FAILED)
    }
}

/**
 * 在真实执行前立即发 RUNNING，执行结束后用同一个 id 原地更新为最终状态。
 * 该函数保持协议无关，OpenAI Compatible / Responses / Anthropic / Gemini 共用。
 */
internal suspend fun emitByokToolLifecycle(
    id: String,
    name: String,
    label: String?,
    argsJson: String?,
    execute: suspend () -> ToolExecutionResult,
    resultPreview: (ToolExecutionResult) -> String? = { null },
    emitEvent: suspend (StreamEvent) -> Unit,
): ToolExecutionResult {
    emitEvent(StreamEvent.Tool(id, name, ToolStatus.RUNNING, label, argsJson))
    val result = try {
        execute()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolExecutionResult.failure("${label ?: name} failed: ${error.message ?: error::class.simpleName}")
    }
    emitEvent(
        StreamEvent.Tool(
            id = id,
            name = name,
            status = result.status,
            label = label,
            argsJson = argsJson,
            resultPreview = resultPreview(result),
        ),
    )
    return result
}

private val toolResultJson = Json { ignoreUnknownKeys = true }

/** 对现有各工具的返回文本做集中、可测试的末端分类，覆盖它们所有显式错误出口。 */
internal fun classifyByokToolResult(name: String, output: String): ToolExecutionResult {
    val text = output.trim()
    val jsonFailed = runCatching {
        toolResultJson.parseToJsonElement(text).jsonObject["success"]?.jsonPrimitive?.booleanOrNull == false
    }.getOrDefault(false)
    if (jsonFailed) return ToolExecutionResult.failure(output)

    val lower = text.lowercase()
    val commonFailure = lower.startsWith("missing ") ||
        lower.startsWith("unsupported tool:")
    val toolFailure = when (name) {
        "search_web" -> lower.startsWith("search failed:") ||
            text.startsWith("未配置 Tavily API Key") ||
            text.startsWith("未配置 Exa API Key") ||
            text.startsWith("未从 DuckDuckGo 获取到结果")
        "fetch_url" -> lower.startsWith("fetch failed:")
        "view_image" -> lower.startsWith("vision failed:") ||
            (lower.startsWith("image index ") && lower.contains(" out of range")) ||
            text.startsWith("外挂视觉未配置")
        "generate_image" -> lower.startsWith("image generation failed:") ||
            text.startsWith("未配置图像服务") ||
            text.startsWith("未配置图像模型") ||
            text.startsWith("图像生成未返回结果")
        "mcp_list_tools" -> lower.lineSequence().any { it.trim().startsWith("tools/list failed:") } ||
            lower.startsWith("no enabled mcp servers")
        "mcp_call" -> lower.startsWith("mcp failed:") ||
            lower.startsWith("mcp server not found:") ||
            lower.startsWith("mcp tool disabled:")
        else -> false
    }
    return if (commonFailure || toolFailure) {
        ToolExecutionResult.failure(output)
    } else {
        ToolExecutionResult.success(output)
    }
}

/**
 * 把 chat-completions 的消息转换为 Responses API input item。
 * 已经带 `type` 的 Responses 原生 item（reasoning/function_call/function_call_output/message）必须原样回放。
 */
internal fun toOpenAiResponseInputItem(item: JsonObject): JsonObject {
    if (!item["type"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return item
    val role = item["role"]?.jsonPrimitive?.contentOrNull ?: "user"
    return buildJsonObject {
        put("role", role)
        put("content", toOpenAiResponseContent(role, item["content"]))
    }
}

/** chat-completions content part → Responses input/output content part。 */
internal fun toOpenAiResponseContent(role: String, content: JsonElement?): JsonElement {
    if (content == null) return JsonPrimitive("")
    (content as? JsonPrimitive)?.let { return it }
    val arr = content as? JsonArray ?: return JsonPrimitive(content.toString())
    val textType = if (role == "assistant") "output_text" else "input_text"
    return buildJsonArray {
        arr.forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "image_url" -> {
                    val url = (obj["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: obj["image_url"]?.jsonPrimitive?.contentOrNull
                    if (!url.isNullOrBlank()) addJsonObject {
                        put("type", "input_image")
                        put("image_url", url)
                    }
                }
                else -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrBlank()) addJsonObject {
                        put("type", textType)
                        put("text", text)
                    }
                }
            }
        }
    }
}

internal fun responseReasoningText(item: JsonObject): String? =
    sequenceOf(item["summary"], item["content"])
        .mapNotNull { it as? JsonArray }
        .flatMap { it.asSequence() }
        .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }

internal fun responseMessageText(item: JsonObject): String? =
    (item["content"] as? JsonArray)
        ?.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "output_text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                "refusal" -> obj["refusal"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }
        ?.joinToString("")
        ?.takeIf { it.isNotBlank() }

internal sealed interface ParsedResponseOutputItem {
    data class Reasoning(val text: String) : ParsedResponseOutputItem
    data class Message(val text: String, val phase: String?) : ParsedResponseOutputItem
    data class FunctionCall(
        val id: String?,
        val callId: String?,
        val name: String,
        val arguments: String,
    ) : ParsedResponseOutputItem
}

/** 按 Responses API 的 output[] 原始顺序解析可展示内容和工具调用。 */
internal fun parseResponseOutputItems(output: List<JsonObject>): List<ParsedResponseOutputItem> =
    output.mapNotNull { item ->
        when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "reasoning" -> responseReasoningText(item)?.let(ParsedResponseOutputItem::Reasoning)
            "message" -> responseMessageText(item)?.let { text ->
                ParsedResponseOutputItem.Message(
                    text = text,
                    phase = item["phase"]?.jsonPrimitive?.contentOrNull,
                )
            }
            "function_call" -> {
                val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ParsedResponseOutputItem.FunctionCall(
                    id = item["id"]?.jsonPrimitive?.contentOrNull,
                    callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                    name = name,
                    arguments = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
            else -> null
        }
    }

internal data class ResponseFunctionOutput(val callId: String, val output: String)

internal fun buildResponseReplayInput(
    previous: List<JsonObject>,
    output: List<JsonObject>,
    functionOutputs: List<ResponseFunctionOutput>,
): List<JsonObject> = buildList {
    addAll(previous)
    addAll(output)
    functionOutputs.forEach { result ->
        add(
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", result.callId)
                put("output", result.output)
            },
        )
    }
}

private val com.molagpt.app.core.model.EnabledTools.hasByokTools: Boolean
    get() = network || steelBrowser || vision || imageGeneration || mcp

internal fun selectByokVisionModel(provider: ByokProvider, fallbackModelId: String): String {
    val current = provider.models.firstOrNull { it.id == fallbackModelId }
    if (current?.supportsVision == true) return fallbackModelId
    return provider.models.firstOrNull { it.supportsVision }?.id ?: fallbackModelId
}
