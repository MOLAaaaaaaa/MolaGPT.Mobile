package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.molagpt.app.core.storage.entity.LorebookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LorebookDao {
    @Query("SELECT * FROM lorebooks WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun listActive(): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun getByIds(ids: List<String>): List<LorebookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LorebookEntity)

    @Query("UPDATE lorebooks SET deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}
