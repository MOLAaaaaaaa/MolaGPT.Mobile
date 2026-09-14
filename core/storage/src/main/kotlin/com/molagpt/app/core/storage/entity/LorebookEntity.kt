package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 共享世界书：独立于角色卡存在，可被多个角色同时引用（角色侧存的是 id 列表）。
 *
 * 随卡带的那本仍然躺在 `PersonaEntity.profileJson` 里——它是卡的一部分，跟着卡走；
 * 这张表只放用户自己建的、或者单独导入的那些。
 */
@Entity(
    tableName = "lorebooks",
    indices = [Index(value = ["deletedAt", "updatedAt"])],
)
data class LorebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** [com.molagpt.app.core.model.Lorebook] 的 JSON（含条目）。 */
    val bookJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
