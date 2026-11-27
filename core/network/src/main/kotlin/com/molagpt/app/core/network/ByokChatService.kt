package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ByokLocalToolHandler
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.ContextOverflow
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.ImageGenerationConfig
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.TitleRequest
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.model.Usage
import com.molagpt.app.core.model.WebSearchOptions
import com.molagpt.app.core.network.sse.sseFlow
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ByokChatService(
    private val http: MolaHttp,
    private val providerResolver: suspend (String) -> ByokProvider?,
    private val mcpServersProvider: () -> List<ByokMcpServer> = { emptyList() },
    private val webSearchOptionsProvider: () -> WebSearchOptions = { WebSearchOptions() },
    /** 用于在聊天内 generate_image 工具中查找图像用途 provider（purpose=IMAGE）。 */
    private val imageProviderResolver: suspend () -> ByokProvider? = { null },
    /**
     * 外挂视觉目标解析：读「BYOK 工具 → 视觉理解」配置的 `<providerId>::<modelId>`，可跨 provider。
     */
    private val visionProviderResolver: suspend () -> Pair<ByokProvider, String>? = { null },
    /** 聊天内 generate_image 出图参数（来自 BYOK 工具设置的「图像生成」卡）。 */
    private val imageGenConfigProvider: suspend () -> ImageGenerationConfig = { ImageGenerationConfig() },
    /** 「BYOK 工具 → 会话标题」总开关；关闭时 [generateTitle] 直接回退占位标题，不打任何请求。 */
    private val autoTitleEnabled: () -> Boolean = { true },
    /** 标题模型目标解析：读设置里的 `<providerId>::<modelId>`（可跨 provider）。 */
    private val titleProviderResolver: suspend () -> Pair<ByokProvider, String>? = { null },
    /** 复用工作台同一出图路径（按 imageFormat 分派，OpenRouter 走 chat/completions）。 */
    private val byokImageApi: ByokImageApi = ByokImageApi(http),
    /** 把出图字节存为本地文件，返回 Coil 可加载的 url（file://...）；返回 null 表示存盘失败（回退内联 data URI）。 */
    private val imageFileSaver: suspend (bytes: ByteArray, ext: String) -> String? = { _, _ -> null },
    /** 本地记忆与历史回忆工具的执行回调。 */
    private val localToolHandler: ByokLocalToolHandler = ByokLocalToolHandler.NoOp,
    private val skillReader: suspend (String, String, Int, String) -> String = { _, _, _, _ ->
        "skill failed: Skill reader is unavailable."
    },
    private val dispatchers: DispatcherProvider,
    private val onMolaLoginInvalidated: (String) -> Unit = {},
) {
    internal val toolRunner = ByokToolRunner(
        http = http,
        mcpServersProvider = mcpServersProvider,
        webSearchOptionsProvider = webSearchOptionsProvider,
        imageProviderResolver = imageProviderResolver,
        visionProviderResolver = visionProviderResolver,
        imageGenConfigProvider = imageGenConfigProvider,
        byokImageApi = byokImageApi,
        imageFileSaver = imageFileSaver,
        localToolHandler = localToolHandler,
        skillReader = skillReader,
        dispatchers = dispatchers,
        onMolaLoginInvalidated = onMolaLoginInvalidated,
    )

    fun sendMessage(request: ChatRequest): Flow<StreamEvent> = flow {
        val provider = providerResolver(request.providerId)
        if (provider == null || !provider.enabled) {
            emit(StreamEvent.Failed("BYOK 服务不可用"))
            return@flow
        }
        if (byokRequestUrl(provider, request.modelId, stream = true) == null) {
            emit(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
            return@flow
        }
        when (provider.type) {
            ByokProviderType.OPENAI_COMPAT -> streamOpenAiCompatible(provider, request).collect { emit(it) }
            ByokProviderType.OPENAI_RESPONSE -> streamOpenAiResponse(provider, request).collect { emit(it) }
            ByokProviderType.ANTHROPIC -> streamAnthropic(provider, request).splitInlineReasoning().collect { emit(it) }
            ByokProviderType.GEMINI -> streamGemini(provider, request).splitInlineReasoning().collect { emit(it) }
        }
    }.flowOn(dispatchers.io)

    private fun Flow<StreamEvent>.splitInlineReasoning(): Flow<StreamEvent> = flow {
        val splitter = InlineThinkSplitter()
        suspend fun forward(segments: List<InlineThinkSplitter.Segment>) {
            for (segment in segments) {
                emit(if (segment.thinking) StreamEvent.Delta(thinking = segment.text) else StreamEvent.Delta(text = segment.text))
            }
        }
        collect { event ->
            val text = (event as? StreamEvent.Delta)?.text
            if (event is StreamEvent.Delta && text != null) {
                event.thinking?.let { emit(StreamEvent.Delta(thinking = it)) }
                forward(splitter.feed(text))
            } else {
                if (event is StreamEvent.Failed || event is StreamEvent.Finish) forward(splitter.flush())
                emit(event)
            }
        }
        forward(splitter.flush())
    }

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

    // ── 辅助文本请求（标题 / 记忆整理 / 上下文摘要） ───────────────────────────────

    suspend fun generateTitle(request: TitleRequest): String {
        val fallback = request.fallbackTitle()
        if (!autoTitleEnabled() || request.messages.isEmpty()) return fallback
        val (provider, modelId) = resolveTitleTarget(request) ?: return fallback
        val prompt = buildTitlePrompt(request.messages, Locale.getDefault().displayName)
        val raw = runCatching {
            withContext(dispatchers.io) { requestText(provider, modelId, prompt, ANTHROPIC_AUX_MAX_TOKENS) }
        }.getOrElse { if (it is CancellationException) throw it else null }
        return cleanGeneratedTitle(raw) ?: fallback
    }

    private suspend fun resolveTitleTarget(request: TitleRequest): Pair<ByokProvider, String>? {
        titleProviderResolver()?.takeIf { it.first.enabled }?.let { return it }
        val provider = providerResolver(request.providerId)?.takeIf { it.enabled } ?: return null
        val modelId = request.modelId.trim().ifBlank { return null }
        return provider to modelId
    }

    suspend fun completeText(
        providerId: String,
        modelId: String,
        prompt: String,
        maxTokens: Int = ANTHROPIC_AUX_MAX_TOKENS,
    ): String? {
        val provider = providerResolver(providerId)?.takeIf { it.enabled } ?: return null
        val target = modelId.trim().ifBlank { return null }
        return runCatching {
            withContext(dispatchers.io) { requestText(provider, target, prompt, maxTokens) }
        }.getOrElse { if (it is CancellationException) throw it else null }
    }

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
        val body = buildByokTextBody(http.json, provider, target, prompt, maxOutputTokens, system)
        val req = buildByokPostRequest(http, provider, target, body, stream = false)
            ?: return TextCompletion.Failure("BYOK 服务地址无效：${provider.baseUrl}", overflow = false)
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
            ?.let(InlineThinkSplitter::visibleText)
            ?.trim()
            .orEmpty()
        if (summary.isEmpty()) return TextCompletion.Failure("模型未返回摘要", overflow = false)
        val root = runCatching { http.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val usage = root?.let {
            when (provider.type) {
                ByokProviderType.OPENAI_COMPAT -> parseOpenAiUsage(it)
                ByokProviderType.OPENAI_RESPONSE -> parseOpenAiResponseUsage(it)
                ByokProviderType.ANTHROPIC -> parseAnthropicUsage(it)
                ByokProviderType.GEMINI -> parseGeminiUsage(it)
            }
        }
        return TextCompletion.Success(summary, usage)
    }

    private suspend fun requestText(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        maxTokens: Int,
    ): String? {
        val body = buildByokTextBody(http.json, provider, modelId, prompt, maxTokens)
        val req = buildByokPostRequest(http, provider, modelId, body, stream = false) ?: return null
        return http.okHttp.newCall(req).useCancellable { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) null else parseTitleResponse(provider.type, http.json, text)
        }
    }

    // ── OpenAI Compatible ───────────────────────────────────────────────────────

    private fun streamOpenAiCompatible(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val baseMessages = buildOpenAiMessages(http.json, provider, request)
        if (request.enabledTools.hasByokTools) {
            var messages = baseMessages
            var usage: Usage? = null
            val citations = WebSearchCitations()
            while (true) {
                currentCoroutineContext().ensureActive()
                val toolRound = runOpenAiToolRound(provider, request, messages, citations) { event ->
                    if (event is StreamEvent.UsageUpdate) {
                        usage = usage.accumulate(event.usage)
                        emit(StreamEvent.UsageUpdate(usage!!))
                    } else emit(event)
                }
                if (toolRound == null) return@flow
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
        streamOpenAiSingleRound(provider, request, baseMessages).collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamOpenAiSingleRound(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
    ): Flow<StreamEvent> = flow {
        val body = buildOpenAiChatBody(http.json, provider, request, messages, stream = true, tools = null)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true)
            ?: return@flow emit(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
        val parser = StreamParser(http.json)
        val call = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        call.useCancellable { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Failed(serverResponseError("OpenAI Compatible 请求失败", resp.code, resp.body?.string().orEmpty())))
                return@flow
            }
            val source = resp.body?.source()
                ?: return@flow emit(StreamEvent.Failed(serverResponseError("OpenAI Compatible 响应为空", resp.code, "")))
            var finished = false
            var usage: Usage? = null
            var failed = false
            val rawText = StringBuilder()
            val rawThinking = StringBuilder()
            suspend fun emitParsed(event: StreamEvent) {
                if (event is StreamEvent.Finish && event.reason in setOf("tool_calls", "function_call")) {
                    failed = true
                    emit(unexpectedToolRound())
                    return
                }
                if (event is StreamEvent.Finish && !finished && rawThinking.isNotEmpty()) {
                    emit(
                        StreamEvent.WireHistory(
                            OpenAiWireHistory.encode(
                                OpenAiWireHistory.CHAT_COMPLETIONS, provider.id, request.modelId,
                                listOf(assistantToolCallMessage(openAiReplayContent(rawText.toString(), rawThinking.toString()), emptyList())),
                            ),
                        ),
                    )
                }
                emit(event)
                if (event is StreamEvent.Finish) finished = true
            }
            sseFlow { source.readUtf8Line() }.collect { payload ->
                currentCoroutineContext().ensureActive()
                if (payload.isDone) {
                    parser.parse(payload).forEach { emitParsed(it) }
                    return@collect
                }
                val root = runCatching { http.json.parseToJsonElement(payload.data) }.getOrNull() as? JsonObject
                root?.let(::parseOpenAiUsage)?.let { usage = it }
                val delta = ((root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject
                forEachOpenAiContentPart(delta?.get("content"), rawText::append, rawThinking::append)
                parser.parse(payload).forEach { event ->
                    if (event is StreamEvent.Failed) {
                        failed = true
                        emit(event.copy(message = serverResponseError("OpenAI Compatible 流式请求失败（${event.message}）", resp.code, payload.data)))
                    } else emitParsed(event)
                }
            }
            val measuredUsage = if (failed) null else usage.withModelRequestTime(modelStartedAt)
            parser.flushTail().forEach { emitParsed(it) }
            if (!finished && !failed) emit(interruptedStream())
            measuredUsage?.let { emit(StreamEvent.UsageUpdate(it)) }
        }
    }.flowOn(dispatchers.io)

    private suspend fun runOpenAiToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        baseMessages: List<JsonObject>,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolRoundResult? {
        val tools = buildOpenAiTools(toolRunner.toolSpecs(provider, request))
        val body = buildOpenAiChatBody(http.json, provider, request, baseMessages, stream = true, tools = tools)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true) ?: run {
            emitEvent(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
            return null
        }
        val parser = StreamParser(http.json)
        val pending = PendingToolCalls()
        val rawContent = StringBuilder()
        val rawThinking = StringBuilder()
        var usage: Usage? = null
        var failed = false
        val networkCall = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        networkCall.useCancellable { resp ->
            if (!resp.isSuccessful) {
                emitEvent(StreamEvent.Failed(serverResponseError("OpenAI Compatible 工具请求失败", resp.code, resp.body?.string().orEmpty())))
                return null
            }
            val source = resp.body?.source() ?: run {
                emitEvent(StreamEvent.Failed(serverResponseError("OpenAI Compatible 工具响应为空", resp.code, "")))
                return null
            }
            suspend fun drain(events: List<StreamEvent>, raw: String) {
                for (event in events) {
                    when (event) {
                        is StreamEvent.Tool -> Unit
                        is StreamEvent.Finish -> usage = event.usage ?: usage
                        is StreamEvent.Failed -> {
                            failed = true
                            emitEvent(event.copy(message = serverResponseError("OpenAI Compatible 工具流式请求失败（${event.message}）", resp.code, raw)))
                        }
                        else -> emitEvent(event)
                    }
                }
            }
            sseFlow { source.readUtf8Line() }.collect { payload ->
                currentCoroutineContext().ensureActive()
                if (!payload.isDone) accumulateOpenAiToolDelta(payload.data, rawContent, rawThinking, pending)
                drain(parser.parse(payload), payload.data)
            }
            drain(parser.flushTail(), "")
        }
        if (failed) return null
        if (parser.terminalReason == null) {
            emitEvent(interruptedStream())
            return null
        }
        emitEvent(StreamEvent.UsageUpdate(usage.withModelRequestTime(modelStartedAt) ?: Usage(costComplete = false)))

        val calls = pending.build()
        if (calls.isEmpty() && parser.terminalReason in setOf("tool_calls", "function_call")) {
            emitEvent(unexpectedToolRound())
            return null
        }
        val messages = baseMessages + assistantToolCallMessage(openAiReplayContent(rawContent.toString(), rawThinking.toString()), calls)
        if (calls.isEmpty()) return ToolRoundResult(messages, completed = true)
        val withResults = messages.toMutableList()
        for (call in calls) {
            val result = toolRunner.executeAndEmit(provider, request, call, citations, emitEvent)
            withResults.add(toolResultMessage(call.id, result.output))
        }
        return ToolRoundResult(withResults, completed = false)
    }

    private fun accumulateOpenAiToolDelta(data: String, content: StringBuilder, thinking: StringBuilder, pending: PendingToolCalls) {
        val root = runCatching { http.json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: return
        val delta = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject ?: return
        forEachOpenAiContentPart(delta["content"], content::append, thinking::append)
        (delta["tool_calls"] as? JsonArray)?.forEach(pending::feed)
    }

    // ── OpenAI Responses API ────────────────────────────────────────────────────

    private fun streamOpenAiResponse(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val baseMessages = buildOpenAiMessages(http.json, provider, request)
        if (request.enabledTools.hasByokTools) {
            var input = baseMessages
            var usage: Usage? = null
            val citations = WebSearchCitations()
            val tools = buildResponseTools(toolRunner.toolSpecs(provider, request))
            while (true) {
                currentCoroutineContext().ensureActive()
                val toolRound = runResponseToolRound(provider, request, input, tools, citations) { event ->
                    if (event is StreamEvent.UsageUpdate) {
                        usage = usage.accumulate(event.usage)
                        emit(StreamEvent.UsageUpdate(usage!!))
                    } else emit(event)
                }
                if (toolRound == null) return@flow
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
        streamResponseSingleRound(provider, request, baseMessages, emptyList()).collect { emit(it) }
    }.flowOn(dispatchers.io)

    private fun streamResponseSingleRound(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        precedingTurnItems: List<JsonObject>,
    ): Flow<StreamEvent> = flow {
        val body = buildOpenAiResponseBody(http.json, provider, request, messages)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true)
            ?: return@flow emit(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
        // 仅官方 Responses 端点过滤 commentary phase，避免兼容端点把正文标成 commentary 时丢字。
        val parser = StreamParser(
            http.json,
            responseFinalAnswerOnly = provider.isOfficialOpenAiResponses,
            responsesProtocol = true,
        )
        val call = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        call.useCancellable { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Failed(serverResponseError("Responses API 请求失败", resp.code, resp.body?.string().orEmpty())))
                return@flow
            }
            val source = resp.body?.source()
                ?: return@flow emit(StreamEvent.Failed(serverResponseError("Responses API 响应为空", resp.code, "")))
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
                    handle(parser.parse(payload))
                    return@collect
                }
                captureResponsesOutputItems(http.json, payload.data, outputItems)
                responsesStreamUsage(http.json, payload.data)?.let { usage = it }
                val events = parser.parse(payload).map { event ->
                    if (event is StreamEvent.Failed) {
                        event.copy(message = serverResponseError("Responses API 流式请求失败（${event.message}）", resp.code, payload.data))
                    } else event
                }
                handle(events)
            }
            handle(parser.flushTail())
            if (finish == null && !failed) {
                emit(interruptedStream())
                failed = true
            }
            if (failed) return@flow
            val measuredUsage = (usage ?: finish?.usage).withModelRequestTime(modelStartedAt)
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
            emit(StreamEvent.Finish(finish?.reason ?: "stop", measuredUsage))
        }
    }.flowOn(dispatchers.io)

    private suspend fun runResponseToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        tools: JsonArray,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolRoundResult? {
        val body = buildOpenAiResponseBody(http.json, provider, request, messages, tools)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true) ?: run {
            emitEvent(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
            return null
        }
        val parser = StreamParser(http.json, responsesProtocol = true)
        val outputItems = mutableListOf<JsonObject>()
        val answer = StringBuilder()
        var usage: Usage? = null
        var failed = false
        val networkCall = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        networkCall.useCancellable { resp ->
            if (!resp.isSuccessful) {
                emitEvent(StreamEvent.Failed(serverResponseError("Responses API 工具请求失败", resp.code, resp.body?.string().orEmpty())))
                return null
            }
            val source = resp.body?.source() ?: run {
                emitEvent(StreamEvent.Failed(serverResponseError("Responses API 工具响应为空", resp.code, "")))
                return null
            }
            suspend fun drain(events: List<StreamEvent>, raw: String) {
                for (event in events) {
                    when (event) {
                        is StreamEvent.Finish -> Unit
                        is StreamEvent.Failed -> {
                            failed = true
                            emitEvent(event.copy(message = serverResponseError("Responses API 工具流式请求失败（${event.message}）", resp.code, raw)))
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
            drain(parser.flushTail(), "")
        }
        if (failed) return null
        if (parser.terminalReason == null) {
            emitEvent(interruptedStream())
            return null
        }
        emitEvent(StreamEvent.UsageUpdate(usage.withModelRequestTime(modelStartedAt) ?: Usage(costComplete = false)))

        if (outputItems.none(::isResponsesMessageItem) && answer.isNotEmpty()) {
            outputItems += syntheticResponsesMessage(answer.toString())
        }
        val calls = parseResponseOutputItems(outputItems)
            .filterIsInstance<ParsedResponseOutputItem.FunctionCall>()
        val functionOutputs = ArrayList<ResponseFunctionOutput>()
        for (item in calls) {
            val call = ByokToolCall(
                id = item.id ?: Ids.newFragmentId(),
                name = item.name,
                arguments = item.arguments,
                responseCallId = item.callId,
            )
            val result = toolRunner.executeAndEmit(provider, request, call, citations, emitEvent)
            functionOutputs.add(
                ResponseFunctionOutput(callId = call.responseCallId ?: call.id, output = result.output),
            )
        }
        return ToolRoundResult(
            messages = buildResponseReplayInput(messages, outputItems, functionOutputs),
            completed = calls.isEmpty(),
        )
    }

    // ── Anthropic Messages ──────────────────────────────────────────────────────

    private fun streamAnthropic(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val messages = buildAnthropicMessages(http.json, provider, request)
        if (request.enabledTools.hasByokTools) {
            runAnthropicToolLoop(provider, request, messages, WebSearchCitations()) { emit(it) }
            return@flow
        }
        val body = buildAnthropicBody(http.json, provider, request, messages)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true)
            ?: return@flow emit(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
        val call = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        call.useCancellable { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Failed(serverResponseError("Anthropic 请求失败", resp.code, resp.body?.string().orEmpty())))
                return@flow
            }
            val source = resp.body?.source()
                ?: return@flow emit(StreamEvent.Failed(serverResponseError("Anthropic 响应为空", resp.code, "")))
            var streamUsage: Usage? = null
            var failed = false
            var completed = false
            var stopReason: String? = null
            sseFlow { source.readUtf8Line() }.collect { payload ->
                currentCoroutineContext().ensureActive()
                if (payload.isDone || failed || completed) return@collect
                val root = runCatching { http.json.parseToJsonElement(payload.data) }.getOrNull() as? JsonObject
                    ?: return@collect
                val incoming = when (root["type"]?.jsonPrimitive?.contentOrNull) {
                    "message_start" -> (root["message"] as? JsonObject)?.let(::parseAnthropicUsage)
                    "message_delta" -> {
                        stopReason = (root["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                            ?: stopReason
                        parseAnthropicUsage(root)
                    }
                    else -> null
                }
                if (incoming != null) {
                    streamUsage = streamUsage.mergeAnthropic(incoming)
                    streamUsage?.let { emit(StreamEvent.UsageUpdate(it)) }
                }
                parseAnthropicEvent(http.json, payload.data)?.let { event ->
                    when (event) {
                        is StreamEvent.Finish -> {
                            completed = true
                            val error = anthropicFailure(stopReason)
                            when {
                                error != null -> { failed = true; emit(error) }
                                stopReason == "tool_use" -> { failed = true; emit(unexpectedToolRound()) }
                                else -> emit(StreamEvent.Finish(stopReason ?: "stop"))
                            }
                        }
                        is StreamEvent.Failed -> {
                            failed = true
                            emit(event.copy(message = serverResponseError("Anthropic 流式请求失败（${event.message}）", resp.code, payload.data)))
                        }
                        else -> emit(event)
                    }
                }
            }
            if (!completed && !failed) emit(interruptedStream())
            if (!failed) streamUsage.withModelRequestTime(modelStartedAt)?.let { emit(StreamEvent.UsageUpdate(it)) }
        }
    }.flowOn(dispatchers.io)

    private suspend fun runAnthropicToolLoop(
        provider: ByokProvider,
        request: ChatRequest,
        initialMessages: List<JsonObject>,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ) {
        val messages = initialMessages.toMutableList()
        val turnItems = ArrayList<JsonObject>()
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

            val calls = round.content.mapNotNull { parseAnthropicToolCall(http.json, it) }
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
                val execution = toolRunner.executeAndEmit(provider, request, call, citations, emitEvent)
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

    private suspend fun streamAnthropicToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        messages: List<JsonObject>,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): AnthropicRound? {
        val tools = buildAnthropicTools(toolRunner.toolSpecs(provider, request))
        val body = buildAnthropicBody(http.json, provider, request, messages, stream = true, tools = tools)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true) ?: run {
            emitEvent(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
            return null
        }
        val blocks = AnthropicStreamBlocks()
        var stopReason: String? = null
        var usage: Usage? = null
        var failed = false
        var completed = false
        val networkCall = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        try {
            networkCall.useCancellable { resp ->
                if (!resp.isSuccessful) {
                    emitEvent(StreamEvent.Failed(serverResponseError("Anthropic 工具请求失败", resp.code, resp.body?.string().orEmpty())))
                    return null
                }
                val source = resp.body?.source() ?: run {
                    emitEvent(StreamEvent.Failed(serverResponseError("Anthropic 工具响应为空", resp.code, "")))
                    return null
                }
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    if (completed || failed) return@collect
                    val root = runCatching { http.json.parseToJsonElement(payload.data) }.getOrNull() as? JsonObject
                        ?: return@collect
                    when (root["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> (root["message"] as? JsonObject)?.let { message ->
                            usage = usage.mergeAnthropic(parseAnthropicUsage(message))
                        }
                        "message_delta" -> {
                            usage = usage.mergeAnthropic(parseAnthropicUsage(root))
                            (root["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                                ?.let { stopReason = it }
                        }
                        "content_block_start" -> blocks.start(root)
                        "content_block_delta" -> blocks.delta(root)
                    }
                    parseAnthropicEvent(http.json, payload.data)?.let { event ->
                        when (event) {
                            is StreamEvent.Finish -> completed = true
                            is StreamEvent.Failed -> {
                                failed = true
                                emitEvent(event.copy(message = serverResponseError("Anthropic 工具流式请求失败（${event.message}）", resp.code, payload.data)))
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
        val error = if (completed) anthropicFailure(stopReason) else interruptedStream()
        if (error != null) {
            emitEvent(error)
            return null
        }
        val content = blocks.build(http.json)
        if (stopReason == "tool_use" && content.none { it["type"] == JsonPrimitive("tool_use") }) {
            emitEvent(unexpectedToolRound())
            return null
        }
        return AnthropicRound(content, stopReason, usage.withModelRequestTime(modelStartedAt))
    }

    // ── Gemini GenerateContent ──────────────────────────────────────────────────

    private fun streamGemini(provider: ByokProvider, request: ChatRequest): Flow<StreamEvent> = flow {
        val contents = buildGeminiContents(http.json, provider, request)
        if (request.enabledTools.hasByokTools) {
            runGeminiToolLoop(provider, request, contents, WebSearchCitations()) { emit(it) }
            return@flow
        }
        val body = buildGeminiBody(http.json, provider, request, contents)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true)
            ?: return@flow emit(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
        val call = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        call.useCancellable { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Failed(serverResponseError("Gemini 请求失败", resp.code, resp.body?.string().orEmpty())))
                return@flow
            }
            val source = resp.body?.source()
                ?: return@flow emit(StreamEvent.Failed(serverResponseError("Gemini 响应为空", resp.code, "")))
            var emittedFinish = false
            var usage: Usage? = null
            var failed = false
            val emittedImages = mutableSetOf<GeminiInlineImageKey>()
            sseFlow { source.readUtf8Line() }.collect { payload ->
                currentCoroutineContext().ensureActive()
                if (payload.isDone || failed) return@collect
                parseGeminiEvent(http.json, payload.data, emittedImages, imageFileSaver).forEach { event ->
                    if (event is StreamEvent.Failed) {
                        failed = true
                        emit(event.copy(message = serverResponseError("Gemini 流式请求失败（${event.message}）", resp.code, payload.data)))
                    } else emit(event)
                    if (event is StreamEvent.Finish) emittedFinish = true
                    if (event is StreamEvent.UsageUpdate) usage = event.usage
                }
            }
            if (!failed) usage.withModelRequestTime(modelStartedAt)?.let { emit(StreamEvent.UsageUpdate(it)) }
            if (!emittedFinish && !failed) emit(interruptedStream())
        }
    }.flowOn(dispatchers.io)

    private suspend fun runGeminiToolLoop(
        provider: ByokProvider,
        request: ChatRequest,
        initialContents: List<JsonObject>,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ) {
        val contents = initialContents.toMutableList()
        val turnItems = ArrayList<JsonObject>()
        val emittedImages = mutableSetOf<GeminiInlineImageKey>()
        var usage: Usage? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val round = streamGeminiToolRound(provider, request, contents, emittedImages, emitEvent) ?: return
            val modelContent = ensureGeminiModelRole(round.content)
            contents += modelContent
            turnItems += modelContent
            usage = usage.accumulate(round.usage ?: Usage(costComplete = false))
            usage?.let { emitEvent(StreamEvent.UsageUpdate(it)) }

            val parts = (modelContent["parts"] as? JsonArray).orEmpty()
            val calls = parts.mapNotNull { parseGeminiToolCall(http.json, it) }
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
                val execution = toolRunner.executeAndEmit(provider, request, call, citations, emitEvent)
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

    private suspend fun streamGeminiToolRound(
        provider: ByokProvider,
        request: ChatRequest,
        contents: List<JsonObject>,
        emittedImages: MutableSet<GeminiInlineImageKey>,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): GeminiRound? {
        val tools = buildGeminiTools(toolRunner.toolSpecs(provider, request))
        val body = buildGeminiBody(http.json, provider, request, contents, tools = tools)
        val req = buildByokPostRequest(http, provider, request.modelId, body, stream = true) ?: run {
            emitEvent(StreamEvent.Failed("BYOK 服务地址无效：${provider.baseUrl}"))
            return null
        }
        val parts = GeminiStreamParts()
        var finishReason: String? = null
        var usage: Usage? = null
        var failed = false
        val networkCall = http.streamOkHttp.newCall(req)
        val modelStartedAt = System.nanoTime()
        try {
            networkCall.useCancellable { resp ->
                if (!resp.isSuccessful) {
                    emitEvent(StreamEvent.Failed(serverResponseError("Gemini 工具请求失败", resp.code, resp.body?.string().orEmpty())))
                    return null
                }
                val source = resp.body?.source() ?: run {
                    emitEvent(StreamEvent.Failed(serverResponseError("Gemini 工具响应为空", resp.code, "")))
                    return null
                }
                sseFlow { source.readUtf8Line() }.collect { payload ->
                    currentCoroutineContext().ensureActive()
                    val root = runCatching { http.json.parseToJsonElement(payload.data) }.getOrNull() as? JsonObject
                        ?: return@collect
                    parts.feed(root)
                    parseGeminiEvent(root = root, emittedImages = emittedImages, imageFileSaver = imageFileSaver).forEach { event ->
                        when (event) {
                            is StreamEvent.Finish -> finishReason = event.reason ?: finishReason
                            is StreamEvent.UsageUpdate -> usage = event.usage
                            is StreamEvent.Failed -> {
                                failed = true
                                emitEvent(event.copy(message = serverResponseError("Gemini 工具流式请求失败（${event.message}）", resp.code, payload.data)))
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
        if (finishReason == null) {
            emitEvent(interruptedStream())
            return null
        }
        val content = parts.build()
        if ((content["parts"] as? JsonArray).isNullOrEmpty()) {
            emitEvent(StreamEvent.Failed("Gemini 工具响应缺少 content"))
            return null
        }
        return GeminiRound(content, finishReason, usage.withModelRequestTime(modelStartedAt))
    }

    private fun Usage?.withModelRequestTime(startedAt: Long): Usage? {
        if (this?.completionTokens == null) return this
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        return copy(modelRequestMs = elapsed.takeIf { it > 0 })
    }

    private fun interruptedStream() = StreamEvent.Failed("连接已结束，回答未完成。", "interrupted", "retry")

    private fun unexpectedToolRound() = StreamEvent.Failed("工具调用未完整返回。", "interrupted", "retry")

    private fun anthropicFailure(reason: String?): StreamEvent.Failed? = when (reason) {
        "max_tokens", "model_context_window_exceeded" -> StreamEvent.Failed("回答已截断", "incomplete", "retry")
        "refusal", "pause_turn" -> StreamEvent.Failed("Anthropic 未完成回答（$reason）", "incomplete")
        else -> null
    }

    private data class ToolRoundResult(
        val messages: List<JsonObject>,
        val completed: Boolean = false,
    )
}
