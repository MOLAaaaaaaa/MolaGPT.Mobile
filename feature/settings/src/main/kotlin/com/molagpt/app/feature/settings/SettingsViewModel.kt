package com.molagpt.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderPresets
import com.molagpt.app.core.model.byokMcpServerTokenKey
import com.molagpt.app.core.model.McpToolInfo
import com.molagpt.app.core.model.webSearchApiKeyKey
import com.molagpt.app.core.model.withoutToken
import com.molagpt.app.core.network.AccountStatusCache
import com.molagpt.app.core.network.ByokImageApi
import com.molagpt.app.core.network.ByokImageAttachment
import com.molagpt.app.core.network.ByokImageWorkbenchConfig
import com.molagpt.app.core.network.ByokImageWorkbenchResult
import com.molagpt.app.core.network.ByokModelApi
import com.molagpt.app.core.network.McpToolListApi
import com.molagpt.app.core.network.MolaApiException
import com.molagpt.app.core.network.SyncApi
import com.molagpt.app.core.storage.ByokProviderRepository
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.CredentialStore
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val store: SettingsStore,
    private val syncEngine: SyncEngine,
    private val syncApi: SyncApi,
    /** 持久登录 JWT 提供者；同步个性化开关到服务端用（游客为空时仅本地）。 */
    private val jwtProvider: () -> String?,
    /** 账号配额状态缓存（容器级，跨 VM 重建存活；设置页只读，不每次重拉）。 */
    private val accountStatus: AccountStatusCache,
    private val byokProviders: ByokProviderRepository,
    private val byokModelApi: ByokModelApi,
    private val byokImageApi: ByokImageApi,
    private val mcpToolListApi: McpToolListApi,
    private val credentialStore: CredentialStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    // 配额状态直接转发容器级缓存：导航返回不再重拉，仅显式刷新/登录态变化才打网络。
    val status: StateFlow<AccountStatus?> = accountStatus.status
    val statusLoading: StateFlow<Boolean> = accountStatus.loading
    val byokProviderList: StateFlow<List<ByokProvider>> =
        byokProviders.providers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _byokStatus = MutableStateFlow<String?>(null)
    val byokStatus: StateFlow<String?> = _byokStatus.asStateFlow()

    init {
        // 仅在缓存为空/过期时拉取；新鲜则直接复用，不重刷整张额度表。
        accountStatus.ensure(force = false)
        migratePlainMcpTokens()
    }

    /** 用户主动点“刷新”才强制重拉；屏幕进入只调 [ensureStatus]（命中缓存即跳过）。 */
    fun refreshStatus() = accountStatus.ensure(force = true)

    /** 屏幕进入时调用：缓存新鲜则不打网络。 */
    fun ensureStatus() = accountStatus.ensure(force = false)

    fun setThemeMode(v: String) = viewModelScope.launch { store.setThemeMode(v) }
    fun setEnterToSend(v: Boolean) = viewModelScope.launch { store.setEnterToSend(v) }
    fun setTemperature(v: Double) = viewModelScope.launch { store.setTemperature(v) }
    fun setUseThinking(v: Boolean) = viewModelScope.launch { store.setUseThinking(v) }
    fun setReasoningEffort(v: String) = viewModelScope.launch { store.setReasoningEffort(v) }
    fun setTools(network: Boolean, steel: Boolean, code: Boolean) =
        viewModelScope.launch { store.setTools(network, steel, code) }

    /** 清空一次性状态文案（新页面 Snackbar 消费后调用，避免重复弹出）。 */
    fun clearByokStatus() { _byokStatus.value = null }

    /** 当前联网搜索 API key（解密读取，供设置页回填输入框）。 */
    fun webSearchApiKey(provider: String): String =
        credentialStore.loadSecret(webSearchApiKeyKey(provider)).orEmpty()

    /** 保存联网搜索配置：provider/结果数写 DataStore，key 经 CredentialStore 加密。 */
    fun setWebSearch(provider: String, apiKey: String, maxResults: Int) = viewModelScope.launch {
        store.setWebSearchProvider(provider)
        store.setWebSearchMaxResults(maxResults)
        val key = apiKey.trim()
        credentialStore.saveSecret(webSearchApiKeyKey(provider), key.takeIf { it.isNotBlank() })
        _byokStatus.value = "已保存搜索设置"
    }

    fun saveMcpServer(server: ByokMcpServer) = viewModelScope.launch {
        val current = settings.value.byokMcpServers.map { it.withoutToken() }
        val token = server.token?.trim()?.takeIf { it.isNotBlank() }
        val normalized = server.copy(
            id = server.id.ifBlank { "mcp-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10) },
            name = server.name.trim().ifBlank { "MCP 服务器" },
            endpoint = server.endpoint.trim(),
            token = null,
        )
        if (normalized.endpoint.isBlank()) {
            _byokStatus.value = "请填写 MCP 地址"
            return@launch
        }
        if (token != null) {
            credentialStore.saveSecret(byokMcpServerTokenKey(normalized.id), token)
        }
        store.setByokMcpServers(current.filterNot { it.id == normalized.id } + normalized)
        _byokStatus.value = "已保存 ${normalized.name}"
    }

    fun deleteMcpServer(id: String) = viewModelScope.launch {
        credentialStore.removeSecret(byokMcpServerTokenKey(id))
        store.setByokMcpServers(settings.value.byokMcpServers.filterNot { it.id == id })
        _byokStatus.value = "已删除 MCP 服务器"
    }

    fun setMcpServerEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        store.setByokMcpServers(settings.value.byokMcpServers.map {
            if (it.id == id) it.copy(enabled = enabled).withoutToken() else it.withoutToken()
        })
    }

    fun setMcpServerDisabledTools(id: String, disabled: List<String>) = viewModelScope.launch {
        store.setByokMcpServers(settings.value.byokMcpServers.map {
            if (it.id == id) it.copy(disabledTools = disabled).withoutToken() else it.withoutToken()
        })
    }

    /** 列出 MCP 服务器工具（调 JSON-RPC tools/list；失败返回空）。详情页调用。 */
    suspend fun listMcpTools(serverId: String): List<McpToolInfo> {
        val server = settings.value.byokMcpServers.firstOrNull { it.id == serverId } ?: return emptyList()
        return mcpToolListApi.listTools(server)
    }

    /** 测试 MCP 连接（未保存的服务器也可测）。返回成功发现工具数 / 失败原因。 */
    suspend fun testMcpConnection(server: ByokMcpServer): String = try {
        val tools = mcpToolListApi.listTools(server)
        if (tools.isEmpty()) "连接成功，但未返回任何工具" else "连接成功，发现 ${tools.size} 个工具"
    } catch (e: Exception) {
        "连接失败：${e.message ?: "未知错误"}"
    }

    /** 仅拉取模型列表（不落库），供「自动获取」选择页用。 */
    suspend fun fetchByokModels(provider: ByokProvider): List<com.molagpt.app.core.model.ProviderModel> =
        withContext(dispatchers.io) { byokModelApi.fetchModels(provider) }

    /** 把用户选中的模型合并进 provider 落库（去重 by id）。 */
    fun addByokModels(provider: ByokProvider, models: List<com.molagpt.app.core.model.ProviderModel>) = viewModelScope.launch {
        val merged = (models.associateBy { it.id } + provider.models.associateBy { it.id })
            .values
            .sortedWith(compareByDescending<com.molagpt.app.core.model.ProviderModel> { it.supportsChat }.thenBy { it.id })
        byokProviders.upsert(provider.copy(models = merged))
        _byokStatus.value = "已添加 ${models.size} 个模型"
    }

    fun setVisionProxy(enabled: Boolean, modelKey: String?) = viewModelScope.launch {
        store.setVisionProxy(enabled, modelKey)
    }

    fun setImageGenConfig(
        enabled: Boolean,
        modelKey: String?,
        size: String,
        style: String?,
        aspectRatio: String = "1:1",
        reasoning: Boolean = false,
        reasoningEffort: String = "medium",
    ) = viewModelScope.launch {
        store.setImageGenConfig(enabled, modelKey, size, style, aspectRatio, reasoning, reasoningEffort)
    }

    /** 外挂视觉 / 图像生成的可选模型列表——从已启用的 BYOK 提供商派生（筛选 vision-capable / image-capable）。 */
    data class ModelOption(val key: String, val label: String)

    val visionModelOptions: StateFlow<List<ModelOption>> = byokProviderList
        .map { providers ->
            providers
                .filter { it.enabled }
                .flatMap { p ->
                    p.models.filter { it.supportsVision }.map {
                        ModelOption("${p.id}::${it.id}", "${p.name} / ${it.displayName}")
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val imageGenModelOptions: StateFlow<List<ModelOption>> = byokProviderList
        .map { providers ->
            providers
                .filter { it.enabled }
                .flatMap { p ->
                    p.models.filter { it.supportsImageGeneration }.map {
                        ModelOption("${p.id}::${it.id}", "${p.name} / ${it.displayName}")
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun migratePlainMcpTokens() = viewModelScope.launch {
        val servers = store.settings.first().byokMcpServers
        val hasPlainTokens = servers.any { !it.token.isNullOrBlank() }
        if (!hasPlainTokens) return@launch
        servers.forEach { server ->
            server.token?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
                credentialStore.saveSecret(byokMcpServerTokenKey(server.id), token)
            }
        }
        store.setByokMcpServers(servers.map { it.withoutToken() })
    }

    fun setCloudSync(v: Boolean) = viewModelScope.launch {
        store.setCloudSyncEnabled(v)
        // 开启时立即做一次全量同步（force 跳过开关判定，把云端历史拉下来 / 本地推上去）。
        if (v && !_syncing.value) {
            _syncing.value = true
            runCatching { syncEngine.syncNow(force = true) }
            _syncing.value = false
        }
    }

    fun setTracks(v: Boolean) = viewModelScope.launch {
        // 乐观写本地并同步服务端 personalized_memory_enabled；失败时回滚本地。
        store.setTracksEnabled(v)
        val jwt = jwtProvider()?.takeIf { it.isNotBlank() } ?: return@launch
        val ok = runCatching {
            withContext(dispatchers.io) { syncApi.updateSetting(jwt, "personalized_memory_enabled", v) }
        }.getOrDefault(false)
        if (!ok) store.setTracksEnabled(!v)
    }

    fun setCompletionNotify(v: Boolean) = viewModelScope.launch { store.setCompletionNotify(v) }

    fun byokPreset(id: String): ByokProvider? = byokProviders.preset(id)

    fun saveByokProvider(provider: ByokProvider) = viewModelScope.launch {
        byokProviders.upsert(provider)
        _byokStatus.value = "已保存 ${provider.name}"
    }

    fun deleteByokProvider(id: String) = viewModelScope.launch {
        byokProviders.delete(id)
        _byokStatus.value = "已删除服务"
    }

    fun refreshByokModels(provider: ByokProvider) = viewModelScope.launch {
        _byokStatus.value = "正在获取模型..."
        runCatching { withContext(dispatchers.io) { byokModelApi.fetchModels(provider) } }
            .onSuccess { models ->
                val merged = (models.associateBy { it.id } + provider.models.associateBy { it.id })
                    .values
                    .sortedWith(compareByDescending<com.molagpt.app.core.model.ProviderModel> { it.supportsChat }.thenBy { it.id })
                byokProviders.upsert(provider.copy(models = merged))
                _byokStatus.value = if (models.isEmpty()) "未获取到可用模型" else "已添加 ${models.size} 个模型"
            }
            .onFailure { e ->
                _byokStatus.value = e.message ?: "模型获取失败"
            }
    }

    suspend fun runImageWorkbenchRequest(
        providerId: String,
        modelId: String,
        prompt: String,
        config: ByokImageWorkbenchConfig,
        attachments: List<ByokImageAttachment>,
    ): ByokImageWorkbenchResult {
        val provider = byokProviders.get(providerId)
            ?: throw MolaApiException(400, "请选择服务")
        if (!provider.enabled) {
            throw MolaApiException(400, "服务已停用")
        }
        if (provider.purpose != com.molagpt.app.core.model.ByokPurpose.IMAGE) {
            throw MolaApiException(400, "请选择图像用途的服务")
        }
        if (provider.models.none { it.id == modelId && it.supportsImageGeneration }) {
            throw MolaApiException(400, "请选择图像模型")
        }
        return withContext(dispatchers.io) {
            byokImageApi.runWorkbench(
                provider = provider,
                modelId = modelId,
                prompt = prompt,
                config = config,
                attachments = attachments,
            )
        }
    }

    val byokPresets: List<ByokProvider> get() = ByokProviderPresets.defaults

    /** 立即同步：触发完整双向云同步（成功后引擎会更新 lastSyncAt）。 */
    fun syncNow() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        runCatching { syncEngine.syncNow(force = true) }
        _syncing.value = false
    }
}
