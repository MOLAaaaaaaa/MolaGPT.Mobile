package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.molagpt.app.core.storage.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    /** 活跃角色（未软删）：置顶优先、再按 sortOrder、名称。 */
    @Query("SELECT * FROM personas WHERE deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, name ASC")
    fun observeActive(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, name ASC")
    suspend fun listActive(): List<PersonaEntity>

    @Query("SELECT * FROM personas WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): PersonaEntity?

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonaEntity)

    /** 首次播种内置角色（已存在则忽略）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<PersonaEntity>)

    /** 软删（内置角色 isBuiltin=1 不受影响）。 */
    @Query("UPDATE personas SET deletedAt = :now WHERE id = :id AND isBuiltin = 0")
    suspend fun softDelete(id: String, now: Long)
}
