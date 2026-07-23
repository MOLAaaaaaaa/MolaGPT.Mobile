package com.molagpt.app.di

import android.content.Context
import android.os.Build
import com.molagpt.app.AgentNotificationController
import com.molagpt.app.AgentNotificationMonitor
import com.molagpt.app.NotificationController
import com.molagpt.app.StreamForegroundService
import com.molagpt.app.core.common.DefaultDispatchers
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.byokMcpServerTokenKey
import com.molagpt.app.core.model.ImageGenerationConfig
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.WebSearchOptions
import com.molagpt.app.core.model.WebSearchProvider
import com.molagpt.app.core.model.webSearchApiKeyKey
import com.molagpt.app.core.network.AltchaSolver
import com.molagpt.app.core.network.AccountStatusCache
import com.molagpt.app.core.network.AgentControlService
import com.molagpt.app.core.network.AuthApi
import com.molagpt.app.core.network.ByokChatService
import com.molagpt.app.core.network.ByokImageApi
import com.molagpt.app.core.network.ByokModelApi
import com.molagpt.app.core.network.ChatService
import com.molagpt.app.core.network.MolaGptChatService
import com.molagpt.app.core.network.MolaHttp
import com.molagpt.app.core.network.ModelApi
import com.molagpt.app.core.network.ModelRegistry
import com.molagpt.app.core.network.McpToolListApi
import com.molagpt.app.core.network.RoutingChatService
import com.molagpt.app.core.network.ShortTokenManager
import com.molagpt.app.core.network.SyncApi
import com.molagpt.app.core.network.UserAgentProvider
import com.molagpt.app.core.network.UserDataApi
import com.molagpt.app.core.network.toAccountStatus
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.ByokProviderRepository
import com.molagpt.app.core.storage.ChatRepository
import com.molagpt.app.core.storage.CredentialStore
import com.molagpt.app.core.storage.MolaDatabase
import com.molagpt.app.core.storage.PersonaRepository
import com.molagpt.app.core.storage.SessionRepository
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.SyncEngine
import com.molagpt.app.core.storage.allModels
import com.molagpt.app.feature.auth.MolaGptAuthService
import com.molagpt.app.feature.chat.BackgroundStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 手动 DI 容器（不引 Hilt，降低多模块盲构建风险）。在 [com.molagpt.app.MolaApp] 创建一次，
 * 经 CompositionLocal 下发，ViewModel 由各屏的工厂注入。
 *
 * 关键：**固定 UA 在此处构造一次**（登录与对话共用，杜绝 JWT-UA 校验 401）；
 * **ChatService 装配真实 [MolaGptChatService]**（直连 MolaGPT，无 Mock 分支）。
 * 运行时编排（后台流前台服务、被杀任务对账、完成通知、云同步触发）也在此处接线。
 */
