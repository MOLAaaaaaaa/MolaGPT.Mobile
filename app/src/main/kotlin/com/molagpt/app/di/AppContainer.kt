package com.molagpt.app.di

import android.content.Context
import com.molagpt.app.NotificationController
import com.molagpt.app.StreamForegroundService
import com.molagpt.app.core.common.DefaultDispatchers
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.network.AltchaSolver
import com.molagpt.app.core.network.AuthApi
import com.molagpt.app.core.network.ChatService
import com.molagpt.app.core.network.MolaGptChatService
import com.molagpt.app.core.network.MolaHttp
import com.molagpt.app.core.network.ModelApi
import com.molagpt.app.core.network.ModelRegistry
import com.molagpt.app.core.network.ShortTokenManager
import com.molagpt.app.core.network.SyncApi
import com.molagpt.app.core.network.UserAgentProvider
import com.molagpt.app.core.network.UserDataApi
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.ChatRepository
import com.molagpt.app.core.storage.CredentialStore
import com.molagpt.app.core.storage.MolaDatabase
import com.molagpt.app.core.storage.SessionRepository
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.SyncEngine
import com.molagpt.app.feature.auth.MolaGptAuthService
import com.molagpt.app.feature.chat.BackgroundStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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

    /** 最近一次设置快照，供同步/通知/删除策略做无挂起读取；由 init 的收集器持续更新。 */
    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    private val database = MolaDatabase.build(context)
    val modelRegistry = ModelRegistry()

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

    val authService = MolaGptAuthService(
        authApi = authApi,
        credentials = credentialStore,
        userAgent = userAgent,
        dispatchers = dispatchers,
        onAuthChanged = {
            shortTokenManager.invalidate()
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

    /** 唯一对话服务。token 由 [shortTokenManager] 统一提供（游客/登录同路径）。 */
    private val chatService: ChatService = MolaGptChatService(
        http = http,
        registry = modelRegistry,
        shortTokenManager = shortTokenManager,
        dispatchers = dispatchers,
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
        apiUrlResolver = { modelId -> modelRegistry.apiUrlFor(modelId) },
    )

    /** App 是否在前台（MainActivity onStart/onStop 维护），用于完成通知抑制。 */
    val appForeground = MutableStateFlow(false)

    /** 当前可见会话 id（ChatHost 维护），用于完成通知抑制。 */
    val foregroundSessionId = MutableStateFlow<String?>(null)

    /** 通知/深链请求打开的会话；ChatHost 消费后清空。 */
    val pendingOpenSessionId = MutableStateFlow<String?>(null)

    private val notificationController = NotificationController(
        appContext = appContext,
        manager = backgroundStreamManager,
        sessionRepository = sessionRepository,
        scope = applicationScope,
        notifyEnabled = { latestSettings.completionNotify },
        appForeground = { appForeground.value },
        foregroundSessionId = { foregroundSessionId.value },
    )

    fun setAppForeground(value: Boolean) {
        appForeground.value = value
    }

    fun setForegroundSession(sessionId: String?) {
        foregroundSessionId.value = sessionId
    }

    /** 通知点击/深链：请求 UI 打开指定会话。 */
    fun requestOpenConversation(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) pendingOpenSessionId.value = sessionId
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

        // 刷新模型 → 对账被杀的在途流任务 → 已登录则做一次启动云同步。
        applicationScope.launch {
            latestSettings = runCatching { settingsStore.settings.first() }.getOrDefault(AppSettings())
            runCatching { modelApi.refresh() }
            reconcileStreamTasks()
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
            val status = runCatching { chatService.checkStreamStatus(record.streamSessionId) }.getOrNull()
            when (status?.status) {
                "completed", "streaming" -> backgroundStreamManager.resume(record)
                else -> runCatching { chatRepository.removeStreamTask(record.sessionId) }
            }
        }
    }
}
