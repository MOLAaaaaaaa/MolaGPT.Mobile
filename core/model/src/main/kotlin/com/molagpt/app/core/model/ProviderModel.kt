package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/**
 * 一个可选模型。来源：`GET model_config_public.php` 的服务端驱动注册表。
 * [id] 使用 modelName，也就是最终发送到请求体的 `model` 值。
 */
@Serializable
data class ProviderModel(
    /** 模型标识：请求体 model 字段，例如 auto、deepseek-v4-flash。 */
    val id: String,
    /** 展示名：优先 tipText，其次 modelName。 */
    val displayName: String,
    /** 相对代理路径（如 "api/auth/chator.php"）。 */
    val apiUrl: String,
    val supportsVision: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportsReasoningEffort: Boolean = false,
    val supportsToolCalling: Boolean = false,
    val supportsImageGeneration: Boolean = false,
    /** 图像模型支持编辑（如 gpt-image、imagen），仅 image 用途 provider 的模型有意义。 */
    val supportsImageEdit: Boolean = false,
    val supportsChat: Boolean = true,
    val group: String? = null,
    val description: String? = null,
    val providerId: String = ProviderIds.MOLAGPT,
    val providerName: String = "MolaGPT",
    val providerKind: ProviderKind = ProviderKind.MOLAGPT,
    /** 推理参数配置（仅 BYOK 聊天模型有意义）。null 表示未配置，按模型 ID/host 启发式处理。 */
    val thinkingConfig: ThinkingConfig? = null,
    /** BYOK: 覆盖该模型请求体顶层字段（type: string|number|boolean|json）。 */
    val customBody: List<CustomBodyParam> = emptyList(),
)

/** 用户自定义请求体覆写项。[type] ∈ string|number|boolean|json，决定 [value] 如何解析成 JSON 值。 */
@Serializable
data class CustomBodyParam(val key: String = "", val type: String = "string", val value: String = "")

/**
 * 图像生成参数（OpenRouter /v1/chat/completions 出图）。
 * 纯 API 入参——由图像工作台（本地记住）或对话工具设置组装后传给网络层，不挂在模型上。
 * - [imageSize]: OpenRouter image_config.image_size，0.5K/1K/2K/4K。
 * - [aspectRatio]: image_config.aspect_ratio，1:1/16:9/9:16/4:3/3:4 等。
 * - [reasoning]: 是否启用推理（仅 GPT-5 Image / Gemini 3 Image 系列生效）。
 * - [reasoningEffort]: reasoning.effort，low/medium/high/xhigh/max。
 */
@Serializable
data class ImageGenerationConfig(
    val imageSize: String = "1K",
    val aspectRatio: String = "1:1",
    val reasoning: Boolean = false,
    val reasoningEffort: String = "medium",
) {
    companion object {
        val IMAGE_SIZES = listOf("0.5K", "1K", "2K", "4K")
        val ASPECT_RATIOS = listOf("1:1", "16:9", "9:16", "4:3", "3:4")
        val REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}

@Serializable
enum class ProviderKind {
    MOLAGPT,
    BYOK,
}

object ProviderIds {
    const val MOLAGPT = "molagpt"
}
