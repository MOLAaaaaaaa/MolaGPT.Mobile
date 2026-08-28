package com.molagpt.app.core.storage

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.ProviderIds
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.storage.dao.ConversationDao
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 会话本地仓库，提供创建、删除、重命名、置顶与历史分页等能力。 */
class SessionRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider,
    /** 云同步开启时删除走墓碑（待 push delete），否则直接硬删（游客无需保留墓碑）。 */
    private val cloudSyncEnabled: () -> Boolean = { false },
) {
    fun pagedSessions(searchQuery: String = ""): Flow<PagingData<SessionHit>> {
        val query = searchQuery.trim().take(MAX_SEARCH_QUERY_CHARS).takeIf { it.isNotEmpty() }
        val config = PagingConfig(
            pageSize = SESSION_ROWS_PER_PAGE,
            enablePlaceholders = false,
        )
        // 两个 DAO 方法的元素类型不同（实体 vs 搜索投影），共用一个 pagingSourceFactory
        // 推不出公共类型，所以按分支各建各的 Pager。
        return if (query == null) {
            Pager(config) { conversationDao.pagingSource() }.flow
                .map { pagingData -> pagingData.map { SessionHit(it.toDomain()) } }
        } else {
            Pager(config) { conversationDao.searchPagingSource(query) }.flow
                .map { pagingData ->
                    pagingData.map { SessionHit(it.conversation.toDomain(), it.matchedSnippet) }
                }
        }
    }

    suspend fun create(
        title: String = DEFAULT_TITLE,
        model: String? = null,
        providerId: String? = ProviderIds.MOLAGPT,
        providerKind: ProviderKind = ProviderKind.MOLAGPT,
        personaId: String? = null,
    ): Conversation =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val c = Conversation(
                sessionId = Ids.newSessionId(),
                title = title,
                model = model,
                providerId = providerId,
                providerKind = providerKind,
                createdAt = now,
                updatedAt = now,
                personaId = personaId,
            )
            conversationDao.upsert(c.toEntity())
            c
        }

    /** 确保会话存在（用于 VM 先生成 sessionId 再落库的场景）。 */
    suspend fun ensure(
        sessionId: String,
        title: String,
        model: String?,
        providerId: String? = ProviderIds.MOLAGPT,
        providerKind: ProviderKind = ProviderKind.MOLAGPT,
        personaId: String? = null,
    ) = withContext(dispatchers.io) {
        val existing = conversationDao.getById(sessionId)
        if (existing == null) {
            val now = System.currentTimeMillis()
            conversationDao.upsert(
                Conversation(
                    sessionId = sessionId,
                    title = title,
                    model = model,
                    providerId = providerId,
                    providerKind = providerKind,
                    createdAt = now,
                    updatedAt = now,
                    personaId = personaId,
                ).toEntity(),
            )
        } else {
            if (existing.title == DEFAULT_TITLE && title != DEFAULT_TITLE) {
                // 会话已被预创建为占位标题（如跨阵营切换进入 BYOK），首条消息发送时把占位标题换成消息内容，
                // 使顶栏在流式回复期间即显示临时标题，与 MolaGPT 默认入口的懒创建行为一致。
                conversationDao.rename(sessionId, title, System.currentTimeMillis())
            }
            if (providerKind == ProviderKind.BYOK && personaId != null && existing.personaId != personaId) {
                conversationDao.updatePersona(sessionId, personaId, System.currentTimeMillis())
            }
        }
    }

    suspend fun get(sessionId: String): Conversation? =
        withContext(dispatchers.io) { conversationDao.getById(sessionId)?.toDomain() }

    /** 观察单个会话（顶栏标题随重命名实时刷新）。 */
    fun observe(sessionId: String): Flow<Conversation?> =
        conversationDao.observeById(sessionId).map { it?.toDomain() }

    suspend fun rename(sessionId: String, title: String) =
        withContext(dispatchers.io) { conversationDao.rename(sessionId, title, System.currentTimeMillis()) }

    suspend fun updateModel(
        sessionId: String,
        model: String?,
        providerId: String? = ProviderIds.MOLAGPT,
        providerKind: ProviderKind = ProviderKind.MOLAGPT,
    ) = withContext(dispatchers.io) {
        conversationDao.updateModel(sessionId, model, providerId, providerKind.name, System.currentTimeMillis())
    }

    /** 绑定 / 切换会话角色（仅 BYOK 会话调用）。 */
    suspend fun updatePersona(sessionId: String, personaId: String?) =
        withContext(dispatchers.io) {
            conversationDao.updatePersona(sessionId, personaId, System.currentTimeMillis())
        }

    suspend fun setPinned(sessionId: String, pinned: Boolean) =
        withContext(dispatchers.io) { conversationDao.setPinned(sessionId, pinned) }

    suspend fun setFavorite(sessionId: String, favorite: Boolean) =
        withContext(dispatchers.io) { conversationDao.setFavorite(sessionId, favorite) }

    suspend fun delete(sessionId: String) = withContext(dispatchers.io) {
        deleteOne(conversationDao.getById(sessionId), sessionId)
    }

    /**
     * 批量删除。逐条走与 [delete] 完全相同的墓碑 / 硬删判定（共用 [deleteOne]，避免两处判定漂移），
     * 返回实际处理的 sessionId，供上层对 MolaGPT 会话逐个 schedulePush。
     */
    suspend fun deleteAll(sessionIds: Collection<String>): List<String> = withContext(dispatchers.io) {
        val ids = sessionIds.distinct()
        if (ids.isEmpty()) return@withContext emptyList<String>()
        ids.chunked(SQLITE_VARIABLE_CHUNK).flatMap { chunk ->
            val existing = conversationDao.getByIds(chunk).associateBy { it.sessionId }
            chunk.map { sessionId ->
                deleteOne(existing[sessionId], sessionId)
                sessionId
            }
        }
    }

    /** 「全选」取数：数据库中全部可见会话，不限于 Paging 已加载的部分。 */
    suspend fun allVisibleSessionIds(): List<String> =
        withContext(dispatchers.io) { conversationDao.allVisibleSessionIds() }

    /**
     * 单条删除的落库判定，调用方需已在 IO 线程。
     * 先清本地消息；只有 MolaGPT 云同步会话需要墓碑，BYOK 始终本地硬删，避免进入账户同步通道。
     */
    private suspend fun deleteOne(conversation: ConversationEntity?, sessionId: String) {
        messageDao.deleteBySession(sessionId)
        if (cloudSyncEnabled() && conversation?.providerKind == ProviderKind.MOLAGPT.name) {
            conversationDao.tombstone(sessionId, System.currentTimeMillis())
        } else {
            conversationDao.deleteById(sessionId)
        }
    }

    suspend fun fetchHistoryMessages(sessionId: String, page: Int, size: Int): List<ChatMessage> =
        withContext(dispatchers.io) {
            messageDao.getPaged(sessionId, size, page * size).map { it.toDomain() }
        }

    companion object {
        // Data-layer page size only. Initial load and prefetch distance use Paging defaults,
        // so this policy is not tied to one device, account size, or measured session count.
        private const val SESSION_ROWS_PER_PAGE = 48

        // 批量删除按此粒度切分 IN (...) 查询：SQLite 单条语句的绑定变量上限是 999，
        // 「全选」在长期使用的账户上可能上千条。
        private const val SQLITE_VARIABLE_CHUNK = 400

        // 新会话的占位标题：预创建行（如跨阵营切换进入 BYOK）以此标题落库，
        // 首条消息发送时由 ensure 替换为消息内容。
        const val DEFAULT_TITLE = "新对话"

        /**
         * 搜索词长度上限：限制输入框可输入长度，同时避免超长 needle 在每次防抖后反复全表扫。
         * 裁剪只在 [pagedSessions] 收口一次。
         */
        const val MAX_SEARCH_QUERY_CHARS = 200
    }
}

/**
 * 侧边栏的一行搜索/列表结果。
 * [matchedSnippet] 只在「正文命中」时非空（标题命中或非搜索态为 null），UI 据此决定是否展示第二行。
 */
data class SessionHit(
    val conversation: Conversation,
    val matchedSnippet: String? = null,
)
