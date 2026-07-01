package com.molagpt.app.core.network

import com.molagpt.app.core.model.ProviderModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内模型注册表。由 [ModelApi] 拉取并按用户过滤后填充；对话时据此把 modelId → apiUrl。
 *
 * [models] 是**响应式流**：登录态变化后 [ModelApi] 重新过滤并 [update]，所有订阅它的聊天页实时更新
 * （StateFlow 按内容去重，列表无变化不重复触发重组）。
 */
class ModelRegistry {
    private val _models = MutableStateFlow<List<ProviderModel>>(emptyList())
    val models: StateFlow<List<ProviderModel>> = _models.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    // True once the MolaGPT model_config fetch has SUCCEEDED at least once (even if the
    // per-account availability filter then leaves 0 official models). Lets retry logic
    // tell "fetch failed" (keep retrying) from "fetched, just empty/filtered" (stop).
    // BYOK models load from the local DB and never touch this flag.
    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()
    private val byId = HashMap<String, ProviderModel>()
    private var molaGptModels: List<ProviderModel> = emptyList()
    private var byokModels: List<ProviderModel> = emptyList()
    private var activeRefreshes = 0

    /** Mark that the MolaGPT model_config fetch succeeded (called by ModelApi). */
    fun markConfigLoaded() {
        _configLoaded.value = true
    }

    @Synchronized
    fun update(list: List<ProviderModel>) {
        updateMolaGpt(list)
    }

    @Synchronized
    fun updateMolaGpt(list: List<ProviderModel>) {
        molaGptModels = list
        publish()
    }

    @Synchronized
    fun updateByok(list: List<ProviderModel>) {
        byokModels = list
        publish()
    }

    private fun publish() {
        val list = molaGptModels + byokModels
        byId.clear()
        list.forEach { byId[registryKey(it.providerId, it.id)] = it }
        list.forEach { byId.putIfAbsent(it.id, it) }
        _models.value = list
    }

    fun all(): List<ProviderModel> = _models.value

    fun find(modelId: String): ProviderModel? = byId[modelId]

    fun find(providerId: String?, modelId: String): ProviderModel? =
        byId[registryKey(providerId, modelId)] ?: byId[modelId]

    @Synchronized
    fun beginRefresh() {
        activeRefreshes += 1
        _isRefreshing.value = true
    }

    @Synchronized
    fun endRefresh() {
        activeRefreshes = (activeRefreshes - 1).coerceAtLeast(0)
        _isRefreshing.value = activeRefreshes > 0
    }

    /** modelId 对应的相对 apiUrl；未知时回退自动路由端点。 */
    fun apiUrlFor(modelId: String): String =
        byId[modelId]?.apiUrl ?: MolaEndpoints.DEFAULT_CHAT_API

    /** providerId + modelId 对应的相对 apiUrl；BYOK 同名模型优先按 provider 精确命中。 */
    fun apiUrlFor(providerId: String?, modelId: String): String =
        find(providerId, modelId)?.apiUrl ?: MolaEndpoints.DEFAULT_CHAT_API

    private fun registryKey(providerId: String?, modelId: String): String =
        "${providerId.orEmpty()}::$modelId"
}
