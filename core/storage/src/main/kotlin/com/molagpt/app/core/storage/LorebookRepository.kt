package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.Lorebook
import com.molagpt.app.core.storage.dao.LorebookDao
import com.molagpt.app.core.storage.entity.LorebookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 共享世界书的本地仓库。
 *
 * 随角色卡带的那本存在 `PersonaEntity.profileJson` 里（它是卡的一部分）；这里只管用户自己建的、
 * 或单独导入的那些——它们要能被多个角色同时引用，所以必须有独立身份。
 */
class LorebookRepository(
    private val dao: LorebookDao,
    private val dispatchers: DispatcherProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val writes = Mutex()

    fun observeAll(): Flow<List<Lorebook>> = dao.observeActive().map { rows -> rows.mapNotNull(::decode) }

    suspend fun list(): List<Lorebook> = withContext(dispatchers.io) { dao.listActive().mapNotNull(::decode) }

    /** 按 id 取出角色引用的那几本，顺序与 [ids] 一致（来源优先级靠它）。 */
    suspend fun byIds(ids: List<String>): List<Lorebook> = withContext(dispatchers.io) {
        if (ids.isEmpty()) return@withContext emptyList()
        val found = dao.getByIds(ids).mapNotNull(::decode).associateBy { it.id }
        ids.mapNotNull { found[it] }
    }

    suspend fun save(book: Lorebook) = withContext(dispatchers.io) {
        writes.withLock { saveCurrent(book) }
    }

    suspend fun update(id: String, change: (Lorebook) -> Lorebook) = withContext(dispatchers.io) {
        writes.withLock {
            val entity = dao.getByIds(listOf(id)).firstOrNull() ?: return@withLock
            val book = json.decodeFromString<Lorebook>(entity.bookJson)
                .copy(id = entity.id, name = entity.name, enabled = entity.enabled)
            saveCurrent(change(book))
        }
    }

    private suspend fun saveCurrent(book: Lorebook) {
        val now = System.currentTimeMillis()
        val existing = dao.getByIds(listOf(book.id)).firstOrNull()
        dao.upsert(
            LorebookEntity(
                id = book.id,
                name = book.name,
                enabled = book.enabled,
                bookJson = json.encodeToString(book),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(id: String) = withContext(dispatchers.io) {
        writes.withLock { dao.softDelete(id, System.currentTimeMillis()) }
    }

    /** 解不出来的那一本跳过就好，别让一条坏数据把整张列表拖垮。 */
    private fun decode(entity: LorebookEntity): Lorebook? =
        runCatching { json.decodeFromString<Lorebook>(entity.bookJson) }
            .getOrNull()
            ?.copy(id = entity.id, name = entity.name, enabled = entity.enabled)
}
