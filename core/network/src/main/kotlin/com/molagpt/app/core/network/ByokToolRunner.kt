package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ByokLocalToolHandler
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.ImageGenerationConfig
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.WebSearchOptions
import com.molagpt.app.core.model.WebSearchProvider
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * BYOK 工具独立执行器：所有工具直接返回结构化 [ToolExecutionResult]，
 * 网页抓取和搜索统一有界读取与有限超时，保留本地记忆门禁和视觉代理安全校验。
 */
internal class ByokToolRunner(
    private val http: MolaHttp,
    private val mcpServersProvider: () -> List<ByokMcpServer>,
    private val webSearchOptionsProvider: () -> WebSearchOptions,
    private val imageProviderResolver: suspend () -> ByokProvider?,
    private val visionProviderResolver: suspend () -> Pair<ByokProvider, String>?,
    private val imageGenConfigProvider: suspend () -> ImageGenerationConfig,
    private val byokImageApi: ByokImageApi,
    private val imageFileSaver: suspend (bytes: ByteArray, ext: String) -> String?,
    private val localToolHandler: ByokLocalToolHandler,
    private val skillReader: suspend (String, String, Int, String) -> String,
    private val dispatchers: DispatcherProvider,
    private val onMolaLoginInvalidated: (String) -> Unit,
) {
    private val fetchClient = http.okHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun toolSpecs(provider: ByokProvider, request: ChatRequest): List<ToolSpec> =
        buildByokToolSpecs(provider, request, webSearchOptionsProvider().provider)

    suspend fun executeAndEmit(
        provider: ByokProvider,
        request: ChatRequest,
        call: ByokToolCall,
        citations: WebSearchCitations,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolExecutionResult {
        val before = citations.snapshot().size
        val result = emitByokToolLifecycle(
            id = call.id,
            name = call.name,
            label = labelForByokTool(call.name),
            argsJson = call.arguments,
            execute = {
                val execution = executeTool(provider, request, call, citations)
                if (execution.status == ToolStatus.SUCCESS && call.name == "generate_image") {
                    processImageToolResult(call, execution.output, emitEvent)
                } else {
                    execution
                }
            },
            resultPreview = { res -> byokToolPreviewOf(http.json, call, res) },
            emitEvent = emitEvent,
        )
        if (citations.snapshot().size > before) {
            emitEvent(StreamEvent.Sources(citations.snapshot(), citations.queryLabel()))
        }
        return result
    }

    private suspend fun executeTool(
        provider: ByokProvider,
        request: ChatRequest,
        call: ByokToolCall,
        citations: WebSearchCitations,
    ): ToolExecutionResult {
        // 只执行本轮真正声明过的工具，防止模型幻觉或网页注入越权调用（如关掉记忆后写入）。
        if (toolSpecs(provider, request).none { it.name == call.name }) {
            return ToolExecutionResult.failure("Tool ${call.name} is not enabled for this conversation.")
        }
        return try {
            when (call.name) {
                "search_web" ->
                    if (webSearchOptionsProvider().provider == WebSearchProvider.MOLAGPT) searchMolaGpt(call, citations)
                    else searchWeb(call.arg(http.json, "query"), call.arg(http.json, "max_results")?.toIntOrNull(), citations)
                "fetch_url" -> fetchUrl(call.arg(http.json, "url"))
                "read_skill" -> readSkill(request, call)
                "view_image" -> viewImage(provider, request, call)
                "generate_image" -> generateImage(provider, call.arg(http.json, "prompt"))
                "mcp_list_tools" -> listMcpTools(call.arg(http.json, "server"))
                "mcp_call" -> callMcpServer(call)
                in ByokLocalToolHandler.ALL -> classifyLocalToolOutput(
                    call.name,
                    localToolHandler.execute(call.name, call.arguments, request.sessionId),
                )
                else -> ToolExecutionResult.failure("Unsupported tool: ${call.name}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (call.name == "read_skill") {
                ToolExecutionResult.failure("读取技能失败：${error.message ?: error::class.simpleName}")
            } else {
                ToolExecutionResult.failure("${labelForByokTool(call.name)} failed: ${error.message ?: error::class.simpleName}")
            }
        }
    }

    private suspend fun readSkill(request: ChatRequest, call: ByokToolCall): ToolExecutionResult {
        val name = call.arg(http.json, "skill") ?: return ToolExecutionResult.failure("Missing skill")
        require(request.availableSkills.any { it.name == name }) { "该技能未在当前会话启用。" }
        val offset = call.arg(http.json, "offset")?.toInt() ?: 0
        val output = skillReader(name, call.arg(http.json, "path") ?: "SKILL.md", offset, request.sessionId)
        val trimmed = output.trim()
        val failed = trimmed.lowercase().startsWith("skill failed:") || hasJsonSuccessFalse(trimmed)
        return if (failed) ToolExecutionResult.failure(output) else ToolExecutionResult.success(output)
    }

    private fun classifyLocalToolOutput(name: String, output: String): ToolExecutionResult {
        val trimmed = output.trim()
        val lower = trimmed.lowercase()
        val failed = when (name) {
            ByokLocalToolHandler.SAVE_MEMORY, ByokLocalToolHandler.FORGET_MEMORY ->
                lower.startsWith(ByokLocalToolHandler.MEMORY_FAILURE_PREFIX)
            ByokLocalToolHandler.RECALL_CONVERSATIONS ->
                lower.startsWith(ByokLocalToolHandler.RECALL_FAILURE_PREFIX)
            else -> false
        } || hasJsonSuccessFalse(trimmed)
        return if (failed) ToolExecutionResult.failure(output) else ToolExecutionResult.success(output)
    }

    private fun hasJsonSuccessFalse(text: String): Boolean = runCatching {
        http.json.parseToJsonElement(text).jsonObject["success"]?.jsonPrimitive?.booleanOrNull == false
    }.getOrDefault(false)

    private suspend fun viewImage(provider: ByokProvider, request: ChatRequest, call: ByokToolCall): ToolExecutionResult {
        val index = call.arg(http.json, "image_index")?.toIntOrNull()
            ?: return ToolExecutionResult.failure("Missing image_index")
        val query = call.arg(http.json, "query")
        // 必须与 content builder 共用同一有序列表（含不可用占位），保证 [图片#N] 序号严格对齐。
        val allImages = request.messages
            .filter { it.role == Role.USER }
            .flatMap { AttachmentParts.orderedImages(it) }
        val image = allImages.getOrNull(index - 1)
            ?: return ToolExecutionResult.failure("Image index $index out of range (1-${allImages.size})")
        if (image.unavailable || image.remoteUrl.isNullOrBlank()) {
            return ToolExecutionResult.failure("Image $index (${image.name}) is no longer available on this device.")
        }
        val prompt = query?.takeIf { it.isNotBlank() } ?: "请分析这张图片。"
        val target = resolveVisionTarget(provider, request.modelId)
            ?: return ToolExecutionResult.failure(
                "外挂视觉未配置可用的视觉模型：请在「设置 → BYOK 工具 → 视觉理解」中选择一个支持视觉的模型。",
            )
        val (visionProvider, visionModelId) = target
        return executeByokVisionRequest(http, visionProvider, visionModelId, image.remoteUrl!!, prompt)
    }

    private suspend fun resolveVisionTarget(current: ByokProvider, currentModelId: String): Pair<ByokProvider, String>? {
        visionProviderResolver()?.let { return it }
        val picked = selectByokVisionModel(current, currentModelId)
        if (current.models.any { it.id == picked && it.supportsVision }) return current to picked
        return null
    }

    private suspend fun generateImage(provider: ByokProvider, prompt: String?): ToolExecutionResult {
        val imagePrompt = prompt?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.failure("Missing image prompt")
        val imageProvider = provider.takeIf { it.models.any { m -> m.supportsImageGeneration } }
            ?: imageProviderResolver() ?: return ToolExecutionResult.failure("未配置图像服务")
        val imageModelId = imageProvider.models.firstOrNull { it.supportsImageGeneration }?.id
            ?: return ToolExecutionResult.failure("未配置图像模型")
        return try {
            val cfg = imageGenConfigProvider()
            val gate = ImageCallGate()
            val result = gate.runCancellable(dispatchers.io) {
                byokImageApi.generate(imageProvider, imageModelId, imagePrompt, size = "", imageConfig = cfg, gate = gate)
            }
            val payload = result.url?.takeIf { it.isNotBlank() } ?: result.raw
            if (payload.isNullOrBlank()) {
                ToolExecutionResult.failure("图像生成未返回结果")
            } else {
                ToolExecutionResult.success(payload)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult.failure("Image generation failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private suspend fun processImageToolResult(
        call: ByokToolCall,
        rawResult: String,
        emitEvent: suspend (StreamEvent) -> Unit,
    ): ToolExecutionResult {
        val prompt = call.arg(http.json, "prompt")
        val url = when {
            rawResult.startsWith("data:image/", ignoreCase = true) -> {
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
            else -> return ToolExecutionResult.success(rawResult)
        }
        emitEvent(StreamEvent.Image(url, prompt))
        return ToolExecutionResult.success("[图像已生成并展示给用户]")
    }

    private suspend fun callMcpServer(call: ByokToolCall): ToolExecutionResult {
        val serverName = call.arg(http.json, "server")?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.failure("Missing MCP server")
        val toolName = call.arg(http.json, "tool")?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.failure("Missing MCP tool")
        val server = mcpServersProvider()
            .filter { it.enabled }
            .firstOrNull { it.name.equals(serverName, ignoreCase = true) || it.id.equals(serverName, ignoreCase = true) }
            ?: return ToolExecutionResult.failure("MCP server not found: $serverName")
        if (toolName in server.disabledTools) return ToolExecutionResult.failure("MCP tool disabled: $toolName")
        val endpoint = parseByokHttpUrl(server.endpoint)
            ?: return ToolExecutionResult.failure("MCP failed: invalid endpoint ${server.endpoint}")
        val arguments = call.argJsonObject(http.json, "arguments") ?: buildJsonObject {}
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
            .url(endpoint)
            .header("Accept", "application/json")
            .apply {
                server.token?.takeIf { it.isNotBlank() }?.let {
                    header(server.headerName.ifBlank { "Authorization" }, "Bearer $it")
                }
            }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(BYOK_JSON_MEDIA))
            .build()
        return try {
            http.okHttp.newCall(req).useCancellable { resp ->
                val text = readBoundedUtf8(resp.body, MCP_RESPONSE_MAX_BYTES)
                if (!resp.isSuccessful) {
                    return@useCancellable ToolExecutionResult.failure("MCP failed: HTTP ${resp.code} ${text.take(800)}")
                }
                formatMcpResult(text)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult.failure("MCP failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private suspend fun listMcpTools(serverName: String?): ToolExecutionResult {
        val servers = mcpServersProvider()
            .filter { it.enabled }
            .filter {
                serverName.isNullOrBlank() ||
                    it.name.equals(serverName, ignoreCase = true) ||
                    it.id.equals(serverName, ignoreCase = true)
            }
        if (servers.isEmpty()) return ToolExecutionResult.failure("No enabled MCP servers")
        var anyFailure = false
        val sections = servers.map { server ->
            val disabled = server.disabledTools.toSet()
            val endpoint = parseByokHttpUrl(server.endpoint)
            if (endpoint == null) {
                anyFailure = true
                return@map "${server.name}\ntools/list failed: invalid endpoint ${server.endpoint}"
            }
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", Ids.newFragmentId())
                put("method", "tools/list")
            }
            val req = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .apply {
                    server.token?.takeIf { it.isNotBlank() }?.let {
                        header(server.headerName.ifBlank { "Authorization" }, "Bearer $it")
                    }
                }
                .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(BYOK_JSON_MEDIA))
                .build()
            val result = try {
                http.okHttp.newCall(req).useCancellable { resp ->
                    val text = readBoundedUtf8(resp.body, MCP_RESPONSE_MAX_BYTES)
                    if (!resp.isSuccessful) {
                        ToolExecutionResult.failure("tools/list failed: HTTP ${resp.code} ${text.take(400)}")
                    } else {
                        formatMcpTools(text, disabled)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ToolExecutionResult.failure("tools/list failed: ${error.message ?: error::class.simpleName}")
            }
            if (result.status == ToolStatus.FAILED) anyFailure = true
            "${server.name}\n${result.output}"
        }
        val combined = sections.joinToString("\n\n")
        return if (anyFailure) ToolExecutionResult.failure(combined) else ToolExecutionResult.success(combined)
    }

    private fun formatMcpResult(text: String): ToolExecutionResult {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ToolExecutionResult.success(text.take(4000))
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            return ToolExecutionResult.failure("MCP failed: $message")
        }
        val result = root["result"] ?: return ToolExecutionResult.success(text.take(4000))
        val resultObject = result as? JsonObject ?: return ToolExecutionResult.success(result.toString().take(4000))
        val content = resultObject["content"] as? JsonArray
        val parts = content?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["data"]?.jsonPrimitive?.contentOrNull
                ?: obj["url"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val formatted = parts.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: result.toString().take(4000)
        return if (resultObject["isError"]?.jsonPrimitive?.booleanOrNull == true) {
            ToolExecutionResult.failure("MCP failed: $formatted")
        } else {
            ToolExecutionResult.success(formatted)
        }
    }

    private fun formatMcpTools(text: String, disabledTools: Set<String> = emptySet()): ToolExecutionResult {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ToolExecutionResult.success(text.take(4000))
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            return ToolExecutionResult.failure("tools/list failed: $message")
        }
        val tools = (root["result"] as? JsonObject)?.get("tools") as? JsonArray
            ?: return ToolExecutionResult.success(root["result"]?.toString()?.take(4000) ?: text.take(4000))
        val listed = tools.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (name in disabledTools) return@mapNotNull null
            val description = obj["description"]?.jsonPrimitive?.contentOrNull
            if (description.isNullOrBlank()) name else "$name - $description"
        }.joinToString("\n").ifBlank { "No tools" }
        return ToolExecutionResult.success(listed)
    }

    private sealed interface WebSearchOutcome {
        data class Hits(val hits: List<WebSearchHit>, val note: String? = null) : WebSearchOutcome
        data class Empty(val text: String) : WebSearchOutcome
        data class Failure(val text: String) : WebSearchOutcome
    }

    private suspend fun searchWeb(query: String?, requestedMax: Int?, citations: WebSearchCitations): ToolExecutionResult {
        if (query.isNullOrBlank()) return ToolExecutionResult.failure("Missing query")
        val options = webSearchOptionsProvider()
        val maxResults = (requestedMax ?: options.maxResults).coerceIn(1, options.maxResults.coerceIn(1, 10))
        val outcome = when (options.provider) {
            WebSearchProvider.MOLAGPT -> return searchMolaGpt(
                ByokToolCall(Ids.newFragmentId(), "search_web", buildJsonObject { put("query", query) }.toString()),
                citations,
            )
            WebSearchProvider.TAVILY -> searchTavily(query, options.apiKey, maxResults)
            WebSearchProvider.EXA -> searchExa(query, options.apiKey, maxResults)
            WebSearchProvider.DUCKDUCKGO -> searchDuckDuckGo(query, maxResults)
        }
        return when (outcome) {
            is WebSearchOutcome.Failure -> ToolExecutionResult.failure(outcome.text)
            is WebSearchOutcome.Empty -> ToolExecutionResult.success(outcome.text)
            is WebSearchOutcome.Hits -> ToolExecutionResult.success(
                listOfNotNull(
                    outcome.note,
                    citations.toolResult(citations.absorb(query, outcome.hits)),
                ).joinToString("\n\n"),
            )
        }
    }

    private suspend fun searchMolaGpt(call: ByokToolCall, citations: WebSearchCitations): ToolExecutionResult {
        val options = webSearchOptionsProvider()
        val jwt = options.molaJwt?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.failure("Search failed: $MOLA_SEARCH_NOT_SIGNED_IN")
        val queries = parseMolaSearchQueries(http.json, call)
        if (queries.isEmpty()) return ToolExecutionResult.failure("Missing query")
        val body = buildJsonObject {
            putJsonArray("queries") {
                queries.forEach { q ->
                    addJsonObject {
                        put("query", q.query)
                        q.topic?.let { put("topic", it) }
                        q.timeRange?.let { put("time_range", it) }
                        q.country?.let { put("country", it) }
                        if (q.includeImages) put("include_images", true)
                    }
                }
            }
            put("max_results", options.maxResults.coerceIn(1, WebSearchProvider.MOLAGPT.maxResultsLimit))
            put("snippet_max_chars", MOLA_SEARCH_SNIPPET_CHARS)
        }
        val req = Request.Builder()
            .url(MolaEndpoints.absolute(MolaEndpoints.ACCOUNT_SEARCH))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $jwt")
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(BYOK_JSON_MEDIA))
            .build()
        val client = http.accountOkHttp.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        return try {
            client.newCall(req).useCancellable { resp ->
                val text = readBoundedUtf8(resp.body, FETCH_URL_MAX_BYTES)
                if (resp.code == 401) {
                    onMolaLoginInvalidated(jwt)
                    return@useCancellable ToolExecutionResult.failure("Search failed: $MOLA_SEARCH_SESSION_EXPIRED")
                }
                if (!resp.isSuccessful) {
                    return@useCancellable ToolExecutionResult.failure("Search failed: ${molaSearchError(text) ?: "HTTP ${resp.code}"}")
                }
                val root = http.json.parseToJsonElement(text).jsonObject
                val failed = mutableListOf<String>()
                val numbered = ArrayList<Pair<WebSearchHit, Int>>()
                val imageBlocks = mutableListOf<String>()
                val resultQueries = (root["queries"] as? JsonArray).orEmpty()
                var allQueriesFailed = resultQueries.isNotEmpty()
                resultQueries.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val query = obj["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val hits = (obj["results"] as? JsonArray).orEmpty().mapNotNull { hit ->
                        val h = hit as? JsonObject ?: return@mapNotNull null
                        WebSearchHit(
                            title = h["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            url = h["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            snippet = h["snippet"]?.jsonPrimitive?.contentOrNull,
                            publishedDate = h["published_date"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                    val queryHits = citations.absorb(query, hits)
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    if (queryHits.isEmpty() && !error.isNullOrBlank()) failed += query
                    if (error.isNullOrBlank() || queryHits.isNotEmpty()) allQueriesFailed = false
                    numbered += queryHits
                    val images = obj["images"] as? JsonArray
                    if (!images.isNullOrEmpty()) {
                        imageBlocks += buildJsonObject {
                            put("query", query)
                            put("images", images)
                        }.toString()
                    }
                }
                if (allQueriesFailed) {
                    return@useCancellable ToolExecutionResult.failure("Search failed: 搜索服务暂时不可用，请稍后重试。")
                }
                val note = failed.takeIf { it.isNotEmpty() }
                    ?.let { "以下查询搜索失败：${it.joinToString("、")}" }
                val out = listOfNotNull(
                    note,
                    citations.toolResult(numbered.distinctBy { it.second }),
                    imageBlocks.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "搜索图片：\n"),
                ).joinToString("\n\n")
                ToolExecutionResult.success(out)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult.failure("Search failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun molaSearchError(body: String): String? = runCatching {
        http.json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun searchDuckDuckGo(query: String, maxResults: Int): WebSearchOutcome {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        // UA 由 MolaHttp 拦截器统一注入，不可改写为浏览器 UA（会触发 202 反爬）。
        val req = Request.Builder()
            .url("https://duckduckgo.com/html/?q=$encoded")
            .header("Accept", "text/html")
            .build()
        return try {
            fetchClient.newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@useCancellable WebSearchOutcome.Failure("Search failed: HTTP ${resp.code}")
                val html = readBoundedUtf8(resp.body, FETCH_URL_MAX_BYTES)
                val regex = Regex(
                    """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
                val hits = regex.findAll(html).take(maxResults).map { match ->
                    val url = decodeDuckDuckGoHref(htmlDecode(match.groupValues[1]))
                    WebSearchHit(title = stripHtml(htmlDecode(match.groupValues[2])).ifBlank { url }, url = url)
                }.toList()
                if (hits.isEmpty()) {
                    WebSearchOutcome.Failure("未从 DuckDuckGo 获取到结果（可能被反爬限制），可在设置改用 Tavily/Exa。")
                } else {
                    WebSearchOutcome.Hits(hits)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            WebSearchOutcome.Failure("Search failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun decodeDuckDuckGoHref(href: String): String {
        val marker = "uddg="
        val idx = href.indexOf(marker)
        if (idx < 0) return if (href.startsWith("//")) "https:$href" else href
        val raw = href.substring(idx + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(href)
    }

    private suspend fun searchTavily(query: String, apiKey: String?, maxResults: Int): WebSearchOutcome {
        if (apiKey.isNullOrBlank()) return WebSearchOutcome.Failure("未配置 Tavily API Key，请在设置填写")
        val body = buildJsonObject {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", "basic")
        }
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Accept", "application/json")
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(BYOK_JSON_MEDIA))
            .build()
        return try {
            fetchClient.newCall(req).useCancellable { resp ->
                val text = readBoundedUtf8(resp.body, FETCH_URL_MAX_BYTES)
                if (!resp.isSuccessful) {
                    return@useCancellable WebSearchOutcome.Failure("Search failed: HTTP ${resp.code} ${text.take(300)}")
                }
                val root = http.json.parseToJsonElement(text).jsonObject
                val hits = (root["results"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    WebSearchHit(
                        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                        snippet = obj["content"]?.jsonPrimitive?.contentOrNull?.take(500),
                        publishedDate = obj["published_date"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                val answer = root["answer"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (hits.isEmpty()) {
                    WebSearchOutcome.Empty(answer?.let { "摘要：$it" } ?: "Tavily 未返回结果")
                } else {
                    WebSearchOutcome.Hits(hits, note = answer?.let { "摘要：$it" })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            WebSearchOutcome.Failure("Search failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private suspend fun searchExa(query: String, apiKey: String?, maxResults: Int): WebSearchOutcome {
        if (apiKey.isNullOrBlank()) return WebSearchOutcome.Failure("未配置 Exa API Key，请在设置填写")
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
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(BYOK_JSON_MEDIA))
            .build()
        return try {
            fetchClient.newCall(req).useCancellable { resp ->
                val text = readBoundedUtf8(resp.body, FETCH_URL_MAX_BYTES)
                if (!resp.isSuccessful) {
                    return@useCancellable WebSearchOutcome.Failure("Search failed: HTTP ${resp.code} ${text.take(300)}")
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
                if (hits.isEmpty()) WebSearchOutcome.Empty("Exa 未返回结果") else WebSearchOutcome.Hits(hits)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            WebSearchOutcome.Failure("Search failed: ${error.message ?: error::class.simpleName}")
        }
    }

    internal suspend fun fetchUrl(url: String?): ToolExecutionResult {
        if (url.isNullOrBlank()) return ToolExecutionResult.failure("Missing url")
        val parsed = parseByokHttpUrl(url)
            ?: return ToolExecutionResult.failure("Fetch failed: 仅支持有效的 http/https 链接")
        val req = Request.Builder()
            .url(parsed)
            .header("Accept", "text/html,text/plain,application/json,application/xml;q=0.9")
            .build()
        return try {
            fetchClient.newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@useCancellable ToolExecutionResult.failure("Fetch failed: HTTP ${resp.code}")
                val contentType = resp.body?.contentType()
                if (contentType != null && !isReadableTextMediaType(contentType.type, contentType.subtype)) {
                    return@useCancellable ToolExecutionResult.failure(
                        "Fetch failed: 不支持的内容类型（${contentType.type}/${contentType.subtype}）",
                    )
                }
                val html = readBoundedUtf8(resp.body, FETCH_URL_MAX_BYTES)
                val out = buildString {
                    extractHtmlTitle(html)?.let { append("标题：").append(it).append('\n') }
                    append("链接：").append(url.trim()).append("\n\n")
                    append(stripHtml(html).take(12000))
                }
                ToolExecutionResult.success(out)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult.failure("Fetch failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun isReadableTextMediaType(type: String, subtype: String): Boolean {
        val t = type.lowercase()
        val sub = subtype.lowercase()
        return t == "text" ||
            (t == "application" && (sub == "json" || sub == "xml" || sub == "xhtml+xml" || sub.endsWith("+json") || sub.endsWith("+xml")))
    }

    private fun extractHtmlTitle(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.getOrNull(1)
            ?.let(::htmlDecode)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(200)

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

    companion object {
        const val FETCH_URL_MAX_BYTES: Long = 1024L * 1024L
        private const val MCP_RESPONSE_MAX_BYTES: Long = 2L * 1024L * 1024L
        private const val MOLA_SEARCH_SNIPPET_CHARS = 800
        private const val MOLA_SEARCH_NOT_SIGNED_IN =
            "未登录 MolaGPT 账号，无法使用 MolaGPT Web Search。请先登录，或在「设置 → 自定义模型工具 → 联网搜索服务」中更换搜索服务。"
        private const val MOLA_SEARCH_SESSION_EXPIRED =
            "MolaGPT 登录已失效，无法使用 MolaGPT Web Search。请重新登录，或在「设置 → 自定义模型工具 → 联网搜索服务」中更换搜索服务。"
    }
}
