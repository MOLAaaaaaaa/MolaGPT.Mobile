package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ByokProvider(
    val id: String,
    val name: String,
    val type: ByokProviderType = ByokProviderType.OPENAI_COMPAT,
    val baseUrl: String,
    val apiKey: String? = null,
    val chatPath: String = "v1/chat/completions",
    val modelsPath: String = "v1/models",
    val imagePath: String = "v1/images/generations",
    /** 用途：对话 or 图像。图像用途的 provider 只装图像模型，对话用途只装聊天模型。 */
    val purpose: ByokPurpose = ByokPurpose.CHAT,
    /** 图像接口格式（仅 image 用途有意义）。 */
    val imageFormat: ByokImageFormat = ByokImageFormat.OPENAI_IMAGES,
    /** 图像编辑路径（仅 image 用途 + openai-images 格式用，如 v1/images/edits）。 */
    val imageEditPath: String = "",
    val enabled: Boolean = true,
    val models: List<ProviderModel> = emptyList(),
    /** BYOK: 附加到该服务全部请求（对话 / 模型列表 / 图像）的自定义请求头，auth 之后追加。 */
    val customHeaders: List<CustomHeader> = emptyList(),
) {
    /** 该模型实际使用的图像接口：模型单独设置过就用模型的，否则跟随服务。 */
    fun imageFormatFor(modelId: String): ByokImageFormat =
        models.firstOrNull { it.id == modelId }?.imageFormat ?: imageFormat

    val isOfficialOpenAiResponses: Boolean
        get() = type == ByokProviderType.OPENAI_RESPONSE && HostUrl.isOfficialOpenAiResponses(baseUrl, chatPath)

    val host: String?
        get() = HostUrl.parseHttpHost(baseUrl)

    val isOpenRouter: Boolean
        get() = HostUrl.matchesDomain(host, "openrouter.ai")

    val usesNativeGemini: Boolean
        get() = type == ByokProviderType.GEMINI || host == "generativelanguage.googleapis.com"

    private fun knownNativeThinkingConfig(modelId: String): ThinkingConfig? =
        ThinkingKinds.autoConfigFor(modelId, baseUrl)

    fun thinkingConfigFor(
        modelId: String,
        declared: ThinkingConfig? = models.firstOrNull { it.id == modelId }?.thinkingConfig,
        legacySupportsThinking: Boolean = false,
    ): ThinkingConfig? {
        val authoritative = declared?.manualOverride == true || declared?.detectSource in
            listOf(ThinkingDetectSource.OVERRIDE, ThinkingDetectSource.CAPABILITY)
        val config = when {
            authoritative -> declared
            declared?.detectSource in listOf(ThinkingDetectSource.HEURISTIC, ThinkingDetectSource.HOST) &&
                ModelCapabilityCatalog.find(modelId) != null ->
                (knownNativeThinkingConfig(modelId) ?: ThinkingKinds.NONE_CONFIG)
                    .copy(offIneffective = declared?.offIneffective == true)
            declared?.kind == ThinkingParamKind.NONE -> declared
            else -> {
                val known = knownNativeThinkingConfig(modelId)
                if (known != null && (declared == null || declared.kind != known.kind ||
                        declared.effortLevels.isEmpty())
                ) {
                    known.copy(offIneffective = declared?.offIneffective == true)
                } else {
                    declared ?: if (ThinkingKinds.isReasoningModel(modelId) || legacySupportsThinking) {
                        ThinkingKinds.autoConfigFor(modelId, baseUrl)
                            ?: ThinkingKinds.configFor(ThinkingParamKind.OPENAI_REASONING_EFFORT)
                                .takeIf { legacySupportsThinking && ModelCapabilityCatalog.find(modelId)?.reasoning != false }
                    } else {
                        null
                    }
                }
            }
        } ?: return null
        if (config.kind == ThinkingParamKind.NONE) return config
        val levels = ThinkingKinds.resolveEffortLevels(config, baseUrl)
        return config.copy(
            effortLevels = levels,
            defaultEffort = ThinkingKinds.resolveDefaultEffort(config, baseUrl),
        )
    }

    /** 模型编辑页「自动识别」统一入口：优先原生已知型号与方言，未知模型仍给出可编辑兜底。 */
    fun detectThinkingConfig(modelId: String): ThinkingConfig {
        val detected = knownNativeThinkingConfig(modelId)
            ?: ThinkingKinds.NONE_CONFIG.takeIf { ModelCapabilityCatalog.find(modelId)?.reasoning == false }
            ?: ThinkingKinds.configFor(
                ThinkingKinds.inferFromModelId(modelId).takeIf { it != ThinkingParamKind.NONE }
                    ?: ThinkingKinds.hostInferredKind(baseUrl)
                    ?: ThinkingParamKind.OPENAI_REASONING_EFFORT,
                alwaysOn = ThinkingKinds.isKimiK3(modelId),
                detectSource = ThinkingDetectSource.HEURISTIC,
            )
        val levels = ThinkingKinds.resolveEffortLevels(detected, baseUrl)
        return detected.copy(
            effortLevels = levels,
            defaultEffort = ThinkingKinds.resolveDefaultEffort(detected, baseUrl),
        )
    }

    /** 模型编辑页切换行为类别或「重置档位」时计算合法默认档位，不因 alwaysOn 错套 Kimi K3 模板。 */
    fun defaultThinkingConfigFor(modelId: String, kind: ThinkingParamKind): ThinkingConfig {
        if (kind == ThinkingParamKind.NONE) {
            return ThinkingKinds.NONE_CONFIG.copy(
                detectSource = ThinkingDetectSource.OVERRIDE,
                manualOverride = true,
            )
        }
        val known = knownNativeThinkingConfig(modelId)?.takeIf { it.kind == kind }
        val base = known ?: ThinkingConfig(
            kind = kind,
            alwaysOn = ThinkingKinds.isKimiK3(modelId) && kind == ThinkingParamKind.OPENAI_REASONING_EFFORT,
        )
        val levels = ThinkingKinds.resolveEffortLevels(base, baseUrl)
        return base.copy(
            effortLevels = levels,
            defaultEffort = ThinkingKinds.resolveDefaultEffort(base, baseUrl),
        )
    }

}

