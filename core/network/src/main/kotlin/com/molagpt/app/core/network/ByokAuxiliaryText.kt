package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.CustomBodyParam
import com.molagpt.app.core.model.ThinkingParamKind
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

internal val BYOK_JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
internal const val ANTHROPIC_AUX_MAX_TOKENS = 2048

private val DEPRECATED_GEMINI_PARAMS = setOf(
    "temperature", "top_p", "topP", "top_k", "topK", "thinking_budget", "thinkingBudget",
)
private val GEMINI_CONFIG_KEYS = setOf(
    "generationConfig", "generation_config", "thinkingConfig", "thinking_config", "extra_body", "google",
)

internal data class ByokDataUrl(val mimeType: String, val base64: String)

internal fun parseByokDataUrl(value: String): ByokDataUrl? {
    if (!value.startsWith("data:", ignoreCase = true)) return null
    val comma = value.indexOf(',')
    if (comma <= 5) return null
    val meta = value.substring(5, comma)
    if (!meta.contains(";base64", ignoreCase = true)) return null
    val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
    val data = value.substring(comma + 1).takeIf { it.isNotBlank() } ?: return null
    return ByokDataUrl(mime, data)
}

internal fun parseByokHttpUrl(rawUrl: String): HttpUrl? {
    val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return null
    return parsed.takeIf { it.scheme.equals("https", ignoreCase = true) || it.scheme.equals("http", ignoreCase = true) }
}

internal fun byokRequestUrl(provider: ByokProvider, modelId: String, stream: Boolean): HttpUrl? {
    val raw = if (provider.type == ByokProviderType.GEMINI) {
        geminiEndpoint(provider, modelId, stream = stream)
    } else {
        provider.endpoint(provider.chatPath)
    }
    return parseByokHttpUrl(raw)
}

internal fun geminiEndpoint(provider: ByokProvider, modelId: String, stream: Boolean): String {
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

internal fun buildByokPostRequest(
    http: MolaHttp,
    provider: ByokProvider,
    modelId: String,
    body: JsonObject,
    stream: Boolean,
): Request? {
    val url = byokRequestUrl(provider, modelId, stream) ?: return null
    return Request.Builder()
        .url(url)
        .apply {
            if (stream) header("Accept", "text/event-stream")
            provider.applyAuthHeaders { name, value -> header(name, value) }
        }
        .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(BYOK_JSON_MEDIA))
        .build()
}

private fun usesGeminiDefaultSampling(provider: ByokProvider, modelId: String): Boolean =
    provider.usesNativeGemini && provider.thinkingConfigFor(modelId)?.kind == ThinkingParamKind.GEMINI_LEVEL

private fun isProtectedBodyKey(key: String): Boolean =
    key == "messages" || key == "input" || key == "contents" || key == "stream" ||
        key == "tools" || key == "tool_choice" || key == "functionDeclarations"

private fun cleanGeminiConfig(value: JsonElement): JsonElement {
    if (value !is JsonObject) return value
    return buildJsonObject {
        value.forEach { (key, child) ->
            if (key !in DEPRECATED_GEMINI_PARAMS) {
                put(key, if (key in GEMINI_CONFIG_KEYS) cleanGeminiConfig(child) else child)
            }
        }
    }
}

/**
 * 把当前模型的自定义 body 覆写项叠加到请求体（作为 builder 的最后一步，覆盖前面所有字段）。
 * 保护键（承载消息/流/工具结构的字段）永不可覆盖，避免破坏请求。与 Desktop CustomRequestParams 对齐。
 */
internal fun JsonObjectBuilder.applyModelCustomBody(json: Json, provider: ByokProvider, modelId: String) {
    val custom = provider.models.firstOrNull { it.id == modelId }?.customBody ?: return
    val geminiLevels = usesGeminiDefaultSampling(provider, modelId)
    custom.forEach { param ->
        val key = param.key.trim()
        if (key.isBlank() || isProtectedBodyKey(key)) return@forEach
        if (geminiLevels && key in DEPRECATED_GEMINI_PARAMS) return@forEach
        val value = customBodyValueToJson(json, param)
        put(key, if (geminiLevels && key in GEMINI_CONFIG_KEYS) cleanGeminiConfig(value) else value)
    }
}

