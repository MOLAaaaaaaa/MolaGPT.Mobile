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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 拉取服务端模型注册表，过滤前端可见模型，并填充 [ModelRegistry]。
 *
 * **按用户过滤**：`model_config_public.php` 不分用户、返回完整列表；真正的可用性在
 * `status.php` 的 `model_status`（游客走 guest_limits、登录走 registered_user_limits，逐模型给 available）。
 * 这里调 status 后按 `reason` 分两类处理：结构性不可用（下架 / 捐赠者专属 / 未定价）从列表移除，
 * 额度与风控挡住的**保留在列表里**并标记 [ProviderModel.quotaBlocked]，由 UI 置灰。
 * 不能一律按 `available` 过滤：点数是全站共享的一份额度，耗尽时所有计费模型都会变成
 * `available:false`，硬过滤会把整个模型选择器清空。status 拿不到则降级不过滤，避免空列表。
 *
 * **可见性只由 `showInFrontend` 决定**，不再用「status 里有没有这一条」兜底：路由器
 * （auto）没有配额条目，status.php 本就不会给它状态。服务端 `model_registry.php` 已经
 * 保证 status 下发的模型集合与选择器同口径。
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

        // 按当前用户可用性过滤；statuses == null 表示 status 拿不到 → 不过滤（降级显示全部）。
        val statuses = fetchModelStatuses()
        var blocked = 0
        val list = cfg.models.entries
            .mapNotNull { (configKey, entry) ->
                if (!entry.showInFrontend) return@mapNotNull null
                val modelName = entry.modelName?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                // status 的 key 可能是 config key 或 modelName，两者都试。
                val quota = statuses?.let { it[configKey] ?: it[modelName] }
                if (quota != null) {
                    if (!quota.available && quota.reason !in KEEP_BUT_DISABLE_REASONS) return@mapNotNull null
                    if (!quota.available) blocked++
                }
                // quota == null 一律保留。status.php 只对「有配额条目」的模型下发状态，
                // 路由器（auto / MolaGPT Routes）本身不是计费主体、没有配额条目，缺状态是
                // 正常的。可见性由 showInFrontend 决定，不该再拿状态的有无兜一遍——
                // 之前那样做正是 auto 在 Android 上一直不出现在模型列表里的原因。
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
                    quotaBlocked = quota?.available == false,
                    quotaMessage = if (quota?.available == false) quota.message else null,
                    creditSymbol = quota?.creditSymbol,
                )
            }
        Logger.d(
            "ModelApi",
            "models total=${cfg.models.size} visible=${list.size} blocked=$blocked filtered=${statuses != null}",
        )
        registry.update(list)
        registry.markConfigLoaded() // config fetch + decode succeeded (even if list filtered to 0)
        return list
    }

    /** status.php 的 model_status → 「模型 key → 配额状态」。失败/超时返回 null（调用方据此不过滤）。 */
    private suspend fun fetchModelStatuses(): Map<String, ModelQuotaStatus>? = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
        runCatching {
            val jwt = shortTokenManager.freshToken()
            val status = authApi.status(jwt) ?: return@runCatching null
            val modelStatus = status["model_status"] as? JsonObject ?: return@runCatching null
            modelStatus.entries.associate { (key, value) ->
                val o = value as? JsonObject
                key to ModelQuotaStatus(
                    available = o?.get("available")?.jsonPrimitive?.booleanOrNull ?: false,
                    reason = o?.get("reason")?.jsonPrimitive?.contentOrNull,
                    message = o?.get("message")?.jsonPrimitive?.contentOrNull,
                    creditSymbol = o?.get("credit_symbol")?.jsonPrimitive?.contentOrNull,
                )
            }
        }.getOrNull()
    }

    /** `model_status` 里客户端关心的那几个字段。 */
    private data class ModelQuotaStatus(
        val available: Boolean,
        val reason: String?,
        val message: String?,
        val creditSymbol: String?,
    )

    private companion object {
        const val MODEL_CONFIG_TIMEOUT_MS = 15_000L
        const val STATUS_TIMEOUT_MS = 15_000L

        /**
         * 因额度/风控暂时不可用——留在列表里置灰，让用户看得到原因。
         * 其余原因（model_disabled / donor_only / unpriced / 未知）一律移除，
         * 与改造前的「不可用即隐藏」保持一致，未知原因按保守侧处理。
         */
        val KEEP_BUT_DISABLE_REASONS = setOf("limit_exceeded", "tokens_limit_exceeded", "risk_restricted")
    }
}
