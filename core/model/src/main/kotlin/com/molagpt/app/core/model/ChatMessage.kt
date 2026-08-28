package com.molagpt.app.core.model

/**
 * 一条聊天消息。`fragments` 承载真正内容；`rawText` 为可选的纯文本镜像（便于复制/分享/重发）。
 * 持久化时 [fragments] 序列化进 Room 的 fragmentsJson 字段（见 :core:storage）。
 */
data class ChatMessage(
    val messageId: String,
    val sessionId: String,
    val role: Role,
    val status: MessageStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val fragments: List<MessageFragment> = emptyList(),
    val rawText: String? = null,
    val model: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    val isStreaming: Boolean
        get() = status == MessageStatus.STREAMING || status == MessageStatus.PENDING
}

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

object ChatMessageMetadataKeys {
    const val OPENAI_WIRE_HISTORY = "openAiWireHistory"
    const val ANTHROPIC_WIRE_HISTORY = "anthropicWireHistory"
    const val GEMINI_WIRE_HISTORY = "geminiWireHistory"

    // —— 单次请求统计（见 [MessageStats]）。历史消息大多缺字段，读取方一律按可空处理。——
    /** 总 token 数。历史最久的一个键，早于其余统计字段存在。 */
    const val TOTAL_TOKENS = "tokens"
    const val PROMPT_TOKENS = "promptTokens"
    const val COMPLETION_TOKENS = "completionTokens"
    const val CACHED_TOKENS = "cachedTokens"
    const val REASONING_TOKENS = "reasoningTokens"
    const val DURATION_MS = "durationMs"
    const val TTFT_MS = "ttftMs"
}

enum class MessageStatus {
    /** 已入队，尚未收到首个 token。 */
    PENDING,

    /** 正在接收流式内容。 */
    STREAMING,

    /** 正常结束。 */
    COMPLETE,

    /** 用户主动停止。 */
    STOPPED,

    /** 出错结束。 */
    ERROR,
}
