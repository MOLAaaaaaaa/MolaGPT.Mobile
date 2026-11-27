package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import com.molagpt.app.core.model.Usage
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val ANTHROPIC_EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
private val ANTHROPIC_CACHEABLE_BLOCKS = setOf("text", "image", "document", "tool_use", "tool_result")

internal data class GeminiInlineImageKey(val mimeType: String, val data: String)

/** 一次原生工具执行后，回传 Claude/Gemini 所需的最小协议信息。 */
internal data class NativeToolResult(
    val callId: String?,
    val name: String,
    val output: String,
    val isError: Boolean,
)

internal fun effectiveByokThinkingKind(provider: ByokProvider, modelId: String): ThinkingParamKind =
    ThinkingKinds.wireKind(provider.thinkingConfigFor(modelId)?.kind ?: ThinkingParamKind.NONE, provider.baseUrl)

internal fun buildOpenAiMessages(json: Json, provider: ByokProvider, request: ChatRequest): List<JsonObject> {
    val replaceImages = replaceImagesWithText(provider, request)
    val imageOrdinal = AtomicInteger(0)
    val replayDeepSeekReasoning =
        provider.type == ByokProviderType.OPENAI_COMPAT &&
            effectiveByokThinkingKind(provider, request.modelId) == ThinkingParamKind.DEEPSEEK_THINKING
    val expectedWireApi = if (provider.type == ByokProviderType.OPENAI_RESPONSE) {
        OpenAiWireHistory.RESPONSES
    } else {
        OpenAiWireHistory.CHAT_COMPLETIONS
    }
    return buildList {
        var leadingOfficialInstructions = provider.isOfficialOpenAiResponses
        request.messages.forEach { message ->
            if (message.role != Role.SYSTEM || message.isRoleInjection) leadingOfficialInstructions = false
            if (message.role == Role.ASSISTANT) {
                val preserved = OpenAiWireHistory.decode(
                    json,
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
                    put("role", if (leadingOfficialInstructions) "developer" else wire.role)
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

internal fun buildOpenAiChatBody(
    json: Json,
    provider: ByokProvider,
    request: ChatRequest,
    messages: List<JsonObject>,
    stream: Boolean,
    tools: JsonArray?,
): JsonObject = buildJsonObject {
    put("model", request.modelId)
    put("stream", stream)
    if (stream && provider.host != "api.mistral.ai") {
        putJsonObject("stream_options") { put("include_usage", true) }
    }
    putJsonArray("messages") { messages.forEach { add(it) } }
    if (tools != null) put("tools", tools)
    addOpenAiThinking(
        provider, request.modelId, request.useThinking, request.reasoningEffort,
        includeReasoningText = true,
    )
    applyModelCustomBody(json, provider, request.modelId)
}

/** 官方端点的前置 developer 消息已由 buildOpenAiMessages 保留。 */
internal fun buildOpenAiResponseBody(
    json: Json,
    provider: ByokProvider,
    request: ChatRequest,
    messages: List<JsonObject>,
    tools: JsonArray? = null,
): JsonObject {
    val official = provider.isOfficialOpenAiResponses
    val leadingSystem = if (official) emptyList() else {
        messages.takeWhile { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
    }
    val systemText = leadingSystem
        .mapNotNull { it["content"]?.jsonPrimitive?.contentOrNull }
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }
    val inputItems = messages.drop(leadingSystem.size).map(::toOpenAiResponseInputItem)
    return buildJsonObject {
        put("model", request.modelId)
        put("stream", true)
        if (systemText != null) put("instructions", systemText)
        putJsonArray("input") { inputItems.forEach { add(it) } }
        addResponsesThinking(provider, request.modelId, request.useThinking, request.reasoningEffort, showSummary = true)
        if (tools != null) put("tools", tools)
        applyModelCustomBody(json, provider, request.modelId)
    }.let { body ->
        if (!official) return@let body
        val include = body["include"] as? JsonArray
        val encryptedReasoning = JsonPrimitive("reasoning.encrypted_content")
        JsonObject(
            body + mapOf(
                "store" to JsonPrimitive(false),
                "include" to JsonArray((include.orEmpty() + encryptedReasoning).distinct()),
            ),
        )
    }
}

internal fun buildAnthropicMessages(json: Json, provider: ByokProvider, request: ChatRequest): List<JsonObject> {
    val replaceImages = replaceImagesWithText(provider, request)
    val imageOrdinal = AtomicInteger(0)
    return buildList {
        request.messages.filter { it.role != Role.SYSTEM || it.isRoleInjection }.forEach { message ->
            if (message.role == Role.ASSISTANT) {
                val preserved = NativeWireHistory.decode(
                    json,
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

internal fun buildAnthropicBody(
    json: Json,
    provider: ByokProvider,
    request: ChatRequest,
    messages: List<JsonObject>,
    stream: Boolean = true,
    tools: JsonArray? = null,
): JsonObject = buildJsonObject {
    put("model", request.modelId)
    put("max_tokens", 4096)
    put("stream", stream)
    putJsonArray("messages") {
        withAnthropicCacheBreakpoint(messages).forEach { add(it) }
    }
    val systemText = request.messages
        .filter { it.role == Role.SYSTEM && !it.isRoleInjection }
        .joinToString("\n") { it.rawText.orEmpty() }
        .trim()
    if (systemText.isNotBlank()) {
        putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", systemText)
                put("cache_control", ANTHROPIC_EPHEMERAL)
            }
        }
    }
    addAnthropicThinking(provider, request.modelId, request.useThinking, request.reasoningEffort, maxTokens = 4096)
    if (tools != null) put("tools", tools)
    applyModelCustomBody(json, provider, request.modelId)
}

internal fun parseAnthropicEvent(json: Json, data: String): StreamEvent? {
    val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
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

internal fun buildGeminiContents(json: Json, provider: ByokProvider, request: ChatRequest): List<JsonObject> {
    val replaceImages = replaceImagesWithText(provider, request)
    val imageOrdinal = AtomicInteger(0)
    return buildList {
        request.messages.filter { it.role != Role.SYSTEM || it.isRoleInjection }.forEach { message ->
            if (message.role == Role.ASSISTANT) {
                val preserved = NativeWireHistory.decode(
                    json,
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

internal fun buildGeminiBody(
    json: Json,
    provider: ByokProvider,
    request: ChatRequest,
    contents: List<JsonObject>,
    tools: JsonArray? = null,
): JsonObject = buildJsonObject {
    putJsonArray("contents") { contents.forEach { add(it) } }
    val systemText = request.messages
        .filter { it.role == Role.SYSTEM && !it.isRoleInjection }
        .joinToString("\n") { it.rawText.orEmpty() }
        .trim()
    if (systemText.isNotBlank()) {
        put("systemInstruction", buildJsonObject {
            putJsonArray("parts") { addJsonObject { put("text", systemText) } }
        })
    }
    putJsonObject("generationConfig") {
        addGeminiThinking(provider, request.modelId, request.useThinking, request.reasoningEffort, showThoughts = true)
    }
    if (tools != null) put("tools", tools)
    applyModelCustomBody(json, provider, request.modelId)
}

internal suspend fun parseGeminiEvent(
    json: Json,
    data: String,
    emittedImages: MutableSet<GeminiInlineImageKey>,
    imageFileSaver: suspend (bytes: ByteArray, ext: String) -> String?,
): List<StreamEvent> {
    val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
    return parseGeminiEvent(root, emittedImages, imageFileSaver)
}

internal suspend fun parseGeminiEvent(
    root: JsonObject,
    emittedImages: MutableSet<GeminiInlineImageKey>,
    imageFileSaver: suspend (bytes: ByteArray, ext: String) -> String?,
): List<StreamEvent> {
    root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let {
        return listOf(StreamEvent.Failed(it))
    }
    val block = (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull
    if (!block.isNullOrBlank() && block != "BLOCK_REASON_UNSPECIFIED") {
        return listOf(StreamEvent.Failed("Gemini 请求被拦截：$block", "incomplete"))
    }
    val candidates = root["candidates"] as? JsonArray ?: JsonArray(emptyList())
    val events = mutableListOf<StreamEvent>()
    for (candidateElement in candidates) {
        val candidate = candidateElement as? JsonObject ?: continue
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
        if (parts != null) {
            for (part in parts) {
                val obj = part as? JsonObject ?: continue
                obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
                    events += if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                        StreamEvent.Delta(thinking = text)
                    } else {
                        StreamEvent.Delta(text = text)
                    }
                }
                parseGeminiInlineImage(obj, emittedImages, imageFileSaver)?.let(events::add)
            }
        }
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        when (finish) {
            null, "", "FINISH_REASON_UNSPECIFIED" -> Unit
            "STOP" -> events += StreamEvent.Finish(finish)
            "MAX_TOKENS" -> events += StreamEvent.Failed("回答已截断", "incomplete", "retry")
            else -> events += StreamEvent.Failed("Gemini 未完成回答（$finish）", "incomplete")
        }
    }
    return listOfNotNull(parseGeminiUsage(root)?.let { StreamEvent.UsageUpdate(it) }) + events
}

private suspend fun parseGeminiInlineImage(
    part: JsonObject,
    emittedImages: MutableSet<GeminiInlineImageKey>,
    imageFileSaver: suspend (bytes: ByteArray, ext: String) -> String?,
): StreamEvent.Image? {
    val inline = part["inlineData"]?.jsonObject ?: part["inline_data"]?.jsonObject ?: return null
    val data = inline["data"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val mimeType = (
        inline["mimeType"]?.jsonPrimitive?.contentOrNull
            ?: inline["mime_type"]?.jsonPrimitive?.contentOrNull
            ?: "image/png"
        ).substringBefore(';').trim().ifBlank { "image/png" }
    if (!mimeType.startsWith("image/", ignoreCase = true)) return null
    if (!emittedImages.add(GeminiInlineImageKey(mimeType, data))) return null
    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
    val ext = when (mimeType.substringAfter('/').lowercase(Locale.ROOT)) {
        "jpeg", "jpg" -> "jpg"
        "webp" -> "webp"
        "gif" -> "gif"
        else -> "png"
    }
    val url = imageFileSaver(bytes, ext)
        ?.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
        ?: error("生成图片保存失败")
    return StreamEvent.Image(url)
}

internal fun parseGeminiUsage(root: JsonObject): Usage? {
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

internal fun toolResultMessage(toolCallId: String, result: String): JsonObject = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", toolCallId)
    put("content", result)
}

internal fun assistantToolCallMessage(content: JsonElement, calls: List<ByokToolCall>): JsonObject = buildJsonObject {
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

/** Anthropic 流式 content 块重建器（保留 thinking / signature / redacted_thinking / tool_use）。 */
internal class AnthropicStreamBlocks {
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

/** Gemini 流式 parts 合并器（保留 thoughtSignature 挂靠的 part 边界）。 */
internal class GeminiStreamParts {
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

/** 流式 tool_calls 累积器：按 index 归位 id / name / arguments。 */
internal class PendingToolCalls {
    private class Slot {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val slots = LinkedHashMap<Int, Slot>()

    fun feed(element: JsonElement) {
        val obj = element as? JsonObject ?: return
        val slot = slots.getOrPut((obj["index"] as? JsonPrimitive)?.intOrNull ?: 0) { Slot() }
        (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { slot.id = it }
        val function = obj["function"] as? JsonObject ?: return
        (function["name"] as? JsonPrimitive)?.contentOrNull?.let { slot.name = it }
        (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let(slot.arguments::append)
    }

    fun build(): List<ByokToolCall> = slots.values.mapNotNull { slot ->
        val name = slot.name ?: return@mapNotNull null
        ByokToolCall(
            id = slot.id ?: Ids.newFragmentId(),
            name = name,
            arguments = slot.arguments.toString().ifBlank { "{}" },
        )
    }
}

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

/** 在最后一条消息的最后一个可缓存块上打断点，返回新列表（不改原件避免断点累积超限）。 */
internal fun withAnthropicCacheBreakpoint(messages: List<JsonObject>): List<JsonObject> {
    val last = messages.lastOrNull() ?: return messages
    val content = last["content"] as? JsonArray ?: return messages
    val index = content.indexOfLast { block ->
        val obj = block as? JsonObject ?: return@indexOfLast false
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        type in ANTHROPIC_CACHEABLE_BLOCKS &&
            (type != "text" || !(obj["text"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank())
    }
    if (index < 0) return messages
    val marked = JsonArray(
        content.mapIndexed { i, block ->
            if (i == index) JsonObject(block.jsonObject + ("cache_control" to ANTHROPIC_EPHEMERAL)) else block
        },
    )
    return messages.dropLast(1) + JsonObject(last + ("content" to marked))
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

/** 把 chat-completions 的消息转换为 Responses API input item；原生 item 原样回放。 */
internal fun toOpenAiResponseInputItem(item: JsonObject): JsonObject {
    if (!item["type"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return item
    val role = item["role"]?.jsonPrimitive?.contentOrNull ?: "user"
    return buildJsonObject {
        put("role", role)
        put("content", toOpenAiResponseContent(role, item["content"]))
    }
}

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

internal fun captureResponsesOutputItems(
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

internal fun responsesStreamUsage(json: Json, data: String): Usage? {
    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
    return when (root["type"]?.jsonPrimitive?.contentOrNull) {
        "response.completed", "response.incomplete" ->
            (root["response"] as? JsonObject)?.let(::parseOpenAiResponseUsage)
        else -> null
    }
}

internal fun isResponsesMessageItem(item: JsonObject): Boolean =
    item["type"]?.jsonPrimitive?.contentOrNull == "message"

internal fun syntheticResponsesMessage(text: String): JsonObject = buildJsonObject {
    put("type", "message")
    put("role", "assistant")
    putJsonArray("content") {
        addJsonObject {
            put("type", "output_text")
            put("text", text)
        }
    }
}
