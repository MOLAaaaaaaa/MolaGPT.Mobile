package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ImageGenerationConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

    fun runWorkbench(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
    ): ByokImageWorkbenchResult {
        if (prompt.isBlank()) throw MolaApiException(400, "请填写 Prompt")
        if (modelId.isBlank()) throw MolaApiException(400, "请选择模型")
        return when (provider.type) {
            ByokProviderType.GEMINI -> runGeminiWorkbench(provider, modelId, prompt, config, attachments)
            ByokProviderType.ANTHROPIC -> throw MolaApiException(400, "${provider.name} 暂未配置图像生成端点")
            ByokProviderType.OPENAI_RESPONSE,
            ByokProviderType.OPENAI_COMPAT -> when {
                provider.imageFormat == ByokImageFormat.OPENAI_CHAT_IMAGE -> runChatImageWorkbench(provider, modelId, prompt, config, attachments)
                attachments.isEmpty() -> runOpenAiImageGenerations(provider, modelId, prompt, config)
                config.batchMode && attachments.size >= 2 -> runOpenAiBatchEdits(provider, modelId, prompt, config, attachments)
                attachments.size == 1 -> runOpenAiSingleEdit(provider, modelId, prompt, config, attachments.first())
                else -> runChatImageWorkbench(provider, modelId, prompt, config, attachments)
            }
        }
    }

    private fun runOpenAiImageGenerations(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
    ): ByokImageWorkbenchResult {
        val hits = mutableListOf<ByokImageHit>()
        val raws = mutableListOf<String>()
        val count = config.n.coerceIn(1, 8)
        repeat(count) { index ->
            val body = buildJsonObject {
                put("model", modelId)
                put("prompt", prompt)
                put("n", 1)
                put("size", config.size.ifBlank { "1024x1024" })
                put("response_format", "b64_json")
                putOpenAiImageExtras(config)
            }
            val raw = executeJson(provider, provider.imagePath.ifBlank { "v1/images/generations" }, body, config.timeoutSeconds)
            hits += extractImageHits(raw).mapIndexed { hitIndex, hit ->
                hit.copy(label = if (count > 1) "${index + 1}.${hitIndex + 1}" else hit.label)
            }
            raws += previewResponse(raw)
        }
        return ByokImageWorkbenchResult(
            hits = hits,
            raw = raws.joinToString("\n\n--- response ---\n\n"),
            requestCount = count,
            status = if (hits.isEmpty()) "未识别到图片" else "生成完成",
        )
    }

    private fun runOpenAiSingleEdit(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachment: ByokImageAttachment,
    ): ByokImageWorkbenchResult {
        val bypassEdits = modelId.contains("pro", ignoreCase = true) && config.sizeMaxEdge >= 1600
        val primary = if (bypassEdits) {
            WorkbenchEndpoint.Json(
                path = provider.chatPath.ifBlank { "v1/chat/completions" },
                body = buildChatEditBody(modelId, prompt, config, listOf(attachment)),
                timeoutSeconds = config.timeoutSeconds,
            )
        } else {
            WorkbenchEndpoint.Multipart(
                path = provider.imageEditPath.ifBlank { "v1/images/edits" },
                body = buildEditMultipart(modelId, prompt, config, attachment),
                timeoutSeconds = config.timeoutSeconds,
            )
        }
        val fallback = if (bypassEdits) null else WorkbenchEndpoint.Json(
            path = provider.chatPath.ifBlank { "v1/chat/completions" },
            body = buildChatEditBody(modelId, prompt, config, listOf(attachment)),
            timeoutSeconds = config.timeoutSeconds,
        )
        val result = executeEndpointWithFallback(provider, primary, fallback)
        val hits = extractImageHits(result.raw)
        return ByokImageWorkbenchResult(
            hits = hits,
            raw = previewResponse(result.raw),
            usedFallback = result.usedFallback,
            requestCount = 1,
            status = if (hits.isEmpty()) "未识别到图片" else "生成完成",
        )
    }

    private fun runOpenAiBatchEdits(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
    ): ByokImageWorkbenchResult {
        val hits = mutableListOf<ByokImageHit>()
        val raws = mutableListOf<String>()
        var fallbackUsed = false
        attachments.forEachIndexed { index, attachment ->
            val result = runOpenAiSingleEdit(
                provider = provider,
                modelId = modelId,
                prompt = prompt,
                config = config.copy(n = 1, batchMode = false),
                attachment = attachment,
            )
            raws += "#${index + 1}\n${result.raw}"
            fallbackUsed = fallbackUsed || result.usedFallback
            hits += result.hits.map { it.copy(label = "${index + 1}") }
        }
        return ByokImageWorkbenchResult(
            hits = hits,
            raw = raws.joinToString("\n\n--- batch item ---\n\n"),
            usedFallback = fallbackUsed,
            requestCount = attachments.size,
            status = "批处理完成 ${hits.size}/${attachments.size}",
        )
    }

    private fun runChatImageWorkbench(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
    ): ByokImageWorkbenchResult {
        val count = if (attachments.isEmpty()) config.n.coerceIn(1, 8) else 1
        val hits = mutableListOf<ByokImageHit>()
        val raws = mutableListOf<String>()
        repeat(count) { index ->
            val body = if (attachments.isEmpty()) {
                buildChatGenerationBody(modelId, prompt, config)
            } else {
                buildChatEditBody(modelId, prompt, config, attachments)
            }
            val raw = executeJson(provider, provider.chatPath.ifBlank { "v1/chat/completions" }, body, config.timeoutSeconds)
            hits += extractImageHits(raw).mapIndexed { hitIndex, hit ->
                hit.copy(label = if (count > 1) "${index + 1}.${hitIndex + 1}" else hit.label)
            }
            raws += previewResponse(raw)
        }
        return ByokImageWorkbenchResult(
            hits = hits,
            raw = raws.joinToString("\n\n--- response ---\n\n"),
            requestCount = count,
            status = if (hits.isEmpty()) "未识别到图片" else "生成完成",
        )
    }

    private fun runGeminiWorkbench(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
    ): ByokImageWorkbenchResult {
        val fullPrompt = if (config.size.isBlank()) prompt else "$prompt\n\nOutput size: ${config.size}."
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
                        addJsonObject { put("text", fullPrompt) }
                        attachments.forEach { attachment ->
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", attachment.mimeType.ifBlank { "image/png" })
                                    put("data", android.util.Base64.encodeToString(attachment.bytes, android.util.Base64.NO_WRAP))
                                }
                            }
                            if (attachment.maskedOverlayBytes != null) {
                                addJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", "image/png")
                                        put("data", android.util.Base64.encodeToString(attachment.maskedOverlayBytes, android.util.Base64.NO_WRAP))
                                    }
                                }
                            }
                        }
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
                throw MolaApiException(resp.code, "图像生成失败：HTTP ${resp.code} ${text.take(240)}")
            }
            val hits = extractImageHits(text)
            return ByokImageWorkbenchResult(
                hits = hits,
                raw = text.take(100_000),
                requestCount = 1,
                status = if (hits.isEmpty()) "未识别到图片" else "生成完成",
            )
        }
    }

    private fun executeEndpointWithFallback(
        provider: ByokProvider,
        primary: WorkbenchEndpoint,
        fallback: WorkbenchEndpoint?,
    ): EndpointResult {
        val primaryResult = runCatching { executeEndpoint(provider, primary) }
        val raw = primaryResult.getOrElse { error ->
            if (fallback == null) throw error
            return EndpointResult(raw = executeEndpoint(provider, fallback), usedFallback = true)
        }
        return EndpointResult(raw = raw, usedFallback = false)
    }

    private fun executeEndpoint(provider: ByokProvider, endpoint: WorkbenchEndpoint): String =
        when (endpoint) {
            is WorkbenchEndpoint.Json -> executeJson(provider, endpoint.path, endpoint.body, endpoint.timeoutSeconds)
            is WorkbenchEndpoint.Multipart -> executeMultipart(provider, endpoint.path, endpoint.body, endpoint.timeoutSeconds)
        }

    private fun executeJson(provider: ByokProvider, path: String, body: JsonObject, timeoutSeconds: Int = 600): String {
        val req = Request.Builder()
            .url(provider.endpoint(path))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val client = http.okHttp.newBuilder()
            .callTimeout(timeoutSeconds.coerceIn(10, 3600).toLong(), TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, "图像请求失败：HTTP ${resp.code} ${extractErrorMessage(text)}")
            }
            return text
        }
    }

    private fun executeMultipart(provider: ByokProvider, path: String, body: MultipartBody, timeoutSeconds: Int = 600): String {
        val req = Request.Builder()
            .url(provider.endpoint(path))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(body)
            .build()
        val client = http.okHttp.newBuilder()
            .callTimeout(timeoutSeconds.coerceIn(10, 3600).toLong(), TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, "图像编辑失败：HTTP ${resp.code} ${extractErrorMessage(text)}")
            }
            return text
        }
    }

    private fun previewResponse(text: String, limit: Int = 20_000): String {
        if (text.length <= limit) return text
        val head = text.take(12_000)
        val tail = text.takeLast(2_000)
        return "$head\n\n… 已省略 ${text.length - 14_000} 个字符，仅保留调试摘要 …\n\n$tail"
    }

    private fun buildEditMultipart(
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachment: ByokImageAttachment,
    ): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", modelId)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("response_format", "b64_json")
            .addFormDataPart("size", config.size.ifBlank { "1024x1024" })
        addMultipartExtras(builder, config)
        builder.addFormDataPart(
            "image",
            attachment.fileName.ifBlank { "image.png" },
            attachment.bytes.toRequestBody((attachment.mimeType.ifBlank { "image/png" }).toMediaTypeOrNull()),
        )
        attachment.maskPngBytes?.let { mask ->
            builder.addFormDataPart(
                "mask",
                "mask.png",
                mask.toRequestBody("image/png".toMediaType()),
            )
        }
        return builder.build()
    }

    private fun buildChatGenerationBody(
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("stream", false)
        putJsonArray("modalities") {
            add("image")
            add("text")
        }
        putJsonObject("image_config") {
            put("image_size", imageSizeTier(config.size))
            put("aspect_ratio", aspectRatioForSize(config.size))
        }
        if (config.reasoning) {
            putJsonObject("reasoning") {
                put("effort", config.reasoningEffort.ifBlank { "medium" })
                put("exclude", true)
            }
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", prompt + sizeSentence(config))
            }
        }
    }

    private fun buildChatEditBody(
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
    ): JsonObject {
        val anyMasked = attachments.any { it.maskedOverlayBytes != null || it.maskPngBytes != null }
        val header = when {
            attachments.isEmpty() -> prompt + sizeSentence(config)
            attachments.size == 1 && anyMasked ->
                "You are given two attached images: the FIRST is the original; the SECOND is the same image with a semi-transparent red overlay marking the ONLY region you may modify. Treat the red overlay as an instruction, NOT as image content. Modify ONLY pixels inside the red region; every pixel outside must remain pixel-identical to the original. ${sizeDirective(config)}\n\nInstruction:\n$prompt"
            attachments.size == 1 ->
                "Edit the attached image as described. ${sizeDirective(config)}\n\nInstruction:\n$prompt"
            anyMasked ->
                "Attached are ${attachments.size} reference image(s). For any image immediately followed by a duplicate with a semi-transparent red overlay, the red overlay marks the ONLY region to edit in that image; treat it as an instruction, not image content. ${sizeDirective(config)}\n\nInstruction:\n$prompt"
            else ->
                "Attached are ${attachments.size} reference images. Treat them as visual context for the instruction below. Output one image.${sizeSentence(config)}\n\nInstruction:\n$prompt"
        }
        return buildJsonObject {
            put("model", modelId)
            put("stream", false)
            putJsonArray("modalities") {
                add("image")
                add("text")
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", header)
                        }
                        attachments.forEach { attachment ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", attachment.dataUrl())
                                }
                            }
                            attachment.maskedOverlayBytes?.let { overlay ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", dataUrl("image/png", overlay))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putOpenAiImageExtras(config: ByokImageWorkbenchConfig) {
        if (config.quality != "auto") put("quality", config.quality)
        if (config.background != "auto") put("background", config.background)
        if (config.outputFormat != "png") put("output_format", config.outputFormat)
        if ((config.outputFormat == "jpeg" || config.outputFormat == "webp") && config.outputCompression in 0..100) {
            put("output_compression", config.outputCompression)
        }
        if (config.moderation != "auto") put("moderation", config.moderation)
    }

    private fun addMultipartExtras(builder: MultipartBody.Builder, config: ByokImageWorkbenchConfig) {
        fun add(key: String, value: Any) = builder.addFormDataPart(key, value.toString())
        if (config.quality != "auto") add("quality", config.quality)
        if (config.background != "auto") add("background", config.background)
        if (config.outputFormat != "png") add("output_format", config.outputFormat)
        if ((config.outputFormat == "jpeg" || config.outputFormat == "webp") && config.outputCompression in 0..100) {
            add("output_compression", config.outputCompression)
        }
        if (config.moderation != "auto") add("moderation", config.moderation)
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

    private fun extractImageHits(text: String): List<ByokImageHit> {
        val root = runCatching { http.json.parseToJsonElement(text) }.getOrNull()
        val hits = if (root != null) extractImageHits(root) else emptyList()
        if (hits.isNotEmpty()) return hits
        return findImagesInText(text)
    }

    private fun extractImageHits(element: JsonElement): List<ByokImageHit> {
        val hits = mutableListOf<ByokImageHit>()
        when (element) {
            is JsonObject -> {
                element["error"]?.jsonObject?.let { error ->
                    val message = error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
                    throw MolaApiException(400, message)
                }
                val direct = listOf("url", "image_url", "b64_json", "image", "src")
                direct.forEach { key ->
                    val value = element[key]
                    when {
                        value == null -> Unit
                        key == "image_url" && value is JsonObject -> {
                            value["url"]?.jsonPrimitive?.contentOrNull?.let { hits += hitFromValue(it) }
                        }
                        else -> runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()?.let { hits += hitFromValue(it) }
                    }
                }
                element["inlineData"]?.jsonObject?.let { inline ->
                    val data = inline["data"]?.jsonPrimitive?.contentOrNull
                    if (!data.isNullOrBlank()) {
                        val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull
                            ?: inline["mime_type"]?.jsonPrimitive?.contentOrNull
                            ?: "image/png"
                        hits += ByokImageHit(url = dataUrl(mime, data), isData = true)
                    }
                }
                element["inline_data"]?.jsonObject?.let { inline ->
                    val data = inline["data"]?.jsonPrimitive?.contentOrNull
                    if (!data.isNullOrBlank()) {
                        val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull
                            ?: inline["mime_type"]?.jsonPrimitive?.contentOrNull
                            ?: "image/png"
                        hits += ByokImageHit(url = dataUrl(mime, data), isData = true)
                    }
                }
                element["text"]?.jsonPrimitive?.contentOrNull?.let { hits += findImagesInText(it) }
                element["content"]?.let { content ->
                    val asText = runCatching { content.jsonPrimitive.contentOrNull }.getOrNull()
                    if (!asText.isNullOrBlank()) hits += findImagesInText(asText)
                    if (content is JsonArray) content.forEach { hits += extractImageHits(it) }
                    if (content is JsonObject) hits += extractImageHits(content)
                }
                listOf("data", "choices", "output", "candidates", "images", "parts").forEach { key ->
                    when (val child = element[key]) {
                        is JsonArray -> child.forEach { hits += extractImageHits(it) }
                        is JsonObject -> hits += extractImageHits(child)
                        else -> Unit
                    }
                }
                element["message"]?.jsonObject?.let { hits += extractImageHits(it) }
            }
            is JsonArray -> element.forEach { hits += extractImageHits(it) }
            else -> Unit
        }
        return hits.distinctBy { it.url }
    }

    private fun hitFromValue(value: String): ByokImageHit {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("data:image/", ignoreCase = true) -> ByokImageHit(url = normalizeDataUrl(trimmed), isData = true)
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) ->
                ByokImageHit(url = trimmed, isData = false)
            trimmed.length > 200 && trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it.isWhitespace() } ->
                ByokImageHit(url = dataUrl("image/png", trimmed.filterNot { it.isWhitespace() }), isData = true)
            else -> ByokImageHit(url = trimmed, isData = false)
        }
    }

    private fun findImagesInText(text: String): List<ByokImageHit> {
        val hits = mutableListOf<ByokImageHit>()
        Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            hits += hitFromValue(match.groupValues[1])
        }
        Regex("""!\[[^\]]*]\((https?://[^)\s]+)\)""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            hits += ByokImageHit(url = match.groupValues[1], isData = false)
        }
        Regex("""data:image/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            hits += ByokImageHit(url = normalizeDataUrl(match.value), isData = true)
        }
        Regex("""https?://[^\s"'<>)]+?\.(?:png|jpe?g|webp|gif)(?:\?[^\s"'<>)]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { match -> hits += ByokImageHit(url = match.value, isData = false) }
        if (hits.isEmpty()) {
            Regex("""[A-Za-z0-9+/=]{200,}""").find(text)?.let { match ->
                hits += ByokImageHit(url = dataUrl("image/png", match.value), isData = true)
            }
        }
        return hits.distinctBy { it.url }
    }

    private fun normalizeDataUrl(raw: String): String {
        val comma = raw.indexOf(',')
        if (comma < 0) return raw.trim()
        val meta = raw.substring(0, comma)
        val body = raw.substring(comma + 1).filterNot { it.isWhitespace() }
        return "$meta,$body"
    }

    private fun ByokImageAttachment.dataUrl(): String = dataUrl(mimeType.ifBlank { "image/png" }, bytes)

    private fun dataUrl(mimeType: String, bytes: ByteArray): String =
        dataUrl(mimeType, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))

    private fun dataUrl(mimeType: String, base64: String): String =
        "data:$mimeType;base64,${base64.filterNot { it.isWhitespace() }}"

    private fun extractErrorMessage(text: String): String {
        val obj = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
        val msg = obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
        return (msg ?: text).take(240)
    }

    private fun imageSizeTier(size: String): String {
        val edge = maxEdge(size)
        return when {
            edge >= 3000 -> "4K"
            edge >= 1600 -> "2K"
            edge > 0 && edge < 1024 -> "0.5K"
            else -> "1K"
        }
    }

    private fun aspectRatioForSize(size: String): String {
        val match = SIZE_RE.matchEntire(size.trim()) ?: return "1:1"
        val w = match.groupValues[1].toDoubleOrNull() ?: return "1:1"
        val h = match.groupValues[2].toDoubleOrNull() ?: return "1:1"
        val ratio = w / h
        return when {
            ratio > 1.6 -> "16:9"
            ratio > 1.15 -> "4:3"
            ratio < 0.62 -> "9:16"
            ratio < 0.86 -> "3:4"
            else -> "1:1"
        }
    }

    private fun sizeDirective(config: ByokImageWorkbenchConfig): String =
        if (config.sizeMaxEdge > 0) "Output the full edited image at exactly ${config.size} pixels."
        else "Output the full edited image, same dimensions as the input."

    private fun sizeSentence(config: ByokImageWorkbenchConfig): String =
        if (config.sizeMaxEdge > 0) " At exactly ${config.size} pixels." else ""

    private fun maxEdge(size: String): Int {
        val match = SIZE_RE.matchEntire(size.trim()) ?: return 0
        val w = match.groupValues[1].toIntOrNull() ?: return 0
        val h = match.groupValues[2].toIntOrNull() ?: return 0
        return maxOf(w, h)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val SIZE_RE = Regex("""(\d+)\s*[xX×*]\s*(\d+)""")
    }
}

@Serializable
data class ByokImageResult(
    val url: String? = null,
    val raw: String? = null,
)

data class ByokImageWorkbenchConfig(
    val size: String = "1024x1024",
    val n: Int = 1,
    val quality: String = "auto",
    val outputFormat: String = "png",
    val background: String = "auto",
    val moderation: String = "auto",
    val outputCompression: Int = 80,
    val timeoutSeconds: Int = 600,
    val batchMode: Boolean = false,
    val reasoning: Boolean = false,
    val reasoningEffort: String = "medium",
) {
    val sizeMaxEdge: Int
        get() {
            val match = Regex("""(\d+)\s*[xX×*]\s*(\d+)""").matchEntire(size.trim()) ?: return 0
            val w = match.groupValues[1].toIntOrNull() ?: return 0
            val h = match.groupValues[2].toIntOrNull() ?: return 0
            return maxOf(w, h)
        }
}

data class ByokImageAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val maskPngBytes: ByteArray? = null,
    val maskedOverlayBytes: ByteArray? = null,
)

data class ByokImageHit(
    val url: String,
    val isData: Boolean,
    val label: String = "",
)

data class ByokImageWorkbenchResult(
    val hits: List<ByokImageHit> = emptyList(),
    val raw: String = "",
    val status: String = "",
    val usedFallback: Boolean = false,
    val requestCount: Int = 1,
)

private data class EndpointResult(
    val raw: String,
    val usedFallback: Boolean,
)

private sealed interface WorkbenchEndpoint {
    data class Json(val path: String, val body: JsonObject, val timeoutSeconds: Int) : WorkbenchEndpoint
    data class Multipart(val path: String, val body: MultipartBody, val timeoutSeconds: Int) : WorkbenchEndpoint
}
