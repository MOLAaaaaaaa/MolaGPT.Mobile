package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.molagpt.app.core.storage.entity.StreamTaskEntity

@Dao
interface StreamTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StreamTaskEntity)

    @Query("DELETE FROM stream_tasks WHERE sessionId = :sessionId AND streamSessionId = :streamSessionId")
    suspend fun delete(sessionId: String, streamSessionId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM stream_tasks WHERE sessionId = :sessionId AND streamSessionId = :streamSessionId AND providerKind = 'MOLAGPT')")
    suspend fun owns(sessionId: String, streamSessionId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM stream_tasks WHERE sessionId = :sessionId AND providerKind = 'MOLAGPT')")
    suspend fun hasOfficialTask(sessionId: String): Boolean

    @Query("DELETE FROM stream_tasks WHERE providerKind = 'MOLAGPT'")
    suspend fun clearOfficial()

    @Query("SELECT * FROM stream_tasks WHERE sessionId IN (SELECT sessionId FROM conversations WHERE deletedAt IS NULL AND " + VISIBLE_HISTORY + ") ORDER BY createdAt ASC")
    suspend fun getAll(includeOfficial: Boolean): List<StreamTaskEntity>
}
