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
    private val byId = HashMap<String, ProviderModel>()
    private var activeRefreshes = 0

    @Synchronized
    fun update(list: List<ProviderModel>) {
        byId.clear()
        list.forEach { byId[it.id] = it }
        _models.value = list
    }

    fun all(): List<ProviderModel> = _models.value

    fun find(modelId: String): ProviderModel? = byId[modelId]

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
}
