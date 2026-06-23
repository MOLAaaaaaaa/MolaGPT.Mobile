package com.molagpt.app.core.storage

import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.storage.entity.ByokProviderEntity
import com.molagpt.app.core.storage.entity.ConversationEntity
import com.molagpt.app.core.storage.entity.MessageEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    providerId = providerId,
    providerKind = runCatching { ProviderKind.valueOf(providerKind) }.getOrDefault(ProviderKind.MOLAGPT),
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
    providerId = providerId,
    providerKind = providerKind.name,
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

internal fun ByokProviderEntity.toDomain(json: Json, apiKey: String?): ByokProvider {
    val type = runCatching { ByokProviderType.valueOf(type) }.getOrDefault(ByokProviderType.OPENAI_COMPAT)
    val purpose = runCatching { ByokPurpose.valueOf(purpose) }.getOrDefault(ByokPurpose.CHAT)
    val imageFormat = runCatching { ByokImageFormat.valueOf(imageFormat) }.getOrDefault(ByokImageFormat.OPENAI_IMAGES)
    val models = runCatching {
        json.decodeFromString<List<ProviderModel>>(modelsJson)
    }.getOrDefault(emptyList())
    return ByokProvider(
        id = id,
        name = name,
        type = type,
        baseUrl = baseUrl,
        apiKey = apiKey,
        chatPath = chatPath,
        modelsPath = modelsPath,
        imagePath = imagePath,
        purpose = purpose,
        imageFormat = imageFormat,
        imageEditPath = imageEditPath,
        enabled = enabled,
        models = models.map {
            it.copy(providerId = id, providerName = name, providerKind = ProviderKind.BYOK)
        },
    )
}

internal fun ByokProvider.toEntity(json: Json, sortOrder: Int = 0): ByokProviderEntity = ByokProviderEntity(
    id = id,
    name = name,
    type = type.name,
    baseUrl = baseUrl,
    chatPath = chatPath,
    modelsPath = modelsPath,
    imagePath = imagePath,
    purpose = purpose.name,
    imageFormat = imageFormat.name,
    imageEditPath = imageEditPath,
    enabled = enabled,
    modelsJson = json.encodeToString(models.map {
        it.copy(providerId = id, providerName = name, providerKind = ProviderKind.BYOK)
    }),
    sortOrder = sortOrder,
    updatedAt = System.currentTimeMillis(),
)
