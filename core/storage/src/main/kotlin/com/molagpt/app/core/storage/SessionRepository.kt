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
    fun pagedSessions(): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = SESSION_ROWS_PER_PAGE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { conversationDao.pagingSource() },
        ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    suspend fun create(
        title: String = "新对话",
        model: String? = null,
        providerId: String? = ProviderIds.MOLAGPT,
        providerKind: ProviderKind = ProviderKind.MOLAGPT,
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
    ) = withContext(dispatchers.io) {
        if (conversationDao.getById(sessionId) == null) {
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
                ).toEntity(),
            )
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

    suspend fun setPinned(sessionId: String, pinned: Boolean) =
        withContext(dispatchers.io) { conversationDao.setPinned(sessionId, pinned) }

    suspend fun setFavorite(sessionId: String, favorite: Boolean) =
        withContext(dispatchers.io) { conversationDao.setFavorite(sessionId, favorite) }

    suspend fun delete(sessionId: String) = withContext(dispatchers.io) {
        val conversation = conversationDao.getById(sessionId)
        // 先清本地消息。只有 MolaGPT 云同步会话需要墓碑；BYOK 始终本地硬删，避免进入账户同步通道。
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

    private companion object {
        // Data-layer page size only. Initial load and prefetch distance use Paging defaults,
        // so this policy is not tied to one device, account size, or measured session count.
        const val SESSION_ROWS_PER_PAGE = 48
    }
}
