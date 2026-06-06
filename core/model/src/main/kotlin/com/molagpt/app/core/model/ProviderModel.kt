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
    val group: String? = null,
    val description: String? = null,
)
