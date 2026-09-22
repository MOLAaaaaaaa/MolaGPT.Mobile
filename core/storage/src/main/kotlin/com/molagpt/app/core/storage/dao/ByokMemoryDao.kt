package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.molagpt.app.core.storage.entity.ByokMemoryCandidateEntity
import com.molagpt.app.core.storage.entity.ByokMemoryEntryEntity
import com.molagpt.app.core.storage.entity.ByokMemoryEvidenceEntity
import com.molagpt.app.core.storage.entity.ByokMemorySuppressionEntity
import com.molagpt.app.core.storage.entity.ByokMemoryTopicEntity
import kotlinx.coroutines.flow.Flow

/**
 * BYOK 本地记忆的数据访问。
 *
 * 排序、衰减与预算裁剪一律**不在 SQL 里做**——有效置信度依赖「距今多少天」，
 * 写成 SQL 会让同一份规则在这里和 `ByokMemoryProjector` 里各存一份。
 * 本地记忆量级是几十到几百条，整表取回后在 Kotlin 侧排序的成本可以忽略。
 */
@Dao
interface ByokMemoryDao {

    // ── 条目 ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM byok_memory_entries WHERE scope = :scope ORDER BY updatedAt DESC")
    fun observeEntries(scope: String): Flow<List<ByokMemoryEntryEntity>>

    @Query("SELECT * FROM byok_memory_entries WHERE scope = :scope")
    suspend fun entries(scope: String): List<ByokMemoryEntryEntity>

    @Query("SELECT * FROM byok_memory_entries WHERE id = :id")
    suspend fun entry(id: String): ByokMemoryEntryEntity?

    @Query("SELECT * FROM byok_memory_entries WHERE scope = :scope AND normalizedKey = :key")
    suspend fun entryByKey(scope: String, key: String): ByokMemoryEntryEntity?

    @Query("SELECT * FROM byok_memory_entries WHERE scope = :scope AND profileKey = :profileKey LIMIT 1")
    suspend fun entryByProfileKey(scope: String, profileKey: String): ByokMemoryEntryEntity?

    /**
     * 必须是 `@Upsert` 而不是 `@Insert(REPLACE)`：REPLACE 在 SQLite 里是「先删后插」，
     * 而证据表对条目是 `ON DELETE CASCADE`——用 REPLACE 编辑一条记忆，会把它的全部来源证据一起删掉。
     */
    @Upsert
    suspend fun upsertEntry(entity: ByokMemoryEntryEntity)

    @Query("DELETE FROM byok_memory_entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM byok_memory_entries WHERE scope = :scope")
    suspend fun deleteAllEntries(scope: String)

    /**
     * 同一事实再次出现时的强化。置信度上调但 clip 到 0.98——
     * 留出余量，让用户的「存疑/否认」永远能把它压下来。
     */
    @Query(
        """
        UPDATE byok_memory_entries
        SET recurrence = recurrence + 1,
            lastReinforcedAt = :now,
            updatedAt = :now,
            confidence = MIN(:ceiling, confidence + :delta)
        WHERE id = :id
        """,
    )
    suspend fun reinforceEntry(id: String, now: Long, delta: Double, ceiling: Double)

    // ── 主题 ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM byok_memory_topics WHERE scope = :scope ORDER BY updatedAt DESC")
    fun observeTopics(scope: String): Flow<List<ByokMemoryTopicEntity>>

    @Query("SELECT * FROM byok_memory_topics WHERE scope = :scope")
    suspend fun topics(scope: String): List<ByokMemoryTopicEntity>

    @Query("SELECT * FROM byok_memory_topics WHERE id = :id")
    suspend fun topic(id: String): ByokMemoryTopicEntity?

    @Query("SELECT * FROM byok_memory_topics WHERE scope = :scope AND normalizedKey = :key")
    suspend fun topicByKey(scope: String, key: String): ByokMemoryTopicEntity?

    @Upsert
    suspend fun upsertTopic(entity: ByokMemoryTopicEntity)

