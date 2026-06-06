package com.molagpt.app.core.storage

import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.storage.entity.ConversationEntity
import com.molagpt.app.core.storage.entity.MessageEntity

internal fun MessageEntity.toDomain(): ChatMessage {
    val meta = MessageJson.decodeMeta(metadataJson)
    return ChatMessage(
        messageId = messageId,
        sessionId = sessionId,
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.ASSISTANT),
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE),
        createdAt = createdAt,
        updatedAt = updatedAt,
        fragments = MessageJson.decodeFragments(fragmentsJson),
        rawText = rawText,
        model = model,
        attachments = MessageJson.decodeAttachments(meta["attachments"]),
        metadata = meta,
    )
}

internal fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    messageId = messageId,
    sessionId = sessionId,
    role = role.name,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    fragmentsJson = MessageJson.encodeFragments(fragments),
    rawText = rawText,
    model = model,
    metadataJson = MessageJson.encodeMeta(
        if (attachments.isEmpty()) {
            metadata
        } else {
            metadata + ("attachments" to MessageJson.encodeAttachments(attachments))
        },
    ),
)

internal fun ConversationEntity.toDomain(): Conversation = Conversation(
    sessionId = sessionId,
    title = title,
    model = model,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    favorite = favorite,
    lastMessagePreview = lastMessagePreview,
)

internal fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    sessionId = sessionId,
    title = title,
    model = model,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    favorite = favorite,
    lastMessagePreview = lastMessagePreview,
    messageCount = 0,
    visibleInList = false,
    // 经领域模型写入的都是本地改动，标脏待 push；云端拉取的会话由 SyncEngine 直接构造实体（dirty=false）。
    dirty = true,
    deletedAt = null,
    placeholder = false,
)
