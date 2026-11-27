package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderPresets
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.storage.dao.ByokProviderDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.molagpt.app.core.storage.entity.ByokProviderEntity

class ByokProviderRepository(
    private val dao: ByokProviderDao,
    private val credentialStore: CredentialStore,
    private val dispatchers: DispatcherProvider,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val gate = Mutex()

    val providers: Flow<List<ByokProvider>> = dao.observeAll().map { list() }

    suspend fun list(): List<ByokProvider> = withContext(dispatchers.io) {
        gate.withLock { dao.list().map(::decode) }
    }

    suspend fun get(id: String): ByokProvider? = withContext(dispatchers.io) {
        gate.withLock { dao.get(id)?.let(::decode) }
    }

    suspend fun upsert(provider: ByokProvider, preserveModels: Boolean = false) = withContext(dispatchers.io) {
        gate.withLock {
            val row = dao.get(provider.id)
            val next = if (preserveModels && row != null) provider.copy(models = decode(row).models) else provider
            save(row, next)
        }
    }

    suspend fun update(id: String, transform: (ByokProvider) -> ByokProvider): Boolean = withContext(dispatchers.io) {
        gate.withLock {
            val row = dao.get(id) ?: return@withLock false
            val current = decode(row)
            val updated = transform(current)
            require(updated.id == id)
            if (updated == current) return@withLock false
            save(row, updated)
            true
        }
    }

    suspend fun delete(id: String) = withContext(dispatchers.io) {
        gate.withLock {
            val row = dao.get(id) ?: return@withLock
            withContext(NonCancellable) {
                dao.delete(id)
                deleteKey(secretKey(row))
                deleteKey(legacyKey(id))
            }
        }
    }

    private fun decode(row: ByokProviderEntity): ByokProvider {
        val encoded = credentialStore.loadSecret(secretKey(row))
        val key = if (encoded == null) credentialStore.loadSecret(legacyKey(row.id))
            else encoded.removePrefix("key:").takeIf { encoded.startsWith("key:") }
        return row.toDomain(json, key)
    }

    private suspend fun save(old: ByokProviderEntity?, provider: ByokProvider) {
        val normalized = provider.normalized()
        val oldKey = old?.let(::decode)?.apiKey
        val key = normalized.apiKey?.takeIf { it.isNotBlank() }
        val sortOrder = old?.sortOrder ?: dao.list().size
        val row = normalized.toEntity(json, sortOrder)
        if (old != null && oldKey == key) {
            dao.upsert(row.copy(updatedAt = old.updatedAt))
            return
        }
        // The encrypted value for the next revision is staged before Room points to it.
        // Readers can only see the old pair or the new pair, even if the process dies in between.
        val staged = if (old == null) row else row.copy(updatedAt = maxOf(row.updatedAt, old.updatedAt + 1))
        withContext(NonCancellable) {
            credentialStore.saveSecret(secretKey(staged), key?.let { "key:$it" } ?: "none", synchronous = true)
            dao.upsert(staged)
            if (old != null) deleteKey(secretKey(old))
            deleteKey(legacyKey(staged.id))
        }
    }

    private fun deleteKey(slot: String) {
        try {
            credentialStore.saveSecret(slot, null, synchronous = true)
        } catch (error: IllegalStateException) {
            Logger.w("ByokProviders", "清理已失效的安全凭据失败", error)
        }
    }

    fun preset(id: String): ByokProvider? =
        ByokProviderPresets.defaults.firstOrNull { it.id == id }

    private fun ByokProvider.normalized(): ByokProvider = copy(
        baseUrl = baseUrl.trim(),
        chatPath = chatPath.trim().trimStart('/'),
        modelsPath = modelsPath.trim().trimStart('/'),
        imagePath = imagePath.trim().trimStart('/'),
        imageEditPath = imageEditPath.trim().trimStart('/'),
        models = models.map {
            it.copy(providerId = id, providerName = name, providerKind = ProviderKind.BYOK)
        },
    )

    private fun legacyKey(id: String): String = "byok.provider.api_key:$id"
    private fun secretKey(row: ByokProviderEntity): String = "byok.provider.slot:" + row.id + ":" + row.updatedAt
}

fun ByokProvider.allModels(): List<ProviderModel> =
    models.map { it.copy(providerId = id, providerName = name, providerKind = ProviderKind.BYOK) }
