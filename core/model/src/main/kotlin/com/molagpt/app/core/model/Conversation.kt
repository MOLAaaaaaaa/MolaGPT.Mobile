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
    /** 绑定的角色 id（仅 BYOK 会话有意义）；null = 未显式选择，注入时回退内置「通用助手」。 */
    val personaId: String? = null,
    /** 会话级系统提示覆盖（预留，首版 UI 暂不暴露）。 */
    val systemPrompt: String? = null,
    /** 角色提示词与会话级提示词的合并模式：override(默认) / append。 */
    val systemPromptMode: String? = null,
)
