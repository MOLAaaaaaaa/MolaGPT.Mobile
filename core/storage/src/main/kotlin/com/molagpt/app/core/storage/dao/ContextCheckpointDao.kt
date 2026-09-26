package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.molagpt.app.core.storage.entity.ContextCheckpointEntity
import com.molagpt.app.core.storage.entity.ContextCompactionUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContextCheckpointDao {
    /** 新的在前：同一时间线上后建的检查点覆盖得更多。 */
    @Query("SELECT * FROM context_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun bySession(sessionId: String): List<ContextCheckpointEntity>

    @Query("SELECT * FROM context_checkpoints WHERE sessionId = :sessionId AND stale = 0 ORDER BY createdAt ASC")
    fun observeValid(sessionId: String): Flow<List<ContextCheckpointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContextCheckpointEntity)

    @Insert
    suspend fun recordUsage(entity: ContextCompactionUsageEntity)

    @Query("SELECT COALESCE(SUM(costUsd), 0.0) FROM context_compaction_usage WHERE sessionId = :sessionId")
    fun observeCostUsd(sessionId: String): Flow<Double>

    @Query("UPDATE context_checkpoints SET stale = :stale WHERE id = :id")
    suspend fun setStale(id: String, stale: Boolean)

    @Query("DELETE FROM context_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM context_compaction_usage WHERE sessionId = :sessionId")
    suspend fun deleteUsageBySession(sessionId: String)
}
