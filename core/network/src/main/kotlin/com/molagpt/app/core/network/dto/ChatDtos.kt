package com.molagpt.app.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 对话请求线格式（POST 到模型的 apiUrl）。
 * `{session_id, conversation_id, model, temperature, messages, stream,
 *   use_thinking, reasoning_effort, enabled_tools, privacy_mode}`。
 */
@Serializable
data class ChatRequestBody(
    @SerialName("session_id") val sessionId: String,
    @SerialName("conversation_id") val conversationId: String,
    val model: String,
    val temperature: Double,
    val messages: List<WireMessage>,
    val stream: Boolean,
    @SerialName("use_thinking") val useThinking: Boolean,
    @SerialName("reasoning_effort") val reasoningEffort: String,
    @SerialName("enabled_tools") val enabledTools: WireEnabledTools,
    @SerialName("privacy_mode") val privacyMode: Boolean,
    @SerialName("molagpt_routes") val molaGptRoutes: JsonObject? = null,
)

/** content 使用 JsonElement，支持纯文本 String 或多模态数组 image_url 等。 */
@Serializable
data class WireMessage(val role: String, val content: JsonElement)

/** 工具开关线格式。服务端 enabled_tools 读取代码执行开关时使用 `code` 键。 */
@Serializable
data class WireEnabledTools(
    val network: Boolean,
    val steelBrowser: Boolean,
    @SerialName("code") val codeExecution: Boolean,
    val deepResearch: Boolean = false,
)
