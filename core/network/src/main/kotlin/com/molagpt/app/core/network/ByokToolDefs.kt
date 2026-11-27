package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokLocalToolHandler
import com.molagpt.app.core.model.ByokProfileKey
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.EnabledTools
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.WebSearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 本次请求是否会带上工具定义。 */
val EnabledTools.hasByokTools: Boolean
    get() = network || steelBrowser || vision || imageGeneration || mcp || memory || conversationRecall || skills

internal data class ByokToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val responseCallId: String? = null,
)

internal data class ToolSpec(
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
internal data class ToolProperty(
    val description: String,
    val type: ToolPropertyType = ToolPropertyType.STRING,
    val enumValues: List<String> = emptyList(),
    /** [ToolPropertyType.ARRAY] 的元素是带这些字段的对象；为空则是不展开 items 的普通数组。 */
    val itemProperties: Map<String, ToolProperty> = emptyMap(),
    val itemRequired: List<String> = emptyList(),
)

internal enum class ToolPropertyType(val lower: String, val upper: String) {
    STRING("string", "STRING"),
    INTEGER("integer", "INTEGER"),
    BOOLEAN("boolean", "BOOLEAN"),
    ARRAY("array", "ARRAY"),
}

internal data class MolaSearchQuery(
    val query: String,
    val topic: String? = null,
    val timeRange: String? = null,
    val country: String? = null,
    val includeImages: Boolean = false,
)

internal val MOLA_SEARCH_TOPICS = listOf("general", "news", "finance", "paper", "technology")
internal val MOLA_SEARCH_TIME_RANGES = listOf("day", "week", "month", "year")

/** BYOK 工具的结构化执行结果；状态由工具执行器直接声明，不再依赖猜测自由文本。 */
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

internal fun modelSupportsVision(provider: ByokProvider, request: ChatRequest): Boolean =
    provider.models.firstOrNull { it.id == request.modelId }?.supportsVision == true

internal fun replaceImagesWithText(provider: ByokProvider, request: ChatRequest): Boolean =
    !modelSupportsVision(provider, request) && request.enabledTools.vision

internal fun selectByokVisionModel(provider: ByokProvider, fallbackModelId: String): String {
    val current = provider.models.firstOrNull { it.id == fallbackModelId }
    if (current?.supportsVision == true) return fallbackModelId
    return provider.models.firstOrNull { it.supportsVision }?.id ?: fallbackModelId
}

internal fun buildByokToolSpecs(
    provider: ByokProvider,
    request: ChatRequest,
    searchProvider: WebSearchProvider,
): List<ToolSpec> = buildList {
    if (request.providerKind == ProviderKind.BYOK &&
        request.enabledTools.skills && request.availableSkills.isNotEmpty()
    ) {
        add(
            ToolSpec(
                name = "read_skill",
                description = "Read an enabled skill's instructions or a referenced text file. " +
                    "Read SKILL.md before using the skill. This tool cannot execute scripts. " +
                    "If next_offset is returned, continue reading at that offset until the instructions are complete.",
                properties = mapOf(
                    "skill" to ToolProperty("Skill name", enumValues = request.availableSkills.map { it.name }),
                    "path" to ToolProperty("Path relative to the skill directory. Defaults to SKILL.md."),
                    "offset" to ToolProperty("Character offset. Defaults to 0.", ToolPropertyType.INTEGER),
                ),
                required = listOf("skill"),
            ),
        )
    }
    if (request.enabledTools.network && searchProvider == WebSearchProvider.MOLAGPT) {
        add(
            ToolSpec(
                name = "search_web",
                description = "在网络上并行搜索资料，支持一次提交 1 至 5 组查询（可混合中英文）。适合查新闻、实时资料、" +
                    "产品/库/文档状态、事实核验。topic 用于区分通用网页、新闻、金融、论文和技术资料。" +
                    "可选 time_range 和 country；country 仅在 general 时生效，只是地域偏好。" +
                    "需要图片或视觉比对时设置 include_images 为 true。",
                properties = mapOf(
                    "queries" to ToolProperty(
                        description = "一组 1~5 个搜索对象。每项必须包含 query 和 topic。",
                        type = ToolPropertyType.ARRAY,
                        itemProperties = mapOf(
                            "query" to ToolProperty("搜索关键词"),
                            "topic" to ToolProperty(
                                description = "必填。根据查询内容选择最合适的分类：\n" +
                                    "- paper：学术论文/研究/综述/学位论文/会议论文等学术内容\n" +
                                    "- technology：编程问题/技术原理/API用法/安全研究/源码分析/库文档等技术内容\n" +
                                    "- news：新闻/时事/最新动态/突发事件\n" +
                                    "- finance：股票/财报/经济数据/金融市场\n" +
                                    "- general：日常生活/百科知识/事实查询/购物/旅游等其他通用查询",
                                enumValues = MOLA_SEARCH_TOPICS,
                            ),
                            "time_range" to ToolProperty(
                                description = "时间范围过滤。day=24h；week=7天；month=30天；year=1年",
                                enumValues = MOLA_SEARCH_TIME_RANGES,
                            ),
                            "country" to ToolProperty(
                                "优先返回特定国家的结果，仅在 topic=general 时使用；填英文国家名小写（如 china、united states、japan）",
                            ),
                            "include_images" to ToolProperty(
                                "需要图片或视觉比对时设为 true。",
                                ToolPropertyType.BOOLEAN,
                            ),
                        ),
                        itemRequired = listOf("query", "topic"),
                    ),
                ),
                required = listOf("queries"),
            ),
        )
    } else if (request.enabledTools.network) {
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

internal fun buildOpenAiTools(specs: List<ToolSpec>): JsonArray = JsonArray(
    specs.map { spec ->
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("parameters", buildToolParameters(spec, uppercaseTypes = false))
            })
        }
    },
)

