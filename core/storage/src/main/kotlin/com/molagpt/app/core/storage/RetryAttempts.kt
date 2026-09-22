package com.molagpt.app.core.storage

import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.RetryAttempt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 重试版本的编解码 + metadata 键。版本（含多态 fragments）序列化成字符串塞进消息 metadata，
 * 零 Room 迁移。使用相同 `classDiscriminator="type"` 以复用 MessageFragment 的 sealed 多态。
 */
object RetryAttempts {
    const val KEY_ATTEMPTS = "retryAttempts"
    const val KEY_CURRENT = "retryCurrent"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    private val serializer = ListSerializer(RetryAttempt.serializer())

    fun encode(list: List<RetryAttempt>): String = json.encodeToString(serializer, list)

    fun decode(s: String?): List<RetryAttempt> =
        if (s.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, s) }.getOrDefault(emptyList())

    fun from(message: ChatMessage): RetryAttempt = RetryAttempt(
        fragments = message.fragments, rawText = message.rawText, model = message.model,
        modelDisplayName = message.metadata["modelDisplayName"], status = message.status.name,
        promptTokens = message.metadata[ChatMessageMetadataKeys.PROMPT_TOKENS]?.toIntOrNull(),
        completionTokens = message.metadata[ChatMessageMetadataKeys.COMPLETION_TOKENS]?.toIntOrNull(),
        cachedTokens = message.metadata[ChatMessageMetadataKeys.CACHED_TOKENS]?.toIntOrNull(),
        totalTokens = message.metadata[ChatMessageMetadataKeys.TOTAL_TOKENS]?.toIntOrNull(),
        reasoningTokens = message.metadata[ChatMessageMetadataKeys.REASONING_TOKENS]?.toIntOrNull(),
        durationMs = message.metadata[ChatMessageMetadataKeys.DURATION_MS]?.toLongOrNull(),
        ttftMs = message.metadata[ChatMessageMetadataKeys.TTFT_MS]?.toLongOrNull(),
        costId = message.metadata[ChatMessageMetadataKeys.COST_ID],
        costUsd = message.metadata[ChatMessageMetadataKeys.COST_USD]?.toDoubleOrNull(),
        costModel = message.metadata[ChatMessageMetadataKeys.COST_MODEL],
        pricingSource = message.metadata[ChatMessageMetadataKeys.PRICING_SOURCE],
        pricingMissing = message.metadata[ChatMessageMetadataKeys.PRICING_MISSING] == "true",
    )

    fun statsMetadata(attempt: RetryAttempt): Map<String, String> = buildMap {
        attempt.promptTokens?.let { put(ChatMessageMetadataKeys.PROMPT_TOKENS, it.toString()) }
        attempt.completionTokens?.let { put(ChatMessageMetadataKeys.COMPLETION_TOKENS, it.toString()) }
        attempt.cachedTokens?.let { put(ChatMessageMetadataKeys.CACHED_TOKENS, it.toString()) }
        attempt.totalTokens?.let { put(ChatMessageMetadataKeys.TOTAL_TOKENS, it.toString()) }
        attempt.reasoningTokens?.let { put(ChatMessageMetadataKeys.REASONING_TOKENS, it.toString()) }
        attempt.durationMs?.let { put(ChatMessageMetadataKeys.DURATION_MS, it.toString()) }
        attempt.ttftMs?.let { put(ChatMessageMetadataKeys.TTFT_MS, it.toString()) }
        attempt.costId?.let { put(ChatMessageMetadataKeys.COST_ID, it) }
        attempt.costUsd?.let { put(ChatMessageMetadataKeys.COST_USD, it.toString()) }
        attempt.costModel?.let { put(ChatMessageMetadataKeys.COST_MODEL, it.toString()) }
        attempt.pricingSource?.let { put(ChatMessageMetadataKeys.PRICING_SOURCE, it.toString()) }
        if (attempt.pricingMissing) put(ChatMessageMetadataKeys.PRICING_MISSING, "true")
    }

    val statsKeys = setOf(
        ChatMessageMetadataKeys.PROMPT_TOKENS,
        ChatMessageMetadataKeys.COMPLETION_TOKENS,
        ChatMessageMetadataKeys.CACHED_TOKENS,
        ChatMessageMetadataKeys.TOTAL_TOKENS,
        ChatMessageMetadataKeys.REASONING_TOKENS,
        ChatMessageMetadataKeys.DURATION_MS,
        ChatMessageMetadataKeys.TTFT_MS,
        ChatMessageMetadataKeys.COST_ID,
        ChatMessageMetadataKeys.COST_USD,
        ChatMessageMetadataKeys.COST_MODEL,
        ChatMessageMetadataKeys.PRICING_SOURCE,
        ChatMessageMetadataKeys.PRICING_MISSING,
    )
}
