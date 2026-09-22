package com.molagpt.app.core.storage

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokMemoryCandidate
import com.molagpt.app.core.model.ByokMemoryEntry
import com.molagpt.app.core.model.ByokMemoryEvidence
import com.molagpt.app.core.model.ByokMemoryOrigin
import com.molagpt.app.core.model.ByokMemoryTopic
import com.molagpt.app.core.model.ByokProfileKey
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.CustomHeader
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.InsightCategory
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.normalizeMemoryKey
import com.molagpt.app.core.storage.entity.ByokMemoryCandidateEntity
import com.molagpt.app.core.storage.entity.ByokMemoryEntryEntity
import com.molagpt.app.core.storage.entity.ByokMemoryEvidenceEntity
import com.molagpt.app.core.storage.entity.ByokMemoryTopicEntity
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

internal fun ChatMessage.toEntity(): MessageEntity {
    val storedFragments = fragments.map { it.stripInlineDataForStorage() }
    val storedAttachments = attachments.map { it.stripInlineDataForStorage() }
    return MessageEntity(
        messageId = messageId,
        sessionId = sessionId,
        role = role.name,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        fragmentsJson = MessageJson.encodeFragments(storedFragments),
        rawText = rawText?.take(MAX_STORED_TEXT_CHARS),
        model = model,
        metadataJson = MessageJson.encodeMeta(
            if (storedAttachments.isEmpty()) {
                metadata
            } else {
                metadata + ("attachments" to MessageJson.encodeAttachments(storedAttachments))
            },
        ),
    )
}

private const val MAX_STORED_TEXT_CHARS = 200_000

private fun MessageFragment.stripInlineDataForStorage(): MessageFragment =
    when (this) {
        is MessageFragment.FileCard -> copy(file = file.stripInlineDataForStorage())
        is MessageFragment.Image -> copy(url = url.stripInlineDataUrl() ?: "")
        else -> this
    }

private fun FileInfo.stripInlineDataForStorage(): FileInfo =
    copy(url = url.stripInlineDataUrl())

private fun Attachment.stripInlineDataForStorage(): Attachment =
    copy(
        remoteUrl = remoteUrl.stripInlineDataUrl(),
        thumbnailUrl = thumbnailUrl.stripInlineDataUrl(),
    )

private fun String?.stripInlineDataUrl(): String? =
    when {
        this == null -> null
        startsWith("data:", ignoreCase = true) && contains(";base64", ignoreCase = true) -> null
        else -> this
    }

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
    personaId = personaId,
    systemPrompt = systemPrompt,
    systemPromptMode = systemPromptMode,
    byokMemoryEnabled = byokMemoryEnabled,
    byokConversationRecallEnabled = byokConversationRecallEnabled,
    byokMemoryWatermarkAt = byokMemoryWatermarkAt,
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
    personaId = personaId,
    systemPrompt = systemPrompt,
    systemPromptMode = systemPromptMode,
    byokMemoryEnabled = byokMemoryEnabled,
    byokConversationRecallEnabled = byokConversationRecallEnabled,
    byokMemoryWatermarkAt = byokMemoryWatermarkAt,
)

// ── BYOK 本地记忆 ───────────────────────────────────────────────────────────
//
// wire 值全部走各枚举的 fromWire：未知值回退默认档而不是抛异常，
// 这样降级安装（新版写入的分类被旧版读到）只会显示得不精确，不会打不开记忆页。

internal fun ByokMemoryEntryEntity.toDomain(
    evidence: List<ByokMemoryEvidenceEntity> = emptyList(),
): ByokMemoryEntry = ByokMemoryEntry(
    id = id,
    scope = scope,
    text = text,
    normalizedKey = normalizedKey,
    section = MemorySection.fromWire(section),
    category = InsightCategory.fromWire(category),
    profileKey = ByokProfileKey.fromWire(profileKey),
    topicId = topicId,
    confidence = confidence,
    halfLifeDays = halfLifeDays,
    expiresAt = expiresAt,
    permanent = permanent,
    origin = ByokMemoryOrigin.fromWire(origin),
    recurrence = recurrence,
    firstObservedAt = firstObservedAt,
    lastReinforcedAt = lastReinforcedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    evidence = evidence.map { it.toDomain() },
)

internal fun ByokMemoryEntry.toEntity(): ByokMemoryEntryEntity = ByokMemoryEntryEntity(
    id = id,
    scope = scope,
    text = text,
    normalizedKey = normalizedKey,
    section = section.wire,
    category = category?.wire,
    profileKey = profileKey?.wire,
    topicId = topicId,
    confidence = confidence,
    halfLifeDays = halfLifeDays,
    expiresAt = expiresAt,
    permanent = permanent,
    origin = origin.wire,
    recurrence = recurrence,
    firstObservedAt = firstObservedAt,
    lastReinforcedAt = lastReinforcedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ByokMemoryEvidenceEntity.toDomain(): ByokMemoryEvidence = ByokMemoryEvidence(
    entryId = entryId,
    sessionId = sessionId,
    messageId = messageId,
    quote = quote,
    observedAt = observedAt,
)

internal fun ByokMemoryCandidateEntity.toDomain(): ByokMemoryCandidate = ByokMemoryCandidate(
    id = id,
    scope = scope,
    text = text,
    normalizedKey = normalizedKey,
    section = MemorySection.fromWire(section),
    category = InsightCategory.fromWire(category),
    profileKey = ByokProfileKey.fromWire(profileKey),
    topicId = topicId,
    confidence = confidence,
    sourceSessionId = sourceSessionId,
    sourceMessageId = sourceMessageId,
    quote = quote,
    createdAt = createdAt,
)

internal fun ByokMemoryCandidate.toEntity(): ByokMemoryCandidateEntity = ByokMemoryCandidateEntity(
    id = id,
    scope = scope,
    text = text,
    normalizedKey = normalizedKey,
    section = section.wire,
    category = category?.wire,
    profileKey = profileKey?.wire,
    topicId = topicId,
    confidence = confidence,
    sourceSessionId = sourceSessionId,
    sourceMessageId = sourceMessageId,
    quote = quote,
    createdAt = createdAt,
)

internal fun ByokMemoryTopicEntity.toDomain(): ByokMemoryTopic = ByokMemoryTopic(
    id = id,
    scope = scope,
    group = groupName,
    title = title,
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ByokMemoryTopic.toEntity(): ByokMemoryTopicEntity = ByokMemoryTopicEntity(
    id = id,
    scope = scope,
    normalizedKey = normalizeMemoryKey(title),
    groupName = group,
    title = title,
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ByokProviderEntity.toDomain(json: Json, apiKey: String?): ByokProvider {
    val type = runCatching { ByokProviderType.valueOf(type) }.getOrDefault(ByokProviderType.OPENAI_COMPAT)
    val purpose = runCatching { ByokPurpose.valueOf(purpose) }.getOrDefault(ByokPurpose.CHAT)
    val imageFormat = runCatching { ByokImageFormat.valueOf(imageFormat) }.getOrDefault(ByokImageFormat.OPENAI_IMAGES)
    val models = runCatching {
        json.decodeFromString<List<ProviderModel>>(modelsJson)
    }.getOrDefault(emptyList())
    val customHeaders = runCatching {
        json.decodeFromString<List<CustomHeader>>(customHeadersJson)
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
        customHeaders = customHeaders,
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
    customHeadersJson = json.encodeToString(customHeaders),
    sortOrder = sortOrder,
    updatedAt = System.currentTimeMillis(),
)
