package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.paging.PagingSource
import com.molagpt.app.core.storage.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /**
     * 置顶优先、再按更新时间倒序。显示条件：未软删，且「有消息」或「云端占位」。
     * 这样本地新建的空会话不进侧边栏（避免反复出现“新对话”），而云端同步下来的占位会话照常显示、点开懒加载。
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE deletedAt IS NULL
          AND visibleInList = 1
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    fun pagingSource(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId")
    suspend fun getById(sessionId: String): ConversationEntity?

    /** 批量删除用：一次取回多个会话，逐条判定墓碑 / 硬删。调用方需按 SQLite 变量上限分批。 */
    @Query("SELECT * FROM conversations WHERE sessionId IN (:sessionIds)")
    suspend fun getByIds(sessionIds: List<String>): List<ConversationEntity>

    /** 「全选」用：可见性条件与 [pagingSource] 保持一致，否则会选中列表上看不见的会话。 */
    @Query(
        """
        SELECT sessionId FROM conversations
        WHERE deletedAt IS NULL
          AND visibleInList = 1
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    suspend fun allVisibleSessionIds(): List<String>

    /** 观察单个会话（用于顶栏标题随重命名实时刷新）。 */
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId")
    fun observeById(sessionId: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now, dirty = 1 WHERE sessionId = :sessionId")
    suspend fun rename(sessionId: String, title: String, now: Long)

    @Query("UPDATE conversations SET model = :model, providerId = :providerId, providerKind = :providerKind, updatedAt = :now, dirty = 1 WHERE sessionId = :sessionId")
    suspend fun updateModel(sessionId: String, model: String?, providerId: String?, providerKind: String, now: Long)

    /** 绑定 / 切换会话角色（仅 BYOK）。 */
    @Query("UPDATE conversations SET personaId = :personaId, updatedAt = :now, dirty = 1 WHERE sessionId = :sessionId")
    suspend fun updatePersona(sessionId: String, personaId: String?, now: Long)

    @Query("UPDATE conversations SET pinned = :pinned, dirty = 1 WHERE sessionId = :sessionId")
    suspend fun setPinned(sessionId: String, pinned: Boolean)

    @Query("UPDATE conversations SET favorite = :favorite, dirty = 1 WHERE sessionId = :sessionId")
    suspend fun setFavorite(sessionId: String, favorite: Boolean)

    @Query(
        """
        UPDATE conversations
        SET updatedAt = :now,
            lastMessagePreview = :preview,
            dirty = 1,
            messageCount = (SELECT COUNT(*) FROM messages WHERE messages.sessionId = :sessionId),
            visibleInList = CASE
                WHEN placeholder = 1 OR (SELECT COUNT(*) FROM messages WHERE messages.sessionId = :sessionId) > 0 THEN 1
                ELSE 0
            END
        WHERE sessionId = :sessionId
        """,
    )
    suspend fun touch(sessionId: String, now: Long, preview: String?)

    // —— 云同步 ——

    /** 未删除、有未推送改动、且已 materialize（非占位）的会话（增量 push 用）。 */
    @Query("SELECT * FROM conversations WHERE dirty = 1 AND deletedAt IS NULL AND placeholder = 0 AND providerKind = 'MOLAGPT'")
    suspend fun getDirty(): List<ConversationEntity>

    /** 全部未删除、已 materialize 的会话（首次全量 push 用；占位会话只有元数据不参与推送）。 */
    @Query("SELECT * FROM conversations WHERE deletedAt IS NULL AND placeholder = 0 AND providerKind = 'MOLAGPT'")
    suspend fun getAllActive(): List<ConversationEntity>

    /** 软删墓碑（待 push delete）。 */
    @Query("SELECT * FROM conversations WHERE deletedAt IS NOT NULL AND providerKind = 'MOLAGPT'")
    suspend fun getDeleted(): List<ConversationEntity>

    /** push 成功后清脏标。 */
    @Query("UPDATE conversations SET dirty = 0 WHERE sessionId = :sessionId")
    suspend fun markSynced(sessionId: String)

    /** 懒加载完成：消息已拉到本地，转为正式会话。 */
    @Query(
        """
        UPDATE conversations
        SET placeholder = 0,
            messageCount = (SELECT COUNT(*) FROM messages WHERE messages.sessionId = :sessionId),
            visibleInList = CASE
                WHEN (SELECT COUNT(*) FROM messages WHERE messages.sessionId = :sessionId) > 0 THEN 1
                ELSE 0
            END
        WHERE sessionId = :sessionId
        """,
    )
    suspend fun markLoaded(sessionId: String)

    @Query(
        """
        UPDATE conversations
        SET messageCount = (SELECT COUNT(*) FROM messages WHERE messages.sessionId = :sessionId),
            visibleInList = CASE
                WHEN placeholder = 1 OR (SELECT COUNT(*) FROM messages WHERE messages.sessionId = :sessionId) > 0 THEN 1
                ELSE 0
            END
        WHERE sessionId = :sessionId
        """,
    )
    suspend fun refreshListVisibility(sessionId: String)

    /** 同步拉取时只更新元数据（不动 dirty/placeholder/消息）。 */
    @Query("UPDATE conversations SET title = :title, model = :model, providerId = 'molagpt', providerKind = 'MOLAGPT', updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun updateMetaFromSync(sessionId: String, title: String, model: String?, updatedAt: Long)

    /** 账户切换/登出时清理只有元数据、无正文的占位会话。 */
    @Query("DELETE FROM conversations WHERE placeholder = 1 AND deletedAt IS NULL")
    suspend fun deletePlaceholders()

    /** 软删：打墓碑并标脏，等待向云端 push delete。 */
    @Query("UPDATE conversations SET deletedAt = :now, dirty = 1 WHERE sessionId = :sessionId")
    suspend fun tombstone(sessionId: String, now: Long)

    /** 云端删除确认后的硬删（连带消息由仓库一并清理）。 */
    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    suspend fun purge(sessionId: String)
}
