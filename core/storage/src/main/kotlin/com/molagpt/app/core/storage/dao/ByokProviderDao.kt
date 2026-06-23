package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.molagpt.app.core.storage.entity.ByokProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ByokProviderDao {
    @Query("SELECT * FROM byok_providers ORDER BY sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<ByokProviderEntity>>

    @Query("SELECT * FROM byok_providers ORDER BY sortOrder ASC, updatedAt DESC")
    suspend fun list(): List<ByokProviderEntity>

    @Query("SELECT * FROM byok_providers WHERE id = :id")
    suspend fun get(id: String): ByokProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ByokProviderEntity)

    @Query("DELETE FROM byok_providers WHERE id = :id")
    suspend fun delete(id: String)
}