internal fun buildResponseTools(specs: List<ToolSpec>): JsonArray = JsonArray(
    specs.map { spec ->
        buildJsonObject {
            put("type", "function")
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", buildToolParameters(spec, uppercaseTypes = false))
        }
    },
)

internal fun buildAnthropicTools(specs: List<ToolSpec>): JsonArray = JsonArray(
    specs.map { spec ->
        buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("input_schema", buildToolParameters(spec, uppercaseTypes = false))
        }
    },
)

internal fun buildGeminiTools(specs: List<ToolSpec>): JsonArray = buildJsonArray {
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

private fun buildToolParameters(spec: ToolSpec, uppercaseTypes: Boolean): JsonObject = buildJsonObject {
    put("type", if (uppercaseTypes) "OBJECT" else "object")
    put("properties", buildJsonObject {
        spec.properties.forEach { (key, property) -> put(key, buildPropertySchema(property, uppercaseTypes)) }
    })
    putJsonArray("required") { spec.required.forEach { add(it) } }
}

private fun buildPropertySchema(property: ToolProperty, uppercaseTypes: Boolean): JsonObject = buildJsonObject {
    put("type", if (uppercaseTypes) property.type.upper else property.type.lower)
    put("description", property.description)
    if (property.enumValues.isNotEmpty()) {
        putJsonArray("enum") { property.enumValues.forEach { add(it) } }
    }
    if (property.type == ToolPropertyType.ARRAY && property.itemProperties.isNotEmpty()) {
        put("items", buildJsonObject {
            put("type", if (uppercaseTypes) "OBJECT" else "object")
            put("properties", buildJsonObject {
                property.itemProperties.forEach { (key, item) -> put(key, buildPropertySchema(item, uppercaseTypes)) }
            })
            putJsonArray("required") { property.itemRequired.forEach { add(it) } }
        })
    }
}

internal fun parseAnthropicToolCall(json: Json, element: JsonElement): ByokToolCall? {
    val obj = element as? JsonObject ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return null
    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: Ids.newFragmentId()
    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val input = obj["input"]?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
    return ByokToolCall(id, name, input)
}

internal fun parseGeminiToolCall(json: Json, element: JsonElement): ByokToolCall? {
    val functionCall = (element as? JsonObject)?.get("functionCall")?.jsonObject ?: return null
    val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val args = functionCall["args"]?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
    val protocolId = functionCall["id"]?.jsonPrimitive?.contentOrNull
    return ByokToolCall(protocolId ?: Ids.newFragmentId(), name, args, responseCallId = protocolId)
}

internal fun ByokToolCall.arg(json: Json, name: String): String? =
    runCatching { json.parseToJsonElement(arguments).jsonObject[name]?.jsonPrimitive?.contentOrNull }
        .getOrNull()

internal fun ByokToolCall.argJsonObject(json: Json, name: String): JsonObject? =
    runCatching {
        val element = json.parseToJsonElement(arguments).jsonObject[name] ?: return@runCatching null
        when (element) {
            is JsonObject -> element
            else -> json.parseToJsonElement(element.jsonPrimitive.content).jsonObject
        }
    }.getOrNull()

/** 取出 `queries`（对象或纯字符串；个别模型会把数组序列化成字符串）与旧的单个 `query`，最多 5 条。 */
internal fun parseMolaSearchQueries(json: Json, call: ByokToolCall): List<MolaSearchQuery> {
    val root = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrNull() ?: return emptyList()
    var queries = root["queries"]
    if (queries is JsonPrimitive && queries.isString) {
        queries = runCatching { json.parseToJsonElement(queries.content) }.getOrNull()
    }
    val parsed = (queries as? JsonArray).orEmpty().mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { MolaSearchQuery(it) }
            is JsonObject -> {
                val text = item["query"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                MolaSearchQuery(
                    query = text,
                    topic = item["topic"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                        ?.takeIf { it in MOLA_SEARCH_TOPICS },
                    timeRange = item["time_range"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                        ?.takeIf { it in MOLA_SEARCH_TIME_RANGES },
                    country = item["country"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                    includeImages = item["include_images"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            else -> null
        }
    }
    val all = parsed.ifEmpty {
        listOfNotNull(call.arg(json, "query")?.trim()?.takeIf { it.isNotEmpty() }?.let { MolaSearchQuery(it) })
    }
    return all.distinctBy { it.query.lowercase() }.take(5)
}

internal fun labelForByokTool(name: String): String = when (name) {
    "search_web" -> "联网搜索"
    "fetch_url" -> "阅读网页"
    "read_skill" -> "读取技能"
    "view_image" -> "视觉理解"
    "generate_image" -> "图像生成"
    "mcp_list_tools" -> "MCP 工具列表"
    "mcp_call" -> "MCP 服务器"
    ByokLocalToolHandler.SAVE_MEMORY -> "记住"
    ByokLocalToolHandler.FORGET_MEMORY -> "忘记"
    ByokLocalToolHandler.RECALL_CONVERSATIONS -> "回忆对话"
    else -> "工具调用"
}

/** 外挂视觉工具的结果仅发给模型，不向用户展示。 */
internal fun shouldShowByokToolPreview(toolName: String): Boolean =
    toolName !in setOf("view_image", "vision_proxy")

internal fun byokToolPreviewOf(json: Json, call: ByokToolCall, result: ToolExecutionResult): String? {
    if (!shouldShowByokToolPreview(call.name)) return null
    if (result.status == ToolStatus.FAILED) return result.output
    return when (call.name) {
        "search_web", "web_search" ->
            (call.arg(json, "query") ?: parseMolaSearchQueries(json, call).joinToString(" / ") { it.query })
                .takeIf { it.isNotBlank() }?.take(200)
        "read_skill" -> "${call.arg(json, "skill")}/${call.arg(json, "path") ?: "SKILL.md"}"
        "fetch_url" ->
            result.output.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n").take(400)
        ByokLocalToolHandler.RECALL_CONVERSATIONS ->
            result.output.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n").take(400)
        else -> result.output.take(1200)
    }
}
