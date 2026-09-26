package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ByokLocalToolHandler
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ByokProfileKey
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.ContextOverflow
import com.molagpt.app.core.model.CustomBodyParam
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.ImageGenerationConfig
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.ReasoningOff
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ThinkingConfig
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import com.molagpt.app.core.model.TitleRequest
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.model.Usage
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
    /**
     * 本地记忆与历史回忆工具的执行回调。数据在 `core:storage`，而 storage 依赖本模块，
     * 因此执行必须回调出去，不能在这里直接读库。
     */
    private val localToolHandler: ByokLocalToolHandler = ByokLocalToolHandler.NoOp,
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

    /**
     * 非流式纯文本补全。会话标题与记忆整理共用同一条路径——两者都是「一次问、一段答」，
     * 都不需要工具循环、角色提示或流式渲染。
     *
     * 返回 null 表示 provider 不可用或请求失败；调用方据此静默降级，不打扰用户。
     */
    suspend fun completeText(
        providerId: String,
        modelId: String,
        prompt: String,
        maxTokens: Int = ANTHROPIC_TITLE_MAX_TOKENS,
        temperature: Double = TITLE_TEMPERATURE,
    ): String? {
        val provider = providerResolver(providerId)?.takeIf { it.enabled } ?: return null
        val target = modelId.trim().ifBlank { return null }
        return runCatching {
            withContext(dispatchers.io) { requestText(provider, target, prompt, maxTokens, temperature) }
        }.getOrNull()
    }

    /**
     * 上下文摘要：非流式、关思考、不带工具与角色提示。
     *
     * 与 [completeText] 的区别是它要把失败原因带回去——摘要本身也可能超长，调用方要据此
     * 把输入切小再试；以及要带回用量，摘要花的是用户的额度。
     * 请求可被取消：用户点停止时连接立刻断开，不会在后台跑完一个大请求。
     */
    suspend fun summarize(
        providerId: String,
        modelId: String,
        system: String,
        prompt: String,
        maxOutputTokens: Int,
    ): TextCompletion {
        val provider = providerResolver(providerId)?.takeIf { it.enabled }
            ?: return TextCompletion.Failure("BYOK 服务不可用", overflow = false)
        val target = modelId.trim().ifBlank { return TextCompletion.Failure("未指定模型", overflow = false) }
        val body = buildSummaryBody(provider, target, system, prompt, maxOutputTokens)
        val url = if (provider.type == ByokProviderType.GEMINI) {
            geminiEndpoint(provider, target, stream = false)
        } else {
            provider.endpoint(provider.chatPath)
        }
        val req = Request.Builder()
            .url(url)
            .apply {
                if (provider.type != ByokProviderType.GEMINI) {
                    provider.applyAuthHeaders { name, value -> header(name, value) }
                }
                if (provider.type == ByokProviderType.ANTHROPIC) header("anthropic-version", "2023-06-01")
            }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val (code, text) = try {
            http.okHttp.executeCancellable(req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TextCompletion.Failure("上下文压缩请求失败：${e.message ?: e.javaClass.simpleName}", overflow = false)
        }
        if (code !in 200..299) {
            return TextCompletion.Failure(
                serverResponseError("上下文压缩请求失败", code, text),
                overflow = ContextOverflow.matches(text),
            )
        }
        val summary = parseTitleResponse(provider.type, http.json, text)
            ?.replace(THINK_BLOCK, "")
            ?.trim()
            .orEmpty()
        if (summary.isEmpty()) return TextCompletion.Failure("模型未返回摘要", overflow = false)
        val root = runCatching { http.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val usage = root?.let {
            when (provider.type) {
                ByokProviderType.OPENAI_COMPAT -> parseOpenAiUsage(it)
                ByokProviderType.OPENAI_RESPONSE -> parseOpenAiResponseUsage(it)
                ByokProviderType.ANTHROPIC -> parseAnthropicUsage(it)
                ByokProviderType.GEMINI -> geminiUsage(it)
            }
        }
        return TextCompletion.Success(summary, usage)
    }

    private fun buildSummaryBody(
        provider: ByokProvider,
        modelId: String,
        system: String,
        prompt: String,
        maxOutputTokens: Int,
    ): JsonObject =
        when (provider.type) {
            ByokProviderType.OPENAI_COMPAT -> buildJsonObject {
                put("model", modelId)
                put("stream", false)
                put("temperature", TITLE_TEMPERATURE)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
                addOpenAiThinking(provider, modelId, requestedThinking = false, reasoningEffort = "")
                applyModelCustomBody(provider, modelId)
            }

            ByokProviderType.OPENAI_RESPONSE -> buildJsonObject {
                put("model", modelId)
                put("stream", false)
                put("instructions", system)
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

            ByokProviderType.ANTHROPIC -> buildJsonObject {
                put("model", modelId)
                put("max_tokens", maxOutputTokens)
                put("temperature", TITLE_TEMPERATURE)
                put("stream", false)
                put("system", system)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
                applyModelCustomBody(provider, modelId)
            }

            ByokProviderType.GEMINI -> buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", system) } }
                }
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

    private fun requestTitle(provider: ByokProvider, modelId: String, prompt: String): String? =
        requestText(provider, modelId, prompt, ANTHROPIC_TITLE_MAX_TOKENS, TITLE_TEMPERATURE)

    private fun requestText(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
    ): String? {
        val body = buildTextBody(provider, modelId, prompt, maxTokens, temperature)
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
     * 纯文本请求体：单条 user 消息、非流式、不带工具、不带角色系统提示
     * （否则猫娘人格会把标题写成「喵～」，也会把记忆整理写成角色扮演）。
     *
     * OpenAI 系**不设 max_tokens**：给推理模型设小上限会让预算全花在思考上、正文返回空。
     * 控成本靠下面显式关思考，不靠截断。[maxTokens] 只用于 Anthropic——那是它的必填字段。
     */
    private fun buildTextBody(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
    ): JsonObject =
        when (provider.type) {
            ByokProviderType.OPENAI_COMPAT -> buildJsonObject {
                put("model", modelId)
                put("stream", false)
                put("temperature", temperature)
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
                put("max_tokens", maxTokens)
                put("temperature", temperature)
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
            var usage: Usage? = null
            // 整条回答共用一份来源账本，跨工具轮累计——编号才连得上正文的 <ref>。
            val citations = WebSearchCitations()
            while (true) {
                currentCoroutineContext().ensureActive()
                val toolRound = runToolRound(provider, request, messages, citations) { event ->
                    if (event is StreamEvent.UsageUpdate) {
                        usage = usage.accumulate(event.usage)
                        emit(StreamEvent.UsageUpdate(usage!!))
                    } else emit(event)
                }
                if (toolRound == null) {
                    return@flow
                }
                messages = toolRound.messages
                if (toolRound.completed) {
                    emit(
                        StreamEvent.WireHistory(
                            OpenAiWireHistory.encode(
                                OpenAiWireHistory.CHAT_COMPLETIONS,
                                provider.id,
                                request.modelId,
                                messages.drop(baseMessages.size),
                            ),
                        ),
                    )
                    emit(StreamEvent.Finish("stop", usage))
                    return@flow
                }
            }
        }
        streamOpenAiCompatible(provider, request, baseMessages, includeTools = false)
            .collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamAnthropic(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val messages = buildAnthropicMessages(provider, request)
        if (request.enabledTools.hasByokTools) {
            runAnthropicToolLoop(provider, request, messages, WebSearchCitations()) { emit(it) }
            return@flow
        }
        val body = buildAnthropicBody(provider, request, messages)
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
                    val text = resp.body?.string().orEmpty()
                    emit(StreamEvent.Failed(serverResponseError("Anthropic 请求失败", resp.code, text)))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed(serverResponseError("Anthropic 响应为空", resp.code, "")))
                    return@flow
                }
                var streamUsage: Usage? = null
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (payload.isDone) {
                        emit(StreamEvent.Finish("stop"))
                        return@collect
                    }
                    val root = http.json.parseToJsonElement(payload.data).jsonObject
                    val incoming = when (root["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> (root["message"] as? JsonObject)?.let { anthropicUsage(it) }
                        "message_delta" -> anthropicUsage(root)
                        else -> null
                    }
                    if (incoming != null) {
                        streamUsage = Usage(
                            promptTokens = incoming.promptTokens ?: streamUsage?.promptTokens,
                            completionTokens = incoming.completionTokens ?: streamUsage?.completionTokens,
                            cachedTokens = incoming.cachedTokens ?: streamUsage?.cachedTokens,
                            cacheWriteTokens = incoming.cacheWriteTokens ?: streamUsage?.cacheWriteTokens,
                        )
                        emit(StreamEvent.UsageUpdate(streamUsage))
                    }
                    parseAnthropicEvent(payload.data)?.let { event ->
                        if (event is StreamEvent.Failed) {
                            emit(
                                StreamEvent.Failed(
                                    serverResponseError(
                                        "Anthropic 流式请求失败（${event.message}）",
                                        resp.code,
                                        payload.data,
                                    ),
                                ),
                            )
                        } else emit(event)
                    }
                }
            }
        } finally {
            runCatching { if (!call.isCanceled()) call.cancel() }
        }
    }.flowOn(dispatchers.io)

    private fun streamGemini(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val contents = buildGeminiContents(provider, request)
        if (request.enabledTools.hasByokTools) {
            runGeminiToolLoop(provider, request, contents, WebSearchCitations()) { emit(it) }
            return@flow
        }
        val body = buildGeminiBody(provider, request, contents)
        val req = Request.Builder()
            .url(geminiEndpoint(provider, request.modelId, stream = true))
            .header("Accept", "text/event-stream")
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val call = http.okHttp.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    emit(StreamEvent.Failed(serverResponseError("Gemini 请求失败", resp.code, text)))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed(serverResponseError("Gemini 响应为空", resp.code, "")))
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
                        if (event is StreamEvent.Failed) {
                            emit(
                                StreamEvent.Failed(
                                    serverResponseError(
                                        "Gemini 流式请求失败（${event.message}）",
                                        resp.code,
                                        payload.data,
                                    ),
                                ),
                            )
                        } else emit(event)
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
                    emit(StreamEvent.Failed(serverResponseError("OpenAI Compatible 请求失败", resp.code, text)))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed(serverResponseError("OpenAI Compatible 响应为空", resp.code, "")))
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
                        if (event is StreamEvent.Failed) {
                            emit(
                                StreamEvent.Failed(
                                    serverResponseError(
                                        "OpenAI Compatible 流式请求失败（${event.message}）",
                                        resp.code,
                                        payload.data,
                                    ),
                                ),
                            )
                        } else emit(event)
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
            var usage: Usage? = null
            val citations = WebSearchCitations()
            while (true) {
                currentCoroutineContext().ensureActive()
                val toolRound = runResponseToolRound(provider, request, input, citations) { event ->
                    if (event is StreamEvent.UsageUpdate) {
                        usage = usage.accumulate(event.usage)
                        emit(StreamEvent.UsageUpdate(usage!!))
                    } else emit(event)
                }
                if (toolRound == null) {
                    return@flow
                }
                input = toolRound.messages
                if (toolRound.completed) {
                    emit(
                        StreamEvent.WireHistory(
                            OpenAiWireHistory.encode(
                                OpenAiWireHistory.RESPONSES,
                                provider.id,
                                request.modelId,
                                input.drop(baseMessages.size),
                            ),
                        ),
                    )
                    emit(StreamEvent.Finish("stop", usage))
                    return@flow
                }
            }
        }
        streamResponseStream(provider, request, baseMessages, emptyList()).collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamResponseStream(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        precedingTurnItems: List<JsonObject>,
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
                    emit(StreamEvent.Failed(serverResponseError("Responses API 请求失败", resp.code, text)))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed(serverResponseError("Responses API 响应为空", resp.code, "")))
                    return@flow
                }
                val outputItems = mutableListOf<JsonObject>()
                val answer = StringBuilder()
                var finish: StreamEvent.Finish? = null
                var usage: Usage? = null
                var failed = false

                suspend fun handle(events: List<StreamEvent>) {
                    events.forEach { event ->
                        when (event) {
                            is StreamEvent.Delta -> {
                                event.text?.let(answer::append)
                                emit(event)
                            }
                            is StreamEvent.Finish -> finish = event
                            is StreamEvent.Failed -> {
                                failed = true
                                emit(event)
                            }
                            else -> emit(event)
                        }
                    }
                }

                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (payload.isDone) {
                        handle(parser.finishTail("stop"))
                        return@collect
                    }
                    captureResponsesOutputItems(http.json, payload.data, outputItems)
                    responsesStreamUsage(http.json, payload.data)?.let { usage = it }
                    val events = parser.parse(payload).map { event ->
                        if (event is StreamEvent.Failed) {
                            StreamEvent.Failed(
                                serverResponseError(
                                    "Responses API 流式请求失败（${event.message}）",
                                    resp.code,
                                    payload.data,
                                ),
                            )
                        } else event
                    }
                    handle(events)
                }
                if (finish == null) handle(parser.finishTail(null))
                if (failed) return@flow

                if (outputItems.none(::isResponsesMessageItem) && answer.isNotEmpty()) {
                    outputItems += syntheticResponsesMessage(answer.toString())
                }
                emit(
                    StreamEvent.WireHistory(
                        OpenAiWireHistory.encode(
                            OpenAiWireHistory.RESPONSES,
                            provider.id,
                            request.modelId,
                            precedingTurnItems + outputItems,
                        ),
                    ),
                )
                // usage 只在 response.completed 的 response 对象里，StreamParser 读的是 chat/completions
                // 形状的顶层 usage，这条路径原本一路是空的。
                emit(StreamEvent.Finish(finish?.reason ?: "stop", usage ?: finish?.usage))
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
        // 只把**开头连续的** system 抽到 instructions——那是角色/系统提示该待的地方。
        // 出现在对话中间的 system 是按位置生效的补充（世界书深度注入），它的价值就是位置，
        // 抽走等于静默失效。Responses 的 input 本来就接受 system 身份
        // （EasyInputMessage.role: user | assistant | system | developer），原样留在原位即可。
        val leadingSystem = messages.takeWhile { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
        val systemText = leadingSystem
            .mapNotNull { it["content"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
        val inputItems = messages.drop(leadingSystem.size).map(::toOpenAiResponseInputItem)
        return buildJsonObject {
            put("model", request.modelId)
            put("stream", stream)
            if (systemText != null) put("instructions", systemText)
            putJsonArray("input") { inputItems.forEach { add(it) } }
            // Responses API 推理：reasoning:{effort}；关闭同样要显式发，与 chat/completions 一致。
            val thinkingOn = request.useThinking || isAlwaysOnThinking(provider, request.modelId)
            if (effectiveThinkingKind(provider, request.modelId) != ThinkingParamKind.NONE) {
                if (thinkingOn) {
                    putJsonObject("reasoning") {
                        put("effort", request.reasoningEffort.ifBlank { ThinkingKinds.HIGH })
                    }
                } else {
                    addReasoningOff(provider, request.modelId)
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

    /**
     * Responses API 工具轮：流式接收，正文边到边发；output[] 从流里收集，函数调用在本轮结束后按序执行。
     *
     * output item 取两个来源：逐条的 `response.output_item.done`，以及终止事件里那份完整 output。
     * 官方两者都发，`response.completed` 的更权威（reasoning 的 encrypted_content 在 `.added` 阶段可能还不完整）；
     * 第三方实现常常只有其中一种。[captureResponsesOutputItems] 负责合并。
     */
    private suspend fun runResponseToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolRoundResult? {
        val body = buildOpenAiResponseBody(provider, request, messages, stream = true, includeTools = true)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .header("Accept", "text/event-stream")
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val parser = StreamParser(http.json)
        val outputItems = mutableListOf<JsonObject>()
        val answer = StringBuilder()
        var usage: Usage? = null
        var failed = false
        val networkCall = http.okHttp.newCall(req)
        try {
            networkCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    emitEvent(StreamEvent.Failed(serverResponseError("Responses API 工具请求失败", resp.code, text)))
                    return null
                }
                val source = resp.body?.source()
                if (source == null) {
                    emitEvent(StreamEvent.Failed(serverResponseError("Responses API 工具响应为空", resp.code, "")))
                    return null
                }
                // 本轮是否结束看 output[] 里有没有 function_call，不看流的 Finish，由外层循环判。
                suspend fun drain(events: List<StreamEvent>, raw: String) {
                    for (event in events) {
                        when (event) {
                            is StreamEvent.Finish -> Unit
                            is StreamEvent.Failed -> {
                                failed = true
                                emitEvent(
                                    StreamEvent.Failed(
                                        serverResponseError(
                                            "Responses API 工具流式请求失败（${event.message}）",
                                            resp.code,
                                            raw,
                                        ),
                                    ),
                                )
                            }
                            is StreamEvent.Delta -> {
                                event.text?.let(answer::append)
                                emitEvent(event)
                            }
                            else -> emitEvent(event)
                        }
                    }
                }
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (!payload.isDone) {
                        captureResponsesOutputItems(http.json, payload.data, outputItems)
                        responsesStreamUsage(http.json, payload.data)?.let { usage = it }
                    }
                    drain(parser.parse(payload), payload.data)
                }
                drain(parser.finishTail(null), "")
            }
        } finally {
            runCatching { if (!networkCall.isCanceled()) networkCall.cancel() }
        }
        if (failed) return null
        emitEvent(StreamEvent.UsageUpdate(usage ?: Usage(costComplete = false)))

        // 只发文本增量、不发 output_item 的端点，这一轮的回答在历史里会整段消失，补一条 message 顶上。
        if (outputItems.none(::isResponsesMessageItem) && answer.isNotEmpty()) {
            outputItems += syntheticResponsesMessage(answer.toString())
        }
        val calls = parseResponseOutputItems(outputItems)
            .filterIsInstance<ParsedResponseOutputItem.FunctionCall>()
        val functionOutputs = ArrayList<ResponseFunctionOutput>()
        for (item in calls) {
            val call = ToolCall(
                id = item.id ?: Ids.newFragmentId(),
                name = item.name,
                arguments = item.arguments,
                responseCallId = item.callId,
            )
            val result = executeAndEmitTool(provider, request, call, citations, emitEvent)
            functionOutputs.add(
                ResponseFunctionOutput(callId = call.responseCallId ?: call.id, output = result.output),
            )
        }
        // 官方手动上下文模式要求先原样回放整个 response.output（含 reasoning 的加密内容），
        // 再附加与 call_id 对应的 function_call_output；不能把这些 item 转成空 user message。
        return ToolRoundResult(
            messages = buildResponseReplayInput(messages, outputItems, functionOutputs),
            completed = calls.isEmpty(),
        )
    }

    /**
     * OpenAI 兼容工具轮：流式接收，tool_calls 分片累积到本轮结束再执行。
     *
     * 工具只是可能发生的分支，正文该边生成边显示。整包接收时一条上万字的回答会让界面
     * 在骨架屏上停十几秒，和请求卡死无法区分；而多数轮次模型根本不调工具。
     */
    private suspend fun runToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        baseMessages: List<JsonObject>,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolRoundResult? {
        val body = buildOpenAiBody(provider, request, baseMessages, stream = true, includeTools = true)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .header("Accept", "text/event-stream")
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val parser = StreamParser(http.json)
        val pending = PendingToolCalls()
        val rawContent = StringBuilder()
        var usage: Usage? = null
        var failed = false
        val networkCall = http.okHttp.newCall(req)
        try {
            networkCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    emitEvent(StreamEvent.Failed(serverResponseError("OpenAI Compatible 工具请求失败", resp.code, text)))
                    return null
                }
                val source = resp.body?.source()
                if (source == null) {
                    emitEvent(StreamEvent.Failed(serverResponseError("OpenAI Compatible 工具响应为空", resp.code, "")))
                    return null
                }
                // Finish 在这里不算数：本轮是否结束看有没有 tool_calls，由外层循环判。
                // Tool 事件同理——解析器看到的是半截参数，真正的工具卡由 executeAndEmitTool 发。
                suspend fun drain(events: List<StreamEvent>, raw: String) {
                    for (event in events) {
                        when (event) {
                            is StreamEvent.Tool -> Unit
                            is StreamEvent.Finish -> usage = event.usage ?: usage
                            is StreamEvent.Failed -> {
                                failed = true
                                emitEvent(
                                    StreamEvent.Failed(
                                        serverResponseError(
                                            "OpenAI Compatible 工具流式请求失败（${event.message}）",
                                            resp.code,
                                            raw,
                                        ),
                                    ),
                                )
                            }
                            else -> emitEvent(event)
                        }
                    }
                }
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (!payload.isDone) accumulateToolCallDelta(payload.data, rawContent, pending)
                    drain(parser.parse(payload), payload.data)
                }
                drain(parser.finishTail(null), "")
            }
        } finally {
            runCatching { if (!networkCall.isCanceled()) networkCall.cancel() }
        }
        if (failed) return null
        emitEvent(StreamEvent.UsageUpdate(usage ?: Usage(costComplete = false)))

        val calls = pending.build()
        val messages = baseMessages + assistantToolCallMessage(rawContent.toString(), calls)
        if (calls.isEmpty()) return ToolRoundResult(messages, completed = true)
        val withResults = messages.toMutableList()
        for (call in calls) {
            val result = executeAndEmitTool(provider, request, call, citations, emitEvent)
            withResults.add(toolResultMessage(call.id, result.output))
        }
        return ToolRoundResult(withResults, completed = false)
    }

    /** 回放历史要的是模型原文，不是 <think> 拆分和工具卡剥离之后的渲染文本，所以这里单独累积一份。 */
    private fun accumulateToolCallDelta(data: String, content: StringBuilder, pending: PendingToolCalls) {
        val root = runCatching { http.json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: return
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val delta = choice["delta"] as? JsonObject ?: return
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.let(content::append)
        (delta["tool_calls"] as? JsonArray)?.forEach(pending::feed)
    }

    /**
     * Claude 原生 Messages 工具循环。
     * 每次都把服务端返回的整个 assistant content（含 thinking/signature/redacted_thinking/tool_use）
     * 原样放回历史，再追加一个包含本轮全部 tool_result 的 user message。
     * content 由 [streamAnthropicToolRound] 从流式分片重建，回放形状与非流式响应一致。
     */
    private suspend fun runAnthropicToolLoop(
        provider: ByokProvider,
        request: ChatRequest,
        initialMessages: List<JsonObject>,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ) {
        val messages = initialMessages.toMutableList()
        val turnItems = ArrayList<JsonObject>()
        // 多轮累计：每轮都是独立计费的请求，只报最后一轮会大幅低估实际消耗。
        var usage: Usage? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val round = streamAnthropicToolRound(provider, request, messages, emitEvent) ?: return
            usage = usage.accumulate(round.usage ?: Usage(costComplete = false))
            usage?.let { emitEvent(StreamEvent.UsageUpdate(it)) }
            val assistantMessage = buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(round.content))
            }
            messages += assistantMessage
            turnItems += assistantMessage

            val calls = round.content.mapNotNull(::parseAnthropicToolCall)

            if (calls.isEmpty()) {
                emitEvent(
                    StreamEvent.WireHistory(
                        json = NativeWireHistory.encode(
                            NativeWireHistory.ANTHROPIC_MESSAGES,
                            provider.id,
                            request.modelId,
                            turnItems,
                        ),
                        metadataKey = ChatMessageMetadataKeys.ANTHROPIC_WIRE_HISTORY,
                    ),
                )
                emitEvent(StreamEvent.Finish(round.stopReason ?: "stop", usage))
                return
            }

            val results = calls.map { call ->
                val execution = executeAndEmitTool(provider, request, call, citations, emitEvent)
                NativeToolResult(
                    callId = call.id,
                    name = call.name,
                    output = execution.output,
                    isError = execution.status == ToolStatus.FAILED,
                )
            }
            val resultMessage = buildAnthropicToolResultMessage(results)
            messages += resultMessage
            turnItems += resultMessage
        }
    }

    private data class AnthropicRound(
        val content: List<JsonObject>,
        val stopReason: String?,
        val usage: Usage?,
    )

    /**
     * Claude 工具轮的一次流式请求：文本与思考边到边发，同时把 content 块重建出来供回放。
     *
     * usage 分两处下发：`message_start` 带输入侧，`message_delta` 带输出侧，都要收。
     * 返回 null 表示本轮已经发过 Failed，调用方直接结束。
     */
    private suspend fun streamAnthropicToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): AnthropicRound? {
        val body = buildAnthropicBody(provider, request, messages, stream = true, includeTools = true)
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath))
            .header("Accept", "text/event-stream")
            .header("anthropic-version", "2023-06-01")
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val blocks = AnthropicStreamBlocks()
        var stopReason: String? = null
        var usage: Usage? = null
        var failed = false
        val networkCall = http.okHttp.newCall(req)
        try {
            networkCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    emitEvent(StreamEvent.Failed(serverResponseError("Anthropic 工具请求失败", resp.code, text)))
                    return null
                }
                val source = resp.body?.source()
                if (source == null) {
                    emitEvent(StreamEvent.Failed(serverResponseError("Anthropic 工具响应为空", resp.code, "")))
                    return null
                }
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    val root = runCatching { http.json.parseToJsonElement(payload.data) }.getOrNull() as? JsonObject
                        ?: return@collect
                    when (root["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> (root["message"] as? JsonObject)?.let { message ->
                            usage = usage.mergeAnthropic(anthropicUsage(message))
                        }
                        "message_delta" -> {
                            usage = usage.mergeAnthropic(anthropicUsage(root))
                            (root["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                                ?.let { stopReason = it }
                        }
                        "content_block_start" -> blocks.start(root)
                        "content_block_delta" -> blocks.delta(root)
                    }
                    parseAnthropicEvent(payload.data)?.let { event ->
                        when (event) {
                            // 本轮是否结束看有没有 tool_use，不看 message_stop，由外层循环判。
                            is StreamEvent.Finish -> Unit
                            is StreamEvent.Failed -> {
                                failed = true
                                emitEvent(
                                    StreamEvent.Failed(
                                        serverResponseError(
                                            "Anthropic 工具流式请求失败（${event.message}）",
                                            resp.code,
                                            payload.data,
                                        ),
                                    ),
                                )
                            }
                            else -> emitEvent(event)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitEvent(StreamEvent.Failed("Anthropic 请求失败：${error.message ?: error::class.simpleName}"))
            return null
        } finally {
            runCatching { if (!networkCall.isCanceled()) networkCall.cancel() }
        }
        if (failed) return null
        return AnthropicRound(blocks.build(http.json), stopReason, usage)
    }

    /**
     * Gemini GenerateContent 原生工具循环。
     * 模型返回的 content/parts（含 thoughtSignature）原样回放；并行调用的响应集中在同一个 user content。
     */
    private suspend fun runGeminiToolLoop(
        provider: ByokProvider,
        request: ChatRequest,
        initialContents: List<JsonObject>,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ) {
        val contents = initialContents.toMutableList()
        val turnItems = ArrayList<JsonObject>()
        // 多轮累计：每轮都是独立计费的请求，只报最后一轮会大幅低估实际消耗。
        var usage: Usage? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val round = streamGeminiToolRound(provider, request, contents, emitEvent) ?: return
            val modelContent = ensureGeminiModelRole(round.content)
            contents += modelContent
            turnItems += modelContent
            usage = usage.accumulate(round.usage ?: Usage(costComplete = false))
            usage?.let { emitEvent(StreamEvent.UsageUpdate(it)) }

            val parts = (modelContent["parts"] as? JsonArray).orEmpty()
            val calls = parts.mapNotNull(::parseGeminiToolCall)

            if (calls.isEmpty()) {
                emitEvent(
                    StreamEvent.WireHistory(
                        json = NativeWireHistory.encode(
                            NativeWireHistory.GEMINI_GENERATE_CONTENT,
                            provider.id,
                            request.modelId,
                            turnItems,
                        ),
                        metadataKey = ChatMessageMetadataKeys.GEMINI_WIRE_HISTORY,
                    ),
                )
                emitEvent(StreamEvent.Finish(round.finishReason ?: "stop", usage))
                return
            }

            val results = calls.map { call ->
                val execution = executeAndEmitTool(provider, request, call, citations, emitEvent)
                NativeToolResult(
                    callId = call.responseCallId,
                    name = call.name,
                    output = execution.output,
                    isError = execution.status == ToolStatus.FAILED,
                )
            }
            val responseContent = buildGeminiFunctionResponseContent(results)
            contents += responseContent
            turnItems += responseContent
        }
    }

    private data class GeminiRound(
        val content: JsonObject,
        val finishReason: String?,
        val usage: Usage?,
    )

    /**
     * Gemini 工具轮的一次流式请求：正文与思考边到边发，同时把 parts 合并回一个 content 供回放。
     *
     * 每个分片的 usageMetadata 是累计值，取最后一份即可，不要再跨分片求和。
     * 返回 null 表示本轮已经发过 Failed，调用方直接结束。
     */
    private suspend fun streamGeminiToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        contents: List<JsonObject>,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): GeminiRound? {
        val body = buildGeminiBody(provider, request, contents, includeTools = true)
        val req = Request.Builder()
            .url(geminiEndpoint(provider, request.modelId, stream = true))
            .header("Accept", "text/event-stream")
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val parts = GeminiStreamParts()
        var finishReason: String? = null
        var usage: Usage? = null
        var blockReason: String? = null
        var failed = false
        val networkCall = http.okHttp.newCall(req)
        try {
            networkCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    emitEvent(StreamEvent.Failed(serverResponseError("Gemini 工具请求失败", resp.code, text)))
                    return null
                }
                val source = resp.body?.source()
                if (source == null) {
                    emitEvent(StreamEvent.Failed(serverResponseError("Gemini 工具响应为空", resp.code, "")))
                    return null
                }
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    val root = runCatching { http.json.parseToJsonElement(payload.data) }.getOrNull() as? JsonObject
                    if (root != null) {
                        parts.feed(root)
                        (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull
                            ?.let { blockReason = it }
                    }
                    parseGeminiEvent(payload.data).forEach { event ->
                        when (event) {
                            // 本轮是否结束看有没有 functionCall，不看 finishReason，由外层循环判。
                            is StreamEvent.Finish -> finishReason = event.reason ?: finishReason
                            is StreamEvent.UsageUpdate -> usage = event.usage
                            is StreamEvent.Failed -> {
                                failed = true
                                emitEvent(
                                    StreamEvent.Failed(
                                        serverResponseError(
                                            "Gemini 工具流式请求失败（${event.message}）",
                                            resp.code,
                                            payload.data,
                                        ),
                                    ),
                                )
                            }
                            else -> emitEvent(event)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitEvent(StreamEvent.Failed("Gemini 请求失败：${error.message ?: error::class.simpleName}"))
            return null
        } finally {
            runCatching { if (!networkCall.isCanceled()) networkCall.cancel() }
        }
        if (failed) return null
        val content = parts.build()
        if ((content["parts"] as? JsonArray).isNullOrEmpty()) {
            val summary = blockReason?.let { "Gemini 工具请求被拦截：$it" } ?: "Gemini 工具响应缺少 content"
            emitEvent(StreamEvent.Failed(summary))
            return null
        }
        return GeminiRound(content, finishReason, usage)
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
        // OpenAI 兼容端点流式默认不返回 usage，必须显式要；不要它就没有 token 统计可展示。
        // usage 会单独放在 finish_reason 之后的 choices:[] chunk 里（StreamParser 有对应处理）。
        if (stream && supportsStreamUsage(provider.baseUrl)) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        putJsonArray("messages") {
            messages.forEach { add(it) }
        }
        if (includeTools && request.enabledTools.hasByokTools) put("tools", toolDefinitions(provider, request))
        addOpenAiThinking(provider, request.modelId, request.useThinking, request.reasoningEffort)
        applyModelCustomBody(provider, request.modelId)
    }

    /**
     * 该 host 是否接受 `stream_options`。Mistral 收到这个字段会直接 400，
     * 所以按 host 排除而不是全量下发——聚合网关（OpenRouter 等）都是支持的。
     */
    private fun supportsStreamUsage(baseUrl: String): Boolean =
        !baseUrl.contains("api.mistral.ai", ignoreCase = true)

    private fun thinkingConfigOf(provider: ByokProvider, modelId: String): ThinkingConfig? =
        provider.models.firstOrNull { it.id == modelId }?.thinkingConfig

    /**
     * 有效推理 kind：模型显式配置（含 NONE=关闭）优先；否则按方言表的 host 形状→模型 ID 兜底。
     * 聚合网关下预算类通过 [ThinkingKinds.wireKind] 折算为 OPENAI_REASONING_EFFORT，
     * 避免把 thinking_budget 等家族私有参数发到网关。
     */
    private fun effectiveThinkingKind(provider: ByokProvider, modelId: String): ThinkingParamKind {
        val raw = thinkingConfigOf(provider, modelId)?.kind
            ?: ThinkingKinds.hostInferredKind(provider.baseUrl)
            ?: ThinkingKinds.inferFromModelId(modelId)
        return ThinkingKinds.wireKind(raw, provider.baseUrl)
    }

    /**
     * 该模型是否关不掉推理：服务商声明强制、方言表无关闭手段、或观测到关了也没用。
     * 关不掉时按「开启」构造请求——发一个服务商不认的禁用参数只会换来 400。
     */
    private fun isAlwaysOnThinking(provider: ByokProvider, modelId: String): Boolean {
        val cfg = thinkingConfigOf(provider, modelId)
            ?: return ThinkingKinds.isKimiK3(modelId)
        return ThinkingKinds.isAlwaysOn(cfg, provider.baseUrl) || ThinkingKinds.isKimiK3(modelId)
    }

    /**
     * 关闭推理：按方言表显式发出禁用参数。
     *
     * 省略参数不等于关闭——混合推理模型上多数服务商把缺省解释为「沿用服务端默认」，
     * 而默认往往是开启。方言表给不出关闭手段时（[ReasoningOff.UNSUPPORTED]）什么都不发，
     * 因为那种模型本来就关不掉，硬发只会 400；UI 侧同样不会给出「关」档。
     */
    private fun JsonObjectBuilder.addReasoningOff(provider: ByokProvider, modelId: String) {
        val kind = thinkingConfigOf(provider, modelId)?.kind ?: effectiveThinkingKind(provider, modelId)
        when (ThinkingKinds.offFor(provider.baseUrl, kind)) {
            ReasoningOff.OMIT, ReasoningOff.UNSUPPORTED -> Unit
            ReasoningOff.REASONING_DISABLED -> putJsonObject("reasoning") { put("enabled", false) }
            ReasoningOff.THINKING_DISABLED -> putJsonObject("thinking") { put("type", "disabled") }
            ReasoningOff.ENABLE_THINKING_FALSE -> put("enable_thinking", false)
        }
    }

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
            addReasoningOff(provider, modelId)
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

    private fun buildAnthropicMessages(provider: ByokProvider, request: ChatRequest): List<JsonObject> {
        val replaceImages = replaceImagesWithText(provider, request)
        val imageOrdinal = AtomicInteger(0)
        return buildList {
            // 带 ROLE_INJECTION 标记的 system 消息靠位置生效，必须留在原处（下面按 user 身份发出）。
            request.messages.filter { it.role != Role.SYSTEM || it.isRoleInjection }.forEach { message ->
                if (message.role == Role.ASSISTANT) {
                    val preserved = NativeWireHistory.decode(
                        http.json,
                        message.metadata[ChatMessageMetadataKeys.ANTHROPIC_WIRE_HISTORY],
                        NativeWireHistory.ANTHROPIC_MESSAGES,
                        provider.id,
                        request.modelId,
                    )
                    if (preserved != null) {
                        addAll(preserved)
                        return@forEach
                    }
                }
                add(
                    buildJsonObject {
                        put("role", if (message.role == Role.ASSISTANT) "assistant" else "user")
                        put("content", ByokMessageContentBuilder.anthropicContent(message, replaceImages, imageOrdinal))
                    },
                )
            }
        }
    }

    private fun buildGeminiContents(provider: ByokProvider, request: ChatRequest): List<JsonObject> {
        val replaceImages = replaceImagesWithText(provider, request)
        val imageOrdinal = AtomicInteger(0)
        return buildList {
            // 带 ROLE_INJECTION 标记的 system 消息靠位置生效，必须留在原处（下面按 user 身份发出）。
            request.messages.filter { it.role != Role.SYSTEM || it.isRoleInjection }.forEach { message ->
                if (message.role == Role.ASSISTANT) {
                    val preserved = NativeWireHistory.decode(
                        http.json,
                        message.metadata[ChatMessageMetadataKeys.GEMINI_WIRE_HISTORY],
                        NativeWireHistory.GEMINI_GENERATE_CONTENT,
                        provider.id,
                        request.modelId,
                    )
                    if (preserved != null) {
                        addAll(preserved)
                        return@forEach
                    }
                }
                add(
                    buildJsonObject {
                        put("role", if (message.role == Role.ASSISTANT) "model" else "user")
                        put("parts", ByokMessageContentBuilder.geminiParts(message, replaceImages, imageOrdinal))
                    },
                )
            }
        }
    }

    private fun buildMessages(provider: ByokProvider, request: ChatRequest): List<JsonObject> {
        val replaceImages = replaceImagesWithText(provider, request)
        val imageOrdinal = AtomicInteger(0)
        val replayDeepSeekReasoning =
            provider.type == ByokProviderType.OPENAI_COMPAT &&
                effectiveThinkingKind(provider, request.modelId) == ThinkingParamKind.DEEPSEEK_THINKING
        val expectedWireApi = if (provider.type == ByokProviderType.OPENAI_RESPONSE) {
            OpenAiWireHistory.RESPONSES
        } else {
            OpenAiWireHistory.CHAT_COMPLETIONS
        }
        return buildList {
            request.messages.forEach { message ->
                if (message.role == Role.ASSISTANT) {
                    val preserved = OpenAiWireHistory.decode(
                        http.json,
                        message.metadata[ChatMessageMetadataKeys.OPENAI_WIRE_HISTORY],
                        expectedWireApi,
                        provider.id,
                        request.modelId,
                    )
                    if (preserved != null) {
                        addAll(preserved)
                        return@forEach
                    }
                }

                val wire = OpenAiMessageContentBuilder.build(
                    message,
                    includeFileParts = true,
                    replaceImagesWithText = replaceImages,
                    imageOrdinal = imageOrdinal,
                )
                add(
                    buildJsonObject {
                        // chat/completions 与 Responses 都接受任意位置的 system，身份原样发出。
                        // 深度注入的位置由 buildOpenAiResponseBody 的「只抽开头的 system」保住。
                        put("role", wire.role)
                        put("content", wire.content)
                        if (replayDeepSeekReasoning && message.role == Role.ASSISTANT) {
                            message.fragments
                                .filterIsInstance<MessageFragment.Thinking>()
                                .joinToString(separator = "", transform = MessageFragment.Thinking::text)
                                .takeIf { it.isNotBlank() }
                                ?.let { put("reasoning_content", it) }
                        }
                    },
                )
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
                    properties = mapOf(
                        "query" to ToolProperty("Search query"),
                        "max_results" to ToolProperty("Number of results", ToolPropertyType.INTEGER),
                    ),
                    required = listOf("query"),
                ),
            )
        }
        if (request.enabledTools.steelBrowser) {
            add(
                ToolSpec(
                    name = "fetch_url",
                    description = "Fetch and read a web page by URL.",
                    properties = mapOf("url" to ToolProperty("URL to fetch")),
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
                        "image_index" to ToolProperty(
                            "1-based global index of the image, matching the [图片#N] marker. Defaults to the most recent image.",
                        ),
                        "query" to ToolProperty("What to inspect or answer about the image."),
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
                    properties = mapOf("prompt" to ToolProperty("Image prompt")),
                    required = listOf("prompt"),
                ),
            )
        }
        if (request.enabledTools.mcp) {
            add(
                ToolSpec(
                    name = "mcp_list_tools",
                    description = "List tools exposed by enabled MCP servers.",
                    properties = mapOf("server" to ToolProperty("Optional MCP server name")),
                    required = emptyList(),
                ),
            )
            add(
                ToolSpec(
                    name = "mcp_call",
                    description = "Call an enabled MCP server tool.",
                    properties = mapOf(
                        "server" to ToolProperty("MCP server name"),
                        "tool" to ToolProperty("Tool name"),
                        "arguments" to ToolProperty("Tool arguments JSON"),
                    ),
                    required = listOf("server", "tool"),
                ),
            )
        }
        if (request.enabledTools.memory) {
            add(
                ToolSpec(
                    name = ByokLocalToolHandler.SAVE_MEMORY,
                    description = "Remember one stable fact about the user that will still be true in a different " +
                        "conversation. Evidence must come from what the user said in the current turn. " +
                        "Never store credentials, tokens, passwords, identity documents or payment details, " +
                        "nor race, ethnicity, religion, sexual orientation, sex life, political views, " +
                        "criminal record, or health and medical history.",
                    properties = mapOf(
                        "text" to ToolProperty(
                            "A complete third-person statement about the user, at most 300 characters. " +
                                "For a profile_key, put only the value itself.",
                        ),
                        "section" to ToolProperty(
                            "Which section this fact belongs to.",
                            enumValues = MemorySection.entries.map { it.wire },
                        ),
                        "source_quote" to ToolProperty(
                            "Verbatim words from the user's current message that support this fact.",
                        ),
                        "profile_key" to ToolProperty(
                            "Optional. Set only when the fact is exactly one of these profile values.",
                            enumValues = ByokProfileKey.entries.map { it.wire },
                        ),
                        "topic" to ToolProperty("Optional short topic name. Reuse an existing topic when possible."),
                        "group" to ToolProperty(
                            "Topic group.",
                            enumValues = com.molagpt.app.core.model.ByokMemoryTopics.groups,
                        ),
                        "summary" to ToolProperty("Optional one-sentence topic summary."),
                    ),
                    required = listOf("text", "section", "source_quote"),
                ),
            )
            add(
                ToolSpec(
                    name = ByokLocalToolHandler.FORGET_MEMORY,
                    description = "Delete a stored memory that the user just said is wrong or asked you to remove. " +
                        "Only call this when the user said so in the current turn.",
                    properties = mapOf(
                        "query" to ToolProperty("What to forget."),
                        "source_quote" to ToolProperty(
                            "Verbatim words from the user's current message asking to remove or correct this.",
                        ),
                    ),
                    required = listOf("query", "source_quote"),
                ),
            )
        }
        if (request.enabledTools.conversationRecall) {
            add(
                ToolSpec(
                    name = ByokLocalToolHandler.RECALL_CONVERSATIONS,
                    description = "Search past conversations stored on this device and read the messages around " +
                        "each hit. Call it when the user refers to something discussed before.",
                    properties = mapOf(
                        // 用分隔字符串而非 array：四套 schema 对数组参数的支持差异大，
                        // 字符串在弱模型上更稳，拆分成本可忽略。
                        "queries" to ToolProperty(
                            "One to four short phrases separated by |. A result matching any phrase is returned.",
                        ),
                        "limit" to ToolProperty("Maximum hits, 1 to 3. Defaults to 3.", ToolPropertyType.INTEGER),
                        "conversation_id" to ToolProperty(
                            "Optional. Restrict the search to one conversation returned by an earlier call.",
                        ),
                    ),
                    required = listOf("queries"),
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
            spec.properties.forEach { (key, property) ->
                put(key, buildJsonObject {
                    put("type", if (uppercaseTypes) property.type.upper else property.type.lower)
                    put("description", property.description)
                    if (property.enumValues.isNotEmpty()) {
                        putJsonArray("enum") { property.enumValues.forEach { add(it) } }
                    }
                })
            }
        })
        putJsonArray("required") { spec.required.forEach { add(it) } }
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
        val protocolId = functionCall["id"]?.jsonPrimitive?.contentOrNull
        return ToolCall(protocolId ?: Ids.newFragmentId(), name, args, responseCallId = protocolId)
    }

    private suspend fun executeAndEmitTool(
        provider: ByokProvider,
        request: ChatRequest,
        call: ToolCall,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolExecutionResult {
        val before = citations.snapshot().size
        val result = emitByokToolLifecycle(
            id = call.id,
            name = call.name,
            label = labelForTool(call.name),
            argsJson = call.arguments,
            execute = {
                val execution = executeTool(provider, request, call, citations)
                // 出图工具：把 base64/图片转本地文件 + Image 事件，回给模型的只留占位文本（绝不回灌 base64）。
                execution.copy(output = processImageToolResult(call, execution.output, emitEvent))
            },
            resultPreview = { result ->
                if (shouldShowToolPreview(call.name)) toolPreviewOf(call, result) else null
            },
            emitEvent = emitEvent,
        )
        // 搜到新来源就把整份账本发给 UI。发全量而不是增量：SetSources 是整体替换，
        // 而且一轮里搜第二次时，第一次的来源必须还在——正文的 <ref> 仍然指着它们。
        if (citations.snapshot().size > before) {
            emitEvent(StreamEvent.Sources(citations.snapshot(), citations.queryLabel()))
        }
        return result
    }

    private suspend fun executeTool(
        provider: ByokProvider,
        request: ChatRequest,
        call: ToolCall,
        citations: WebSearchCitations,
    ): ToolExecutionResult {
        // 只执行本轮真正声明过的工具。模型会凭空调用没给它的工具（幻觉，或被读到的网页诱导），
        // 而"关掉记忆"必须意味着写不进来，不能只是没告诉模型有这个工具。
        if (toolSpecs(provider, request).none { it.name == call.name }) {
            return ToolExecutionResult.failure("Tool ${call.name} is not enabled for this conversation.")
        }
        val output = try {
            when (call.name) {
                "search_web" -> searchWeb(call.arg("query"), call.arg("max_results")?.toIntOrNull(), citations)
                "fetch_url" -> fetchUrl(call.arg("url"))
                "view_image" -> viewImage(provider, request, call)
                "generate_image" -> generateImage(provider, request, call.arg("prompt"))
                "mcp_list_tools" -> listMcpTools(call.arg("server"))
                "mcp_call" -> callMcpServer(call)
                // 本地记忆与历史回忆：执行完全在 core:storage，这里只转发原始参数与会话 id。
                in ByokLocalToolHandler.ALL -> localToolHandler.execute(call.name, call.arguments, request.sessionId)
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
        // 必须与 content builder 用同一份有序列表：那边给不可用的图也留了序号，
        // 这里若过滤掉，后面每一张图的下标都会左移，模型按 [图片#N] 取到的就不是它要的那张。
        val allImages = request.messages
            .filter { it.role == com.molagpt.app.core.model.Role.USER }
            .flatMap { AttachmentParts.orderedImages(it) }
        val image = allImages.getOrNull(index - 1)
            ?: return "Image index $index out of range (1-${allImages.size})"
        if (image.unavailable || image.remoteUrl.isNullOrBlank()) {
            return "Image $index (${image.name}) is no longer available on this device."
        }
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
                if (!resp.isSuccessful) {
                    return "Vision failed: ${serverResponseError("OpenAI Compatible 视觉请求失败", resp.code, text)}"
                }
                parseOpenAiTextResult(resp.code, text)
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
                if (!resp.isSuccessful) {
                    return "Vision failed: ${serverResponseError("Responses API 视觉请求失败", resp.code, text)}"
                }
                parseResponseTextResult(resp.code, text)
            }
        }.getOrElse { "Vision failed: ${it.message}" }
    }

    /** 从 Responses API 非流式结果中抽取 output[].content[].text。 */
    private fun parseResponseTextResult(statusCode: Int, text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return "Vision failed: ${serverResponseError("Responses API 视觉响应格式无效", statusCode, text)}"
        if (root["status"]?.jsonPrimitive?.contentOrNull.equals("failed", ignoreCase = true)) {
            return "Vision failed: ${serverResponseError("Responses API 视觉请求失败", statusCode, text)}"
        }
        val output = root["output"] as? JsonArray
            ?: return "Vision failed: ${serverResponseError("Responses API 视觉响应缺少 output", statusCode, text)}"
        return output.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o["type"]?.jsonPrimitive?.contentOrNull != "message") return@mapNotNull null
            (o["content"] as? JsonArray)?.mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                if (co["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    co["text"]?.jsonPrimitive?.contentOrNull
                } else null
            }?.joinToString("")
        }.joinToString("").ifBlank {
            "Vision failed: ${serverResponseError("Responses API 视觉响应缺少文本", statusCode, text)}"
        }
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
                if (!resp.isSuccessful) {
                    return "Vision failed: ${serverResponseError("Anthropic 视觉请求失败", resp.code, text)}"
                }
                parseAnthropicTextResult(resp.code, text)
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
                if (!resp.isSuccessful) {
                    return "Vision failed: ${serverResponseError("Gemini 视觉请求失败", resp.code, text)}"
                }
                parseGeminiTextResult(resp.code, text)
            }
        }.getOrElse { "Vision failed: ${it.message}" }
    }

    private fun parseOpenAiTextResult(statusCode: Int, text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return "Vision failed: ${serverResponseError("OpenAI Compatible 视觉响应格式无效", statusCode, text)}"
        root["error"]?.jsonObject?.let {
            return "Vision failed: ${serverResponseError("OpenAI Compatible 视觉请求失败", statusCode, text)}"
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return "Vision failed: ${serverResponseError("OpenAI Compatible 视觉响应缺少 choice", statusCode, text)}"
        val message = choice["message"] as? JsonObject
            ?: return "Vision failed: ${serverResponseError("OpenAI Compatible 视觉响应缺少 message", statusCode, text)}"
        val content = message["content"]
            ?: return "Vision failed: ${serverResponseError("OpenAI Compatible 视觉响应缺少 content", statusCode, text)}"
        if (content is JsonArray) {
            return content.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("\n").ifBlank {
                "Vision failed: ${serverResponseError("OpenAI Compatible 视觉响应缺少文本", statusCode, text)}"
            }
        }
        return content.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "Vision failed: ${serverResponseError("OpenAI Compatible 视觉响应缺少文本", statusCode, text)}"
    }

    private fun parseAnthropicTextResult(statusCode: Int, text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return "Vision failed: ${serverResponseError("Anthropic 视觉响应格式无效", statusCode, text)}"
        root["error"]?.jsonObject?.let {
            return "Vision failed: ${serverResponseError("Anthropic 视觉请求失败", statusCode, text)}"
        }
        val content = root["content"] as? JsonArray
            ?: return "Vision failed: ${serverResponseError("Anthropic 视觉响应缺少 content", statusCode, text)}"
        return content.mapNotNull { part ->
            (part as? JsonObject)
                ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        }.joinToString("\n").ifBlank {
            "Vision failed: ${serverResponseError("Anthropic 视觉响应缺少文本", statusCode, text)}"
        }
    }

    private fun parseGeminiTextResult(statusCode: Int, text: String): String {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return "Vision failed: ${serverResponseError("Gemini 视觉响应格式无效", statusCode, text)}"
        root["error"]?.jsonObject?.let {
            return "Vision failed: ${serverResponseError("Gemini 视觉请求失败", statusCode, text)}"
        }
        val parts = (root["candidates"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts") as? JsonArray
            ?: return "Vision failed: ${serverResponseError("Gemini 视觉响应缺少 content", statusCode, text)}"
        return parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .ifBlank { "Vision failed: ${serverResponseError("Gemini 视觉响应缺少文本", statusCode, text)}" }
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

    /**
     * 搜完就地编号：命中进 [citations] 拿到跨调用连续的编号，模型看到的是带 `[来源 N]`
     * 的上下文加一条引用规则，UI 拿到的是同一批来源（由调用方发 [StreamEvent.Sources]）。
     *
     * 三家 provider 的差别只在解析：DDG 抓 HTML 只有标题和链接，Tavily/Exa 还带摘要和日期。
     * 摘要缺了来源卡照样成立，所以没有为此把 DDG 排除在外。
     */
    private fun searchWeb(query: String?, requestedMax: Int?, citations: WebSearchCitations): String {
        if (query.isNullOrBlank()) return "Missing query"
        val options = webSearchOptionsProvider()
        val maxResults = (requestedMax ?: options.maxResults).coerceIn(1, options.maxResults.coerceIn(1, 10))
        val outcome = when (options.provider) {
            WebSearchProvider.TAVILY -> searchTavily(query, options.apiKey, maxResults)
            WebSearchProvider.EXA -> searchExa(query, options.apiKey, maxResults)
            WebSearchProvider.DUCKDUCKGO -> searchDuckDuckGo(query, maxResults)
        }
        return when (outcome) {
            is WebSearchOutcome.Message -> outcome.text
            is WebSearchOutcome.Hits -> listOfNotNull(
                outcome.note,
                citations.toolResult(citations.absorb(query, outcome.hits)),
            ).joinToString("\n\n")
        }
    }

    /** 搜索的两种结局：拿到命中（可附带 provider 自己给的摘要），或一段直接回给模型的说明。 */
    private sealed interface WebSearchOutcome {
        data class Hits(val hits: List<WebSearchHit>, val note: String? = null) : WebSearchOutcome
        data class Message(val text: String) : WebSearchOutcome
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): WebSearchOutcome {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        // UA 不在此处设置：MolaHttp 的拦截器会统一覆盖为固定 UA。实测 DDG 恰好对固定 UA 返回正常
        // 结果，而浏览器 UA 会触发 202 反爬页，故不要在这里改写 UA。
        val req = Request.Builder()
            .url("https://duckduckgo.com/html/?q=$encoded")
            .header("Accept", "text/html")
            .build()
        return runCatching {
            http.okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return WebSearchOutcome.Message("Search failed: HTTP ${resp.code}")
                val html = resp.body?.string().orEmpty()
                // DDG HTML 结构多变：href 与 title 顺序/属性可能调换，故用更宽松的两段匹配。
                val regex = Regex(
                    """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
                val hits = regex.findAll(html).take(maxResults).map { match ->
                    val url = decodeDuckDuckGoHref(htmlDecode(match.groupValues[1]))
                    // 抓 HTML 只拿得到标题和链接：没有摘要，也没有发布日期。
                    WebSearchHit(title = stripHtml(htmlDecode(match.groupValues[2])).ifBlank { url }, url = url)
                }.toList()
                if (hits.isEmpty()) {
                    WebSearchOutcome.Message("未从 DuckDuckGo 获取到结果（可能被反爬限制），可在设置改用 Tavily/Exa。")
                } else {
                    WebSearchOutcome.Hits(hits)
                }
            }
        }.getOrElse { WebSearchOutcome.Message("Search failed: ${it.message}") }
    }

    /** DDG 的 result__a href 常是 /l/?uddg=<encoded-real-url> 跳转链接，解出真实 URL。 */
    private fun decodeDuckDuckGoHref(href: String): String {
        val marker = "uddg="
        val idx = href.indexOf(marker)
        // 没有跳转包装时 href 可能是 //host/path 这种省略协议的写法，补全再交出去：
        // 这个字符串现在是来源的 url，要能被系统浏览器直接打开。
        if (idx < 0) return if (href.startsWith("//")) "https:$href" else href
        val raw = href.substring(idx + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(href)
    }

    private fun searchTavily(query: String, apiKey: String?, maxResults: Int): WebSearchOutcome {
        if (apiKey.isNullOrBlank()) return WebSearchOutcome.Message("未配置 Tavily API Key，请在设置填写")
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
                if (!resp.isSuccessful) {
                    return WebSearchOutcome.Message("Search failed: HTTP ${resp.code} ${text.take(300)}")
                }
                val root = http.json.parseToJsonElement(text).jsonObject
                val hits = (root["results"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    WebSearchHit(
                        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                        snippet = obj["content"]?.jsonPrimitive?.contentOrNull?.take(500),
                        // news 主题才带日期，general 不带；取不到就让来源卡只显示站点。
                        publishedDate = obj["published_date"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                val answer = root["answer"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (hits.isEmpty()) {
                    WebSearchOutcome.Message(answer?.let { "摘要：$it" } ?: "Tavily 未返回结果")
                } else {
                    WebSearchOutcome.Hits(hits, note = answer?.let { "摘要：$it" })
                }
            }
        }.getOrElse { WebSearchOutcome.Message("Search failed: ${it.message}") }
    }

    private fun searchExa(query: String, apiKey: String?, maxResults: Int): WebSearchOutcome {
        if (apiKey.isNullOrBlank()) return WebSearchOutcome.Message("未配置 Exa API Key，请在设置填写")
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
                if (!resp.isSuccessful) {
                    return WebSearchOutcome.Message("Search failed: HTTP ${resp.code} ${text.take(300)}")
                }
                val root = http.json.parseToJsonElement(text).jsonObject
                val hits = (root["results"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    WebSearchHit(
                        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                        snippet = obj["text"]?.jsonPrimitive?.contentOrNull?.take(500),
                        publishedDate = obj["publishedDate"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                if (hits.isEmpty()) WebSearchOutcome.Message("Exa 未返回结果") else WebSearchOutcome.Hits(hits)
            }
        }.getOrElse { WebSearchOutcome.Message("Search failed: ${it.message}") }
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
                val html = resp.body?.string().orEmpty()
                // 抬头两行是给「这一页是什么」定位用的：模型本来只能从一堆去标签正文里猜自己读了啥，
                // 工具卡的预览也靠它（见 toolPreviewOf）——正文一律不进卡片。
                buildString {
                    extractHtmlTitle(html)?.let { append("标题：").append(it).append('\n') }
                    append("链接：").append(url).append("\n\n")
                    append(stripHtml(html).take(12000))
                }
            }
        }.getOrElse { "Fetch failed: ${it.message}" }
    }

    /** 从原始 HTML 取 <title>；必须在 stripHtml 之前抽，去完标签就找不回来了。 */
    private fun extractHtmlTitle(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.getOrNull(1)
            ?.let { htmlDecode(it) }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(200)

    private fun toolResultMessage(toolCallId: String, result: String): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        put("content", result)
    }

    /**
     * 工具轮回放用的 assistant 消息。
     * 流式拿不到服务端那个完整 message 对象，按累积结果重建：协议要求的就是 content 与 tool_calls，
     * reasoning_content 不回灌（DeepSeek 等会拒绝带着它的后续请求）。
     */
    private fun assistantToolCallMessage(content: String, calls: List<ToolCall>): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", content)
        if (calls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                calls.forEach { call ->
                    addJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        }
                    }
                }
            }
        }
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
        ByokLocalToolHandler.SAVE_MEMORY -> "记住"
        ByokLocalToolHandler.FORGET_MEMORY -> "忘记"
        ByokLocalToolHandler.RECALL_CONVERSATIONS -> "回忆对话"
        else -> "工具调用"
    }

    /**
     * 判断是否应向前端展示工具结果预览。
     * 外挂视觉工具的结果仅发给模型，不向用户展示。
     */
    private fun shouldShowToolPreview(toolName: String): Boolean {
        return toolName !in setOf("view_image", "vision_proxy")
    }

    /**
     * 工具卡里显示什么。默认给结果前 1200 字，两类例外——它们的完整结果都是**给模型读的**，
     * 铺进卡片既没有可读性，又能把这一项撑到比视口还高（高度暴涨会连带把列表的自动跟随搅乱）：
     *
     * - 联网搜索：只报搜了什么关键词。搜到哪些页由模型消化后写进正文，卡片不重复一遍。
     * - 阅读网页：只报抬头两行（读到的标题 + 链接），正文一概不进卡片。
     *
     * 失败时一律显示错误输出——这时候「搜了什么」没有意义，「为什么没成」才有。
     */
    private fun toolPreviewOf(call: ToolCall, result: ToolExecutionResult): String? {
        if (!shouldShowToolPreview(call.name)) return null
        if (result.status == ToolStatus.FAILED) return result.output
        return when (call.name) {
            "search_web", "web_search" -> call.arg("query")?.takeIf { it.isNotBlank() }?.take(200)
            "fetch_url" ->
                result.output.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n").take(400)
            // 回忆结果整份是给模型读的，铺进卡片既难读又会把这一项撑得比视口还高；
            // 执行器已把命中的会话标题与时间放在首行，卡片只取抬头。
            ByokLocalToolHandler.RECALL_CONVERSATIONS ->
                result.output.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n").take(400)
            else -> result.output.take(1200)
        }
    }

    private fun buildAnthropicBody(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        stream: Boolean = true,
        includeTools: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", request.modelId)
        put("max_tokens", 4096)
        put("temperature", request.temperature)
        put("stream", stream)
        putJsonArray("messages") {
            messages.forEach { add(it) }
        }
        val systemText = request.messages
            .filter { it.role == com.molagpt.app.core.model.Role.SYSTEM && !it.isRoleInjection }
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
                val thinking = delta?.get("thinking")?.jsonPrimitive?.contentOrNull
                val text = delta?.get("text")?.jsonPrimitive?.contentOrNull
                when {
                    !thinking.isNullOrEmpty() -> StreamEvent.Delta(thinking = thinking)
                    !text.isNullOrEmpty() -> StreamEvent.Delta(text = text)
                    else -> null
                }
            }
            "message_stop" -> StreamEvent.Finish("stop")
            "error" -> StreamEvent.Failed(root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic 请求失败")
            else -> null
        }
    }

    private fun anthropicUsage(root: JsonObject): Usage? = parseAnthropicUsage(root)

    private fun buildGeminiBody(
        provider: ByokProvider,
        request: ChatRequest,
        contents: List<JsonObject>,
        includeTools: Boolean = false,
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            contents.forEach { add(it) }
        }
        val systemText = request.messages
            .filter { it.role == com.molagpt.app.core.model.Role.SYSTEM && !it.isRoleInjection }
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
        val candidates = root["candidates"] as? JsonArray ?: JsonArray(emptyList())
        return listOfNotNull(geminiUsage(root)?.let { StreamEvent.UsageUpdate(it) }) + candidates.flatMap { candidateElement ->
            val candidate = candidateElement as? JsonObject ?: return@flatMap emptyList()
            val content = candidate["content"] as? JsonObject
            val parts = content?.get("parts") as? JsonArray
            val events = parts?.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
                    if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                        StreamEvent.Delta(thinking = text)
                    } else {
                        StreamEvent.Delta(text = text)
                    }
                }
            }.orEmpty().toMutableList<StreamEvent>()
            val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            if (!finish.isNullOrBlank()) events += StreamEvent.Finish(finish)
            events
        }
    }

    private fun geminiUsage(root: JsonObject): Usage? {
        val usage = root["usageMetadata"] as? JsonObject ?: return null
        val prompt = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull
        val completion = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull
        val total = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull
        val reasoning = usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull
        if (prompt == null && completion == null && total == null && reasoning == null) return null
        return Usage(
            promptTokens = prompt,
            completionTokens = completion?.let { it + (reasoning ?: 0) },
            totalTokens = total,
            reasoningTokens = reasoning,
            cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull,
        )
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
        const val TITLE_TEMPERATURE = 0.3
        const val ANTHROPIC_TITLE_MAX_TOKENS = 2048

        /** 部分部署把思考内联在正文里；摘要里不该出现。 */
        val THINK_BLOCK = Regex("(?s)<think>.*?</think>")
    }

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
        val responseCallId: String? = null,
    )

    /**
     * Anthropic 流式 content 块重建器。
     * `content_block_start` 给骨架，随后的 delta 按类型往里填：text_delta→text、thinking_delta→thinking、
     * signature_delta→signature、input_json_delta→拼成 tool_use 的 input。
     * redacted_thinking 这类没有 delta 的块，骨架本身就是完整内容。
     */
    private class AnthropicStreamBlocks {
        private class Slot(val skeleton: JsonObject) {
            val text = StringBuilder()
            val thinking = StringBuilder()
            val partialJson = StringBuilder()
            var signature: String? = null
        }

        private val slots = LinkedHashMap<Int, Slot>()

        fun start(root: JsonObject) {
            val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
            val block = root["content_block"] as? JsonObject ?: return
            slots[index] = Slot(block)
        }

        fun delta(root: JsonObject) {
            val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
            val slot = slots[index] ?: return
            val delta = root["delta"] as? JsonObject ?: return
            (delta["text"] as? JsonPrimitive)?.contentOrNull?.let(slot.text::append)
            (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let(slot.thinking::append)
            (delta["partial_json"] as? JsonPrimitive)?.contentOrNull?.let(slot.partialJson::append)
            (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { slot.signature = it }
        }

        fun build(json: Json): List<JsonObject> = slots.values.map { slot ->
            buildJsonObject {
                slot.skeleton.forEach { (key, value) -> put(key, value) }
                when (slot.skeleton["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> put("text", skeletonText(slot.skeleton, "text") + slot.text)
                    "thinking" -> {
                        put("thinking", skeletonText(slot.skeleton, "thinking") + slot.thinking)
                        slot.signature?.let { put("signature", it) }
                    }
                    // 参数是分片拼出来的 JSON 文本；拼不成对象时保留骨架里的空 input，
                    // 让工具带着空参数失败并把原因显示出来。
                    "tool_use" -> slot.partialJson.toString()
                        .takeIf { it.isNotBlank() }
                        ?.let { raw -> runCatching { json.parseToJsonElement(raw) }.getOrNull() }
                        ?.let { put("input", it) }
                }
            }
        }

        private fun skeletonText(block: JsonObject, key: String): String =
            (block[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /**
     * Gemini 流式 parts 合并器。
     * 文本按分片下发，functionCall 一次给全。thoughtSignature 可能挂在任意 part 上
     * （generateContent 没有独立的思考块，签名就是挂在 functionCall 或末尾 part 的元数据），
     * 所以只合并两个都不带签名的纯文本 part，其余原样保留——否则回放时签名对不上。
     */
    private class GeminiStreamParts {
        private val parts = ArrayList<JsonObject>()

        fun feed(root: JsonObject) {
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
            val incoming = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return
            incoming.forEach { element ->
                val part = element as? JsonObject ?: return@forEach
                val last = parts.lastOrNull()
                if (last != null && isPlainText(last) && isPlainText(part) && last["thought"] == part["thought"]) {
                    parts[parts.lastIndex] = buildJsonObject {
                        last.forEach { (key, value) -> put(key, value) }
                        put("text", textOf(last) + textOf(part))
                    }
                } else {
                    parts += part
                }
            }
        }

        fun build(): JsonObject = buildJsonObject {
            put("role", "model")
            put("parts", JsonArray(parts))
        }

        private fun isPlainText(part: JsonObject): Boolean =
            part["text"] is JsonPrimitive && part["thoughtSignature"] == null && part["functionCall"] == null

        private fun textOf(part: JsonObject): String = (part["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /**
     * 流式 tool_calls 累积器。一次调用被拆成多个分片：id 与 function.name 只出现在首片，
     * function.arguments 是逐段拼起来的 JSON 文本，靠 index 归位。
     */
    private class PendingToolCalls {
        private class Slot {
            var id: String? = null
            var name: String? = null
            val arguments = StringBuilder()
        }

        private val slots = LinkedHashMap<Int, Slot>()

        fun feed(element: JsonElement) {
            val obj = element as? JsonObject ?: return
            // index 是协议里的分片键；省略它的端点只会发单个调用。
            val slot = slots.getOrPut((obj["index"] as? JsonPrimitive)?.intOrNull ?: 0) { Slot() }
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { slot.id = it }
            val function = obj["function"] as? JsonObject ?: return
            (function["name"] as? JsonPrimitive)?.contentOrNull?.let { slot.name = it }
            (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let(slot.arguments::append)
        }

        fun build(): List<ToolCall> = slots.values.mapNotNull { slot ->
            val name = slot.name ?: return@mapNotNull null
            ToolCall(
                id = slot.id ?: Ids.newFragmentId(),
                name = name,
                arguments = slot.arguments.toString().ifBlank { "{}" },
            )
        }
    }
    private data class ToolRoundResult(
        val messages: List<JsonObject>,
        val completed: Boolean = false,
    )
    private data class ToolSpec(
        val name: String,
        val description: String,
        val properties: Map<String, ToolProperty>,
        val required: List<String>,
    )

    /**
     * 一个工具参数。带 [enumValues] 是为了让分节这类固定取值走 schema 约束而不是描述里的祈使句——
     * 便宜小模型对 enum 的遵从度远高于「请从以下五项中选择」。
     * OpenAI / Responses / Anthropic / Gemini 四套 schema 都支持 enum。
     */
    private data class ToolProperty(
        val description: String,
        val type: ToolPropertyType = ToolPropertyType.STRING,
        val enumValues: List<String> = emptyList(),
    )

    private enum class ToolPropertyType(val lower: String, val upper: String) {
        STRING("string", "STRING"),
        INTEGER("integer", "INTEGER"),
    }
    private data class DataUrl(val mimeType: String, val base64: String)
}

/** 一次原生工具执行后，回传 Claude/Gemini 所需的最小协议信息。 */
internal data class NativeToolResult(
    val callId: String?,
    val name: String,
    val output: String,
    val isError: Boolean,
)

/** Anthropic 要求同一轮并行工具的所有 tool_result 放进紧随其后的同一个 user message。 */
internal fun buildAnthropicToolResultMessage(results: List<NativeToolResult>): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("content") {
        results.forEach { result ->
            addJsonObject {
                put("type", "tool_result")
                put("tool_use_id", requireNotNull(result.callId))
                put("content", result.output)
                if (result.isError) put("is_error", true)
            }
        }
    }
}

/** Gemini 3 要求 functionResponse.id 与 functionCall.id 对应；并行响应必须集中在一个 user content。 */
internal fun buildGeminiFunctionResponseContent(results: List<NativeToolResult>): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("parts") {
        results.forEach { result ->
            addJsonObject {
                putJsonObject("functionResponse") {
                    result.callId?.let { put("id", it) }
                    put("name", result.name)
                    putJsonObject("response") {
                        put(if (result.isError) "error" else "result", result.output)
                    }
                }
            }
        }
    }
}

/** GenerateContent 响应通常自带 role=model；缺失时只补 role，不重建或改写任何 part/signature。 */
internal fun ensureGeminiModelRole(content: JsonObject): JsonObject {
    if (content["role"] != null) return content
    return buildJsonObject {
        put("role", "model")
        content.forEach { (key, value) -> put(key, value) }
    }
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
        ByokLocalToolHandler.SAVE_MEMORY, ByokLocalToolHandler.FORGET_MEMORY ->
            lower.startsWith(ByokLocalToolHandler.MEMORY_FAILURE_PREFIX)
        ByokLocalToolHandler.RECALL_CONVERSATIONS ->
            lower.startsWith(ByokLocalToolHandler.RECALL_FAILURE_PREFIX)
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

private fun captureResponsesOutputItems(
    json: Json,
    data: String,
    outputItems: MutableList<JsonObject>,
) {
    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return
    when (root["type"]?.jsonPrimitive?.contentOrNull) {
        "response.output_item.done" -> {
            (root["item"] as? JsonObject)?.let(outputItems::add)
        }
        "response.completed", "response.incomplete" -> {
            val authoritative = ((root["response"] as? JsonObject)?.get("output") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?: return
            if (authoritative.isEmpty() && outputItems.isNotEmpty()) return
            outputItems.clear()
            outputItems.addAll(authoritative)
        }
    }
}

/** Responses 流的 usage 只挂在终止事件的 response 对象上，字段名也和 chat/completions 不同。 */
private fun responsesStreamUsage(json: Json, data: String): Usage? {
    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
    return when (root["type"]?.jsonPrimitive?.contentOrNull) {
        "response.completed", "response.incomplete" ->
            (root["response"] as? JsonObject)?.let(::parseOpenAiResponseUsage)
        else -> null
    }
}

private fun isResponsesMessageItem(item: JsonObject): Boolean =
    item["type"]?.jsonPrimitive?.contentOrNull == "message"

private fun syntheticResponsesMessage(text: String): JsonObject = buildJsonObject {
    put("type", "message")
    put("role", "assistant")
    putJsonArray("content") {
        addJsonObject {
            put("type", "output_text")
            put("text", text)
        }
    }
}

/** 本次请求是否会带上工具定义。 */
val com.molagpt.app.core.model.EnabledTools.hasByokTools: Boolean
    get() = network || steelBrowser || vision || imageGeneration || mcp || memory || conversationRecall

internal fun selectByokVisionModel(provider: ByokProvider, fallbackModelId: String): String {
    val current = provider.models.firstOrNull { it.id == fallbackModelId }
    if (current?.supportsVision == true) return fallbackModelId
    return provider.models.firstOrNull { it.supportsVision }?.id ?: fallbackModelId
}
