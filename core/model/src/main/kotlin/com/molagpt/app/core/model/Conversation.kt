package com.molagpt.app.core.model

/**
 * 一个本地会话。
 * 会话与消息均为**客户端本地持久化**（Room），仅对话/停止/恢复打服务端。
 */
data class Conversation(
    val sessionId: String,
    val title: String,
    val model: String? = null,
    val providerId: String? = ProviderIds.MOLAGPT,
    val providerKind: ProviderKind = ProviderKind.MOLAGPT,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val lastMessagePreview: String? = null,
)