    @Query("UPDATE byok_memory_entries SET topicId = :topicId, updatedAt = :now WHERE id IN (:entryIds)")
    suspend fun assignTopic(entryIds: List<String>, topicId: String, now: Long)

    @Query("DELETE FROM byok_memory_topics WHERE scope = :scope")
    suspend fun deleteAllTopics(scope: String)

    @Query("SELECT * FROM byok_memory_entries WHERE scope = :scope AND topicId = :topicId")
    suspend fun entriesByTopic(scope: String, topicId: String): List<ByokMemoryEntryEntity>

    @Query("SELECT * FROM byok_memory_candidates WHERE scope = :scope AND topicId = :topicId")
    suspend fun candidatesByTopic(scope: String, topicId: String): List<ByokMemoryCandidateEntity>

    @Query("DELETE FROM byok_memory_entries WHERE scope = :scope AND topicId = :topicId")
    suspend fun deleteEntriesByTopic(scope: String, topicId: String)

    @Query("DELETE FROM byok_memory_candidates WHERE scope = :scope AND topicId = :topicId")
    suspend fun deleteCandidatesByTopic(scope: String, topicId: String)

    @Query("DELETE FROM byok_memory_topics WHERE scope = :scope AND id = :topicId")
    suspend fun deleteTopic(scope: String, topicId: String)

    @Transaction
    suspend fun deleteTopicWithSuppressions(scope: String, topicId: String, reason: String, now: Long) {
        entriesByTopic(scope, topicId).forEach { entry ->
            upsertSuppression(
                ByokMemorySuppressionEntity(scope, entry.normalizedKey, entry.text, reason, now),
            )
        }
        candidatesByTopic(scope, topicId).forEach { candidate ->
            upsertSuppression(
                ByokMemorySuppressionEntity(scope, candidate.normalizedKey, candidate.text, reason, now),
            )
        }
        deleteEntriesByTopic(scope, topicId)
        deleteCandidatesByTopic(scope, topicId)
        deleteTopic(scope, topicId)
    }

    // ── 证据 ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvidence(entity: ByokMemoryEvidenceEntity)

    @Query("SELECT * FROM byok_memory_evidence WHERE entryId = :entryId ORDER BY observedAt DESC LIMIT :limit")
    suspend fun evidenceFor(entryId: String, limit: Int): List<ByokMemoryEvidenceEntity>

    @Query("DELETE FROM byok_memory_evidence")
    suspend fun deleteAllEvidence()

