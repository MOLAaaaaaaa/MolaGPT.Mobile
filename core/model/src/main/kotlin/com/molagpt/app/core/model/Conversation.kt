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
    /**
     * 本会话是否使用 BYOK 本地记忆；null = 跟随全局开关。
     * 用三态而非布尔：用户改全局开关时，没有显式表过态的旧会话应当跟着变。
     */
    val byokMemoryEnabled: Boolean? = null,
    /** 本会话是否允许模型检索历史对话；null = 跟随全局开关。 */
    val byokConversationRecallEnabled: Boolean? = null,
    /**
     * 记忆整理水位线：`createdAt` 大于它的消息才是「还没整理过的」。0 = 从未整理。
     *
     * 用一条水位线而不是逐条摄入记录，是因为整理本来就是按窗口做的：
     * 「这一段处理到哪儿了」是会话的属性，不是每条消息各自的状态。
     */
    val byokMemoryWatermarkAt: Long = 0L,
)

/** 这一行是画图任务（见 [ProviderIds.IMAGE_WORKBENCH]），点开应进画图工作台而不是聊天页。 */
val Conversation.isImageTask: Boolean get() = providerId == ProviderIds.IMAGE_WORKBENCH
