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

    /** 按位置插入的角色补充：不能被当成普通 system 抽到最前面。 */
    val isRoleInjection: Boolean
        get() = role == Role.SYSTEM && metadata.containsKey(ChatMessageMetadataKeys.ROLE_INJECTION)

    /** 角色卡的开场白：内容来自卡而非模型。 */
    val isRoleGreeting: Boolean
        get() = metadata.containsKey(ChatMessageMetadataKeys.ROLE_GREETING)
}

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

object ChatMessageMetadataKeys {
    /**
     * 标记一条**按位置插入**的角色补充（世界书的 at_depth、角色补充、后置指令）。
     *
     * 它的值在于位置本身，不能像普通 system 那样被收拢到请求最前面。各协议的处理并不相同，
     * 理由也不同，不要一并套用：
     * - OpenAI chat/completions 与 Responses：协议本来就接受任意位置的 system
     *   （Responses 见 `EasyInputMessage.role`），**原样保留身份与位置**。
     * - Anthropic / Gemini：协议的消息列表里根本没有 system 这个身份，system 只能放在
     *   顶层 `system` / `systemInstruction`。带此标记的只能**原地降级成 user** 发出去：
     *   位置保住了，身份语义并不等同于 system——这是协议限制下的取舍。
     */
    const val ROLE_INJECTION = "roleInjection"

    /**
     * 角色开场白。它是卡里写好的台词，不是模型生成的，所以不给「重新生成」；
     * 备选开场白复用重试版本栈，切换直接走消息下方那条版本栏。
     */
    const val ROLE_GREETING = "roleGreeting"

    const val OPENAI_WIRE_HISTORY = "openAiWireHistory"
    const val ANTHROPIC_WIRE_HISTORY = "anthropicWireHistory"
    const val GEMINI_WIRE_HISTORY = "geminiWireHistory"

    /**
     * 上一轮助手消息的 provider 原生协议快照，下一轮原样回放。
     *
     * 正文一旦变了（后处理改写、用户编辑、切到另一个版本），这份快照就必须跟着变或者作废，
     * 否则下一轮发出去的是屏幕上没有的文本。
     */
    val WIRE_HISTORY: Set<String> = setOf(
        OPENAI_WIRE_HISTORY,
        ANTHROPIC_WIRE_HISTORY,
        GEMINI_WIRE_HISTORY,
    )

    // —— 单次请求统计（见 [MessageStats]）。历史消息大多缺字段，读取方一律按可空处理。——
    /** 总 token 数。历史最久的一个键，早于其余统计字段存在。 */
    const val TOTAL_TOKENS = "tokens"
    const val PROMPT_TOKENS = "promptTokens"
    const val COMPLETION_TOKENS = "completionTokens"
    const val CACHED_TOKENS = "cachedTokens"
    const val REASONING_TOKENS = "reasoningTokens"
    const val DURATION_MS = "durationMs"
    const val TTFT_MS = "ttftMs"
    const val COST_ID = "costId"
    const val COST_USD = "costUsd"
    const val COST_MODEL = "costModel"
    const val PRICING_SOURCE = "pricingSource"
    const val PRICING_MISSING = "pricingMissing"
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