    @Query("DELETE FROM byok_memory_evidence WHERE entryId = :entryId")
    suspend fun deleteEvidenceFor(entryId: String)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM byok_memory_evidence " +
            "WHERE entryId = :entryId AND messageId = :messageId AND quote = :quote)",
    )
    suspend fun evidenceExists(entryId: String, messageId: String, quote: String): Boolean

    /** 条目与首条证据必须一起落库，否则会出现无法溯源的「凭空记忆」。 */
    @Transaction
    suspend fun insertEntryWithEvidence(
        entry: ByokMemoryEntryEntity,
        evidence: ByokMemoryEvidenceEntity?,
    ) {
        upsertEntry(entry)
        evidence?.let { upsertEvidence(it) }
    }

    /** 写入新事实并移除被替代的旧条目，整个过程对观察者只呈现最终状态。 */
    @Transaction
    suspend fun insertEntryWithEvidenceReplacing(
        entry: ByokMemoryEntryEntity,
        evidence: ByokMemoryEvidenceEntity?,
        replacedIds: List<String>,
        clearExistingEvidence: Boolean,
    ) {
        if (clearExistingEvidence) deleteEvidenceFor(entry.id)
        replacedIds.filter { it != entry.id }.distinct().forEach { deleteEntry(it) }
        upsertEntry(entry)
        evidence?.let { upsertEvidence(it) }
    }

    @Transaction
    suspend fun promoteCandidate(
        entry: ByokMemoryEntryEntity,
        evidence: ByokMemoryEvidenceEntity,
        replacedIds: List<String>,
        clearExistingEvidence: Boolean,
        candidateId: String,
        suppressionScope: String,
        suppressionKey: String,
    ) {
        if (clearExistingEvidence) deleteEvidenceFor(entry.id)
        replacedIds.filter { it != entry.id }.distinct().forEach { deleteEntry(it) }
        upsertEntry(entry)
        upsertEvidence(evidence)
        deleteCandidate(candidateId)
        deleteSuppression(suppressionScope, suppressionKey)
    }

    /**
     * 同一来源窗口重放时不重复增加 recurrence。Android 进程可能在写入完成、推进水位线之前退出，
     * 因此证据是否已存在才是一次强化是否已经应用过的依据。
     */
    @Transaction
    suspend fun reinforceEntryWithEvidence(
        entryId: String,
        reclassified: ByokMemoryEntryEntity?,
        now: Long,
        delta: Double,
        ceiling: Double,
        evidence: ByokMemoryEvidenceEntity,
        replacedIds: List<String>,
    ): Boolean {
        replacedIds.filter { it != entryId }.distinct().forEach { deleteEntry(it) }
        reclassified?.let { upsertEntry(it) }
        val isNewEvidence = !evidenceExists(entryId, evidence.messageId, evidence.quote)
        if (isNewEvidence) {
            reinforceEntry(entryId, now, delta, ceiling)
            upsertEvidence(evidence)
        }
        return isNewEvidence
    }

    @Transaction
    suspend fun upsertEntryReplacing(
        entry: ByokMemoryEntryEntity,
        replacedIds: List<String>,
        clearEvidence: Boolean,
        suppressionScope: String,
        suppressionKey: String,
    ) {
        replacedIds.filter { it != entry.id }.distinct().forEach { deleteEntry(it) }
        if (clearEvidence) deleteEvidenceFor(entry.id)
        upsertEntry(entry)
        deleteSuppression(suppressionScope, suppressionKey)
    }

    // ── 候选 ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM byok_memory_candidates WHERE scope = :scope ORDER BY createdAt DESC")
    fun observeCandidates(scope: String): Flow<List<ByokMemoryCandidateEntity>>

    @Query("SELECT * FROM byok_memory_candidates WHERE id = :id")
    suspend fun candidate(id: String): ByokMemoryCandidateEntity?

    @Query("SELECT * FROM byok_memory_candidates WHERE scope = :scope AND normalizedKey = :key")
    suspend fun candidateByKey(scope: String, key: String): ByokMemoryCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidate(entity: ByokMemoryCandidateEntity)

    @Query("DELETE FROM byok_memory_candidates WHERE id = :id")
    suspend fun deleteCandidate(id: String)

    @Query("DELETE FROM byok_memory_candidates WHERE scope = :scope")
    suspend fun deleteAllCandidates(scope: String)

    // ── 压制 ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM byok_memory_suppressions WHERE scope = :scope AND normalizedKey = :key")
    suspend fun suppression(scope: String, key: String): ByokMemorySuppressionEntity?

    @Query("SELECT * FROM byok_memory_suppressions WHERE scope = :scope ORDER BY createdAt DESC")
    suspend fun suppressions(scope: String): List<ByokMemorySuppressionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuppression(entity: ByokMemorySuppressionEntity)

    @Query("DELETE FROM byok_memory_suppressions WHERE scope = :scope AND normalizedKey = :key")
    suspend fun deleteSuppression(scope: String, key: String)

    @Query("DELETE FROM byok_memory_suppressions WHERE scope = :scope")
    suspend fun deleteAllSuppressions(scope: String)

    @Transaction
    suspend fun deleteEntryWithSuppression(
        entryId: String,
        suppression: ByokMemorySuppressionEntity?,
    ) {
        deleteEntry(entryId)
        suppression?.let { upsertSuppression(it) }
    }

    @Transaction
    suspend fun dismissCandidateWithSuppression(
        candidateId: String,
        suppression: ByokMemorySuppressionEntity,
    ) {
        deleteCandidate(candidateId)
        upsertSuppression(suppression)
    }
}
