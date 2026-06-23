package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ImageGenerationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ByokImageApi(private val http: MolaHttp) {
    fun generate(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        size: String,
        imageConfig: ImageGenerationConfig? = null,
    ): ByokImageResult = when (provider.type) {
        ByokProviderType.GEMINI -> generateGemini(provider, modelId, prompt)
        ByokProviderType.ANTHROPIC -> throw MolaApiException(400, "${provider.name} 暂未配置图像生成端点")
        ByokProviderType.OPENAI_COMPAT, ByokProviderType.OPENAI_RESPONSE -> when (provider.imageFormat) {
            ByokImageFormat.OPENAI_CHAT_IMAGE -> generateViaChatCompletions(provider, modelId, prompt, size, imageConfig)
            ByokImageFormat.OPENAI_IMAGES -> generateOpenAiCompatible(provider, modelId, prompt, size)
        }
    }

    private fun generateOpenAiCompatible(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        size: String,
    ): ByokImageResult {
        val body = buildJsonObject {
            put("model", modelId)
            put("prompt", prompt)
            put("size", size.ifBlank { "1024x1024" })
            put("n", 1)
        }
        val req = Request.Builder()
            .url(provider.endpoint(provider.imagePath.ifBlank { "v1/images/generations" }))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, "图像生成失败：HTTP ${resp.code} ${text.take(160)}")
            }
            return parseOpenAiImageResult(text)
        }
    }

    /**
     * 对话补全出图（openai-chat-image 格式）：POST chatPath，让模型在回复里产出图片 URL/base64。
     * 适用 OpenRouter nano-banana / gpt-5-image 等通过 chat/completions 返回图像的 provider。
     *
     * OpenRouter 图像 API 与 OpenAI /v1/images/generations 不同——统一走 /v1/chat/completions：
     * - modalities: ["image","text"] 声明图像输出
     * - image_config: { image_size, aspect_ratio } 控制分辨率与宽高比
     * - reasoning: { effort, exclude } 控制推理强度（仅 GPT-5 Image / Gemini 3 Image 系列生效）
     */
    private fun generateViaChatCompletions(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        size: String,
        imageConfig: ImageGenerationConfig?,
    ): ByokImageResult {
        val body = buildJsonObject {
            put("model", modelId)
            put("stream", false)
            // 声明图像输出模态；OpenRouter 据此返回 message.images[]。
            putJsonArray("modalities") {
                add("image")
                add("text")
            }
            // image_config：OpenRouter 出图分辨率 + 宽高比（仅 chat-completions 格式有效）。
            // 校验兜底——历史持久化值（如旧的 "1024x1024"）或脏输入不在合法集内时回退默认，避免 400 invalid_value。
            val cfg = imageConfig ?: ImageGenerationConfig()
            val size = cfg.imageSize.takeIf { it in ImageGenerationConfig.IMAGE_SIZES } ?: "1K"
            val ratio = cfg.aspectRatio.takeIf { it in ImageGenerationConfig.ASPECT_RATIOS } ?: "1:1"
            putJsonObject("image_config") {
                put("image_size", size)
                put("aspect_ratio", ratio)
            }
            // 推理强度：仅当模型支持且用户开启时发送，避免对不支持的模型报错。
            if (imageConfig != null && imageConfig.reasoning) {
                putJsonObject("reasoning") {
                    put("effort", imageConfig.reasoningEffort.ifBlank { "medium" })
                    put("exclude", true)
                }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    // chat-completions 出图：尺寸由 image_config 承载，prompt 仅保留文本与风格提示。
                    // size（OpenAI 原生 "WxH"）在此格式下无作用，仅在用户显式填写时附作提示。
                    put("content", prompt + if (size.isBlank()) "" else "（尺寸 $size）")
                }
            }
        }
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath.ifBlank { "v1/chat/completions" }))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, "图像生成失败：HTTP ${resp.code} ${text.take(160)}")
            }
            return parseChatCompletionsImageResult(text)
        }
    }

    private fun generateGemini(provider: ByokProvider, modelId: String, prompt: String): ByokImageResult {
        val path = provider.imagePath.ifBlank { provider.chatPath }
            .replace("{model}", modelId)
            .replace(":streamGenerateContent", ":generateContent")
        val key = provider.apiKey?.takeIf { it.isNotBlank() }
        val separator = if (path.contains("?")) "&" else "?"
        val url = provider.endpoint(path) + if (key.isNullOrBlank()) {
            ""
        } else {
            "${separator}key=${URLEncoder.encode(key, Charsets.UTF_8.name())}"
        }
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
            put("generationConfig", buildJsonObject {
                putJsonArray("responseModalities") {
                    add("IMAGE")
                    add("TEXT")
                }
            })
        }
        val req = Request.Builder()
            .url(url)
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, "图像生成失败：HTTP ${resp.code} ${text.take(160)}")
            }
            return parseGeminiImageResult(text)
        }
    }

    private fun parseOpenAiImageResult(text: String): ByokImageResult {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ByokImageResult(raw = text.take(4000))
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw MolaApiException(400, message)
        }
        val data = root["data"] as? JsonArray ?: return ByokImageResult(raw = text.take(4000))
        val first = data.firstOrNull() as? JsonObject ?: return ByokImageResult(raw = text.take(4000))
        first["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            return ByokImageResult(url = it, raw = text.take(4000))
        }
        first["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            return ByokImageResult(url = "data:image/png;base64,$it", raw = text.take(4000))
        }
        return ByokImageResult(raw = text.take(4000))
    }

    private fun parseGeminiImageResult(text: String): ByokImageResult {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ByokImageResult(raw = text.take(4000))
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw MolaApiException(400, message)
        }
        val candidates = root["candidates"] as? JsonArray ?: return ByokImageResult(raw = text.take(4000))
        val parts = candidates.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts") as? JsonArray
            ?: return ByokImageResult(raw = text.take(4000))
        parts.forEach { part ->
            val inline = part.jsonObject["inlineData"]?.jsonObject ?: part.jsonObject["inline_data"]?.jsonObject
            val data = inline?.get("data")?.jsonPrimitive?.contentOrNull
            if (!data.isNullOrBlank()) {
                val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull
                    ?: inline["mime_type"]?.jsonPrimitive?.contentOrNull
                    ?: "image/png"
                return ByokImageResult(url = "data:$mime;base64,$data", raw = text.take(4000))
            }
        }
        val textParts = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        return ByokImageResult(raw = textParts.joinToString("\n").ifBlank { text.take(4000) })
    }

    private fun parseChatCompletionsImageResult(text: String): ByokImageResult {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ByokImageResult(raw = text.take(4000))
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw MolaApiException(400, message)
        }
        val choices = root["choices"] as? JsonArray
        val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        // 1) OpenRouter/nano-banana/gpt-5-image 常把图片放 message.images[]：
        //    - 旧格式: images[].url
        //    - OpenRouter image_url 格式: images[].image_url.url
        val images = message?.get("images") as? JsonArray
        images?.firstOrNull()?.jsonObject?.let { img ->
            img["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { return ByokImageResult(url = it, raw = text.take(4000)) }
            img["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { return ByokImageResult(url = it, raw = text.take(4000)) }
        }
        // 2) 否则从 content 文本里抽取 http(s) URL 或 data:image base64。
        //    content 可能是字符串，也可能是多模态对象/数组——仅当为字符串时抽取。
        val content = runCatching { message?.get("content")?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (!content.isNullOrBlank()) {
            extractImageUrl(content)?.let { return ByokImageResult(url = it, raw = text.take(4000)) }
        }
        return ByokImageResult(raw = content ?: text.take(4000))
    }

    /** 从文本里抽取第一张图片：data:image base64 或 http(s) URL。 */
    private fun extractImageUrl(text: String): String? {
        val dataIdx = text.indexOf("data:image/")
        if (dataIdx >= 0) {
            val comma = text.indexOf(',', dataIdx)
            if (comma > dataIdx) return text.substring(dataIdx, minOf(text.length, comma + 1 + 1_500_000)).trim()
        }
        val httpMatch = Regex("""https?://\S+\.(?:png|jpe?g|webp|gif)\b""", RegexOption.IGNORE_CASE).find(text)
        return httpMatch?.value
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class ByokImageResult(
    val url: String? = null,
    val raw: String? = null,
)
