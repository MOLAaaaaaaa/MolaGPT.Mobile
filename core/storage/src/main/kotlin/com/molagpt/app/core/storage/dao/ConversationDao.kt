package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.molagpt.app.core.storage.CloudChange
import androidx.paging.PagingSource
import com.molagpt.app.core.storage.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

internal const val VISIBLE_HISTORY = "(providerKind = 'BYOK' OR (providerKind = 'MOLAGPT' AND :includeOfficial))"
private const val OFFICIAL = "providerKind = 'MOLAGPT'"

@Dao
interface ConversationDao {
    /**
     * 置顶优先、再按更新时间倒序。显示条件：未软删，且「有消息」或「云端占位」。
     * 这样本地新建的空会话不进侧边栏（避免反复出现“新对话”），而云端同步下来的占位会话照常显示、点开懒加载。
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE """ + VISIBLE_HISTORY + """ AND deletedAt IS NULL
          AND visibleInList = 1
          AND (
              :source = 'ALL'
              OR (:source = 'CHAT' AND providerId IS NOT 'image-workbench')
              OR (:source = 'IMAGE' AND providerId = 'image-workbench')
          )
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    fun pagingSource(source: String, includeOfficial: Boolean): PagingSource<Int, ConversationEntity>

    /**
     * 搜索侧边栏会话。标题始终可搜；正文只查已经落到本地的 rawText。
     * placeholder=1 的 MolaGPT 云端占位只有元数据，因此明确跳过正文子查询。
     * 消息子查询按已有的 messages(sessionId) 索引收窄，不把消息加载到 Kotlin 层。
     *
     * 用 `instr(lower(x), lower(:query))` 而非 LIKE：两者在 SQLite 下都只对 ASCII 大小写不敏感，
     * 对字面子串完全等价，但 instr 没有通配符语义，用户输入的 % _ \ 天然按普通字符处理，无需转义。
     * 同一个表达式还能直接给出命中位置，供 matchedSnippet 截窗口。
     *
     * matchedSnippet 取**最近一条**命中消息、命中点前 24 字起的 96 字窗口。
     * 截取必须留在 SQL 侧：回传整条 rawText 会重演 MIGRATION_9_10 记录的 CursorWindow 撑爆事故。
     */
    @Query(
        """
        SELECT c.*, (
            SELECT substr(m.rawText, MAX(1, instr(lower(m.rawText), lower(:query)) - 24), 96)
            FROM messages AS m
            WHERE m.sessionId = c.sessionId
              AND m.rawText IS NOT NULL
              AND instr(lower(m.rawText), lower(:query)) > 0
            ORDER BY m.createdAt DESC
            LIMIT 1
        ) AS matchedSnippet
        FROM conversations AS c
        WHERE """ + VISIBLE_HISTORY + """ AND c.deletedAt IS NULL
          AND c.visibleInList = 1
          AND (
              :source = 'ALL'
              OR (:source = 'CHAT' AND c.providerId IS NOT 'image-workbench')
              OR (:source = 'IMAGE' AND c.providerId = 'image-workbench')
          )
          AND (
              instr(lower(c.title), lower(:query)) > 0
              OR (
                  c.placeholder = 0
                  AND EXISTS (
                      SELECT 1 FROM messages AS m
                      WHERE m.sessionId = c.sessionId
                        AND m.rawText IS NOT NULL
                        AND instr(lower(m.rawText), lower(:query)) > 0
                  )
              )
          )
        ORDER BY c.pinned DESC, c.updatedAt DESC
        """,
    )
    fun searchPagingSource(query: String, source: String, includeOfficial: Boolean): PagingSource<Int, ConversationSearchRow>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId AND deletedAt IS NULL AND " + VISIBLE_HISTORY)
    suspend fun getById(sessionId: String, includeOfficial: Boolean = false): ConversationEntity?

    /** 批量删除用：一次取回多个会话，逐条判定墓碑 / 硬删。调用方需按 SQLite 变量上限分批。 */
    @Query("SELECT * FROM conversations WHERE sessionId IN (:sessionIds) AND deletedAt IS NULL AND " + VISIBLE_HISTORY)
    suspend fun getByIds(sessionIds: List<String>, includeOfficial: Boolean): List<ConversationEntity>

