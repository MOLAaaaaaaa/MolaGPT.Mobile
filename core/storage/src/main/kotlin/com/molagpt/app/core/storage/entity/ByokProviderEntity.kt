package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "byok_providers")
data class ByokProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val chatPath: String,
    val modelsPath: String,
    val imagePath: String = "v1/images/generations",
    val purpose: String = "CHAT",
    val imageFormat: String = "OPENAI_IMAGES",
    val imageEditPath: String = "",
    val enabled: Boolean = true,
    val modelsJson: String = "[]",
    val customHeadersJson: String = "[]",
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
