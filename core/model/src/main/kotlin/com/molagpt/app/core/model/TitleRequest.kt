package com.molagpt.app.core.model

/** 参与标题总结的一条消息（已抽成纯文本，不带附件/工具卡）。 */
data class TitleMessage(val role: Role, val text: String)

/**
 * 生成会话标题的请求。
 *
 * 承载**阵营路由信息**：RoutingChatService 据此把请求分派给 MolaGPT 的 generateTitle 端点，
 * 或用户自己的 BYOK provider（后者绝不经过 MolaGPT 服务器，保持 BYOK 的隐私预期）。
 */
data class TitleRequest(
    val sessionId: String,
    val providerKind: ProviderKind,
    val providerId: String,
    val modelId: String,
    /** 参与总结的消息尾窗口，时间正序。见 [titleWindow]。 */
    val messages: List<TitleMessage>,
) {
    val firstUserText: String? get() = messages.firstOrNull { it.role == Role.USER }?.text
    val lastAssistantText: String? get() = messages.lastOrNull { it.role == Role.ASSISTANT }?.text

    /**
     * 生成失败时返回的标题：与会话新建时写入的占位标题一致，调用方比对后自然不会改名。
     * 连用户文本都没有（纯附件消息）时返回空串——此时占位标题是附件名，
     * 用「无标题对话」覆盖它反而是倒退。
     */
    fun fallbackTitle(): String =
        firstUserText?.takeIf { it.isNotBlank() }?.let(::titleFallback).orEmpty()
}

/**
 * 标题兜底：截断首条用户消息。会话新建时先用它占位，模型生成失败/未启用时也停在这里。
 */
fun titleFallback(content: String): String =
    content.replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .let { if (it.length <= 25) it else it.take(25).trim() + "..." }
        .ifBlank { "无标题对话" }

/**
 * 取消息尾窗口喂给标题模型：从最后一条往前收，最多 [maxMessages] 条、
 * 单条截 [maxCharsPerMessage] 字、合计不超过 [maxTotalChars] 字，返回时间正序。
 *
 * 只喂尾部而非首轮两条，是为了让长对话「重新生成标题」也有意义。
 */
fun titleWindow(
    messages: List<ChatMessage>,
    maxMessages: Int = 6,
    maxCharsPerMessage: Int = 500,
    maxTotalChars: Int = 3000,
): List<TitleMessage> {
    val picked = ArrayList<TitleMessage>(maxMessages)
    var total = 0
    for (message in messages.asReversed()) {
        if (picked.size >= maxMessages || total >= maxTotalChars) break
        if (message.role != Role.USER && message.role != Role.ASSISTANT) continue
        val text = message.titleText().takeIf { it.isNotBlank() } ?: continue
        val clipped = if (text.length <= maxCharsPerMessage) text else text.take(maxCharsPerMessage)
        picked.add(TitleMessage(message.role, clipped))
        total += clipped.length
    }
    return picked.asReversed()
}

/** 消息的纯文本视图：优先 rawText，否则拼接文本类片段（思考/工具卡/图片不进标题上下文）。 */
private fun ChatMessage.titleText(): String {
    rawText?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    return fragments.mapNotNull { fragment ->
        when (fragment) {
            is MessageFragment.Text -> fragment.markdown
            is MessageFragment.CodeBlock -> fragment.code
            else -> null
        }
    }.joinToString("\n").trim()
}
