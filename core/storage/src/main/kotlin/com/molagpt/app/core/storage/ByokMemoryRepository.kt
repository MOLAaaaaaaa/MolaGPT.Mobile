package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ByokMemoryCandidate
import com.molagpt.app.core.model.ByokMemoryEntry
import com.molagpt.app.core.model.ByokMemoryEvidence
import com.molagpt.app.core.model.ByokMemoryOrigin
import com.molagpt.app.core.model.ByokMemoryProjection
import com.molagpt.app.core.model.ByokMemoryScopes
import com.molagpt.app.core.model.ByokMemoryTopic
import com.molagpt.app.core.model.ByokMemoryTopics
import com.molagpt.app.core.model.ByokProfileKey
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.InsightCategory
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.model.normalizeMemoryKey
import com.molagpt.app.core.storage.dao.ByokMemoryDao
import com.molagpt.app.core.storage.entity.ByokMemoryEvidenceEntity
import com.molagpt.app.core.storage.entity.ByokMemorySuppressionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * BYOK 本地记忆仓库。**所有写入路径（手动、工具、自动整理）都收口在这里**，
 * 去重、强化与 suppression 判定只有一份实现——否则「删掉的记忆自己长回来」
 * 这类问题会随着写入方增加而反复出现。
 */
class ByokMemoryRepository(
    private val dao: ByokMemoryDao,
    private val settingsStore: SettingsStore,
    private val dispatchers: DispatcherProvider,
    /**
     * 「清除全部」时把各会话的整理水位线推到该时间点。
     * 用回调而不是直接依赖 `SessionRepository`：会话仓库反过来是被聊天链路依赖的，
     * 直接引用会绕出一个环。
     */
    private val onCleared: suspend (at: Long) -> Unit = {},
    private val scope: String = ByokMemoryScopes.GLOBAL,
) {

    // ── 读 ──────────────────────────────────────────────────────────────────

    fun observeEntries(): Flow<List<ByokMemoryEntry>> =
        dao.observeEntries(scope).map { rows -> rows.map { it.toDomain() } }

    fun observeCandidates(): Flow<List<ByokMemoryCandidate>> =
        dao.observeCandidates(scope).map { rows -> rows.map { it.toDomain() } }

    fun observeTopics(): Flow<List<ByokMemoryTopic>> =
        dao.observeTopics(scope).map { rows -> mergeTopics(rows.map { it.toDomain() }) }

    suspend fun entries(): List<ByokMemoryEntry> =
        withContext(dispatchers.io) { dao.entries(scope).map { it.toDomain() } }

    suspend fun topics(): List<ByokMemoryTopic> =
        withContext(dispatchers.io) { mergeTopics(dao.topics(scope).map { it.toDomain() }) }

    suspend fun profileValue(key: ByokProfileKey): String? = withContext(dispatchers.io) {
        dao.entryByProfileKey(scope, key.wire)?.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun evidenceFor(entryId: String, limit: Int = MAX_EVIDENCE_SHOWN): List<ByokMemoryEvidence> =
        withContext(dispatchers.io) { dao.evidenceFor(entryId, limit).map { it.toDomain() } }

    /** 最近被删除或忽略的事实，供自动整理避免用同义改写重新写回。 */
    suspend fun suppressedTexts(limit: Int = MAX_SUPPRESSIONS_IN_DIGEST): List<String> =
        withContext(dispatchers.io) { dao.suppressions(scope).take(limit).map { it.text } }

    /**
     * 本次请求实际会发出去的内容。记忆页与会话内面板都读它，保证显示与实际一致。
     * [budgetTokens] 为 null 时取用户在记忆页设置的上限。
     */
    suspend fun projection(
        nowMillis: Long = System.currentTimeMillis(),
        budgetTokens: Int? = null,
    ): ByokMemoryProjection = withContext(dispatchers.io) {
        ByokMemoryProjector.project(
            entries = dao.entries(scope).map { it.toDomain() },
            nowMillis = nowMillis,
            budgetTokens = budgetTokens ?: settingsStore.settings.first().byokMemoryBudgetTokens,
        )
    }

    // ── 手动维护 ────────────────────────────────────────────────────────────

    /**
     * 用户在记忆页手写一条。
     *
     * `permanent = true` 且 `origin = MANUAL`：不衰减、排序置顶、自动整理不得改写。
     * 同时清掉同键的 suppression——用户重新写下这句话，就是最明确的「我要它回来」。
     */
    suspend fun addManual(
        text: String,
        section: MemorySection,
        profileKey: ByokProfileKey? = null,
        topicId: String? = null,
    ): ByokMemoryEntry? = withContext(dispatchers.io) {
        val clean = text.trim().take(ByokMemoryEntry.MAX_TEXT_CHARS)
        if (clean.isEmpty()) return@withContext null
        val key = normalizeMemoryKey(clean)
        if (key.isEmpty()) return@withContext null
        val now = System.currentTimeMillis()
        val existingByKey = dao.entryByKey(scope, key)
        val existingByProfile = profileKey?.let { dao.entryByProfileKey(scope, it.wire) }
        val existing = existingByProfile ?: existingByKey
        val replacedIds = listOfNotNull(existingByKey?.id, existingByProfile?.id)
            .filter { it != existing?.id }
        val entry = ByokMemoryEntry(
            id = existing?.id ?: Ids.newMemoryId(),
            scope = scope,
            text = clean,
            normalizedKey = key,
            section = section,
            category = existing?.category?.let { InsightCategory.fromWire(it) },
            profileKey = profileKey,
            topicId = topicId ?: existing?.topicId ?: ByokMemoryTopics.defaultId(section),
            confidence = ByokMemoryEntry.MAX_CONFIDENCE,
            halfLifeDays = null,
            expiresAt = null,
            permanent = true,
            origin = ByokMemoryOrigin.MANUAL,
            recurrence = existing?.recurrence ?: 1,
            firstObservedAt = existing?.firstObservedAt ?: now,
            lastReinforcedAt = now,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsertEntryReplacing(
            entry = entry.toEntity(),
            replacedIds = replacedIds,
            clearEvidence = existing != null && existing.normalizedKey != key,
            suppressionScope = scope,
            suppressionKey = key,
        )
        entry
    }

    /** 编辑既有条目的正文 / 分节 / 画像字段。改文本会改去重键，需要防撞车。 */
    suspend fun updateEntry(
        id: String,
        text: String,
        section: MemorySection,
        profileKey: ByokProfileKey?,
        topicId: String?,
    ): Boolean = withContext(dispatchers.io) {
        val current = dao.entry(id) ?: return@withContext false
        val clean = text.trim().take(ByokMemoryEntry.MAX_TEXT_CHARS)
        if (clean.isEmpty()) return@withContext false
        val key = normalizeMemoryKey(clean)
        if (key.isEmpty()) return@withContext false
        val keyCollision = dao.entryByKey(scope, key)?.takeIf { it.id != id }
        val profileCollision = profileKey?.let { dao.entryByProfileKey(scope, it.wire) }?.takeIf { it.id != id }
        dao.upsertEntryReplacing(
            entry = current.copy(
                text = clean,
                normalizedKey = key,
                section = section.wire,
                profileKey = profileKey?.wire,
                topicId = topicId,
                updatedAt = System.currentTimeMillis(),
            ),
            replacedIds = listOfNotNull(keyCollision?.id, profileCollision?.id),
            // 来源引文只支持原文。用户改了正文后继续展示旧引文会形成错误溯源。
            clearEvidence = current.normalizedKey != key,
            suppressionScope = scope,
            suppressionKey = key,
        )
        true
    }

    /**
     * 删除一条记忆并写 suppression。
     *
     * 只删不压制的话，同一句话会在下一轮自动整理里原样回来——用户会认为记忆删不掉。
     */
    suspend fun deleteEntry(id: String, suppress: Boolean = true) = withContext(dispatchers.io) {
        val entry = dao.entry(id) ?: return@withContext
        dao.deleteEntryWithSuppression(
            entryId = id,
            suppression = if (suppress) {
                suppression(entry.normalizedKey, entry.text, REASON_DELETED, scope)
            } else {
                null
            },
        )
    }

    // ── 候选 ────────────────────────────────────────────────────────────────

    /** 用户确认一条候选：晋升为正式条目，并把候选当时的逐字原话留成证据。 */
    suspend fun confirmCandidate(candidateId: String): ByokMemoryEntry? = withContext(dispatchers.io) {
        val candidate = dao.candidate(candidateId)?.toDomain() ?: return@withContext null
        val now = System.currentTimeMillis()
        val existingByKey = dao.entryByKey(scope, candidate.normalizedKey)
        val existingByProfile = candidate.profileKey?.let { dao.entryByProfileKey(scope, it.wire) }
        val existing = existingByProfile ?: existingByKey
        val entry = ByokMemoryEntry(
            id = existing?.id ?: Ids.newMemoryId(),
            scope = scope,
            text = candidate.text,
            normalizedKey = candidate.normalizedKey,
            section = candidate.section,
            category = candidate.category,
            profileKey = candidate.profileKey,
            topicId = candidate.topicId,
            // 用户确认过，可信度等同手动写入：`permanent = true` 已经保证不衰减、排序置顶。
            // 但来源仍标 CONFIRMED 而不是 MANUAL——这条是模型提取、用户点头，
            // 标成「手动添加」等于告诉用户这句话是他自己写的。
            confidence = ByokMemoryEntry.MAX_CONFIDENCE,
            permanent = true,
            origin = ByokMemoryOrigin.CONFIRMED,
            recurrence = (existing?.recurrence ?: 0) + 1,
            firstObservedAt = existing?.firstObservedAt ?: candidate.createdAt,
            lastReinforcedAt = now,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.promoteCandidate(
            entry = entry.toEntity(),
            evidence = ByokMemoryEvidenceEntity(
                id = Ids.newMemoryId(),
                entryId = entry.id,
                sessionId = candidate.sourceSessionId,
                messageId = candidate.sourceMessageId,
                quote = candidate.quote.take(ByokMemoryEvidence.MAX_QUOTE_CHARS),
                observedAt = candidate.createdAt,
            ),
            replacedIds = listOfNotNull(existingByKey?.id, existingByProfile?.id),
            clearExistingEvidence = existing != null && existing.normalizedKey != candidate.normalizedKey,
            candidateId = candidateId,
            suppressionScope = scope,
            suppressionKey = candidate.normalizedKey,
        )
        entry
    }

    /** 用户忽略一条候选：写 suppression，自动整理不再重复建议同一件事。 */
    suspend fun dismissCandidate(candidateId: String) = withContext(dispatchers.io) {
        val candidate = dao.candidate(candidateId) ?: return@withContext
        dao.dismissCandidateWithSuppression(
            candidateId = candidateId,
            suppression = suppression(candidate.normalizedKey, candidate.text, REASON_DISMISSED, scope),
        )
    }

    // ── 写入原语（工具与自动整理共用） ──────────────────────────────────────

    /** 该事实是否已被用户压制。所有自动写入路径都必须先问这一句。 */
    suspend fun isSuppressed(normalizedKey: String): Boolean =
        withContext(dispatchers.io) { dao.suppression(scope, normalizedKey) != null }

    suspend fun suppress(normalizedKey: String, text: String, reason: String) =
        withContext(dispatchers.io) {
            dao.upsertSuppression(
                ByokMemorySuppressionEntity(
                    scope = scope,
                    normalizedKey = normalizedKey,
                    text = text.take(ByokMemoryEntry.MAX_TEXT_CHARS),
                    reason = reason,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

    /**
     * 工具与自动整理的唯一写入口：同键强化、异键新增、被压制则拒绝。
     *
     * 返回 null 表示这次写入被拒（命中 suppression 或文本无效），调用方据此给模型一个明确的失败原因，
     * 而不是假装写成功——否则模型会反复尝试写同一条。
     */
    suspend fun saveFromModel(
        text: String,
        section: MemorySection,
        profileKey: ByokProfileKey?,
        topicId: String?,
        sessionId: String,
        messageId: String,
        quote: String,
        confidence: Double,
        origin: ByokMemoryOrigin,
        /** `supersede` 指向的旧条目。成功写入后与新条目在同一事务中移除。 */
        replaceEntryIds: Set<String> = emptySet(),
    ): SaveResult = withContext(dispatchers.io) {
        val clean = text.trim().take(ByokMemoryEntry.MAX_TEXT_CHARS)
        if (clean.isEmpty()) return@withContext SaveResult.Rejected("text 为空")
        val key = normalizeMemoryKey(clean)
        if (key.isEmpty()) return@withContext SaveResult.Rejected("text 不含有效内容")
        if (dao.suppression(scope, key) != null) {
            return@withContext SaveResult.Rejected("用户此前删除过这条记忆，不能自动写回")
        }

        val now = System.currentTimeMillis()
        val profileExisting = profileKey?.let { dao.entryByProfileKey(scope, it.wire) }
        if (profileExisting != null && profileExisting.normalizedKey != key && profileExisting.isUserAuthored) {
            return@withContext SaveResult.Rejected("该个人信息由用户维护，不能自动改写")
        }
        val replacements = (replaceEntryIds + listOfNotNull(profileExisting?.id)).toList()
        val existing = dao.entryByKey(scope, key)
        if (existing != null) {
            val reclassified = if (
                (profileKey != null && existing.profileKey != profileKey.wire) ||
                (topicId != null && existing.topicId != topicId)
            ) {
                existing.copy(
                    section = section.wire,
                    profileKey = profileKey?.wire ?: existing.profileKey,
                    topicId = topicId ?: existing.topicId,
                )
            } else {
                null
            }
            val reinforced = dao.reinforceEntryWithEvidence(
                entryId = existing.id,
                reclassified = reclassified,
                now = now,
                delta = REINFORCE_DELTA,
                ceiling = ByokMemoryEntry.MAX_CONFIDENCE,
                evidence = ByokMemoryEvidenceEntity(
                    id = Ids.newMemoryId(),
                    entryId = existing.id,
                    sessionId = sessionId,
                    messageId = messageId,
                    quote = quote.take(ByokMemoryEvidence.MAX_QUOTE_CHARS),
                    observedAt = now,
                ),
                replacedIds = replacements,
            )
            val saved = dao.entry(existing.id)!!.toDomain()
            return@withContext if (reinforced) SaveResult.Reinforced(saved) else SaveResult.Existing(saved)
        }

        val entry = ByokMemoryEntry(
            id = Ids.newMemoryId(),
            scope = scope,
            text = clean,
            normalizedKey = key,
            section = section,
            profileKey = profileKey,
            topicId = topicId,
            confidence = confidence.coerceIn(0.0, ByokMemoryEntry.MAX_CONFIDENCE),
            halfLifeDays = section.defaultHalfLifeDays(),
            permanent = false,
            origin = origin,
            recurrence = 1,
            firstObservedAt = now,
            lastReinforcedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertEntryWithEvidenceReplacing(
            entry = entry.toEntity(),
            evidence = ByokMemoryEvidenceEntity(
                id = Ids.newMemoryId(),
                entryId = entry.id,
                sessionId = sessionId,
                messageId = messageId,
                quote = quote.take(ByokMemoryEvidence.MAX_QUOTE_CHARS),
                observedAt = now,
            ),
            replacedIds = replacements,
            clearExistingEvidence = false,
        )
        SaveResult.Added(entry)
    }

    suspend fun addCandidate(
        text: String,
        section: MemorySection,
        profileKey: ByokProfileKey?,
        topicId: String?,
        sessionId: String,
        messageId: String,
        quote: String,
        confidence: Double,
    ): ByokMemoryCandidate? = withContext(dispatchers.io) {
        val clean = text.trim().take(ByokMemoryEntry.MAX_TEXT_CHARS)
        if (clean.isEmpty()) return@withContext null
        val key = normalizeMemoryKey(clean)
        if (key.isEmpty()) return@withContext null
        if (dao.suppression(scope, key) != null) return@withContext null
        if (dao.entryByKey(scope, key) != null) return@withContext null
        if (dao.candidateByKey(scope, key) != null) return@withContext null

        val candidate = ByokMemoryCandidate(
            id = Ids.newMemoryId(),
            scope = scope,
            text = clean,
            normalizedKey = key,
            section = section,
            profileKey = profileKey,
            topicId = topicId,
            confidence = confidence.coerceIn(0.0, 1.0),
            sourceSessionId = sessionId,
            sourceMessageId = messageId,
            quote = quote.take(ByokMemoryEvidence.MAX_QUOTE_CHARS),
            createdAt = System.currentTimeMillis(),
        )
        dao.upsertCandidate(candidate.toEntity())
        candidate
    }

    suspend fun saveTopic(
        id: String?,
        title: String,
        group: String,
        summary: String,
    ): ByokMemoryTopic? = withContext(dispatchers.io) {
        val cleanTitle = title.trim()
        val cleanSummary = summary.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > 60 || cleanSummary.length > 240) return@withContext null
        if (group !in ByokMemoryTopics.groups) return@withContext null
        val key = normalizeMemoryKey(cleanTitle)
        if (key.isEmpty()) return@withContext null
        val matchingDefault = ByokMemoryTopics.defaults.firstOrNull { normalizeMemoryKey(it.title) == key }
        if (id == null && matchingDefault != null) return@withContext null
        if (matchingDefault != null && id != null && matchingDefault.id != id) return@withContext null
        val existingByKey = dao.topicByKey(scope, key)?.toDomain()
        if (id == null && existingByKey != null) return@withContext null
        if (existingByKey != null && id != null && existingByKey.id != id) return@withContext null
        val now = System.currentTimeMillis()
        val existing = id?.let { dao.topic(it)?.toDomain() }
        val topic = ByokMemoryTopic(
            id = existing?.id ?: id ?: existingByKey?.id ?: matchingDefault?.id ?: Ids.newMemoryTopicId(),
            scope = scope,
            group = group,
            title = cleanTitle,
            summary = cleanSummary,
            createdAt = existing?.createdAt ?: existingByKey?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsertTopic(topic.toEntity())
        topic
    }

    suspend fun resolveTopic(
        section: MemorySection,
        title: String?,
        group: String?,
        summary: String?,
    ): ByokMemoryTopic = withContext(dispatchers.io) {
        val cleanTitle = title?.trim()?.take(60).orEmpty()
        if (cleanTitle.isEmpty()) {
            return@withContext mergeTopics(dao.topics(scope).map { it.toDomain() })
                .first { it.id == ByokMemoryTopics.defaultId(section) }
        }
        val key = normalizeMemoryKey(cleanTitle)
        dao.topicByKey(scope, key)?.toDomain()?.let { return@withContext it }
        ByokMemoryTopics.defaults.firstOrNull { normalizeMemoryKey(it.title) == key }?.let { return@withContext it }
        val validGroup = group?.takeIf { it in ByokMemoryTopics.groups }
            ?: ByokMemoryTopics.defaults.first { it.id == ByokMemoryTopics.defaultId(section) }.group
        val now = System.currentTimeMillis()
        val topic = ByokMemoryTopic(
            id = Ids.newMemoryTopicId(),
            scope = scope,
            group = validGroup,
            title = cleanTitle,
            summary = summary?.trim()?.take(240).orEmpty(),
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertTopic(topic.toEntity())
        topic
    }

    suspend fun assignTopic(entryIds: List<String>, topicId: String) = withContext(dispatchers.io) {
        if (entryIds.isNotEmpty()) dao.assignTopic(entryIds.distinct(), topicId, System.currentTimeMillis())
    }

    suspend fun deleteTopic(id: String): Boolean = withContext(dispatchers.io) {
        if (ByokMemoryTopics.isDefault(id) || dao.topic(id) == null) return@withContext false
        dao.deleteTopicWithSuppressions(scope, id, REASON_DELETED, System.currentTimeMillis())
        true
    }

    /**
     * `forget_memory` 的匹配：先精确命中去重键，否则做子串匹配。
     * **只有唯一匹配才删**——多条命中时交给用户在记忆页处理，不替模型猜。
     */
    suspend fun findForForget(query: String): List<ByokMemoryEntry> = withContext(dispatchers.io) {
        val clean = query.trim()
        if (clean.isEmpty()) return@withContext emptyList()
        val key = normalizeMemoryKey(clean)
        val all = dao.entries(scope).map { it.toDomain() }
        all.firstOrNull { it.normalizedKey == key }?.let { return@withContext listOf(it) }
        if (key.isEmpty()) return@withContext emptyList()
        all.filter { it.normalizedKey.contains(key) || key.contains(it.normalizedKey) }
    }

    // ── 清空 ────────────────────────────────────────────────────────────────

    /**
     * 清除全部。同时把所有会话的整理水位线推到当前时间：否则重新开启自动学习后，
     * 旧消息会被再扫一遍，用户刚清掉的东西又全回来了。
     */
    suspend fun clearAll() = withContext(dispatchers.io) {
        dao.deleteAllEntries(scope)
        // 外键级联已经会删掉对应证据，这里再扫一遍是为了不给「清除全部」留任何解释空间：
        // 溯源记录里存着用户的逐字原话，用户点了清除就不该还剩下。
        dao.deleteAllEvidence()
        dao.deleteAllCandidates(scope)
        dao.deleteAllSuppressions(scope)
        dao.deleteAllTopics(scope)
        val now = System.currentTimeMillis()
        settingsStore.setByokMemoryResetAt(now)
        onCleared(now)
    }

    sealed interface SaveResult {
        data class Added(val entry: ByokMemoryEntry) : SaveResult
        data class Reinforced(val entry: ByokMemoryEntry) : SaveResult
        data class Existing(val entry: ByokMemoryEntry) : SaveResult
        data class Rejected(val reason: String) : SaveResult
    }

    companion object {
        const val REASON_DELETED = "deleted"
        const val REASON_DENIED = "denied"
        const val REASON_DISMISSED = "dismissed"


        /** 每次复现的置信度增量。刻意小：靠反复出现慢慢变强，而不是说一次就当定论。 */
        private const val REINFORCE_DELTA = 0.08

        private const val MAX_EVIDENCE_SHOWN = 3
        private const val MAX_SUPPRESSIONS_IN_DIGEST = 30
    }

    private fun mergeTopics(saved: List<ByokMemoryTopic>): List<ByokMemoryTopic> {
        val savedById = saved.associateBy { it.id }
        return ByokMemoryTopics.defaults.map { savedById[it.id] ?: it } +
            saved.filterNot { ByokMemoryTopics.isDefault(it.id) }
    }
}

private fun suppression(
    normalizedKey: String,
    text: String,
    reason: String,
    scope: String,
): ByokMemorySuppressionEntity = ByokMemorySuppressionEntity(
    scope = scope,
    normalizedKey = normalizedKey,
    text = text.take(ByokMemoryEntry.MAX_TEXT_CHARS),
    reason = reason,
    createdAt = System.currentTimeMillis(),
)

private val com.molagpt.app.core.storage.entity.ByokMemoryEntryEntity.isUserAuthored: Boolean
    get() {
        val parsed = ByokMemoryOrigin.fromWire(origin)
        return parsed == ByokMemoryOrigin.MANUAL || parsed == ByokMemoryOrigin.CONFIRMED
    }

/**
 * 分节决定默认半衰期。身份与明确要求不衰减——它们不会因为最近没提起就变得不真；
 * 近期上下文衰减最快，它本来就只描述"这阵子"。
 */
private fun MemorySection.defaultHalfLifeDays(): Double? = when (this) {
    MemorySection.IDENTITY, MemorySection.PROHIBITION -> null
    MemorySection.PREFERENCE -> 180.0
    MemorySection.PROJECT -> 60.0
    MemorySection.CONTEXT -> 21.0
}
