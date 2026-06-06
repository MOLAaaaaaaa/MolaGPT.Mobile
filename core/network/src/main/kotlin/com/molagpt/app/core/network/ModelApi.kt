package com.molagpt.app.core.network

import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.network.dto.ModelConfigResponse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 拉取服务端模型注册表，过滤前端可见模型，并填充 [ModelRegistry]。
 *
 * **按用户过滤**：`model_config_public.php` 不分用户、返回完整列表；真正的可用性在
 * `status.php` 的 `model_status`（游客走 guest_limits、登录走 registered_user_limits，逐模型给 available）。
 * 这里调 status 后**只保留 available 的模型**（游客只看自己能用的）；status 拿不到则降级不过滤，避免空列表。
 *
 * **超时**：共享 OkHttp client 为 SSE 设了无限 callTimeout，model_config / status 都是普通请求——
 * 各用 [withTimeoutOrNull] 限时，避免移动网络抖动时无限转圈。
 */
class ModelApi(
    private val http: MolaHttp,
    private val registry: ModelRegistry,
    private val shortTokenManager: ShortTokenManager,
    private val authApi: AuthApi,
) {
    suspend fun refresh(): List<ProviderModel> {
        registry.beginRefresh()
        return try {
            refreshInternal()
        } finally {
            registry.endRefresh()
        }
    }

    private suspend fun refreshInternal(): List<ProviderModel> {
        val text = withTimeoutOrNull(MODEL_CONFIG_TIMEOUT_MS) {
            val resp = http.client.get(MolaEndpoints.absolute(MolaEndpoints.MODEL_CONFIG))
            if (!resp.status.isSuccess()) {
                Logger.w("ModelApi", "model config failed: HTTP ${resp.status.value}")
                return@withTimeoutOrNull null
            }
            resp.bodyAsText().trimStart('\uFEFF')
        }
        if (text == null) {
            Logger.w(
                "ModelApi",
                "model config timeout(${MODEL_CONFIG_TIMEOUT_MS}ms)/failed; keep current ${registry.all().size}",
            )
            return registry.all()
        }
        val cfg = runCatching { http.json.decodeFromString<ModelConfigResponse>(text) }.getOrElse { e ->
            Logger.w("ModelApi", "model config decode failed: ${e.message}", e)
            return registry.all()
        }

        // 按当前用户可用性过滤；availability == null 表示 status 拿不到 → 不过滤（降级显示全部）。
        val availability = fetchAvailability()
        val list = cfg.models.entries
            .mapNotNull { (configKey, entry) ->
                if (!entry.showInFrontend) return@mapNotNull null
                val modelName = entry.modelName?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                if (availability != null) {
                    // status 的 key 可能是 config key 或 modelName，两者都试。
                    val avail = availability[configKey] ?: availability[modelName]
                    if (avail != true) return@mapNotNull null
                }
                val apiUrl = entry.apiUrl?.trim()?.takeIf { it.isNotBlank() }
                    ?: MolaEndpoints.DEFAULT_CHAT_API
                val displayName = entry.tipText?.trim()?.takeIf { it.isNotBlank() }
                    ?: modelName
                ProviderModel(
                    id = modelName,
                    displayName = displayName,
                    apiUrl = apiUrl,
                    supportsVision = entry.showImageUpload,
                    supportsThinking = entry.supportsThinking,
                    supportsReasoningEffort = entry.supportsReasoningEffort,
                    group = entry.group,
                )
            }
        Logger.d("ModelApi", "models total=${cfg.models.size} visible=${list.size} filtered=${availability != null}")
        registry.update(list)
        return list
    }

    /** status.php 的 model_status → 「模型 key → 是否可用」。失败/超时返回 null（调用方据此不过滤）。 */
    private suspend fun fetchAvailability(): Map<String, Boolean>? = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
        runCatching {
            val jwt = shortTokenManager.freshToken()
            val status = authApi.status(jwt) ?: return@runCatching null
            val modelStatus = status["model_status"] as? JsonObject ?: return@runCatching null
            modelStatus.entries.associate { (key, value) ->
                key to ((value as? JsonObject)?.get("available")?.jsonPrimitive?.booleanOrNull ?: false)
            }
        }.getOrNull()
    }

    private companion object {
        const val MODEL_CONFIG_TIMEOUT_MS = 15_000L
        const val STATUS_TIMEOUT_MS = 15_000L
    }
}
