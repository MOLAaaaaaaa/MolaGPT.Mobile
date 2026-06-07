package com.molagpt.app.core.storage

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.Ids
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

    suspend fun create(title: String = "新对话", model: String? = null): Conversation =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val c = Conversation(Ids.newSessionId(), title, model, now, now)
            conversationDao.upsert(c.toEntity())
            c
        }

    /** 确保会话存在（用于 VM 先生成 sessionId 再落库的场景）。 */
    suspend fun ensure(sessionId: String, title: String, model: String?) = withContext(dispatchers.io) {
        if (conversationDao.getById(sessionId) == null) {
            val now = System.currentTimeMillis()
            conversationDao.upsert(Conversation(sessionId, title, model, now, now).toEntity())
        }
    }

    suspend fun get(sessionId: String): Conversation? =
        withContext(dispatchers.io) { conversationDao.getById(sessionId)?.toDomain() }

    suspend fun rename(sessionId: String, title: String) =
        withContext(dispatchers.io) { conversationDao.rename(sessionId, title, System.currentTimeMillis()) }

    suspend fun updateModel(sessionId: String, model: String?) =
        withContext(dispatchers.io) { conversationDao.updateModel(sessionId, model, System.currentTimeMillis()) }

    suspend fun setPinned(sessionId: String, pinned: Boolean) =
        withContext(dispatchers.io) { conversationDao.setPinned(sessionId, pinned) }

    suspend fun setFavorite(sessionId: String, favorite: Boolean) =
        withContext(dispatchers.io) { conversationDao.setFavorite(sessionId, favorite) }

    suspend fun delete(sessionId: String) = withContext(dispatchers.io) {
        // 先清本地消息。云同步开启时打墓碑（dirty），等 SyncEngine push delete 后硬删；游客直接硬删。
        messageDao.deleteBySession(sessionId)
        if (cloudSyncEnabled()) {
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
