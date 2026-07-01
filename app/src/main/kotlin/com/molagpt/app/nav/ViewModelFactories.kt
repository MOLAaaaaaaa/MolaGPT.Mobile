package com.molagpt.app.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.EnabledTools
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.network.toAccountStatus
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.allModels
import com.molagpt.app.di.AppContainer
import com.molagpt.app.feature.auth.AuthViewModel
import com.molagpt.app.feature.agentcontrol.AgentControlViewModel
import com.molagpt.app.feature.chat.ChatViewModel
import com.molagpt.app.feature.session.SessionViewModel
import com.molagpt.app.feature.settings.PersonalizationViewModel
import com.molagpt.app.feature.settings.SettingsViewModel

/** 简单 ViewModel 工厂集合（手动 DI；按需把容器依赖喂给各 VM）。 */
object ViewModelFactories {

    fun auth(container: AppContainer) = factory {
        AuthViewModel(container.authService)
    }

    fun session(container: AppContainer) = factory {
        SessionViewModel(container.sessionRepository)
    }

    fun agentControl(container: AppContainer) = factory {
        AgentControlViewModel(
            service = container.agentControlService,
        )
    }

    fun settings(container: AppContainer) = factory {
        SettingsViewModel(
            store = container.settingsStore,
            syncEngine = container.syncEngine,
            syncApi = container.syncApi,
            jwtProvider = { container.credentialStore.jwt },
            accountStatus = container.accountStatusCache,
            byokProviders = container.byokProviderRepository,
            byokModelApi = container.byokModelApi,
            byokImageApi = container.byokImageApi,
            mcpToolListApi = container.mcpToolListApi,
            credentialStore = container.credentialStore,
            dispatchers = container.dispatchers,
        )
    }

    fun personalization(container: AppContainer) = factory {
        PersonalizationViewModel(
            userDataApi = container.userDataApi,
            syncApi = container.syncApi,
            store = container.settingsStore,
            jwtProvider = { container.credentialStore.jwt },
            accountStatusLoader = { loadAccountStatus(container) },
            dispatchers = container.dispatchers,
        )
    }

    fun chat(container: AppContainer, sessionId: String, settings: AppSettings) = factory {
        ChatViewModel(
            sessionId = sessionId,
            chatRepository = container.chatRepository,
            backgroundStreams = container.backgroundStreamManager,
            sessionRepository = container.sessionRepository,
            personaRepository = container.personaRepository,
            syncEngine = container.syncEngine,
            dispatchers = container.dispatchers,
            modelsFlow = container.modelRegistry.models,
            modelRefreshingFlow = container.modelRegistry.isRefreshing,
            modelRefresher = { providerKind -> refreshModels(container, providerKind) },
            settingsFlow = container.settingsFlow,
            defaultModelId = settings.defaultModel,
            // composer 运行时工具开关初值（network/网页拉取/代码执行）。
            // mcp/vision/imageGeneration 在对话内无开关，由 BYOK 工具设置页实时驱动（见 resolveRequestTools），不在此初始化。
            tools = EnabledTools(
                network = settings.toolNetwork,
                steelBrowser = settings.toolSteel,
                codeExecution = settings.toolCode,
            ),
            useThinking = settings.useThinking,
            reasoningEffort = settings.reasoningEffort,
            temperature = settings.temperature,
            throttleMs = settings.throttleMs,
            appContext = container.appContext,
        )
    }

    /** 短 token → status.php → AccountStatus（设置页 / 个性化页共用，含读回服务端个性化开关）。 */
    private suspend fun loadAccountStatus(container: AppContainer): AccountStatus? {
        val jwt = container.shortTokenManager.freshToken()
        return container.authApi.status(jwt)?.toAccountStatus { mid ->
            container.modelRegistry.find(mid)?.displayName
        }
    }

    private suspend fun refreshModels(container: AppContainer, providerKind: ProviderKind) =
        if (providerKind == ProviderKind.MOLAGPT) {
            container.modelApi.refresh()
        } else {
            container.modelRegistry.beginRefresh()
            try {
                val providers = container.byokProviderRepository.list().filter { it.enabled }
                providers.forEach { provider ->
                    runCatching {
                        val fetched = container.byokModelApi.fetchModels(provider)
                        val merged = (fetched.associateBy { it.id } + provider.models.associateBy { it.id })
                            .values
                            .sortedWith(compareByDescending<com.molagpt.app.core.model.ProviderModel> { it.supportsChat }.thenBy { it.id })
                        container.byokProviderRepository.upsert(provider.copy(models = merged))
                    }
                }
                container.byokProviderRepository.list()
                    .filter { it.enabled }
                    .flatMap { it.allModels() }
            } finally {
                container.modelRegistry.endRefresh()
            }
        }

    private inline fun <reified VM : ViewModel> factory(crossinline create: () -> VM) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
        }
}
