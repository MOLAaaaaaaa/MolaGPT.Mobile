package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderPresets
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.storage.dao.ByokProviderDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

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

    val providers: Flow<List<ByokProvider>> = dao.observeAll().map { rows ->
        rows.map { row -> row.toDomain(json, credentialStore.loadSecret(secretKey(row.id))) }
    }

    suspend fun list(): List<ByokProvider> = withContext(dispatchers.io) {
        dao.list().map { row -> row.toDomain(json, credentialStore.loadSecret(secretKey(row.id))) }
    }

    suspend fun get(id: String): ByokProvider? = withContext(dispatchers.io) {
        dao.get(id)?.let { row -> row.toDomain(json, credentialStore.loadSecret(secretKey(row.id))) }
    }

    suspend fun upsert(provider: ByokProvider) = withContext(dispatchers.io) {
        val sortOrder = dao.list().indexOfFirst { it.id == provider.id }.takeIf { it >= 0 } ?: dao.list().size
        credentialStore.saveSecret(secretKey(provider.id), provider.apiKey)
        dao.upsert(provider.normalized().toEntity(json, sortOrder))
    }

    suspend fun delete(id: String) = withContext(dispatchers.io) {
        credentialStore.removeSecret(secretKey(id))
        dao.delete(id)
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

    private fun secretKey(id: String): String = "byok.provider.api_key:$id"
}

fun ByokProvider.allModels(): List<ProviderModel> =
    models.map { it.copy(providerId = id, providerName = name, providerKind = ProviderKind.BYOK) }
