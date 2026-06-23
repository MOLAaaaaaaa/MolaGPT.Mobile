package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ThinkingConfig
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import com.molagpt.app.core.model.ProviderModel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class ByokModelApi(private val http: MolaHttp) {
    suspend fun fetchModels(provider: ByokProvider): List<ProviderModel> {
        val url = provider.modelsEndpoint()
        val resp = http.client.get(url) {
            provider.applyAuthHeaders { name, value -> header(name, value) }
        }
        val text = resp.bodyAsText().trimStart('﻿')
        if (!resp.status.isSuccess()) {
            throw MolaApiException(resp.status.value, "模型列表获取失败：HTTP ${resp.status.value} ${text.take(160)}")
        }
        val root = http.json.parseToJsonElement(text) as? JsonObject
            ?: return emptyList()
        return parseByokModels(root, provider)
    }

    /**
     * 按 purpose 分流解析：image 用途只取图像模型，chat 用途只取聊天模型。
     * 对齐桌面端 FetchModelListAsync 的 purpose 分流做法。
     */
    internal fun parseByokModels(root: JsonObject, provider: ByokProvider): List<ProviderModel> {
        if (provider.purpose == ByokPurpose.IMAGE) return parseImageModels(root, provider)
        return when (provider.type) {
            ByokProviderType.OPENAI_COMPAT -> parseOpenAiModels(root, provider)
            ByokProviderType.OPENAI_RESPONSE -> parseOpenAiModels(root, provider)
            ByokProviderType.ANTHROPIC -> parseAnthropicModels(root, provider)
            ByokProviderType.GEMINI -> parseGeminiModels(root, provider)
        }
    }

    private fun parseOpenAiModels(root: JsonObject, provider: ByokProvider): List<ProviderModel> {
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // chat 用途：仅保留聊天模型，图像模型交给 image 用途 provider。
            if (!looksLikeByokChatModel(id)) return@mapNotNull null
            ProviderModel(
                id = id,
                displayName = beautifyModelName(id),
                apiUrl = provider.chatPath,
                supportsVision = looksLikeByokVisionModel(id),
                supportsThinking = looksLikeByokReasoningModel(id),
                supportsReasoningEffort = looksLikeByokReasoningModel(id),
                supportsToolCalling = looksLikeByokToolModel(id),
                supportsImageGeneration = false,
                supportsChat = true,
                providerId = provider.id,
                providerName = provider.name,
                providerKind = ProviderKind.BYOK,
                thinkingConfig = thinkingConfigFor(provider, id),
            )
        }.sortedWith(compareByDescending<ProviderModel> { it.supportsToolCalling }.thenBy { it.id })
    }

    private fun parseAnthropicModels(root: JsonObject, provider: ByokProvider): List<ProviderModel> {
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val isThinking = looksLikeByokReasoningModel(id)
            ProviderModel(
                id = id,
                displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull ?: beautifyModelName(id),
                apiUrl = provider.chatPath,
                supportsVision = looksLikeByokVisionModel(id),
                supportsThinking = isThinking,
                supportsReasoningEffort = isThinking,
                supportsToolCalling = true,
                supportsImageGeneration = false,
                supportsChat = true,
                providerId = provider.id,
                providerName = provider.name,
                providerKind = ProviderKind.BYOK,
                thinkingConfig = thinkingConfigFor(provider, id),
            )
        }
    }

    private fun parseGeminiModels(root: JsonObject, provider: ByokProvider): List<ProviderModel> {
        val data = (root["models"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["id"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val id = rawName.removePrefix("models/")
            val methods = obj["supportedGenerationMethods"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            val supportsGenerateContent = methods.isEmpty() ||
                methods.any { it.equals("generateContent", ignoreCase = true) || it.equals("streamGenerateContent", ignoreCase = true) }
            val isImagen = id.contains("imagen", ignoreCase = true)
            // chat 用途：跳过 imagen 与不支持 generateContent 的模型。
            if (isImagen || !supportsGenerateContent) return@mapNotNull null
            ProviderModel(
                id = id,
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: beautifyModelName(id),
                apiUrl = provider.chatPath,
                supportsVision = looksLikeByokVisionModel(id),
                supportsThinking = looksLikeByokReasoningModel(id),
                supportsReasoningEffort = looksLikeByokReasoningModel(id),
                supportsToolCalling = true,
                supportsImageGeneration = false,
                supportsChat = true,
                providerId = provider.id,
                providerName = provider.name,
                providerKind = ProviderKind.BYOK,
                thinkingConfig = thinkingConfigFor(provider, id),
            )
        }
    }

    /** image 用途：仅保留图像模型，并按名称标记 supportsImageEdit。 */
    private fun parseImageModels(root: JsonObject, provider: ByokProvider): List<ProviderModel> {
        val data = (root["data"] as? JsonArray)
            ?: (root["models"] as? JsonArray)
            ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val rawName = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val id = rawName.removePrefix("models/")
            if (id.isBlank() || !looksLikeByokImageModel(id)) return@mapNotNull null
            // 对话补全出图格式（openai-chat-image）下，模型仍由名称判定， apiUrl 指向 chatPath。
            val apiUrl = if (provider.imageFormat == ByokImageFormat.OPENAI_CHAT_IMAGE) provider.chatPath
                else provider.imagePath
            ProviderModel(
                id = id,
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["display_name"]?.jsonPrimitive?.contentOrNull
                    ?: beautifyModelName(id),
                apiUrl = apiUrl,
                supportsVision = false,
                supportsThinking = false,
                supportsReasoningEffort = false,
                supportsToolCalling = false,
                supportsImageGeneration = true,
                supportsImageEdit = looksLikeByokImageEditModel(id),
                supportsChat = false,
                providerId = provider.id,
                providerName = provider.name,
                providerKind = ProviderKind.BYOK,
            )
        }.sortedBy { it.id }
    }

    private fun beautifyModelName(id: String): String =
        id.substringAfterLast('/').replace('_', ' ')

    /**
     * 据模型 ID + provider 推断推理配置（仅对推理模型返回非 null）。
     * OpenRouter 统一走 reasoning:{effort} 对象，忽略模型家族差异；
     * 其余 provider 先按模型 ID 推断，漏掉时按 host 兜底，再退到 OPENAI_REASONING_EFFORT。
     */
    private fun thinkingConfigFor(provider: ByokProvider, id: String): ThinkingConfig? {
        if (!looksLikeByokReasoningModel(id)) return null
        val kind = if (ThinkingKinds.isOpenRouter(provider.baseUrl)) {
            ThinkingParamKind.OPENAI_REASONING_EFFORT
        } else {
            ThinkingKinds.inferFromModelId(id).takeIf { it != ThinkingParamKind.NONE }
                ?: ThinkingKinds.hostInferredKind(provider.baseUrl)
                ?: ThinkingParamKind.OPENAI_REASONING_EFFORT
        }
        return ThinkingKinds.configFor(kind)
    }
}

internal fun looksLikeByokChatModel(id: String): Boolean {
    val lower = id.lowercase()
    // 排除非聊天模型：embedding/moderation/tts/transcribe/whisper/audio/realtime/computer-use/
    // deep-research/live/veo/lyria，以及所有图像模型。
    if (listOf(
            "embedding", "moderation", "tts", "transcribe", "whisper", "audio", "realtime",
            "computer-use", "deep-research", "live", "veo", "lyria", "imagen",
        ).any { lower.contains(it) }
    ) {
        return false
    }
    if (looksLikeByokImageModel(id)) return false
    val leaf = lower.substringAfterLast('/')
    val chatPrefixes = listOf(
        "gpt-", "gpt5", "o1", "o3", "o4", "deepseek", "moonshot", "kimi", "qwen", "qwq",
        "gemini", "claude", "llama", "mistral", "mixtral", "grok", "glm", "yi-", "ernie",
    )
    return chatPrefixes.any { leaf.startsWith(it) } ||
        listOf("/gpt-", "/deepseek", "/moonshot", "/kimi", "/qwen", "/gemini", "/claude", "/llama", "/mistral", "/grok", "/glm").any {
            lower.contains(it)
        } ||
        lower.contains("chat") ||
        lower.contains("instruct")
}

/**
 * 图像生成模型启发式判定（覆盖 OpenRouter 全量图像输出清单 + 常见原生图像模型）。
 * 按家族列匹配，ID 含任一即判为图像模型：
 *  - OpenAI: gpt-image-*, gpt-5-image / gpt-5.4-image / gpt-5.1-image 等 gpt-N.N-image-*, dall-e-*
 *  - Google: imagen-*, gemini-*-flash-image, gemini-*-pro-image, gemini-3.1-*-image
 *  - Black Forest Labs: flux / flux.2-*（含 klein/max/pro/flex）
 *  - ByteDance: seedream-*
 *  - Microsoft: mai-image-*
 *  - Recraft: recraft-*
 *  - Stability: sdxl, stable-diffusion
 *  - Sourceful (OpenRouter): riverflow-*
 *  - Midjourney: midjourney-*
 *  - xAI: grok-imagine-* (Grok Imagine)
 *  - 通用兜底: *-image-generation, image generation 短语
 * 不用裸 `image` 子串——避免误伤视觉理解模型 / 带 image 的对话模型。
 */
internal fun looksLikeByokImageModel(id: String): Boolean {
    val lower = id.lowercase()
    val leaf = lower.substringAfterLast('/')
    // OpenAI 图像：gpt-image-1 / gpt-5-image / gpt-5.4-image-2 / gpt-5.1-image-mini ...
    if (Regex("gpt-image").containsMatchIn(lower)) return true
    if (Regex("gpt-\\d+(?:\\.\\d+)?-image").containsMatchIn(lower)) return true
    if (lower.contains("dall-e") || lower.contains("dall·e")) return true
    // Google
    if (lower.contains("imagen")) return true
    if (Regex("gemini-.*-(flash|pro)-image").containsMatchIn(lower)) return true
    // Black Forest Labs flux（flux / flux.2-flex / flux.2-pro / flux-klein ...）
    if (Regex("flux[.0-9-]*").matches(leaf) || lower.contains("flux")) return true
    // ByteDance
    if (lower.contains("seedream")) return true
    // Microsoft
    if (Regex("mai-image").containsMatchIn(lower)) return true
    // Recraft / Stability / Sourceful / Midjourney / xAI Grok Imagine
    if (lower.contains("recraft") || lower.contains("sdxl") ||
        lower.contains("stable-diffusion") || lower.contains("riverflow") ||
        lower.contains("midjourney") || lower.contains("grok-imagine")) return true
    // 通用兜底：image generation / image-generation 短语
    if (lower.contains("image-generation") || lower.contains("image generation")) return true
    return false
}

/**
 * 推理/思考模型启发式判定。借鉴 Cherry Studio 的 REASONING_REGEX + 厂商标识：
 *  - o 系列推理模型（o1/o3/o4，排除 o1-preview/o1-mini 仍按推理）
 *  - 含 reasoning/reasoner/thinking/think 的 id
 *  - "-R\d" 后缀（如 -r1）
 *  - qwq / hunyuan-t1 / glm-zero-preview
 *  - grok-3-mini / grok-4 / grok-4-fast
 *  - gpt-5 系列、gpt-oss
 *  - DeepSeek 混合推理：deepseek-v3.x / v4+ / deepseek-chat / deepseek-reasoner / deepseek-r1
 *  - Kimi：kimi-k2-thinking / kimi-k2.5 及更新 / kimi-k3+
 *  - Qwen3 / QwQ / GLM-5 / GLM-4.x / MiniMax-M2/3 / MiMo / magistral / seed-oss / gemma-4 / pangu-pro-moe
 * 非 `-non-reasoning` 变体被显式排除。
 */
internal fun looksLikeByokReasoningModel(id: String): Boolean {
    val lower = id.lowercase()
    if (lower.contains("-non-reasoning")) return false
    val leaf = lower.substringAfterLast('/')

    // DeepSeek 混合推理：deepseek-v3.2（含 .x 子版本）、deepseek-v4+、deepseek-chat、reasoner、r1。
    if (Regex("(\\w+-)?deepseek-v(?:3(?:\\.\\d|\\d)|[4-9]\\d*|[1-9]\\d{1,})(?:[.-]\\w+)*").containsMatchIn(lower) ||
        lower.contains("deepseek-chat") || lower.contains("deepseek-reasoner") ||
        lower.contains("deepseek-r1")) return true

    // Kimi：kimi-k2-thinking(+turbo)、kimi-k2.5+、kimi-k3+。
    if (Regex("kimi-k(?:2-thinking(?:-turbo)?|2\\.[5-9]\\d*|[3-9]\\d*(?:\\.\\d+)?)").containsMatchIn(leaf)) return true

    return leaf.startsWith("o1") || leaf.startsWith("o3") || leaf.startsWith("o4") ||
        leaf.startsWith("gpt-5") || lower.contains("gpt-oss") ||
        lower.contains("reasoning") || lower.contains("reasoner") ||
        lower.contains("thinking") || lower.contains("think") ||
        Regex("-(?:r|R)\\d+").containsMatchIn(leaf) ||
        lower.contains("qwq") || lower.contains("qwen3") ||
        lower.contains("hunyuan-t1") || lower.contains("glm-zero-preview") ||
        lower.contains("glm-5") || Regex("glm-4\\.\\d").containsMatchIn(lower) ||
        Regex("minimax-m[23]").containsMatchIn(lower) || lower.contains("mimo-v2-flash") ||
        lower.contains("grok-3-mini") || lower.contains("grok-4") ||
        lower.contains("gemini-2.5") || lower.contains("gemini-3") ||
        lower.contains("claude-3-7") || lower.contains("claude-3.7") ||
        lower.contains("sonnet-4") || lower.contains("opus-4") || lower.contains("haiku-4") ||
        lower.contains("magistral") || lower.contains("seed-oss") ||
        lower.contains("gemma-4") || lower.contains("pangu-pro-moe")
}

internal fun looksLikeByokVisionModel(id: String): Boolean {
    val lower = id.lowercase()
    return lower.contains("vision") || lower.contains("gpt-4o") || lower.contains("gpt-4.1") ||
        lower.contains("gpt-5") || lower.contains("gemini") ||
        lower.contains("claude-3") || lower.contains("sonnet-4") || lower.contains("opus-4") ||
        lower.contains("haiku-4") || lower.contains("deepseek-chat") || lower.contains("qwen-vl")
}

/**
 * 图像「编辑」能力启发式：仅判定 OpenRouter 已知支持编辑/重绘的图像模型家族。
 * 避免裸 `edit` 子串误伤带 edited/editable 后缀的对话模型。
 *  - gpt-image-1 / gpt-5-image 系列（OpenAI 原生多模态编辑）
 *  - imagen-4（Imagen edit）
 *  - gemini flash-image / pro-image（Nano Banana 系列支持原生编辑）
 *  - recraft-v3 / v4 及以上（Recraft 支持 inpaint/edit）
 *  - grok-imagine（Grok Imagine 编辑）
 *  - 明确 -edit / -inpaint / -outpaint 后缀
 */
internal fun looksLikeByokImageEditModel(id: String): Boolean {
    val lower = id.lowercase()
    if (lower.contains("gpt-image") || Regex("gpt-\\d+(?:\\.\\d+)?-image").containsMatchIn(lower)) return true
    if (lower.contains("imagen")) return true
    if (Regex("gemini-.*-(flash|pro)-image").containsMatchIn(lower)) return true
    if (Regex("recraft-v[3-9]").containsMatchIn(lower)) return true
    if (lower.contains("grok-imagine")) return true
    if (Regex("-(edit|inpaint|outpaint)\\b").containsMatchIn(lower)) return true
    return false
}

/**
 * 图像模型「推理」能力启发式：判定哪些图像模型支持 OpenRouter 的 reasoning.effort 参数。
 * 依据：OpenRouter /api/v1/models 的 supported_parameters 含 "reasoning" 的图像模型。
 *  - OpenAI: gpt-image-1 / gpt-5-image / gpt-5-image-mini / gpt-5.4-image-2 等 gpt-N.N-image-*
 *  - Google: gemini-3.x-*-image（Nano Banana 2 系列；gemini-2.5-flash-image 不支持，故仅匹配 3+）
 * 其余图像模型（flux / seedream / recraft / dall-e / gemini-2.5-image 等）不支持推理。
 *
 * public 供 UI（单模型配置页）据模型 ID 决定是否展示推理强度控件。
 */
fun looksLikeByokImageReasoningModel(id: String): Boolean {
    val lower = id.lowercase()
    if (lower.contains("gpt-image")) return true
    if (Regex("gpt-\\d+(?:\\.\\d+)?-image").containsMatchIn(lower)) return true
    if (Regex("gemini-3(?:\\.\\d+)?-.*-image").containsMatchIn(lower)) return true
    return false
}

internal fun looksLikeByokToolModel(id: String): Boolean {
    val lower = id.lowercase()
    return looksLikeByokChatModel(id) && !lower.contains("base") && !lower.contains("instruct")
}

fun ByokProvider.endpoint(path: String): String =
    baseUrl.trimEnd('/') + "/" + path.trimStart('/')

fun ByokProvider.modelsEndpoint(): String {
    val url = endpoint(modelsPath)
    if (type != ByokProviderType.GEMINI) return url
    val key = apiKey?.takeIf { it.isNotBlank() } ?: return url
    val separator = if (url.contains("?")) "&" else "?"
    return "$url${separator}key=${URLEncoder.encode(key, Charsets.UTF_8.name())}"
}

fun ByokProvider.applyAuthHeaders(add: (String, String) -> Unit) {
    val key = apiKey?.takeIf { it.isNotBlank() } ?: return
    when (type) {
        ByokProviderType.ANTHROPIC -> {
            add("x-api-key", key)
            add("anthropic-version", "2023-06-01")
        }
        ByokProviderType.GEMINI -> Unit
        ByokProviderType.OPENAI_COMPAT, ByokProviderType.OPENAI_RESPONSE -> add("Authorization", "Bearer $key")
    }
}
