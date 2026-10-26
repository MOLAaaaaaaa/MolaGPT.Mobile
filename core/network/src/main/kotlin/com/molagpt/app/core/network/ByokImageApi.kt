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
import kotlinx.serialization.json.JsonPrimitive
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
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

class ByokImageApi(private val http: MolaHttp) {
    fun generate(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        size: String,
        imageConfig: ImageGenerationConfig? = null,
    ): ByokImageResult = when (provider.type) {
        ByokProviderType.GEMINI -> generateGemini(provider, modelId, prompt, imageConfig)
        ByokProviderType.ANTHROPIC -> throw MolaApiException(400, "${provider.name} 暂未配置图像生成端点")
        ByokProviderType.OPENAI_COMPAT, ByokProviderType.OPENAI_RESPONSE -> when (provider.imageFormatFor(modelId)) {
            ByokImageFormat.OPENAI_CHAT_IMAGE -> generateViaChatCompletions(provider, modelId, prompt, size, imageConfig)
            ByokImageFormat.OPENAI_IMAGES -> {
                val result = runOpenAiImageGenerations(
                    provider, modelId, prompt, ByokImageWorkbenchConfig(size = size.ifBlank { "1024x1024" }), gate = null,
                    imageConfig = imageConfig,
                )
                ByokImageResult(url = result.hits.firstOrNull()?.url, raw = result.raw.take(4000))
            }
        }
    }

    fun runWorkbench(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
        gate: ImageCallGate? = null,
    ): ByokImageWorkbenchResult {
        if (prompt.isBlank()) throw MolaApiException(400, "请填写 Prompt")
        if (modelId.isBlank()) throw MolaApiException(400, "请选择模型")
        return when (provider.type) {
            ByokProviderType.GEMINI -> runGeminiWorkbench(provider, modelId, prompt, config, attachments, gate)
            ByokProviderType.ANTHROPIC -> throw MolaApiException(400, "${provider.name} 暂未配置图像生成端点")
            ByokProviderType.OPENAI_RESPONSE,
            ByokProviderType.OPENAI_COMPAT -> when {
                provider.imageFormatFor(modelId) == ByokImageFormat.OPENAI_CHAT_IMAGE ->
                    runChatImageWorkbench(provider, modelId, prompt, config, attachments, gate)
                attachments.isEmpty() -> runOpenAiImageGenerations(provider, modelId, prompt, config, gate)
                config.batchMode && attachments.size >= 2 -> runOpenAiBatchEdits(provider, modelId, prompt, config, attachments, gate)
                else -> runOpenAiImageEdit(provider, modelId, prompt, config, attachments, gate)
            }
        }
    }

