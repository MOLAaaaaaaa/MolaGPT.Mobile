package com.molagpt.app.core.storage

import com.molagpt.app.core.storage.entity.StreamTaskEntity

/** 在途流式任务的领域记录（[BackgroundStreamManager] 与启动对账共用，避免直接暴露 Room 实体）。 */
data class StreamTaskRecord(
    val sessionId: String,
    val streamSessionId: String,
    val conversationId: String,
    val assistantMessageId: String,
    val modelId: String,
    val modelDisplayName: String?,
    val apiUrl: String,
    val createdAt: Long,
)

internal fun StreamTaskEntity.toRecord() = StreamTaskRecord(
    sessionId = sessionId,
    streamSessionId = streamSessionId,
    conversationId = conversationId,
    assistantMessageId = assistantMessageId,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    apiUrl = apiUrl,
    createdAt = createdAt,
)

internal fun StreamTaskRecord.toEntity() = StreamTaskEntity(
    sessionId = sessionId,
    streamSessionId = streamSessionId,
    conversationId = conversationId,
    assistantMessageId = assistantMessageId,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    apiUrl = apiUrl,
    createdAt = createdAt,
)
