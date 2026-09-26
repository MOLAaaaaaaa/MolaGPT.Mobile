package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 每次成功的摘要请求单独记账；后续分段失败时，已发生的费用仍保留。 */
@Entity(tableName = "context_compaction_usage", indices = [Index("sessionId")])
data class ContextCompactionUsageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val costUsd: Double,
)
