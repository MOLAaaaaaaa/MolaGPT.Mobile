package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/**
 * 一个 provider 无关的对话请求。网络层据此构造 MolaGPT 代理线格式：
 * `{session_id, conversation_id, model, temperature, messages, stream,
 *   use_thinking, reasoning_effort, enabled_tools, privacy_mode}` + Bearer JWT。
 */
data class ChatRequest(
    val modelId: String,
    val modelDisplayName: String? = null,
    val providerId: String = ProviderIds.MOLAGPT,
    val providerKind: ProviderKind = ProviderKind.MOLAGPT,
    val messages: List<ChatMessage>,
    /** 本地会话 id：用于 Room/UI 归属，通常是 sess_<ts>_<rand>。 */
    val sessionId: String,
    /** 单次生成的服务端流 id：用于 stream_cache / stop / resume，每轮生成必须唯一。 */
    val streamSessionId: String = Ids.newSessionId(),
    val conversationId: String,
    val temperature: Double = 0.7,
    val stream: Boolean = true,
    val useThinking: Boolean = false,
    /** low | medium | high。 */
    val reasoningEffort: String = "medium",
    val enabledTools: EnabledTools = EnabledTools(),
    val privacyMode: Boolean = false,
)

/**
 * 工具开关：联网搜索、网页访问、代码执行。
 * 注意：真实 JSON 键名以 :core:network 的 DTO 映射为准，代码执行在线上后端读取为 `code`。
 */
@Serializable
data class EnabledTools(
    /** 联网搜索（enable-network-tools）。 */
    val network: Boolean = false,
    /** 网页访问 / Steel Browser（enable-steel-browser），默认关闭。 */
    val steelBrowser: Boolean = false,
    /** 代码执行（enable-code-execution），默认开启。 */
    val codeExecution: Boolean = true,
    /** BYOK MCP 服务器工具。MolaGPT 账户模式下由服务端能力决定。 */
    val mcp: Boolean = false,
    /** BYOK 外挂视觉工具。 */
    val vision: Boolean = false,
    /** BYOK 图像生成工具。 */
    val imageGeneration: Boolean = false,
)