class AppContainer(
    context: Context,
    versionName: String,
    sdkInt: Int,
    isDebug: Boolean,
) {
    val dispatchers: DispatcherProvider = DefaultDispatchers
    private val applicationScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Application context（供需要读 ContentResolver 的 VM，如图片附件读取）。 */
    val appContext: Context = context.applicationContext

    val userAgent: String = UserAgentProvider.build(versionName, sdkInt)

    private val http = MolaHttp(userAgent = userAgent, enableLogging = isDebug)

    val credentialStore = CredentialStore(context)
    val settingsStore = SettingsStore(context)
    private val agentDeviceId: String = run {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                appContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
        androidId?.let { "android-$it" }
            ?: credentialStore.loadSecret("agent.device_id")
            ?: "app-${java.util.UUID.randomUUID()}".also { credentialStore.saveSecret("agent.device_id", it) }
    }
    private val agentDeviceName: String = listOf(Build.MANUFACTURER, Build.MODEL)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android" }

    /** 最近一次设置快照，供同步/通知/删除策略做无挂起读取；由 init 的收集器持续更新。 */
    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    /** 设置 StateFlow（供聊天 VM 订阅 MCP 服务器存在性等门控信号）。 */
    val settingsFlow: StateFlow<AppSettings> =
        settingsStore.settings.stateIn(applicationScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, AppSettings())

    private val database = MolaDatabase.build(context)
    val modelRegistry = ModelRegistry()
    val byokProviderRepository = ByokProviderRepository(
        dao = database.byokProviderDao(),
        credentialStore = credentialStore,
        dispatchers = dispatchers,
    )

    /** providerId → baseUrl 快照（供聊天页推理弹层判断聚合网关/预算折算，`.value` 无挂起读取）。 */
    val byokProviderBaseUrls: StateFlow<Map<String, String>> =
        byokProviderRepository.providers
            .map { list -> list.associate { it.id to it.baseUrl } }
            .stateIn(applicationScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())
    val personaRepository = PersonaRepository(
        personaDao = database.personaDao(),
        dispatchers = dispatchers,
    )

    val authApi = AuthApi(http)

    private val altchaSolver = AltchaSolver(http.json)

    val shortTokenManager = ShortTokenManager(
        http = http,
        altchaSolver = altchaSolver,
        dispatchers = dispatchers,
        longTokenProvider = { credentialStore.jwt },
        onRenewedLongToken = { credentialStore.jwt = it },
        onLoginInvalidated = { credentialStore.clear() },
    )

    val modelApi = ModelApi(http, modelRegistry, shortTokenManager, authApi)
    val byokModelApi = ByokModelApi(http)
    val byokImageApi = ByokImageApi(http)
    val mcpToolListApi = McpToolListApi(http)

    /**
     * 账号配额状态缓存（容器级，跨 ViewModel 重建存活）。设置页/个性化页只读它，
     * 导航返回不再每次重拉整张额度表；登录态变化由 [MolaGptAuthService.onAuthChanged] invalidate。
     */
    val accountStatusCache = AccountStatusCache(
        scope = applicationScope,
        dispatchers = dispatchers,
        loader = {
            val jwt = shortTokenManager.freshToken()
            authApi.status(jwt)?.toAccountStatus { mid -> modelRegistry.find(mid)?.displayName }
        },
    )

    val authService = MolaGptAuthService(
        authApi = authApi,
        credentials = credentialStore,
        userAgent = userAgent,
        dispatchers = dispatchers,
        onAuthChanged = {
            shortTokenManager.invalidate()
            accountStatusCache.invalidate()
            applicationScope.launch { runCatching { modelApi.refresh() } }
            // 登录后做一次全量云同步；登出后重置游标（下次按首次全量处理）。
            applicationScope.launch {
                if (credentialStore.isLoggedIn) {
                    runCatching { syncEngine.syncNow() }
                } else {
                    // 登出/切号：清理旧账户的占位会话并重置同步游标。
                    runCatching { syncEngine.prunePlaceholders() }
                    runCatching { settingsStore.resetSyncCursor() }
                }
            }
        },
    )

    private val molaGptChatService: ChatService = MolaGptChatService(
        http = http,
        registry = modelRegistry,
        shortTokenManager = shortTokenManager,
        dispatchers = dispatchers,
    )

    private val byokChatService = ByokChatService(
        http = http,
        providerResolver = { id -> byokProviderRepository.get(id) },
        mcpServersProvider = {
            latestSettings.byokMcpServers.map { server ->
                server.copy(token = credentialStore.loadSecret(byokMcpServerTokenKey(server.id)))
            }
        },
        webSearchOptionsProvider = {
            val provider = WebSearchProvider.fromId(latestSettings.webSearchProvider)
            WebSearchOptions(
                provider = provider,
                apiKey = credentialStore.loadSecret(webSearchApiKeyKey(provider.id)),
                maxResults = latestSettings.webSearchMaxResults,
            )
        },
        imageProviderResolver = {
            byokProviderRepository.list().firstOrNull { it.enabled && it.purpose == ByokPurpose.IMAGE }
        },
        visionProviderResolver = {
            // 「BYOK 工具 → 视觉理解」配置的目标 `<providerId>::<modelId>`（可跨 provider）→ (目标 provider, modelId)。
            latestSettings.visionProxyModelKey
                ?.takeIf { it.contains("::") }
                ?.let { key ->
                    val providerId = key.substringBefore("::")
                    val modelId = key.substringAfter("::")
                    byokProviderRepository.get(providerId)
                        ?.takeIf { it.enabled }
                        ?.let { it to modelId }
                }
        },
        imageGenConfigProvider = {
            ImageGenerationConfig(
                imageSize = latestSettings.imageGenSize,
                aspectRatio = latestSettings.imageGenAspectRatio,
                reasoning = latestSettings.imageGenReasoning,
                reasoningEffort = latestSettings.imageGenReasoningEffort,
            )
        },
        byokImageApi = byokImageApi,
        imageFileSaver = { bytes, ext ->
            // 生成图落地到 app 私有 filesDir/gen_images，返回 file:// 供 Coil 加载（消息只存引用，不存 base64）。
            runCatching {
                val dir = java.io.File(appContext.filesDir, "gen_images").apply { mkdirs() }
                val file = java.io.File(dir, "${java.util.UUID.randomUUID()}.$ext")
                file.writeBytes(bytes)
                android.net.Uri.fromFile(file).toString()
            }.getOrNull()
        },
        dispatchers = dispatchers,
    )

    /** 唯一对话服务。按会话来源路由到 MolaGPT 账户或 BYOK。 */
    private val chatService: ChatService = RoutingChatService(
        molaGpt = molaGptChatService,
        byok = byokChatService,
    )

    val sessionRepository = SessionRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        dispatchers = dispatchers,
        cloudSyncEnabled = { latestSettings.cloudSyncEnabled },
    )

    /** ChatRepository 唯一实例（始终对接真实服务）。 */
    val chatRepository: ChatRepository = ChatRepository(
        chatService = chatService,
        messageDao = database.messageDao(),
        conversationDao = database.conversationDao(),
        streamTaskDao = database.streamTaskDao(),
        dispatchers = dispatchers,
    )

    /** 云同步底层调用（会话同步 / 用户设置写入 update_setting）。 */
    val syncApi = SyncApi(http)

    /** 个性化数据接口（人格洞察 / 对话风格偏好）；设置页与个性化管理页共用。 */
    val userDataApi = UserDataApi(http)

    /**
     * Agent 控制 relay 客户端——手机遥控桌面 Claude Code / Codex 会话。复用登录**长 JWT**
     * （免 ALTCHA/短 token），连 `agent_*.php` 中继。详见 :core:network AgentControlService。
     */
    val agentControlService = AgentControlService(
        http = http,
        dispatchers = dispatchers,
        jwtProvider = { credentialStore.jwt },
        deviceIdProvider = { agentDeviceId },
        deviceNameProvider = { agentDeviceName },
    )

    /** 云同步引擎（个人中心“立即同步”、登录、每轮完成均触发）。 */
    val syncEngine = SyncEngine(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        syncApi = syncApi,
        settingsStore = settingsStore,
        jwtProvider = { credentialStore.jwt },
        cloudSyncEnabled = { latestSettings.cloudSyncEnabled },
        dispatchers = dispatchers,
        scope = applicationScope,
    )

    val backgroundStreamManager = BackgroundStreamManager(
        chatRepository = chatRepository,
        scope = applicationScope,
        apiUrlResolver = { providerId, modelId -> modelRegistry.apiUrlFor(providerId, modelId) },
    )

    /** App 是否在前台（MainActivity onStart/onStop 维护），用于完成通知抑制。 */
    val appForeground = MutableStateFlow(false)

    /** 当前可见会话 id（ChatHost 维护），用于完成通知抑制。 */
    val foregroundSessionId = MutableStateFlow<String?>(null)

    /** 当前可见的远程 Agent 会话 id，用于 Agent 通知抑制。 */
    val foregroundAgentSessionId = MutableStateFlow<String?>(null)

    /** 通知/深链请求打开的会话；ChatHost 消费后清空。 */
    val pendingOpenSessionId = MutableStateFlow<String?>(null)

    /** Agent 通知请求打开的远程会话；AgentControl 路由消费后清空。 */
    val pendingOpenAgentSessionId = MutableStateFlow<String?>(null)

    private val notificationController = NotificationController(
        appContext = appContext,
        manager = backgroundStreamManager,
        sessionRepository = sessionRepository,
        scope = applicationScope,
        notifyEnabled = { latestSettings.completionNotify },
        appForeground = { appForeground.value },
        foregroundSessionId = { foregroundSessionId.value },
    )

    private val agentNotificationController = AgentNotificationController(
        context = appContext,
        appForeground = { appForeground.value },
        foregroundAgentSessionId = { foregroundAgentSessionId.value },
    )

    internal val agentNotificationMonitor = AgentNotificationMonitor(
        service = agentControlService,
        controller = agentNotificationController,
        scope = applicationScope,
        notifyEnabled = { latestSettings.completionNotify },
        isAuthenticated = { !credentialStore.jwt.isNullOrBlank() },
    )

    fun setAppForeground(value: Boolean) {
        appForeground.value = value
    }

    fun setForegroundSession(sessionId: String?) {
        foregroundSessionId.value = sessionId
    }

    fun setForegroundAgentSession(sessionId: String?) {
        foregroundAgentSessionId.value = sessionId
    }

    /** 通知点击/深链：请求 UI 打开指定会话。 */
    fun requestOpenConversation(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) pendingOpenSessionId.value = sessionId
    }

    fun requestOpenAgentSession(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) pendingOpenAgentSessionId.value = sessionId
    }

    init {
        if (isDebug) {
            Logger.sink = object : Logger.Sink {
                override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                    when (level) {
                        Logger.Level.DEBUG -> android.util.Log.d(tag, message, throwable)
                        Logger.Level.INFO -> android.util.Log.i(tag, message, throwable)
                        Logger.Level.WARN -> android.util.Log.w(tag, message, throwable)
                        Logger.Level.ERROR -> android.util.Log.e(tag, message, throwable)
                    }
                }
            }
        }
        if (isDebug) {
            DebugCredentialImporter.importIfPresent(context, credentialStore, userAgent)
        }
        // 启动时校验 UA 是否漂移，漂移则静默清 token。
        authService.ensureJwtValidForUa()

        // 设置快照：持续更新本地缓存供无挂起读取。
        applicationScope.launch {
            settingsStore.settings.collect { latestSettings = it }
        }

        // BYOK provider 配置变化时刷新运行时模型注册表。
        applicationScope.launch {
            byokProviderRepository.providers.collect { providers ->
                modelRegistry.updateByok(
                    providers
                        .filter { it.enabled }
                        .flatMap { it.allModels() },
                )
            }
        }

        // 刷新模型 → 对账被杀的在途流任务 → 已登录则做一次启动云同步。
        applicationScope.launch {
            latestSettings = runCatching { settingsStore.settings.first() }.getOrDefault(AppSettings())
            runCatching { modelApi.refresh() }
            reconcileStreamTasks()
            runCatching { personaRepository.ensureSeeded() }
            if (authService.isLoggedIn && latestSettings.cloudSyncEnabled) {
                runCatching { syncEngine.syncNow() }
            }
        }

        // 活跃流计数 >0 时拉起前台服务保活，归零即停。
        applicationScope.launch {
            var serviceRunning = false
            backgroundStreamManager.activeCount
                .collect { count ->
                    val shouldRun = count > 0
                    if (shouldRun && !serviceRunning) {
                        StreamForegroundService.start(appContext)
                        serviceRunning = true
                    } else if (!shouldRun && serviceRunning) {
                        StreamForegroundService.stop(appContext)
                        serviceRunning = false
                    }
                }
        }

        // 每轮对话正常完成 → 增量推送该会话到云端。
        applicationScope.launch {
            backgroundStreamManager.completions.collect { syncEngine.schedulePush(it.sessionId) }
        }
    }

    /**
     * 启动对账：读出被杀前落库的在途任务，向服务端 stream_cache 查状态——
     * completed/streaming 则 resume(offset=0) 续接补收，其余（停止/过期/不存在）清理任务。
     */
    private suspend fun reconcileStreamTasks() {
        val tasks = runCatching { chatRepository.loadStreamTasks() }.getOrDefault(emptyList())
        for (record in tasks) {
            if (record.providerKind == ProviderKind.BYOK) {
                runCatching { chatRepository.removeStreamTask(record.sessionId) }
                continue
            }
            val status = runCatching { chatService.checkStreamStatus(record.streamSessionId) }.getOrNull()
            when (status?.status) {
                "completed", "streaming" -> backgroundStreamManager.resume(record)
                else -> runCatching { chatRepository.removeStreamTask(record.sessionId) }
            }
        }
    }
}
