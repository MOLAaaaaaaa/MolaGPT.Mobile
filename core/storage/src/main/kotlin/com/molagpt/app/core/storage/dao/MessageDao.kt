package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.molagpt.app.core.storage.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    /** 一次性取整会话消息（云同步打包用）。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getAllBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(sessionId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    /**
     * 本轮最后一条用户消息。`save_memory` 用它校验 source_quote 是否真出自用户之口。
     *
     * 单独一条查询而非从整会话里挑：这段代码跑在工具循环里（用户正等着回答），
     * 而 SELECT * 拉全会话在长对话上既慢又有 CursorWindow 撑爆的先例（见 MIGRATION_9_10）。
     * 正文不截断——引文校验必须对着完整原文做，截断会把长消息里的合法引用误判为伪造。
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId AND role = 'USER' AND rawText IS NOT NULL AND rawText != ''
        ORDER BY createdAt DESC
        LIMIT 1
        """,
    )
    suspend fun latestUserMessage(sessionId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MessageEntity>)

    /** 编辑分支切换：整体换成另一条时间线，中间态不外泄给 observeMessages。 */
    @Transaction
    suspend fun replaceAll(sessionId: String, entities: List<MessageEntity>) {
        deleteBySession(sessionId)
        upsertAll(entities)
    }

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** regenerate/edit 时裁剪：删掉某时间点之后的消息。 */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND createdAt >= :fromCreatedAt")
    suspend fun deleteFrom(sessionId: String, fromCreatedAt: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int

    /** 会话里已落库的消息 id。请求里其余消息（角色提示、示例对话）是临时拼的，不参与上下文压缩。 */
    @Query("SELECT messageId FROM messages WHERE sessionId = :sessionId")
    suspend fun idsBySession(sessionId: String): List<String>

    /**
     * 用户说过几句。角色扮演会话一开场就有一条助手开场白，
     * 「这是不是第一轮」不能再用总条数判断，否则自动标题会被开场白顶掉。
     */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'USER'")
    suspend fun countUserMessages(sessionId: String): Int

    /** 附件孤儿回收用：附件信息编码在 metadataJson 里，取全量交给上层解析。 */
    @Query("SELECT metadataJson FROM messages WHERE metadataJson LIKE '%localPath%'")
    suspend fun allMetadataWithAttachments(): List<String>

    // ── 历史对话回忆（recall_conversations 工具） ──────────────────────────────
    //
    // 摘要与正文一律在 SQL 侧 substr 截断：把整条 rawText 拉进游标会重演
    // MIGRATION_9_10 记录的 CursorWindow 撑爆事故。
    //
    // 用 instr 而非 FTS 是有意的：Android 各版本对 FTS5 的编译支持不保证，Room 只稳定支持 FTS4，
    // 而 FTS4/5 的默认分词器都不切中文；能做中文子串的 trigram 分词器要 SQLite 3.34+，minSdk 23 覆盖不到。

    /**
     * 单个短语的命中投影。只搜已经落到本机的 BYOK 会话，排除软删、云端占位与空正文。
     * 只返回 USER / ASSISTANT——思考过程、工具原始数据与附件正文不进模型可见范围。
     */
    @Query(
        """
        SELECT m.messageId AS messageId,
               m.sessionId AS sessionId,
               c.title AS title,
               m.role AS role,
               m.createdAt AS createdAt,
               substr(m.rawText, MAX(1, instr(lower(m.rawText), lower(:query)) - 40), 160) AS snippet
        FROM messages AS m
        JOIN conversations AS c ON c.sessionId = m.sessionId
        WHERE c.deletedAt IS NULL
          AND c.placeholder = 0
          AND c.providerKind = 'BYOK'
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.rawText IS NOT NULL
          AND m.rawText != ''
          AND (:excludeSessionId IS NULL OR m.sessionId != :excludeSessionId)
          AND (:onlySessionId IS NULL OR m.sessionId = :onlySessionId)
          AND instr(lower(m.rawText), lower(:query)) > 0
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchRecallHits(
        query: String,
        excludeSessionId: String?,
        onlySessionId: String?,
        limit: Int,
    ): List<RecallHitRow>

    /** 命中点之前的可见消息。 */
    @Query(
        """
        SELECT messageId AS messageId,
               role AS role,
               createdAt AS createdAt,
               substr(rawText, 1, :maxChars) AS body
        FROM messages
        WHERE sessionId = :sessionId
          AND role IN ('USER', 'ASSISTANT')
          AND rawText IS NOT NULL
          AND rawText != ''
          AND createdAt < :anchorAt
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun recallContextBefore(
        sessionId: String,
        anchorAt: Long,
        limit: Int,
        maxChars: Int,
    ): List<RecallMessageRow>

    /** 命中点之后的可见消息。 */
    @Query(
        """
        SELECT messageId AS messageId,
               role AS role,
               createdAt AS createdAt,
               substr(rawText, 1, :maxChars) AS body
        FROM messages
        WHERE sessionId = :sessionId
          AND role IN ('USER', 'ASSISTANT')
          AND rawText IS NOT NULL
          AND rawText != ''
          AND createdAt > :anchorAt
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun recallContextAfter(
        sessionId: String,
        anchorAt: Long,
        limit: Int,
        maxChars: Int,
    ): List<RecallMessageRow>

    /** 命中消息本身。 */
    @Query(
        """
        SELECT messageId AS messageId,
               role AS role,
               createdAt AS createdAt,
               substr(rawText, 1, :maxChars) AS body
        FROM messages
        WHERE messageId = :messageId
        """,
    )
    suspend fun recallMessage(messageId: String, maxChars: Int): RecallMessageRow?
}
