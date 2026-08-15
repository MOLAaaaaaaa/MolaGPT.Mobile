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

    /** 附件孤儿回收用：附件信息编码在 metadataJson 里，取全量交给上层解析。 */
    @Query("SELECT metadataJson FROM messages WHERE metadataJson LIKE '%localPath%'")
    suspend fun allMetadataWithAttachments(): List<String>
}
