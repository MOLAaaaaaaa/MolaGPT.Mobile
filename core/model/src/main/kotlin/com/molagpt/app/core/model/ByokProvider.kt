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
)

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

/** 图像生成接口格式。OPENAI_IMAGES=DALL·E/gpt-image 走 /v1/images/generations；OPENAI_CHAT_IMAGE=对话补全出图（OpenRouter/nano-banana）。 */
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
            modelsPath = "v1/models",
            imagePath = "",
            purpose = ByokPurpose.IMAGE,
            imageFormat = ByokImageFormat.OPENAI_CHAT_IMAGE,
        ),
        ByokProvider(
            id = "gemini-image",
            name = "Gemini 图像",
            type = ByokProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            chatPath = "models/{model}:streamGenerateContent",
            modelsPath = "models",
            imagePath = "models/{model}:generateContent",
            purpose = ByokPurpose.IMAGE,
            imageFormat = ByokImageFormat.OPENAI_IMAGES,
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
