package com.molagpt.app.core.storage

import com.molagpt.app.core.model.*

data class ModelSpend(val model: String, val costUsd: Double, val turns: Int)
data class ConversationSpend(val costUsd: Double = 0.0, val byModel: List<ModelSpend> = emptyList()) {
    val hasSpend: Boolean get() = costUsd > 0
}

object ConversationSpendCalculator {
    fun from(messages: List<ChatMessage>, providerKind: ProviderKind): ConversationSpend {
        if (providerKind != ProviderKind.BYOK) return ConversationSpend()
        val all = messages + messages.flatMap { EditSnapshots.archivedMessages(it.sessionId, it.metadata[EditSnapshots.KEY]) }
        val seen = mutableSetOf<String>()
        val entries = all.filter { it.role == Role.ASSISTANT }.flatMap { message ->
            val attempts = RetryAttempts.decode(message.metadata[RetryAttempts.KEY_ATTEMPTS])
                .ifEmpty { listOf(RetryAttempts.from(message)) }
            attempts.mapNotNull { attempt ->
                val costId = attempt.costId
                if (costId != null && !seen.add(costId)) return@mapNotNull null
                attempt.costUsd?.let { (attempt.costModel ?: attempt.model ?: "未知模型") to it }
            }
        }
        val byModel = entries.groupBy { it.first }.map { (model, costs) ->
            ModelSpend(model, costs.sumOf { it.second }, costs.size)
        }.sortedByDescending { it.costUsd }
        return ConversationSpend(byModel.sumOf { it.costUsd }, byModel)
    }
}
