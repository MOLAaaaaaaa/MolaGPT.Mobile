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

    @Query("DELETE FROM stream_tasks WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("SELECT * FROM stream_tasks ORDER BY createdAt ASC")
    suspend fun getAll(): List<StreamTaskEntity>
}
