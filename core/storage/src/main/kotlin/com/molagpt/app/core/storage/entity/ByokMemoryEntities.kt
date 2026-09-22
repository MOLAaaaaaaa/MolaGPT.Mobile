package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BYOK 本地记忆的四张表。全部**不写 SQL 默认值**：它们是 v12 新建的空表，没有旧行要回填，
 * 因此 migration 里的 CREATE 语句可以与 Room 生成的 `createAllTables` 逐字一致，校验最省心。
 * 业务默认值放在领域模型的 Kotlin 默认参数上。
 *
 * （给 `conversations` 追加列是另一回事：SQLite 的 `ADD COLUMN ... NOT NULL` 必须带 DEFAULT。
 * Room 只在实体声明了 `defaultValue` 而库里没有时报错，反过来是容忍的——本库 `dirty`、
 * `messageCount` 等列从 v1 起就是这个形态。）
 */

/** 长期记忆的唯一事实源。 */
@Entity(
    tableName = "byok_memory_entries",
    indices = [
        // 去重键唯一：重复表述走强化路径，不堆出第二条几乎一样的记忆。
        Index(value = ["scope", "normalizedKey"], unique = true),
        // SQLite 的 UNIQUE 允许多行 NULL，因此普通记忆不受影响；画像字段每种只能保留一个当前值。
        Index(value = ["scope", "profileKey"], unique = true),
        Index(value = ["scope", "section"]),
        Index(value = ["scope", "topicId"]),
    ],
)
data class ByokMemoryEntryEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val text: String,
    val normalizedKey: String,
    val section: String,
    val category: String?,
    val profileKey: String?,
    val topicId: String?,
    val confidence: Double,
    /** null = 不衰减。 */
    val halfLifeDays: Double?,
    /** 硬过期时间（ms）；null = 无时限。 */
    val expiresAt: Long?,
    val permanent: Boolean,
    val origin: String,
    val recurrence: Int,
    val firstObservedAt: Long,
    val lastReinforcedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "byok_memory_topics",
    indices = [
        Index(value = ["scope", "normalizedKey"], unique = true),
        Index(value = ["scope", "groupName"]),
    ],
)
data class ByokMemoryTopicEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val normalizedKey: String,
    val groupName: String,
    val title: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 条目的来源锚点。只写 `USER` 消息——助手回复、工具输出、联网结果、附件正文
 * 与历史回忆命中都不能作为证据，否则一次成功的注入就能永久改写用户画像。
 */
@Entity(
    tableName = "byok_memory_evidence",
    foreignKeys = [
        ForeignKey(
            entity = ByokMemoryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId"), Index("messageId")],
)
data class ByokMemoryEvidenceEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val sessionId: String,
    val messageId: String,
    val quote: String,
    val observedAt: Long,
)

/** 待用户裁决的低置信度事实。 */
@Entity(
    tableName = "byok_memory_candidates",
    indices = [
        Index(value = ["scope", "normalizedKey"]),
        Index(value = ["createdAt"]),
    ],
)
data class ByokMemoryCandidateEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val text: String,
    val normalizedKey: String,
    val section: String,
    val category: String?,
    val profileKey: String?,
    val topicId: String?,
    val confidence: Double,
    val sourceSessionId: String,
    val sourceMessageId: String,
    val quote: String,
    val createdAt: Long,
)

/**
 * 用户删除、否认或忽略过的事实。
 *
 * 没有这张表，自动学习会在下一轮把刚被删掉的条目原样写回来——用户会看到「删不掉的记忆」。
 * 用户手动添加相同内容时清除对应行，这是唯一的解除路径。
 */
@Entity(
    tableName = "byok_memory_suppressions",
    primaryKeys = ["scope", "normalizedKey"],
)
data class ByokMemorySuppressionEntity(
    val scope: String,
    val normalizedKey: String,
    /** 被压制时的原文，仅供记忆页展示与排查。 */
    val text: String,
    /** `deleted` | `denied` | `dismissed`。 */
    val reason: String,
    val createdAt: Long,
)