    /** 「全选」用：可见性条件与 [pagingSource] 保持一致，否则会选中列表上看不见的会话。 */
    @Query(
        """
        SELECT sessionId FROM conversations
        WHERE """ + VISIBLE_HISTORY + """ AND deletedAt IS NULL
          AND visibleInList = 1
          AND (
              :source = 'ALL'
              OR (:source = 'CHAT' AND providerId IS NOT 'image-workbench')
              OR (:source = 'IMAGE' AND providerId = 'image-workbench')
          )
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    suspend fun allVisibleSessionIds(source: String, includeOfficial: Boolean): List<String>

    /** 观察单个会话（用于顶栏标题随重命名实时刷新）。 */
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId AND deletedAt IS NULL AND " + VISIBLE_HISTORY)
    fun observeById(sessionId: String, includeOfficial: Boolean = false): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ConversationEntity): Long

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now, syncChanges = syncChanges | CASE WHEN providerKind = 'MOLAGPT' THEN " + CloudChange.TITLE + " ELSE 0 END, localRevision = localRevision + 1 WHERE sessionId = :sessionId AND deletedAt IS NULL AND title IS NOT :title AND " + VISIBLE_HISTORY)
    suspend fun rename(sessionId: String, title: String, now: Long, includeOfficial: Boolean)

    @Query("UPDATE conversations SET model = :model, providerId = :providerId, updatedAt = :now, syncChanges = syncChanges | CASE WHEN providerKind = 'MOLAGPT' AND model IS NOT :model THEN " + CloudChange.MODEL + " ELSE 0 END, localRevision = localRevision + 1 WHERE sessionId = :sessionId AND deletedAt IS NULL AND (model IS NOT :model OR providerId IS NOT :providerId) AND providerKind = :providerKind AND " + VISIBLE_HISTORY)
    suspend fun updateModel(sessionId: String, model: String?, providerId: String?, providerKind: String, now: Long, includeOfficial: Boolean)

    /** 绑定 / 切换会话角色（仅 BYOK）。 */
    @Query("UPDATE conversations SET personaId = :personaId, updatedAt = :now WHERE sessionId = :sessionId AND providerKind = 'BYOK'")
    suspend fun updatePersona(sessionId: String, personaId: String?, now: Long)

    /**
     * 会话级记忆覆盖（仅 BYOK）。null 表示撤销覆盖、重新跟随全局开关。
     * 不动 updatedAt / dirty：这是本地偏好，不该把会话顶到侧边栏最前，也不该推给云端。
     */
    @Query("UPDATE conversations SET byokMemoryEnabled = :enabled WHERE sessionId = :sessionId AND providerKind = 'BYOK'")
    suspend fun updateByokMemoryEnabled(sessionId: String, enabled: Boolean?)

    @Query("UPDATE conversations SET byokConversationRecallEnabled = :enabled WHERE sessionId = :sessionId AND providerKind = 'BYOK'")
    suspend fun updateByokConversationRecallEnabled(sessionId: String, enabled: Boolean?)

    /**
     * 推进记忆整理水位线。只增不减：并发的两次整理里慢的那次不能把快的那次推进的结果退回去，
     * 否则中间那段会被重复整理、重复计费。
     */
    @Query(
        "UPDATE conversations SET byokMemoryWatermarkAt = MAX(byokMemoryWatermarkAt, :at) WHERE sessionId = :sessionId AND providerKind = 'BYOK'",
    )
    suspend fun advanceByokMemoryWatermark(sessionId: String, at: Long)

    /** 「清除全部记忆」时把所有水位线归零之外的另一半：这里是把它推到当前时间，旧消息不再被扫。 */
    @Query("UPDATE conversations SET byokMemoryWatermarkAt = :at WHERE providerKind = 'BYOK'")
    suspend fun resetAllByokMemoryWatermarks(at: Long)

    /** 有待整理消息的 BYOK 会话。按最近活动排序，整理时优先处理用户刚聊过的。 */
    @Query(
        """
        SELECT c.sessionId
        FROM conversations AS c
        WHERE c.deletedAt IS NULL
          AND c.placeholder = 0
          AND c.providerKind = 'BYOK'
          AND (c.personaId IS NULL OR c.personaId IN (:allowedPersonaIds))
          AND EXISTS (
              SELECT 1 FROM messages AS m
              WHERE m.sessionId = c.sessionId
                AND m.createdAt > c.byokMemoryWatermarkAt
          )
        ORDER BY c.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun sessionsPendingMemory(limit: Int, allowedPersonaIds: List<String>): List<String>

    @Query("UPDATE conversations SET pinned = :pinned, syncChanges = syncChanges | CASE WHEN providerKind = 'MOLAGPT' THEN " + CloudChange.PIN + " ELSE 0 END, localRevision = localRevision + 1 WHERE sessionId = :sessionId AND deletedAt IS NULL AND pinned IS NOT :pinned AND " + VISIBLE_HISTORY)
    suspend fun setPinned(sessionId: String, pinned: Boolean, includeOfficial: Boolean)

    @Query("UPDATE conversations SET favorite = :favorite WHERE sessionId = :sessionId AND deletedAt IS NULL AND " + VISIBLE_HISTORY)
    suspend fun setFavorite(sessionId: String, favorite: Boolean, includeOfficial: Boolean)

    @Query(
        """
        UPDATE conversations
        SET updatedAt = :now,
            lastMessagePreview = :preview,
            syncChanges = syncChanges | CASE WHEN providerKind = 'MOLAGPT' THEN """ + CloudChange.BODY + """ ELSE 0 END,
            localRevision = localRevision + 1,
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

    @Query("SELECT * FROM conversations WHERE syncChanges != 0 AND deletedAt IS NULL AND " + OFFICIAL)
    suspend fun getDirty(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE remoteVersion IS NOT NULL AND deletedAt IS NULL AND syncChanges = 0 AND " + OFFICIAL)
    suspend fun getConfirmed(): List<ConversationEntity>

    /** 软删墓碑（待 push delete）。 */
    @Query("SELECT * FROM conversations WHERE deletedAt IS NOT NULL AND " + OFFICIAL)
    suspend fun getDeleted(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId AND " + OFFICIAL)
    suspend fun officialById(sessionId: String): ConversationEntity?

    @Query("SELECT sessionId FROM conversations WHERE " + OFFICIAL)
    suspend fun officialIds(): List<String>

    @Query("DELETE FROM conversations WHERE " + OFFICIAL)
    suspend fun clearOfficial()

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

    /** 软删：打墓碑并标脏，等待向云端 push delete。 */
    @Query("UPDATE conversations SET deletedAt = :now, localRevision = localRevision + 1 WHERE sessionId = :sessionId AND deletedAt IS NULL AND " + OFFICIAL)
    suspend fun tombstone(sessionId: String, now: Long)

    /** 云端删除确认后的硬删（连带消息由仓库一并清理）。 */
    @Query("DELETE FROM conversations WHERE sessionId = :sessionId AND deletedAt IS NOT NULL AND localRevision = :revision AND " + OFFICIAL)
    suspend fun purge(sessionId: String, revision: Long): Int
}
