package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息表。**fragments 以 JSON 存** [fragmentsJson]（kotlinx.serialization 多态序列化），
 * 首版简单可靠；如需细粒度查询/部分更新，阶段 5 可拆独立 FragmentEntity 表。
 */
@Entity(
    tableName = "messages",
    indices = [Index("sessionId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val role: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val fragmentsJson: String,
    val rawText: String?,
    val model: String?,
    val metadataJson: String,
)
