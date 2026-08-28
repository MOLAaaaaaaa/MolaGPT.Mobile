package com.molagpt.app.core.model

/**
 * 单次请求的统计信息，存在助手消息的 [ChatMessage.metadata] 里（键见 [ChatMessageMetadataKeys]）。
 *
 * 全部字段可空：各家 provider 上报的 usage 字段差别很大（缓存命中、推理 token 只有部分家有），
 * 老消息更是一个都没有，读取方必须按缺失处理而不是补 0——补 0 会把「没上报」显示成「真的是 0」。
 */
data class MessageStats(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** prompt 里命中缓存的部分，包含在 [promptTokens] 内，不是额外的量。 */
    val cachedTokens: Int? = null,
    val reasoningTokens: Int? = null,
    /** 请求发出到流结束的总耗时。 */
    val durationMs: Long? = null,
    /** 请求发出到第一个可见字的耗时（time to first token）。 */
    val ttftMs: Long? = null,
) {
    val hasAny: Boolean
        get() = promptTokens != null || completionTokens != null || totalTokens != null ||
            durationMs != null

    /**
     * 生成速度（token/秒）。
     *
     * 分母刻意扣掉 [ttftMs]：等待首 token 的那段没有产出任何 token，算进去会把「连接慢」
     * 和「生成慢」糊成同一个数字——BYOK 直连海外 API 时前者动辄好几秒，能把 tok/s 压到失真。
     * 两段分开展示，这个数才是模型真实吐字速度。没有 TTFT 的老消息退回总耗时口径。
     */
    val tokensPerSecond: Double?
        get() {
            val out = completionTokens?.takeIf { it > 0 } ?: return null
            val total = durationMs?.takeIf { it > 0 } ?: return null
            val window = ttftMs?.let { (total - it).coerceAtLeast(1L) } ?: total
            return out * 1000.0 / window
        }

    companion object {
        /** 从消息元数据还原；一个字段都没有时返回 null（不显示统计行）。 */
        fun from(metadata: Map<String, String>): MessageStats? {
            fun int(key: String) = metadata[key]?.toIntOrNull()
            fun long(key: String) = metadata[key]?.toLongOrNull()
            val stats = MessageStats(
                promptTokens = int(ChatMessageMetadataKeys.PROMPT_TOKENS),
                completionTokens = int(ChatMessageMetadataKeys.COMPLETION_TOKENS),
                totalTokens = int(ChatMessageMetadataKeys.TOTAL_TOKENS),
                cachedTokens = int(ChatMessageMetadataKeys.CACHED_TOKENS),
                reasoningTokens = int(ChatMessageMetadataKeys.REASONING_TOKENS),
                durationMs = long(ChatMessageMetadataKeys.DURATION_MS),
                ttftMs = long(ChatMessageMetadataKeys.TTFT_MS),
            )
            return stats.takeIf { it.hasAny }
        }
    }
}