/** 模型参数覆写项按声明的类型转成 JSON 值。对话和图像请求共用。 */
internal fun customBodyValueToJson(json: Json, param: CustomBodyParam): JsonElement =
    when (param.type.trim().lowercase()) {
        "number" -> param.value.toLongOrNull()?.let { JsonPrimitive(it) }
            ?: param.value.toDoubleOrNull()?.let { JsonPrimitive(it) }
            ?: JsonPrimitive(param.value)
        "boolean" -> JsonPrimitive(param.value.trim().toBooleanStrictOrNull() ?: false)
        "json" -> runCatching { json.parseToJsonElement(param.value) }.getOrDefault(JsonPrimitive(param.value))
        else -> JsonPrimitive(param.value)
    }

/**
 * 纯文本辅助请求体（标题 / 记忆整理 / 上下文摘要）：单条 user 消息、非流式、关思考、不带工具。
 * OpenAI 系不设 max_tokens（推理模型小上限会全花在思考上）；[maxTokens] 用于 Anthropic 必填项。
 */
internal fun buildByokTextBody(
    json: Json,
    provider: ByokProvider,
    modelId: String,
    prompt: String,
    maxTokens: Int,
    system: String? = null,
): JsonObject = when (provider.type) {
    ByokProviderType.OPENAI_COMPAT -> buildJsonObject {
        put("model", modelId)
        put("stream", false)
        putJsonArray("messages") {
            system?.let { text ->
                addJsonObject {
                    put("role", "system")
                    put("content", text)
                }
            }
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
        addOpenAiThinking(provider, modelId, requestedThinking = false, reasoningEffort = "")
        applyModelCustomBody(json, provider, modelId)
    }

    ByokProviderType.OPENAI_RESPONSE -> buildJsonObject {
        put("model", modelId)
        put("stream", false)
        if (provider.isOfficialOpenAiResponses) put("store", false)
        else system?.let { put("instructions", it) }
        putJsonArray("input") {
            if (provider.isOfficialOpenAiResponses && !system.isNullOrBlank()) {
                addJsonObject {
                    put("role", "developer")
                    put("content", system)
                }
            }
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
        addResponsesThinking(provider, modelId, requestedThinking = false, requestedEffort = "", showSummary = false)
        applyModelCustomBody(json, provider, modelId)
    }

    ByokProviderType.ANTHROPIC -> buildJsonObject {
        put("model", modelId)
        put("max_tokens", maxTokens)
        put("stream", false)
        system?.let { put("system", it) }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
        addAnthropicThinking(provider, modelId, requestedThinking = false, requestedEffort = "", maxTokens = maxTokens)
        applyModelCustomBody(json, provider, modelId)
    }

    ByokProviderType.GEMINI -> buildJsonObject {
        system?.let { text ->
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", text) } }
            }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt) }
                }
            }
        }
        val generation = buildJsonObject {
            addGeminiThinking(provider, modelId, requestedThinking = false, requestedEffort = "", showThoughts = false)
        }
        if (generation.isNotEmpty()) put("generationConfig", generation)
        applyModelCustomBody(json, provider, modelId)
    }
}

