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