/** 用户自定义 HTTP 请求头。 */
@Serializable
data class CustomHeader(val name: String = "", val value: String = "")

@Serializable
enum class ByokProviderType {
    OPENAI_COMPAT,
    OPENAI_RESPONSE,
    ANTHROPIC,
    GEMINI,
}

@Serializable
enum class ByokPurpose {
    CHAT,
    IMAGE,
}

/**
 * 图像请求走专用图像接口还是对话接口。
 * Gemini 使用 generateContent，OpenRouter 图像接口使用 /images。
 * 枚举名称已写入持久化数据，保持不变。
 */
@Serializable
enum class ByokImageFormat {
    OPENAI_IMAGES,
    OPENAI_CHAT_IMAGE,
}

object ByokProviderPresets {
    val defaults: List<ByokProvider> = listOf(
        // —— OpenAI 格式 API（兼容 + Response）——
        preset("openrouter", "OpenRouter", "https://openrouter.ai/api/", "v1/chat/completions", "v1/models"),
        preset("openai", "OpenAI", "https://api.openai.com/", "v1/chat/completions", "v1/models"),
        preset("openai-response", "OpenAI (Response API)", "https://api.openai.com/", "v1/responses", "v1/models",
            type = ByokProviderType.OPENAI_RESPONSE),
        preset("deepseek", "DeepSeek", "https://api.deepseek.com/", "v1/chat/completions", "v1/models"),
        preset("moonshot", "Moonshot (Kimi)", "https://api.moonshot.cn/", "v1/chat/completions", "v1/models"),
        preset("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/", "v1/chat/completions", "v1/models"),
        preset("dashscope", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/", "v1/chat/completions", "v1/models"),
        preset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/", "v4/chat/completions", "v4/models"),
        preset("groq", "Groq", "https://api.groq.com/openai/", "v1/chat/completions", "v1/models"),
        preset("xai", "xAI", "https://api.x.ai/", "v1/chat/completions", "v1/models"),
        preset("together", "Together AI", "https://api.together.xyz/", "v1/chat/completions", "v1/models"),
        // —— Claude ——
        ByokProvider(
            id = "anthropic",
            name = "Anthropic (Claude)",
            type = ByokProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/",
            chatPath = "v1/messages",
            modelsPath = "v1/models",
            imagePath = "",
        ),
        // —— Gemini ——
        ByokProvider(
            id = "gemini",
            name = "Google Gemini",
            type = ByokProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            chatPath = "models/{model}:streamGenerateContent",
            modelsPath = "models",
            imagePath = "models/{model}:generateContent",
        ),
        // —— 图像服务 ——
        ByokProvider(
            id = "openai-images",
            name = "OpenAI 图像",
            type = ByokProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.openai.com/",
            chatPath = "v1/chat/completions",
            modelsPath = "v1/models",
            imagePath = "v1/images/generations",
            imageEditPath = "v1/images/edits",
            purpose = ByokPurpose.IMAGE,
            imageFormat = ByokImageFormat.OPENAI_IMAGES,
        ),
        ByokProvider(
            id = "openrouter-images",
            name = "OpenRouter 图像",
            type = ByokProviderType.OPENAI_COMPAT,
            baseUrl = "https://openrouter.ai/api/",
            chatPath = "v1/chat/completions",
            modelsPath = "v1/images/models",
            imagePath = "v1/images",
            purpose = ByokPurpose.IMAGE,
            imageFormat = ByokImageFormat.OPENAI_IMAGES,
        ),
        ByokProvider(
            id = "gemini-image",
            name = "Gemini 图像",
            type = ByokProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            chatPath = "models/{model}:generateContent",
            modelsPath = "models",
            imagePath = "models/{model}:generateContent",
            purpose = ByokPurpose.IMAGE,
            imageFormat = ByokImageFormat.OPENAI_CHAT_IMAGE,
        ),
        ByokProvider(
            id = "ark-images",
            name = "火山方舟 图像",
            baseUrl = "https://ark.cn-beijing.volces.com/api/",
            chatPath = "v3/chat/completions",
            modelsPath = "v3/models",
            imagePath = "v3/images/generations",
            purpose = ByokPurpose.IMAGE,
        ),
        ByokProvider(
            id = "siliconflow-images",
            name = "SiliconFlow 图像",
            baseUrl = "https://api.siliconflow.cn/",
            modelsPath = "v1/models?type=image",
            imagePath = "v1/images/generations",
            purpose = ByokPurpose.IMAGE,
        ),
        ByokProvider(
            id = "xai-images",
            name = "xAI 图像",
            baseUrl = "https://api.x.ai/",
            imagePath = "v1/images/generations",
            imageEditPath = "v1/images/edits",
            purpose = ByokPurpose.IMAGE,
        ),
    )

    private fun preset(
        id: String,
        name: String,
        baseUrl: String,
        chatPath: String,
        modelsPath: String,
        type: ByokProviderType = ByokProviderType.OPENAI_COMPAT,
    ) = ByokProvider(
        id = id,
        name = name,
        type = type,
        baseUrl = baseUrl,
        chatPath = chatPath,
        modelsPath = modelsPath,
    )
}
