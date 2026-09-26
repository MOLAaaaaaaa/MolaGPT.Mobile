package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 上下文压缩检查点：[anchorMessageId] 及之前的消息在请求里由 [summary] 代替。
 *
 * 原消息一条不动，界面照常显示；删掉检查点即恢复发送完整历史。
 * [coveredDigest] 是锚点及之前内容的指纹——其中任何一条被编辑、切换版本，摘要就与原文对不上，
 * 此时置 [stale] 并停止使用，而不是继续发一份过时的摘要。
 */
@Entity(
    tableName = "context_checkpoints",
    indices = [Index("sessionId")],
)
data class ContextCheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val anchorMessageId: String,
    val summary: String,
    val coveredDigest: String,
    /** 压缩前后的上下文大小（token，估算并按实测用量校准）。 */
    val tokensBefore: Int,
    val tokensAfter: Int,
    val reason: String,
    /** 生成摘要所用的模型。 */
    val providerId: String?,
    val modelId: String?,
    /** 生成摘要本身消耗的 token。 */
    val inputTokens: Int?,
    val outputTokens: Int?,
    val stale: Boolean,
    val createdAt: Long,
)
