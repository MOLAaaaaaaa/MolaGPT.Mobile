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
import com.molagpt.app.core.model.ResponseRegexRule
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.webSearchApiKeyKey
import com.molagpt.app.core.model.withoutToken
import com.molagpt.app.core.network.AccountStatusCache
import com.molagpt.app.core.network.ByokModelApi
import com.molagpt.app.core.network.McpToolListApi
import com.molagpt.app.core.network.MolaApiException
import com.molagpt.app.core.storage.ByokProviderRepository
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.CredentialStore
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.SyncEngine
import com.molagpt.app.core.storage.TracksToggle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
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
    /** 个性化记忆总开关的共用写入口（与个性化页共用，避免两份不一致的实现）。 */
    private val tracksToggle: TracksToggle,
    /** 账号配额状态缓存（容器级，跨 VM 重建存活；设置页只读，不每次重拉）。 */
    private val accountStatus: AccountStatusCache,
    private val byokProviders: ByokProviderRepository,
    private val byokModelApi: ByokModelApi,
    private val modelsDevCatalog: com.molagpt.app.core.network.ModelsDevCatalog,
    private val mcpToolListApi: McpToolListApi,
    private val credentialStore: CredentialStore,
    private val dispatchers: DispatcherProvider,
    molaLoginState: Flow<Boolean>,
) : ViewModel() {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    val historyIdentity = credentialStore.historyIdentity

    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val molaSignedIn: StateFlow<Boolean> = molaLoginState.stateIn(
        viewModelScope, SharingStarted.Eagerly, !credentialStore.jwt.isNullOrBlank(),
    )

    // 配额状态直接转发容器级缓存：导航返回不再重拉，仅显式刷新/登录态变化才打网络。
    val status: StateFlow<AccountStatus?> = accountStatus.status
    val statusLoading: StateFlow<Boolean> = accountStatus.loading
    val statusError: StateFlow<String?> = accountStatus.error
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
    fun setShowAgentControlShortcut(v: Boolean) =
        viewModelScope.launch { store.setShowAgentControlShortcut(v) }
    fun setShowImageWorkbenchShortcut(v: Boolean) =
        viewModelScope.launch { store.setShowImageWorkbenchShortcut(v) }
    fun setVisualAnswersEnabled(v: Boolean) = viewModelScope.launch { store.setVisualAnswersEnabled(v) }
    fun setHtmlAsCard(v: Boolean) = viewModelScope.launch { store.setHtmlAsCard(v) }
    fun setUseThinking(v: Boolean) = viewModelScope.launch { store.setUseThinking(v) }
    fun setReasoningEffort(v: String) = viewModelScope.launch { store.setReasoningEffort(v) }
    fun setWebAccessTools(enabled: Boolean) =
        viewModelScope.launch { store.setWebAccessTools(enabled) }
    fun setCodeTool(enabled: Boolean) = viewModelScope.launch { store.setCodeTool(enabled) }

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

    suspend fun fetchByokModels(provider: ByokProvider): List<com.molagpt.app.core.model.ProviderModel> =
        withContext(dispatchers.io) {
            val fetched = byokModelApi.fetchModels(provider)
            val byId = fetched.associateBy { it.id }
            byokProviders.update(provider.id) { current ->
                if (!sameDiscoveryEndpoint(current, provider)) return@update current
                current.copy(models = current.models.map { model ->
                    val declared = byId[model.id] ?: return@map model
                    val config = if (model.thinkingConfig?.manualOverride == true) model.thinkingConfig
                    else declared.thinkingConfig?.copy(offIneffective = model.thinkingConfig?.offIneffective == true)
                        ?: model.thinkingConfig
                    val imageFormat = declared.imageFormat.takeIf {
                        current.purpose == com.molagpt.app.core.model.ByokPurpose.IMAGE && model.imageFormat == null
                    }
                    model.copy(
                        pricing = com.molagpt.app.core.model.mergeModelPricing(model.pricing, declared.pricing),
                        contextWindow = model.contextWindow ?: declared.contextWindow,
                        thinkingConfig = config,
                        supportsThinking = config?.let { it.kind != com.molagpt.app.core.model.ThinkingParamKind.NONE }
                            ?: model.supportsThinking,
                        supportsReasoningEffort = config?.let { ThinkingKinds.resolveEffortLevels(it, current.baseUrl).isNotEmpty() }
                            ?: model.supportsReasoningEffort,
                        imageFormat = imageFormat ?: model.imageFormat,
                        apiUrl = if (imageFormat == null) model.apiUrl else declared.apiUrl,
                        supportsImageEdit = if (current.isOpenRouter && imageFormat == com.molagpt.app.core.model.ByokImageFormat.OPENAI_IMAGES)
                            declared.supportsImageEdit else model.supportsImageEdit,
                    )
                })
            }
            fetched
        }

    private fun sameDiscoveryEndpoint(current: ByokProvider, requested: ByokProvider): Boolean =
        current.baseUrl.trim().trimEnd('/') == requested.baseUrl.trim().trimEnd('/') &&
            current.modelsPath.trimStart('/') == requested.modelsPath.trimStart('/') &&
            current.type == requested.type && current.purpose == requested.purpose &&
            current.apiKey == requested.apiKey && current.customHeaders == requested.customHeaders

    private val _pricingRefresh = MutableStateFlow(PricingRefreshState(cachedAt = modelsDevCatalog.cachedAt))
    val pricingRefresh: StateFlow<PricingRefreshState> = _pricingRefresh.asStateFlow()

    suspend fun fetchModelPriceCandidates(modelIds: Collection<String>): Map<String, List<com.molagpt.app.core.model.ModelsDevPrice>> {
        val providers = modelsDevCatalog.load().providers
        return modelIds.associateWith { modelId ->
            providers.mapNotNull { provider ->
                provider.models[modelId.trim().lowercase()]?.let { pricing ->
                    com.molagpt.app.core.model.ModelsDevPrice(provider.key, provider.name, pricing)
                }
            }
        }
    }

    fun fetchAllModelPrices(force: Boolean = false) {
        if (_pricingRefresh.value.busy) return
        _pricingRefresh.value = _pricingRefresh.value.copy(busy = true, result = null)
        viewModelScope.launch {
            try {
                val loaded = modelsDevCatalog.load(force)
                val targets = byokProviders.list().filter { it.purpose == com.molagpt.app.core.model.ByokPurpose.CHAT }
                val match = com.molagpt.app.core.model.ModelsDevPricing.match(loaded.providers,
                    targets.flatMap { it.models }.filter { it.supportsChat }.map { it.id })
                var written = 0
                for (provider in targets) written += applyByokPrices(provider.id, match.agreed)
                var resolved = 0
                val reviews = targets.flatMap { provider ->
                    provider.models.filter { it.supportsChat && it.pricing?.isManual != true }.mapNotNull { model ->
                        val candidates = match.conflicts[model.id]
                        if (candidates == null && model.id !in match.unmatched) return@mapNotNull null
                        if (candidates?.any { candidate -> samePrice(model.pricing, candidate.pricing) } == true) {
                            resolved++
                            return@mapNotNull null
                        }
                        ModelPriceReview(provider.id, provider.name, model, candidates.orEmpty(),
                            com.molagpt.app.core.model.ModelsDevPricing.guessProviderKey(provider.baseUrl))
                    }
                }
                val manual = targets.sumOf { it.models.count { model -> model.supportsChat && model.pricing?.isManual == true } }
                val conflicts = reviews.count { it.candidates.isNotEmpty() }
                val unmatched = reviews.size - conflicts
                val result = listOfNotNull(
                    if (written > 0) "已保存 $written 项价格" else null,
                    if (manual > 0) "保留 $manual 项手动价格" else null,
                    if (conflicts > 0) "$conflicts 项价格不一致" else null,
                    if (unmatched > 0) "$unmatched 项未匹配" else null,
                    loaded.warning,
                ).joinToString("；").ifEmpty {
                    if (resolved > 0) "模型价格已是最新" else "没有可用的价格"
                }
                _pricingRefresh.value = PricingRefreshState(cachedAt = modelsDevCatalog.cachedAt, result = result, reviews = reviews)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _pricingRefresh.value = _pricingRefresh.value.copy(result = "获取定价失败：${e.message}")
            } finally {
                _pricingRefresh.value = _pricingRefresh.value.copy(busy = false)
            }
        }
    }

    suspend fun applyByokPrices(providerId: String, prices: Map<String, com.molagpt.app.core.model.ModelPricing>): Int {
        var written = 0
        byokProviders.update(providerId) { current ->
            current.copy(models = current.models.map { model ->
                val pricing = com.molagpt.app.core.model.mergeModelPricing(model.pricing, prices[model.id])
                if (pricing != model.pricing) written++
                model.copy(pricing = pricing)
            })
        }
        return written
    }

    suspend fun saveReviewedPrice(
        review: ModelPriceReview,
        pricing: com.molagpt.app.core.model.ModelPricing,
        sourceProviderKey: String?,
        applyToMatchingModels: Boolean,
    ) {
        require(pricing.isValid)
        val currentReviews = _pricingRefresh.value.reviews
        val targets = if (applyToMatchingModels && sourceProviderKey != null) {
            currentReviews.filter { item ->
                item.providerId == review.providerId && item.candidates.any { it.providerKey == sourceProviderKey }
            }
        } else {
            listOf(review)
        }
        val prices = targets.associate { item ->
            val selected = sourceProviderKey?.let { key -> item.candidates.firstOrNull { it.providerKey == key }?.pricing }
            item.model.id to (selected ?: pricing).copy(
                source = sourceProviderKey?.let { "models.dev:$it" } ?: pricing.source,
            )
        }
        applyByokPrices(review.providerId, prices)
        val applied = targets.map { it.providerId to it.model.id }.toSet()
        _pricingRefresh.value = _pricingRefresh.value.copy(
            result = if (targets.size == 1) {
                "已保存 ${review.providerName} / ${review.model.displayName} 的价格"
            } else {
                "已为 ${review.providerName} 的 ${targets.size} 个模型保存价格"
            },
            reviews = currentReviews.filterNot { (it.providerId to it.model.id) in applied },
        )
    }

    private fun samePrice(
        first: com.molagpt.app.core.model.ModelPricing?,
        second: com.molagpt.app.core.model.ModelPricing,
    ): Boolean = first != null &&
        (first.source == "models.dev" || first.source?.startsWith("models.dev:") == true) &&
        first.copy(source = null) == second.copy(source = null)

    /** 合并用户选中的候选模型，保留现有模型的手动配置。 */
    fun addByokModels(provider: ByokProvider, models: List<com.molagpt.app.core.model.ProviderModel>) = viewModelScope.launch {
        mergeDiscoveredModels(provider, models)
        _byokStatus.value = "已添加 ${models.size} 个模型"
    }

    private suspend fun mergeDiscoveredModels(provider: ByokProvider, models: List<com.molagpt.app.core.model.ProviderModel>) {
        byokProviders.update(provider.id) { current ->
            if (!sameDiscoveryEndpoint(current, provider)) return@update current
            val merged = (models.associateBy { it.id } + current.models.associateBy { it.id }).values
            current.copy(models = merged.sortedWith(compareByDescending<com.molagpt.app.core.model.ProviderModel> { it.supportsChat }.thenBy { it.id }))
        }
    }

    fun setVisionProxy(enabled: Boolean, modelKey: String?) = viewModelScope.launch {
        store.setVisionProxy(enabled, modelKey)
    }

    fun setAutoTitle(enabled: Boolean, modelKey: String?) = viewModelScope.launch {
        store.setAutoTitle(enabled, modelKey)
    }

    fun setContextCompaction(enabled: Boolean, modelKey: String?) = viewModelScope.launch {
        store.setContextCompaction(enabled, modelKey)
    }

    fun setContextSlimming(enabled: Boolean) = viewModelScope.launch {
        store.setContextSlimming(enabled)
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

    /** 标题模型候选：所有已启用 provider 的对话模型（挂个便宜小模型最划算）。 */
    val titleModelOptions: StateFlow<List<ModelOption>> = byokProviderList
        .map { providers ->
            providers
                .filter { it.enabled }
                .flatMap { p ->
                    p.models.filter { it.supportsChat }.map {
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
        val expected = credentialStore.historyIdentity.value
        if (!expected.canSync) return@launch
        store.setCloudSyncEnabled(v)
        // The preference is committed before sync, without relying on a cached UI snapshot.
        if (v && !_syncing.value) {
            _syncing.value = true
            runCatching { syncEngine.syncNow(expected = expected) }
            _syncing.value = false
        }
    }

    /**
     * 个性化记忆总开关。写入逻辑收敛在 [TracksToggle]（与个性化页共用一份）。
     * 失败时本地已回滚，这里只负责把原因说给用户——旧实现在未登录时静默失败。
     */
    fun setTracks(v: Boolean) = viewModelScope.launch {
        val result = withContext(dispatchers.io) { tracksToggle.setTracksEnabled(v) }
        result.message?.let { _byokStatus.value = it }
    }

    fun setCompletionNotify(v: Boolean) = viewModelScope.launch { store.setCompletionNotify(v) }

    // —— 回答后处理 ——

    fun setResponsePostProcessingEnabled(v: Boolean) =
        viewModelScope.launch { store.setResponsePostProcessingEnabled(v) }

    fun setResponseRegexRules(rules: List<ResponseRegexRule>) =
        viewModelScope.launch { store.setResponseRegexRules(rules) }

    fun resetResponseRegexRules() = viewModelScope.launch { store.resetResponseRegexRules() }

    fun byokPreset(id: String): ByokProvider? = byokProviders.preset(id)

    fun saveByokProvider(provider: ByokProvider) = viewModelScope.launch {
        try {
            byokProviders.upsert(provider, preserveModels = true)
            _byokStatus.value = "已保存 ${provider.name}"
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            _byokStatus.value = "无法保存服务配置"
        }
    }

    fun setByokProviderEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        byokProviders.update(id) { it.copy(enabled = enabled) }
    }

    fun saveByokModel(id: String, model: com.molagpt.app.core.model.ProviderModel) = viewModelScope.launch {
        byokProviders.update(id) { current ->
            current.copy(models = (current.models.filterNot { it.id == model.id } + model).sortedBy { it.id })
        }
    }

    fun deleteByokModels(id: String, models: Set<String>) = viewModelScope.launch {
        byokProviders.update(id) { it.copy(models = it.models.filterNot { model -> model.id in models }) }
    }

    fun deleteByokProvider(id: String) = viewModelScope.launch {
        byokProviders.delete(id)
        _byokStatus.value = "已删除服务"
    }

    fun refreshByokModels(provider: ByokProvider) = viewModelScope.launch {
        _byokStatus.value = "正在获取模型..."
        runCatching { fetchByokModels(provider) }
            .onSuccess { models ->
                mergeDiscoveredModels(provider, models)
                _byokStatus.value = if (models.isEmpty()) "未获取到可用模型" else "已添加 ${models.size} 个模型"
            }
            .onFailure { e ->
                _byokStatus.value = e.message ?: "模型获取失败"
            }
    }

    val byokPresets: List<ByokProvider> get() = ByokProviderPresets.defaults

    /** 立即同步：触发完整双向云同步（成功后引擎会更新 lastSyncAt）。 */
    fun syncNow() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        runCatching { syncEngine.syncNow() }
        _syncing.value = false
    }
}