    /**
     * 把结果里的远程图片下载成字节。服务商给的 URL 多半有时效，不落地的话过几天历史里就只剩裂图。
     * 失败返回 null，由调用方退回保存原 URL。
     */
    fun download(url: String, gate: ImageCallGate? = null): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgentProvider.BROWSER_UA)
            .get()
            .build()
        val call = http.okHttp.newCall(req)
        gate?.register(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.bytes()
        }
    }.getOrNull()

    private fun runOpenAiImageGenerations(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        gate: ImageCallGate?,
        imageConfig: ImageGenerationConfig? = null,
    ): ByokImageWorkbenchResult {
        val hits = mutableListOf<ByokImageHit>()
        val raws = mutableListOf<String>()
        val count = config.n.coerceIn(1, 8)
        val body = imagesRequestBody(provider, modelId, prompt, config, refs = emptyList(), imageConfig = imageConfig)
        repeat(count) { index ->
            val raw = executeJson(provider, imagesPath(provider), body, config.timeoutSeconds, gate)
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

    private fun runOpenAiImageEdit(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
        gate: ImageCallGate?,
    ): ByokImageWorkbenchResult {
        val editPath = provider.imageEditPath.ifBlank { "v1/images/edits" }
        val raw = when (imagesDialect(provider, modelId)) {
            ImagesDialect.OPENAI, ImagesDialect.ZHIPU -> executeMultipart(
                provider, editPath, buildEditMultipart(provider, modelId, prompt, config, attachments), config.timeoutSeconds, gate,
            )
            ImagesDialect.XAI -> executeJson(
                provider, editPath, imagesRequestBody(provider, modelId, prompt, config, attachments), config.timeoutSeconds, gate,
            )
            ImagesDialect.OPENROUTER, ImagesDialect.REFS_IN_BODY, ImagesDialect.SEEDREAM, ImagesDialect.SILICONFLOW -> executeJson(
                provider,
                imagesPath(provider),
                imagesRequestBody(provider, modelId, prompt, config, attachments),
                config.timeoutSeconds,
                gate,
            )
        }
        val hits = extractImageHits(raw)
        return ByokImageWorkbenchResult(
            hits = hits,
            raw = previewResponse(raw),
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
        gate: ImageCallGate?,
    ): ByokImageWorkbenchResult {
        val hits = mutableListOf<ByokImageHit>()
        val raws = mutableListOf<String>()
        attachments.forEachIndexed { index, attachment ->
            val result = runOpenAiImageEdit(
                provider = provider,
                modelId = modelId,
                prompt = prompt,
                config = config.copy(n = 1, batchMode = false),
                attachments = listOf(attachment),
                gate = gate,
            )
            raws += "#${index + 1}\n${result.raw}"
            hits += result.hits.map { it.copy(label = "${index + 1}") }
        }
        return ByokImageWorkbenchResult(
            hits = hits,
            raw = raws.joinToString("\n\n--- batch item ---\n\n"),
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
        gate: ImageCallGate?,
    ): ByokImageWorkbenchResult {
        val count = if (attachments.isEmpty()) config.n.coerceIn(1, 8) else 1
        val hits = mutableListOf<ByokImageHit>()
        val raws = mutableListOf<String>()
        repeat(count) { index ->
            val body = if (attachments.isEmpty()) {
                buildChatGenerationBody(provider, modelId, prompt, config)
            } else {
                buildChatEditBody(provider, modelId, prompt, config, attachments)
            }
            val raw = executeJson(provider, provider.chatPath.ifBlank { "v1/chat/completions" }, body, config.timeoutSeconds, gate)
            hits += extractChatImageHits(raw).mapIndexed { hitIndex, hit ->
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
        gate: ImageCallGate?,
    ): ByokImageWorkbenchResult {
        if (modelId.substringAfterLast('/').startsWith("imagen-", ignoreCase = true)) {
            throw MolaApiException(400, "Imagen 已停止服务，请选择 Gemini 图像模型")
        }
        val path = provider.imagePath.ifBlank { provider.chatPath }
            .replace("{model}", modelId)
            .replace(":streamGenerateContent", ":generateContent")
        val key = provider.apiKey?.takeIf { it.isNotBlank() }
        val separator = if (path.contains("?")) "&" else "?"
        val requestPath = path + if (key.isNullOrBlank()) {
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
                geminiImageConfig(modelId, config.size)?.let { put("imageConfig", it) }
            })
            modelCustomBody(provider, modelId).forEach { (key, value) -> put(key, value) }
        }
        val count = if (attachments.isEmpty()) config.n.coerceIn(1, 8) else 1
        val hits = mutableListOf<ByokImageHit>()
        val raws = mutableListOf<String>()
        repeat(count) {
            val raw = executeJson(provider, requestPath, body, config.timeoutSeconds, gate)
            hits += extractImageHits(raw)
            raws += previewResponse(raw)
        }
        return ByokImageWorkbenchResult(
            hits = hits,
            raw = raws.joinToString("\n\n--- response ---\n\n"),
            requestCount = count,
            status = if (hits.isEmpty()) "未识别到图片" else "生成完成",
        )
    }

    private fun executeJson(provider: ByokProvider, path: String, body: JsonObject, timeoutSeconds: Int = 600, gate: ImageCallGate? = null): String {
        val req = Request.Builder()
            .url(provider.endpoint(path))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val client = http.okHttp.newBuilder()
            .callTimeout(timeoutSeconds.coerceIn(10, 3600).toLong(), TimeUnit.SECONDS)
            .build()
        client.newCall(req).also { gate?.register(it) }.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, serverResponseError("图像请求失败", resp.code, text))
            }
            return text
        }
    }

    private fun executeMultipart(provider: ByokProvider, path: String, body: MultipartBody, timeoutSeconds: Int = 600, gate: ImageCallGate? = null): String {
        val req = Request.Builder()
            .url(provider.endpoint(path))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(body)
            .build()
        val client = http.okHttp.newBuilder()
            .callTimeout(timeoutSeconds.coerceIn(10, 3600).toLong(), TimeUnit.SECONDS)
            .build()
        client.newCall(req).also { gate?.register(it) }.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, serverResponseError("图像编辑失败", resp.code, text))
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

    /**
     * /images/generations 的请求体；[refs] 非空时也用作参考图编辑（OpenAI 的 multipart 编辑除外）。
     * 模型上配置的参数覆写最后写入，可以覆盖这里的任何字段。
     */
    private fun imagesRequestBody(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        refs: List<ByokImageAttachment>,
        imageConfig: ImageGenerationConfig? = null,
    ): JsonObject = buildJsonObject {
        val size = config.size.ifBlank { "1024x1024" }
        val refUrls = refs.flatMap { ref ->
            listOfNotNull(ref.dataUrl(), ref.maskedOverlayBytes?.let { dataUrl("image/png", it) })
        }
        put("model", modelId)
        put("prompt", overlayPrompt(prompt, refs))
        when (imagesDialect(provider, modelId)) {
            ImagesDialect.OPENROUTER -> {
                if (refUrls.size > 16) throw MolaApiException(400, "最多可添加 16 张参考图")
                put("n", 1)
                val ratio = imageConfig?.aspectRatio?.takeIf { it in ImageGenerationConfig.ASPECT_RATIOS } ?: aspectRatioForSize(size)
                val resolution = imageConfig?.imageSize?.takeIf { it in ImageGenerationConfig.IMAGE_SIZES } ?: imageSizeTier(size)
                put("aspect_ratio", ratio)
                put("resolution", if (resolution == "0.5K") "512" else resolution)
                if (config.quality != "auto") put("quality", config.quality)
                if (config.background != "auto") put("background", config.background)
                put("output_format", config.outputFormat)
                if ((config.outputFormat == "jpeg" || config.outputFormat == "webp") && config.outputCompression in 0..100) {
                    put("output_compression", config.outputCompression)
                }
                if (refUrls.isNotEmpty()) {
                    putJsonArray("input_references") {
                        refUrls.forEach { url ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", url) }
                            }
                        }
                    }
                }
            }
            ImagesDialect.OPENAI -> {
                put("n", 1)
                put("size", size)
                if (isGptImageModel(modelId)) {
                    openAiImageExtras(config).forEach { (key, value) -> put(key, value) }
                } else {
                    put("response_format", "b64_json")
                }
            }
            ImagesDialect.ZHIPU -> put("size", size)
            ImagesDialect.XAI -> {
                if (refUrls.size > 5) throw MolaApiException(400, "最多可添加 5 张参考图")
                put("n", 1)
                put("aspect_ratio", aspectRatioForSize(size, XAI_RATIOS))
                put("resolution", if (maxEdge(size) >= 1600) "2k" else "1k")
                put("response_format", "b64_json")
                when (refUrls.size) {
                    0 -> Unit
                    1 -> putJsonObject("image") {
                        put("type", "image_url")
                        put("url", refUrls.first())
                    }
                    else -> putJsonArray("images") {
                        refUrls.forEach { url ->
                            addJsonObject {
                                put("type", "image_url")
                                put("url", url)
                            }
                        }
                    }
                }
            }
            ImagesDialect.SEEDREAM -> {
                put("size", seedreamSize(size))
                put("response_format", "b64_json")
                // 方舟默认在右下角加「AI 生成」水印。
                put("watermark", false)
                putRefs(refUrls)
            }
            ImagesDialect.SILICONFLOW -> {
                val maxReferences = if (modelId.substringAfterLast('/').equals("qwen-image-edit-2509", ignoreCase = true)) 3 else 1
                if (refUrls.size > maxReferences) {
                    throw MolaApiException(400, "该模型最多支持 $maxReferences 张参考图")
                }
                if (!modelId.substringAfterLast('/').startsWith("qwen-image-edit", ignoreCase = true)) {
                    put("image_size", size)
                }
                refUrls.forEachIndexed { index, url -> put(if (index == 0) "image" else "image${index + 1}", url) }
            }
            ImagesDialect.REFS_IN_BODY -> {
                put("n", 1)
                put("size", size)
                putRefs(refUrls)
            }
        }
        modelCustomBody(provider, modelId).forEach { (key, value) -> put(key, value) }
    }

    private fun JsonObjectBuilder.putRefs(refUrls: List<String>) {
        when (refUrls.size) {
            0 -> Unit
            1 -> put("image", refUrls.first())
            else -> putJsonArray("image") { refUrls.forEach { add(it) } }
        }
    }

    /** 不支持蒙版的接口：红色标注图紧跟在原图后面，提示词里说明只改标注区域。 */
    private fun overlayPrompt(prompt: String, refs: List<ByokImageAttachment>): String =
        if (refs.none { it.maskedOverlayBytes != null }) {
            prompt
        } else {
            "For any image immediately followed by a duplicate with a semi-transparent red overlay, the red overlay marks the ONLY region to edit in that image; treat it as an instruction, not image content. Keep everything outside it unchanged.\n\nInstruction:\n$prompt"
        }

    /** Seedream 4.5 / 5.0 lite 要求总像素不低于 2560x1440，小尺寸按原比例放大到下限。 */
    private fun seedreamSize(size: String): String {
        val match = SIZE_RE.matchEntire(size.trim()) ?: return size
        val w = match.groupValues[1].toInt()
        val h = match.groupValues[2].toInt()
        val pixels = w.toLong() * h
        if (pixels >= SEEDREAM_MIN_PIXELS) return "${w}x$h"
        val scale = sqrt(SEEDREAM_MIN_PIXELS.toDouble() / pixels)
        fun up(v: Int) = ceil(v * scale / 16).toInt() * 16
        return "${up(w)}x${up(h)}"
    }

    private fun imagesDialect(provider: ByokProvider, modelId: String): ImagesDialect {
        val host = runCatching { URI(provider.baseUrl.trim()).host.orEmpty() }.getOrDefault("").lowercase()
        val id = modelId.substringAfterLast('/').lowercase()
        return when {
            provider.isOpenRouter -> ImagesDialect.OPENROUTER
            host.endsWith("siliconflow.cn") || host.endsWith("siliconflow.com") -> ImagesDialect.SILICONFLOW
            host == "api.x.ai" || id.startsWith("grok-imagine") || id.startsWith("grok-2-image") -> ImagesDialect.XAI
            host.endsWith("bigmodel.cn") || id.startsWith("cogview") || id.startsWith("glm-image") -> ImagesDialect.ZHIPU
            host.endsWith("volces.com") || id.contains("seedream") -> ImagesDialect.SEEDREAM
            id.startsWith("qwen-image") || id.startsWith("wan2") || id.startsWith("wanx") -> ImagesDialect.REFS_IN_BODY
            else -> ImagesDialect.OPENAI
        }
    }

    private fun imagesPath(provider: ByokProvider): String = when {
        provider.isOpenRouter && provider.imagePath.trimStart('/') in listOf("", "v1/images/generations") -> "v1/images"
        else -> provider.imagePath.ifBlank { "v1/images/generations" }
    }

    private fun buildEditMultipart(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
    ): MultipartBody {
        val fields = linkedMapOf(
            "model" to modelId,
            "prompt" to prompt,
            "size" to config.size.ifBlank { "1024x1024" },
        )
        if (isGptImageModel(modelId)) {
            fields += openAiImageExtras(config).mapValues { it.value.content }
        } else {
            fields["response_format"] = "b64_json"
        }
        modelCustomBody(provider, modelId).forEach { (key, value) ->
            fields[key] = (value as? JsonPrimitive)?.content ?: value.toString()
        }
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (key, value) -> builder.addFormDataPart(key, value) }
        attachments.forEach { attachment ->
            builder.addFormDataPart(
                if (attachments.size == 1) "image" else "image[]",
                attachment.fileName.ifBlank { "image.png" },
                attachment.bytes.toRequestBody((attachment.mimeType.ifBlank { "image/png" }).toMediaTypeOrNull()),
            )
        }
        attachments.first().maskPngBytes?.let { mask ->
            builder.addFormDataPart(
                "mask",
                "mask.png",
                mask.toRequestBody("image/png".toMediaType()),
            )
        }
        return builder.build()
    }

    private fun buildChatGenerationBody(
        provider: ByokProvider,
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
        putChatImageConfig(provider, modelId, imageSizeTier(config.size), aspectRatioForSize(config.size))
        if (config.reasoning && provider.isOpenRouter) {
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
        modelCustomBody(provider, modelId).forEach { (key, value) -> put(key, value) }
    }

    private fun buildChatEditBody(
        provider: ByokProvider,
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
            putChatImageConfig(provider, modelId, imageSizeTier(config.size), aspectRatioForSize(config.size))
            if (config.reasoning && provider.isOpenRouter) {
                putJsonObject("reasoning") {
                    put("effort", config.reasoningEffort.ifBlank { "medium" })
                    put("exclude", true)
                }
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
            modelCustomBody(provider, modelId).forEach { (key, value) -> put(key, value) }
        }
    }

    /** gpt-image 的质量、格式、背景与审核参数；保持默认时不发送。 */
    private fun openAiImageExtras(config: ByokImageWorkbenchConfig): Map<String, JsonPrimitive> = buildMap {
        if (config.quality != "auto") put("quality", JsonPrimitive(config.quality))
        if (config.background != "auto") put("background", JsonPrimitive(config.background))
        if (config.outputFormat != "png") put("output_format", JsonPrimitive(config.outputFormat))
        if ((config.outputFormat == "jpeg" || config.outputFormat == "webp") && config.outputCompression in 0..100) {
            put("output_compression", JsonPrimitive(config.outputCompression))
        }
        if (config.moderation != "auto") put("moderation", JsonPrimitive(config.moderation))
    }

    /**
     * 对话接口出图的尺寸参数。OpenRouter 读顶层 image_config；中转站把请求转给 Gemini 时
     * 只读 extra_body.google.image_config，所以非 OpenRouter 两处都带上。
     */
    private fun JsonObjectBuilder.putChatImageConfig(provider: ByokProvider, modelId: String, size: String, ratio: String) {
        val openRouter = provider.isOpenRouter
        val config = buildJsonObject {
            // Gemini 2.5 的输出分辨率固定，原生接口只接受宽高比。
            if (openRouter || !modelId.contains("gemini-2.5", ignoreCase = true)) put("image_size", size)
            put("aspect_ratio", ratio)
        }
        put("image_config", config)
        if (!openRouter) {
            putJsonObject("extra_body") {
                putJsonObject("google") { put("image_config", config) }
            }
        }
    }

    /** 模型上配置的参数覆写；承载提示词和消息的字段不允许覆盖。 */
    private fun modelCustomBody(provider: ByokProvider, modelId: String): Map<String, JsonElement> =
        provider.models.firstOrNull { it.id == modelId }?.customBody.orEmpty()
            .filter { it.key.isNotBlank() && it.key.trim() !in PROTECTED_BODY_KEYS }
            .associate { it.key.trim() to customBodyValueToJson(http.json, it) }

    /**
     * 对话补全出图（openai-chat-image 格式）：POST chatPath，让模型在回复里产出图片 URL/base64。
     * 适用 OpenRouter nano-banana / gpt-5-image 等通过 chat/completions 返回图像的 provider。
     *
     * OpenRouter 对话出图通过 /v1/chat/completions：
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
            putChatImageConfig(provider, modelId, size, ratio)
            // 推理强度：仅当模型支持且用户开启时发送，避免对不支持的模型报错。
            if (imageConfig != null && imageConfig.reasoning && provider.isOpenRouter) {
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
            modelCustomBody(provider, modelId).forEach { (key, value) -> put(key, value) }
        }
        val req = Request.Builder()
            .url(provider.endpoint(provider.chatPath.ifBlank { "v1/chat/completions" }))
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, serverResponseError("图像生成失败", resp.code, text))
            }
            return parseChatCompletionsImageResult(resp.code, text)
        }
    }

    private fun generateGemini(
        provider: ByokProvider,
        modelId: String,
        prompt: String,
        imageConfig: ImageGenerationConfig?,
    ): ByokImageResult {
        if (modelId.substringAfterLast('/').startsWith("imagen-", ignoreCase = true)) {
            throw MolaApiException(400, "Imagen 已停止服务，请选择 Gemini 图像模型")
        }
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
                geminiImageConfig(modelId, size = "", imageConfig = imageConfig)?.let { put("imageConfig", it) }
            })
            modelCustomBody(provider, modelId).forEach { (key, value) -> put(key, value) }
        }
        val req = Request.Builder()
            .url(url)
            .apply { provider.applyAuthHeaders { name, value -> header(name, value) } }
            .post(http.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MolaApiException(resp.code, serverResponseError("图像生成失败", resp.code, text))
            }
            return parseGeminiImageResult(resp.code, text)
        }
    }

    private fun parseGeminiImageResult(statusCode: Int, text: String): ByokImageResult {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ByokImageResult(raw = text.take(4000))
        root["error"]?.jsonObject?.let {
            throw MolaApiException(statusCode, serverResponseError("图像生成失败", statusCode, text))
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

    private fun extractChatImageHits(text: String): List<ByokImageHit> {
        val root = runCatching { http.json.parseToJsonElement(text) }.getOrNull()
            ?: return findImagesInText(text)
        val choices = (root as? JsonObject)?.get("choices") as? JsonArray
        val messageImages = choices.orEmpty().flatMap { choice ->
            val message = (choice as? JsonObject)?.get("message") as? JsonObject
            val images = message?.get("images") ?: return@flatMap emptyList()
            extractImageHits(images)
        }
        return messageImages.ifEmpty { extractImageHits(root) }
    }

    private fun parseChatCompletionsImageResult(statusCode: Int, text: String): ByokImageResult {
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ByokImageResult(raw = text.take(4000))
        root["error"]?.jsonObject?.let {
            throw MolaApiException(statusCode, serverResponseError("图像生成失败", statusCode, text))
        }
        val choices = root["choices"] as? JsonArray
        val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        message?.get("images")?.let { images ->
            extractImageHits(images).firstOrNull()?.let {
                return ByokImageResult(url = it.url, raw = text.take(4000))
            }
        }
        extractImageHits(root).firstOrNull()?.let {
            return ByokImageResult(url = it.url, raw = text.take(4000))
        }
        val content = runCatching { message?.get("content")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return ByokImageResult(raw = content ?: text.take(4000))
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
                        key == "b64_json" -> value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { data ->
                            val mime = element["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                            hits += ByokImageHit(url = dataUrl(mime, data), isData = true)
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

    private fun imageSizeTier(size: String): String {
        val edge = maxEdge(size)
        return when {
            edge >= 3000 -> "4K"
            edge >= 1600 -> "2K"
            edge > 0 && edge < 1024 -> "0.5K"
            else -> "1K"
        }
    }

    private fun geminiImageConfig(
        modelId: String,
        size: String,
        imageConfig: ImageGenerationConfig? = null,
    ): JsonObject? {
        if (imageConfig == null && size.isBlank()) return null
        val imageSize = imageConfig?.imageSize?.takeIf { it in ImageGenerationConfig.IMAGE_SIZES }
            ?: imageSizeTier(size)
        val aspectRatio = imageConfig?.aspectRatio?.takeIf { it in ImageGenerationConfig.ASPECT_RATIOS }
            ?: aspectRatioForSize(size)
        return buildJsonObject {
            put("aspectRatio", aspectRatio)
            // Gemini 2.5 的输出分辨率固定，只接受宽高比。
            if (!modelId.contains("gemini-2.5", ignoreCase = true)) {
                put("imageSize", imageSize)
            }
        }
    }

    private fun aspectRatioForSize(size: String, candidates: List<String> = CHAT_RATIOS): String {
        val match = SIZE_RE.matchEntire(size.trim()) ?: return "1:1"
        val w = match.groupValues[1].toDoubleOrNull() ?: return "1:1"
        val h = match.groupValues[2].toDoubleOrNull() ?: return "1:1"
        val ratio = w / h
        return candidates.minBy {
            val (rw, rh) = it.split(':').map(String::toDouble)
            abs(ln(ratio / (rw / rh)))
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
        val CHAT_RATIOS = listOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")
        val XAI_RATIOS = listOf("1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "1:2", "2:1", "21:9")
        const val SEEDREAM_MIN_PIXELS = 2560L * 1440
        val PROTECTED_BODY_KEYS = setOf("prompt", "messages", "contents", "stream")
    }
}

/** gpt-image 系列：认质量、格式、背景与审核参数，且不接受 response_format。 */
fun isGptImageModel(modelId: String): Boolean {
    val id = modelId.substringAfterLast('/').lowercase()
    return id.startsWith("gpt-image-") || id.startsWith("chatgpt-image-")
}

/** 各家 /images/generations 的参数差异。 */
private enum class ImagesDialect {
    /** OpenRouter：/images 的 JSON 接口，参考图使用 input_references。 */
    OPENROUTER,

    /** OpenAI 及多数中转站：size、response_format，参考图走 multipart 的 /images/edits。 */
    OPENAI,

    /** 智谱：只返回链接，不认 response_format，没有编辑接口。 */
    ZHIPU,

    /** xAI：不接受 size，用 aspect_ratio + resolution；编辑是 JSON 的 /images/edits。 */
    XAI,

    /** Seedream：参考图放生成请求的 image 字段，默认带水印，像素下限高。 */
    SEEDREAM,

    /** SiliconFlow：image_size 代替 size，参考图是 image / image2 / image3。 */
    SILICONFLOW,

    /** 千问图像、万相等：参考图放生成请求的 image 字段。 */
    REFS_IN_BODY,
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
    val requestCount: Int = 1,
)

/**
 * 一次工作台请求里发出的全部 HTTP 调用。阻塞的 `execute()` 不响应协程取消，
 * 停止时靠 [cancel] 直接掐断连接，调用线程随即抛 IOException 退出。
 * 取消之后才登记的调用（循环里的下一张）会被立即掐掉。
 */
class ImageCallGate {
    private val calls = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call>()

    @Volatile
    var cancelled: Boolean = false
        private set

    fun register(call: okhttp3.Call) {
        calls += call
        if (cancelled) call.cancel()
    }

    fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
    }
}