/** 视觉辅助请求体：四协议共用关闭思考、官方 Responses `store: false` 与自定义参数策略。 */
internal fun buildByokVisionBody(
    json: Json,
    provider: ByokProvider,
    modelId: String,
    imageUrl: String,
    prompt: String,
): JsonObject = when (provider.type) {
    ByokProviderType.OPENAI_COMPAT -> buildJsonObject {
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
                        put("image_url", buildJsonObject { put("url", imageUrl) })
                    }
                }
            }
        }
        addOpenAiThinking(provider, modelId, requestedThinking = false, reasoningEffort = "")
        applyModelCustomBody(json, provider, modelId)
    }

    ByokProviderType.OPENAI_RESPONSE -> buildJsonObject {
        put("model", modelId)
        put("stream", false)
        if (provider.isOfficialOpenAiResponses) put("store", false)
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
                        put("image_url", imageUrl)
                    }
                }
            }
        }
        addResponsesThinking(provider, modelId, requestedThinking = false, requestedEffort = "", showSummary = false)
        applyModelCustomBody(json, provider, modelId)
    }

    ByokProviderType.ANTHROPIC -> {
        val data = parseByokDataUrl(imageUrl)
        buildJsonObject {
            put("model", modelId)
            put("max_tokens", ANTHROPIC_AUX_MAX_TOKENS)
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
                                    put("url", imageUrl)
                                }
                            })
                        }
                    }
                }
            }
            addAnthropicThinking(
                provider, modelId, requestedThinking = false, requestedEffort = "", maxTokens = ANTHROPIC_AUX_MAX_TOKENS,
            )
            applyModelCustomBody(json, provider, modelId)
        }
    }

    ByokProviderType.GEMINI -> {
        val data = parseByokDataUrl(imageUrl)
        buildJsonObject {
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
                                    put("fileUri", imageUrl)
                                })
                            }
                        }
                    }
                }
            }
            val generation = buildJsonObject {
                addGeminiThinking(provider, modelId, requestedThinking = false, requestedEffort = "", showThoughts = false)
            }
            if (generation.isNotEmpty()) put("generationConfig", generation)
            applyModelCustomBody(json, provider, modelId)
        }
    }
}

internal suspend fun executeByokVisionRequest(
    http: MolaHttp,
    provider: ByokProvider,
    modelId: String,
    imageUrl: String,
    prompt: String,
): ToolExecutionResult {
    val label = when (provider.type) {
        ByokProviderType.OPENAI_COMPAT -> "OpenAI Compatible"
        ByokProviderType.OPENAI_RESPONSE -> "Responses API"
        ByokProviderType.ANTHROPIC -> "Anthropic"
        ByokProviderType.GEMINI -> "Gemini"
    }
    val body = buildByokVisionBody(http.json, provider, modelId, imageUrl, prompt)
    val req = buildByokPostRequest(http, provider, modelId, body, stream = false)
        ?: return ToolExecutionResult.failure("Vision failed: 服务地址无效（${provider.baseUrl}）")
    return try {
        http.okHttp.newCall(req).useCancellable { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@useCancellable ToolExecutionResult.failure(
                    "Vision failed: ${serverResponseError("$label 视觉请求失败", resp.code, text)}",
                )
            }
            val root = runCatching { http.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return@useCancellable ToolExecutionResult.failure(
                    "Vision failed: ${serverResponseError("$label 视觉响应格式无效", resp.code, text)}",
                )
            val failedStatus = provider.type == ByokProviderType.OPENAI_RESPONSE &&
                root["status"]?.jsonPrimitive?.contentOrNull.equals("failed", ignoreCase = true)
            if (failedStatus || root["error"]?.takeIf { it !is JsonNull } != null) {
                return@useCancellable ToolExecutionResult.failure(
                    "Vision failed: ${serverResponseError("$label 视觉请求失败", resp.code, text)}",
                )
            }
            val parsed = parseTitleResponse(provider.type, http.json, text)?.trim()
            if (parsed.isNullOrEmpty()) {
                ToolExecutionResult.failure("Vision failed: ${serverResponseError("$label 视觉响应缺少文本", resp.code, text)}")
            } else {
                ToolExecutionResult.success(parsed)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolExecutionResult.failure("Vision failed: ${error.message ?: error::class.simpleName}")
    }
}

/**
 * 有界读取文本响应体，超过 [maxBytes] 立即停止读取并返回前缀（防止网页/大文件 OOM）。
 */
internal fun readBoundedUtf8(body: ResponseBody?, maxBytes: Long): String {
    if (body == null || maxBytes <= 0L) return ""
    val source = body.source()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = maxBytes
    while (remaining > 0L) {
        val want = minOf(buffer.size.toLong(), remaining).toInt()
        val read = source.read(buffer, 0, want)
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toString(Charsets.UTF_8.name())
}
