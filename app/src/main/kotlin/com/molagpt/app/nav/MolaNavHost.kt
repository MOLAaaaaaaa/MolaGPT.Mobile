package com.molagpt.app.nav

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.molagpt.app.LocalAppContainer
import com.molagpt.app.di.AppContainer
import com.molagpt.app.core.model.DesktopInfo
import com.molagpt.app.core.model.DesktopLinkState
import com.molagpt.app.core.network.DesktopRemoteService
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.SessionRepository
import com.molagpt.app.feature.agentcontrol.AgentControlScreen
import com.molagpt.app.feature.agentcontrol.AgentControlViewModel
import com.molagpt.app.feature.agentcontrol.desktop.DesktopsScreen
import com.molagpt.app.feature.agentcontrol.desktop.DesktopsViewModel
import com.molagpt.app.feature.agentcontrol.desktop.RemoteConversationScreen
import com.molagpt.app.feature.agentcontrol.desktop.RemoteConversationViewModel
import com.molagpt.app.feature.auth.AuthViewModel
import com.molagpt.app.feature.auth.LoginScreen
import com.molagpt.app.feature.chat.ChatScreen
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.feature.chat.ModelMenuContent
import com.molagpt.app.feature.chat.ChatViewModel
import com.molagpt.app.feature.chat.DesktopModelsState
import com.molagpt.app.feature.chat.DesktopRunTarget
import com.molagpt.app.feature.imagegen.Base64ToolScreen
import com.molagpt.app.feature.imagegen.ImageGalleryScreen
import com.molagpt.app.feature.imagegen.ImageGalleryViewModel
import com.molagpt.app.feature.imagegen.ImageWorkbenchScreen
import com.molagpt.app.feature.imagegen.ImageWorkbenchViewModel
import com.molagpt.app.feature.session.SessionDrawer
import com.molagpt.app.feature.session.DesktopSidebarConversation
import com.molagpt.app.feature.session.SessionViewModel
import com.molagpt.app.feature.settings.AboutScreen
import com.molagpt.app.feature.settings.ByokProviderDetailScreen
import com.molagpt.app.feature.settings.ByokProvidersScreen
import com.molagpt.app.feature.settings.ByokToolsScreen
import com.molagpt.app.feature.settings.LorebookScreen
import com.molagpt.app.feature.settings.McpServerDetailScreen
import com.molagpt.app.feature.settings.MolaAccountScreen
import com.molagpt.app.feature.settings.PersonaEditScreen
import com.molagpt.app.feature.settings.PersonaManagementScreen
import com.molagpt.app.feature.settings.PersonaViewScreen
import com.molagpt.app.feature.settings.ByokMemoryScreen
import com.molagpt.app.feature.settings.ByokMemoryViewModel
import com.molagpt.app.feature.settings.PersonalizationScreen
import com.molagpt.app.feature.settings.PersonalizationViewModel
import com.molagpt.app.feature.settings.PostProcessingScreen
import com.molagpt.app.feature.settings.SettingsScreen
import com.molagpt.app.feature.settings.SkillDetailScreen
import com.molagpt.app.feature.settings.SkillsScreen
import com.molagpt.app.feature.settings.SettingsViewModel
import com.molagpt.app.feature.settings.StartupNoticesHost
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private object Routes {
    const val LOGIN = "login"
    const val AGENT_LOGIN = "login/agent-control"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val MOLA_ACCOUNT = "mola_account"
    const val PERSONALIZATION = "personalization"
    const val ABOUT = "about"
    const val IMAGE_WORKBENCH = "image_workbench"
    const val IMAGE_GALLERY = "image_gallery"
    const val IMAGE_BASE64 = "image_base64"
    const val AGENT_CONTROL = "agent_control"
    const val DESKTOPS = "desktops"
    const val BYOK_PROVIDERS = "byok_providers"
    const val BYOK_PROVIDER_DETAIL = "byok_provider_detail"
    const val BYOK_TOOLS = "byok_tools"
    const val BYOK_MEMORY = "byok_memory"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skill_detail"
    const val POST_PROCESSING = "post_processing"
    const val LOREBOOKS = "lorebooks"
    const val MCP_SERVER_DETAIL = "mcp_server_detail"
    const val PERSONA_MANAGEMENT = "persona_management"
    const val PERSONA_VIEW = "persona_view"
    const val PERSONA_EDIT = "persona_edit"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MolaNavHost(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val openAgentControl = {
        val target = if (container.authService.isLoggedIn) Routes.AGENT_CONTROL else Routes.AGENT_LOGIN
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) { launchSingleTop = true }
        }
    }
    // 「电脑」页：手机控制电脑上的 MolaGPT 对话；外部 Agent（Claude Code / Codex）从它进入。
    var loginTarget by remember { mutableStateOf(Routes.AGENT_CONTROL) }
    val openDesktops = {
        val target = if (container.authService.isLoggedIn) Routes.DESKTOPS else Routes.AGENT_LOGIN
        if (target == Routes.AGENT_LOGIN) loginTarget = Routes.DESKTOPS
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) { launchSingleTop = true }
        }
    }
    // 电脑对话和本地对话一样显示在主页上，不是二级页：从「电脑」页、通知打开的，都回到主页再切过去。
    val desktopChatRequests = remember { MutableStateFlow<DesktopChatTarget?>(null) }
    val openDesktopConversation = { machineId: String, conversationId: String, machineName: String ->
        desktopChatRequests.value = DesktopChatTarget(machineId, conversationId, machineName)
        if (navController.currentDestination?.route != Routes.CHAT) {
            navController.navigate(Routes.CHAT) {
                popUpTo(Routes.CHAT)
                launchSingleTop = true
            }
        }
    }
    val pendingDesktopOpen by container.pendingOpenDesktopConversation.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDesktopOpen) {
        val key = pendingDesktopOpen ?: return@LaunchedEffect
        container.pendingOpenDesktopConversation.value = null
        if (container.authService.isLoggedIn) {
            openDesktopConversation(key.substringBefore('/'), key.substringAfter('/'), "")
        }
    }
    val pendingAgentOpen by container.pendingOpenAgentSessionId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAgentOpen) {
        if (!pendingAgentOpen.isNullOrBlank()) {
            openAgentControl()
        }
    }
    // 通知、主侧边栏、画廊要打开某个画图任务：确保工作台在最上面，任务由工作台自己接走。
    val openImageWorkbench = {
        val onStack = runCatching { navController.getBackStackEntry(Routes.IMAGE_WORKBENCH) }.isSuccess
        if (onStack) {
            navController.popBackStack(Routes.IMAGE_WORKBENCH, inclusive = false)
        } else {
            navController.navigate(Routes.IMAGE_WORKBENCH) { launchSingleTop = true }
        }
    }
    val pendingImageOpen by container.pendingOpenImageTaskId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImageOpen) {
        if (!pendingImageOpen.isNullOrBlank() && navController.currentDestination?.route != Routes.IMAGE_WORKBENCH) {
            openImageWorkbench()
        }
    }
    // 游客可直接进入聊天；登录入口由设置页提供。
    val start = Routes.CHAT

    Box(modifier = modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = start,
        modifier = Modifier.fillMaxSize(),
        // 全局转场：二级页面从右侧推入，返回时当前页滑出，下层页面带轻微视差（统一声明见 MolaMotion）。
        // 返回手势会驱动 popExitTransition 的进度；目标页面不应额外拦截 BackHandler。
        enterTransition = { MolaMotion.PushEnter },
        exitTransition = { MolaMotion.PushExit },
        popEnterTransition = { MolaMotion.PopEnter },
        popExitTransition = { MolaMotion.PopExit },
    ) {
        composable(Routes.LOGIN) {
            val vm: AuthViewModel = viewModel(factory = ViewModelFactories.auth(container))
            LoginScreen(
                viewModel = vm,
                onLoggedIn = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    }
                },
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.AGENT_LOGIN) {
            val vm: AuthViewModel = viewModel(factory = ViewModelFactories.auth(container))
            // A notification target must survive a successful login so the Agent
            // route can select it, but backing out as a guest explicitly abandons it.
            DisposableEffect(Unit) {
                onDispose {
                    if (!container.authService.isLoggedIn) {
                        container.pendingOpenAgentSessionId.value = null
                    }
                }
            }
            LoginScreen(
                viewModel = vm,
                onLoggedIn = {
                    navController.navigate(loginTarget) {
                        popUpTo(Routes.AGENT_LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                    loginTarget = Routes.AGENT_CONTROL
                },
                onBack = {
                    container.pendingOpenAgentSessionId.value = null
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.AGENT_LOGIN) { inclusive = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.CHAT) {
            ChatHost(
                settings = settings,
                onOpenSettings = {
                    if (navController.currentDestination?.route != Routes.SETTINGS) {
                        navController.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenByokModelSettings = { providerId, modelId ->
                    val encodedModel = android.net.Uri.encode(modelId)
                    val target = "${Routes.BYOK_PROVIDER_DETAIL}/$providerId?editModel=$encodedModel"
                    navController.navigate(target) { launchSingleTop = true }
                },
                onOpenAgentControl = openDesktops,
                desktopChatRequests = desktopChatRequests,
                onOpenImageWorkbench = {
                    if (navController.currentDestination?.route != Routes.IMAGE_WORKBENCH) {
                        navController.navigate(Routes.IMAGE_WORKBENCH) { launchSingleTop = true }
                    }
                },
                onOpenImageTask = { taskId ->
                    container.requestOpenImageTask(taskId)
                    openImageWorkbench()
                },
                onOpenPersonaManagement = {
                    if (navController.currentDestination?.route != Routes.PERSONA_MANAGEMENT) {
                        navController.navigate(Routes.PERSONA_MANAGEMENT) { launchSingleTop = true }
                    }
                },
                onOpenByokMemory = {
                    if (navController.currentDestination?.route != Routes.BYOK_MEMORY) {
                        navController.navigate(Routes.BYOK_MEMORY) { launchSingleTop = true }
                    }
                },
                onAuthExpired = {
                    // 只在服务端关了游客聊天时才会走到这里（点了需登录的模型，或登录已失效）。
                    // 游客聊天开放时短 token 失败只由聊天错误条提示，不会触发跳转。
                    if (navController.currentDestination?.route != Routes.LOGIN) {
                        navController.navigate(Routes.LOGIN) { launchSingleTop = true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            val account by container.authService.account.collectAsStateWithLifecycle()
            var desktopStatus by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(account.loggedIn, account.username) {
                if (!account.loggedIn || account.username.isNullOrBlank()) {
                    desktopStatus = "登录后可在手机上继续电脑上的对话"
                    return@LaunchedEffect
                }
                desktopStatus = null
                desktopStatus = try {
                    desktopStatusText(container.desktopRemoteService.listDesktops())
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            SettingsScreen(
                viewModel = vm,
                loggedIn = account.loggedIn,
                username = account.username,
                onOpenSkills = { navController.navigate(Routes.SKILLS) { launchSingleTop = true } },
                onBack = {
                    if (navController.currentDestination?.route == Routes.SETTINGS) {
                        val returnedToPrevious = navController.popBackStack()
                        if (!returnedToPrevious) {
                            navController.navigate(Routes.CHAT) {
                                launchSingleTop = true
                            }
                        }
                    }
                },
                onOpenMolaAccount = {
                    if (navController.currentDestination?.route != Routes.MOLA_ACCOUNT) {
                        navController.navigate(Routes.MOLA_ACCOUNT) { launchSingleTop = true }
                    }
                },
                onOpenAbout = {
                    if (navController.currentDestination?.route != Routes.ABOUT) {
                        navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                    }
                },
                onOpenImageWorkbench = {
                    if (navController.currentDestination?.route != Routes.IMAGE_WORKBENCH) {
                        navController.navigate(Routes.IMAGE_WORKBENCH) { launchSingleTop = true }
                    }
                },
                onOpenAgentControl = openDesktops,
                desktopStatus = desktopStatus,
                onOpenByokProviders = {
                    if (navController.currentDestination?.route != Routes.BYOK_PROVIDERS) {
                        navController.navigate(Routes.BYOK_PROVIDERS) { launchSingleTop = true }
                    }
                },
                onOpenByokTools = {
                    if (navController.currentDestination?.route != Routes.BYOK_TOOLS) {
                        navController.navigate(Routes.BYOK_TOOLS) { launchSingleTop = true }
                    }
                },
                onOpenPersonaManagement = {
                    if (navController.currentDestination?.route != Routes.PERSONA_MANAGEMENT) {
                        navController.navigate(Routes.PERSONA_MANAGEMENT) { launchSingleTop = true }
                    }
                },
                onOpenByokMemory = {
                    if (navController.currentDestination?.route != Routes.BYOK_MEMORY) {
                        navController.navigate(Routes.BYOK_MEMORY) { launchSingleTop = true }
                    }
                },
                onOpenPostProcessing = {
                    if (navController.currentDestination?.route != Routes.POST_PROCESSING) {
                        navController.navigate(Routes.POST_PROCESSING) { launchSingleTop = true }
                    }
                },
                onOpenLorebooks = {
                    if (navController.currentDestination?.route != Routes.LOREBOOKS) {
                        navController.navigate(Routes.LOREBOOKS) { launchSingleTop = true }
                    }
                },
                buildLabel = "MolaGPT v${com.molagpt.app.BuildConfig.VERSION_NAME} · 构建 ${com.molagpt.app.BuildConfig.BUILD_TIME}",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // MolaGPT 账户页：账户域（账户信息/配额/云同步/个性化记忆/账户工具）整体收在此二级页，
        // 设置根页只留一行入口。复用 SettingsViewModel（与图像工作台/BYOK 各页同一惯例）。
        composable(Routes.MOLA_ACCOUNT) {
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            val account by container.authService.account.collectAsStateWithLifecycle()
            MolaAccountScreen(
                viewModel = vm,
                loggedIn = account.loggedIn,
                username = account.username,
                onOpenLogin = {
                    if (navController.currentDestination?.route != Routes.LOGIN) {
                        navController.navigate(Routes.LOGIN) { launchSingleTop = true }
                    }
                },
                onLogout = { container.authService.logout() },
                onBack = {
                    if (navController.currentDestination?.route == Routes.MOLA_ACCOUNT) {
                        navController.popBackStack()
                    }
                },
                onOpenPersonalization = {
                    if (navController.currentDestination?.route != Routes.PERSONALIZATION) {
                        navController.navigate(Routes.PERSONALIZATION) { launchSingleTop = true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.IMAGE_WORKBENCH) {
            val vm: ImageWorkbenchViewModel = viewModel(factory = ViewModelFactories.imageWorkbench(container))
            ImageWorkbenchScreen(
                viewModel = vm,
                onBack = {
                    if (navController.currentDestination?.route == Routes.IMAGE_WORKBENCH) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.CHAT) { launchSingleTop = true }
                        }
                    }
                },
                onManageModels = { providerId ->
                    val target = if (!providerId.isNullOrBlank()) "${Routes.BYOK_PROVIDER_DETAIL}/$providerId" else Routes.BYOK_PROVIDERS
                    navController.navigate(target) { launchSingleTop = true }
                },
                onOpenGallery = {
                    if (navController.currentDestination?.route != Routes.IMAGE_GALLERY) {
                        navController.navigate(Routes.IMAGE_GALLERY) { launchSingleTop = true }
                    }
                },
                onOpenBase64 = {
                    if (navController.currentDestination?.route != Routes.IMAGE_BASE64) {
                        navController.navigate(Routes.IMAGE_BASE64) { launchSingleTop = true }
                    }
                },
                onVisibleTaskChange = { container.foregroundImageTaskId.value = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.IMAGE_GALLERY) {
            val vm: ImageGalleryViewModel = viewModel(factory = ViewModelFactories.imageGallery(container))
            ImageGalleryScreen(
                viewModel = vm,
                onBack = {
                    if (navController.currentDestination?.route == Routes.IMAGE_GALLERY) navController.popBackStack()
                },
                onOpenTask = { taskId ->
                    container.requestOpenImageTask(taskId)
                    openImageWorkbench()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.IMAGE_BASE64) {
            Base64ToolScreen(
                onBack = {
                    if (navController.currentDestination?.route == Routes.IMAGE_BASE64) navController.popBackStack()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.DESKTOPS) {
            val account by container.authService.account.collectAsStateWithLifecycle()
            if (!account.loggedIn) {
                LaunchedEffect(Unit) {
                    loginTarget = Routes.DESKTOPS
                    navController.navigate(Routes.AGENT_LOGIN) {
                        popUpTo(Routes.DESKTOPS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
            } else {
                val vm: DesktopsViewModel = viewModel(factory = ViewModelFactories.desktops(container))
                DesktopsScreen(
                    vm = vm,
                    onOpenConversation = { machineId, conversationId ->
                        val name = vm.state.value.desktops.firstOrNull { it.info.machineId == machineId }?.info?.machineName.orEmpty()
                        openDesktopConversation(machineId, conversationId, name)
                    },
                    onOpenExternalAgents = openAgentControl,
                    onExit = {
                        if (navController.currentDestination?.route == Routes.DESKTOPS && !navController.popBackStack()) {
                            navController.navigate(Routes.CHAT) { launchSingleTop = true }
                        }
                    },
                )
            }
        }

        composable(Routes.AGENT_CONTROL) {
            val account by container.authService.account.collectAsStateWithLifecycle()
            if (!account.loggedIn) {
                // Route-level guard covers future/direct entry paths in addition to
                // the current chat, settings and notification entry points.
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.AGENT_LOGIN) {
                        popUpTo(Routes.AGENT_CONTROL) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {}
            } else {
                val vm: AgentControlViewModel = viewModel(factory = ViewModelFactories.agentControl(container))
                val sessions by vm.sessions.collectAsStateWithLifecycle()
                val targetSessionId by container.pendingOpenAgentSessionId.collectAsStateWithLifecycle()
                LaunchedEffect(targetSessionId, sessions) {
                    val target = targetSessionId?.let { id -> sessions.firstOrNull { it.conversationId == id } }
                    if (target != null) {
                        vm.select(target)
                        container.pendingOpenAgentSessionId.value = null
                    }
                }
                AgentControlScreen(
                    vm = vm,
                    enterToSend = settings.enterToSend,
                    onVisibleSessionChanged = container::setForegroundAgentSession,
                    onExit = {
                        if (navController.currentDestination?.route == Routes.AGENT_CONTROL) {
                            if (!navController.popBackStack()) {
                                navController.navigate(Routes.CHAT) { launchSingleTop = true }
                            }
                        }
                    },
                )
            }
        }

        composable(Routes.BYOK_PROVIDERS) {
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            ByokProvidersScreen(
                viewModel = vm,
                onOpenDetail = { id ->
                    navController.navigate("${Routes.BYOK_PROVIDER_DETAIL}/$id") { launchSingleTop = true }
                },
                onBack = {
                    if (navController.currentDestination?.route == Routes.BYOK_PROVIDERS) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(
            route = "${Routes.BYOK_PROVIDER_DETAIL}/{id}?editModel={editModel}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("editModel") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            val editModel = backStackEntry.arguments?.getString("editModel").orEmpty()
            ByokProviderDetailScreen(
                providerId = backStackEntry.arguments?.getString("id").orEmpty(),
                viewModel = vm,
                initialEditModelId = editModel.ifBlank { null },
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.BYOK_PROVIDERS) { launchSingleTop = true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.BYOK_TOOLS) {
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            ByokToolsScreen(
                viewModel = vm,
                onOpenImageWorkbench = {
                    if (navController.currentDestination?.route != Routes.IMAGE_WORKBENCH) {
                        navController.navigate(Routes.IMAGE_WORKBENCH) { launchSingleTop = true }
                    }
                },
                onOpenMcpDetail = { id ->
                    navController.navigate("${Routes.MCP_SERVER_DETAIL}/$id") { launchSingleTop = true }
                },
                onOpenByokProviders = {
                    if (navController.currentDestination?.route != Routes.BYOK_PROVIDERS) {
                        navController.navigate(Routes.BYOK_PROVIDERS) { launchSingleTop = true }
                    }
                },
                onBack = {
                    if (navController.currentDestination?.route == Routes.BYOK_TOOLS) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(
            route = "${Routes.MCP_SERVER_DETAIL}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            McpServerDetailScreen(
                serverId = backStackEntry.arguments?.getString("id").orEmpty(),
                viewModel = vm,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.BYOK_TOOLS) { launchSingleTop = true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // BYOK 本地记忆页。与 PERSONALIZATION 是两条独立链路：那边是账户 Tracks（服务端），
        // 这边只读写本机 Room，不依赖登录。
        composable(Routes.BYOK_MEMORY) {
            val vm: ByokMemoryViewModel = viewModel(factory = ViewModelFactories.byokMemory(container))
            ByokMemoryScreen(
                viewModel = vm,
                onBack = {
                    if (navController.currentDestination?.route == Routes.BYOK_MEMORY) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                onOpenByokProviders = {
                    if (navController.currentDestination?.route != Routes.BYOK_PROVIDERS) {
                        navController.navigate(Routes.BYOK_PROVIDERS) { launchSingleTop = true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.SKILLS) {
            SkillsScreen(
                repository = container.skillRepository,
                settingsStore = container.settingsStore,
                onBack = { navController.popBackStack() },
                onOpenSkill = { id -> navController.navigate("${Routes.SKILL_DETAIL}/$id") { launchSingleTop = true } },
            )
        }

        composable(
            route = "${Routes.SKILL_DETAIL}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            SkillDetailScreen(
                skillId = entry.arguments?.getString("id").orEmpty(),
                repository = container.skillRepository,
                settingsStore = container.settingsStore,
                onBack = { navController.popBackStack() },
            )
        }

        // 回答后处理页：规则在流结束后施加、处理结果直接落库，与 BYOK 无关，对所有模型生效。
        composable(Routes.POST_PROCESSING) {
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            PostProcessingScreen(
                viewModel = vm,
                onBack = {
                    if (navController.currentDestination?.route == Routes.POST_PROCESSING) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.LOREBOOKS) {
            LorebookScreen(
                repository = container.lorebookRepository,
                onBack = {
                    if (navController.currentDestination?.route == Routes.LOREBOOKS) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.PERSONALIZATION) {
            val vm: PersonalizationViewModel = viewModel(factory = ViewModelFactories.personalization(container))
            PersonalizationScreen(
                viewModel = vm,
                onBack = {
                    if (navController.currentDestination?.route == Routes.PERSONALIZATION) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                versionName = com.molagpt.app.BuildConfig.VERSION_NAME,
                buildTime = com.molagpt.app.BuildConfig.BUILD_TIME,
                onBack = {
                    if (navController.currentDestination?.route == Routes.ABOUT) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.PERSONA_MANAGEMENT) {
            PersonaManagementScreen(
                repository = container.personaRepository,
                avatars = container.personaAvatars,
                onOpenView = { id ->
                    navController.navigate("${Routes.PERSONA_VIEW}/$id") { launchSingleTop = true }
                },
                onOpenEdit = { id ->
                    navController.navigate("${Routes.PERSONA_EDIT}?personaId=$id") { launchSingleTop = true }
                },
                onNewPersona = {
                    navController.navigate(Routes.PERSONA_EDIT) { launchSingleTop = true }
                },
                onBack = {
                    if (navController.currentDestination?.route == Routes.PERSONA_MANAGEMENT) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(
            route = "${Routes.PERSONA_VIEW}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            PersonaViewScreen(
                repository = container.personaRepository,
                personaId = backStackEntry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() },
                onDuplicate = {
                    val id = backStackEntry.arguments?.getString("id").orEmpty()
                    // 复制后进入编辑页，并把查看页弹出栈：返回直接回列表，符合「复制即编辑」语义。
                    navController.navigate("${Routes.PERSONA_EDIT}?copyFromId=$id") {
                        popUpTo("${Routes.PERSONA_VIEW}/{id}") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(
            route = "${Routes.PERSONA_EDIT}?personaId={personaId}&copyFromId={copyFromId}",
            arguments = listOf(
                navArgument("personaId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("copyFromId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            PersonaEditScreen(
                repository = container.personaRepository,
                lorebooks = container.lorebookRepository,
                personaId = backStackEntry.arguments?.getString("personaId"),
                copyFromId = backStackEntry.arguments?.getString("copyFromId"),
                onClose = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.PERSONA_MANAGEMENT) { launchSingleTop = true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // 进入前台时检查更新、运营消息与本地 Promo（叠在 NavHost 之上）。
    StartupNoticesHost(
        versionName = com.molagpt.app.BuildConfig.VERSION_NAME,
        settingsStore = container.settingsStore,
        onOpenPersonaManagement = {
            if (navController.currentDestination?.route != Routes.PERSONA_MANAGEMENT) {
                navController.navigate(Routes.PERSONA_MANAGEMENT) { launchSingleTop = true }
            }
        },
    )
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun ChatHost(
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onOpenByokModelSettings: (providerId: String, modelId: String) -> Unit,
    onOpenAgentControl: () -> Unit,
    /** 从主页以外（「电脑」页、通知）要打开的电脑对话，消费后清空。 */
    desktopChatRequests: MutableStateFlow<DesktopChatTarget?>,
    onOpenImageWorkbench: () -> Unit,
    onOpenImageTask: (String) -> Unit,
    onOpenPersonaManagement: () -> Unit,
    onOpenByokMemory: () -> Unit,
    onAuthExpired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val sessionVm: SessionViewModel = viewModel(factory = ViewModelFactories.session(container))
    val account by container.authService.account.collectAsStateWithLifecycle()
    val desktopLink by container.desktopRemoteService.link.collectAsStateWithLifecycle()
    val onlineDesktopIds by container.desktopRemoteService.onlineMachines.collectAsStateWithLifecycle()
    // 电脑列表由服务保管：上次的结果（含本地缓存）随时可用，侧边栏打开时先显示它，刷新回来原地替换。
    val cachedDesktops by container.desktopRemoteService.desktops.collectAsStateWithLifecycle()
    val desktopInfos = if (account.loggedIn) cachedDesktops.orEmpty() else emptyList()
    // 配对密钥在加密存储里，逐行读太重；随列表刷新在 IO 上算一次。
    var pairedDesktopIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var desktopLoadError by remember { mutableStateOf<String?>(null) }
    var pairingDesktopId by remember { mutableStateOf<String?>(null) }

    // 当前会话需要跨二级页面往返和进程重建恢复。
    var currentSessionId by rememberSaveable { mutableStateOf<String?>(Ids.newSessionId()) }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerProgress = remember { Animatable(0f) }
    // 主页正在显示的电脑对话；null 即显示本地会话 currentSessionId。
    var desktopTarget by rememberSaveable(stateSaver = DesktopChatTargetSaver) { mutableStateOf<DesktopChatTarget?>(null) }
    // 电脑对话的 ViewModel 放在它自己的存储里：切走就清掉，断开实时连接；跳到设置等二级页再回来时保留。
    val remoteChatHost: RemoteChatHost = viewModel { RemoteChatHost() }
    LaunchedEffect(desktopTarget == null) {
        if (desktopTarget == null) remoteChatHost.clear()
    }
    val desktopRequest by desktopChatRequests.collectAsStateWithLifecycle()
    LaunchedEffect(desktopRequest) {
        val target = desktopRequest ?: return@LaunchedEffect
        desktopChatRequests.value = null
        desktopTarget = target
        drawerOpen = false
    }

    val scope = rememberCoroutineScope()
    // 抽屉里的删除反馈。ChatHost 没有 Scaffold，所以在根 Box 内自建宿主；
    // 抽屉盖在聊天页之上，Snackbar 必须画在最外层才可见。
    val snackbar = remember { SnackbarHostState() }

    // 登录态变化时换成当前账号的缓存列表；未登录则清空。
    LaunchedEffect(account.loggedIn, account.username) {
        desktopLoadError = null
        container.desktopRemoteService.restoreDesktops()
    }

    LaunchedEffect(desktopInfos) {
        val service = container.desktopRemoteService
        pairedDesktopIds = withContext(container.dispatchers.io) {
            desktopInfos.mapNotNullTo(HashSet()) { info -> info.machineId.takeIf(service::isPaired) }
        }
    }

    // 侧边栏打开期间保持电脑实时连接并刷新列表；关闭后断开，但列表保留，下次打开不必从空白等起。
    LaunchedEffect(drawerOpen, account.loggedIn, account.username) {
        if (!drawerOpen || !account.loggedIn || account.username.isNullOrBlank()) return@LaunchedEffect
        val service = container.desktopRemoteService
        service.acquire()
        try {
            suspend fun refresh() {
                try {
                    service.listDesktops()
                    desktopLoadError = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    desktopLoadError = error.message ?: "电脑列表加载失败"
                }
            }
            refresh()
            service.listChanged.debounce(400).collect { refresh() }
        } finally {
            service.release()
        }
    }

    val desktopSidebarSessions = remember(desktopInfos, pairedDesktopIds, onlineDesktopIds, desktopLink, pairingDesktopId) {
        desktopInfos.flatMap { info ->
            val online = if (desktopLink == DesktopLinkState.Connected) {
                info.machineId in onlineDesktopIds
            } else {
                info.online
            }
            info.conversations.map { conversation ->
                DesktopSidebarConversation(
                    machineId = info.machineId,
                    machineName = info.machineName,
                    conversation = conversation,
                    online = online,
                    paired = info.machineId in pairedDesktopIds,
                    pairing = info.machineId == pairingDesktopId,
                )
            }
        }
    }

    LaunchedEffect(desktopSidebarSessions) {
        sessionVm.sessionItems.setDesktopSessions(desktopSidebarSessions)
    }

    // 首页模型菜单「在电脑上运行」：登录后先取一次电脑列表，菜单首次打开就有；
    // 菜单打开期间保持实时连接，在线状态与列表随之刷新。
    var modelMenuOpen by remember { mutableStateOf(false) }
    var menuDesktopInfos by remember { mutableStateOf<List<DesktopInfo>>(emptyList()) }
    var menuPairedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 至少成功取到过一次列表：首页引导要等它，免得已配对的人先看到一闪。
    var menuDesktopsLoaded by remember { mutableStateOf(false) }
    var desktopModels by remember { mutableStateOf<DesktopModelsState?>(null) }
    var desktopModelsJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(modelMenuOpen, account.loggedIn, account.username) {
        if (!account.loggedIn || account.username.isNullOrBlank()) {
            menuDesktopInfos = emptyList()
            menuPairedIds = emptySet()
            menuDesktopsLoaded = false
            return@LaunchedEffect
        }
        val service = container.desktopRemoteService
        suspend fun refresh() {
            try {
                val infos = service.listDesktops()
                menuPairedIds = withContext(container.dispatchers.io) {
                    infos.mapNotNullTo(HashSet()) { info -> info.machineId.takeIf(service::isPaired) }
                }
                menuDesktopInfos = infos
                menuDesktopsLoaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 取不到就不显示这一节，不打扰选本机模型。
            }
        }
        if (!modelMenuOpen) {
            if (menuDesktopInfos.isEmpty()) refresh()
            return@LaunchedEffect
        }
        service.acquire()
        try {
            refresh()
            service.listChanged.debounce(400).collect { refresh() }
        } finally {
            service.release()
        }
    }
    val desktopTargets = remember(menuDesktopInfos, menuPairedIds, onlineDesktopIds, desktopLink) {
        menuDesktopInfos.map { info ->
            DesktopRunTarget(
                machineId = info.machineId,
                name = info.machineName,
                online = if (desktopLink == DesktopLinkState.Connected) info.machineId in onlineDesktopIds else info.online,
                paired = info.machineId in menuPairedIds,
            )
        }
    }

    /** 用指定模型新建本机对话。 */
    fun newChatWithModel(modelId: String, providerId: String?, kind: ProviderKind, personaId: String?) {
        scope.launch {
            // 跨阵营会新建会话，也必须与空白会话直接切换一样更新下次启动默认值。
            runCatching {
                container.settingsStore.setDefaultModelSelection(modelId, kind, providerId)
            }
            val conversation = container.sessionRepository.create(
                title = SessionRepository.DEFAULT_TITLE,
                model = modelId,
                providerId = providerId,
                providerKind = kind,
                personaId = personaId,
            )
            currentSessionId = conversation.sessionId
            desktopTarget = null
        }
    }

    /** 点开一台电脑：没配对先用账号配对，再取它的模型。 */
    fun openDesktopModels(machineId: String) {
        desktopModelsJob?.cancel()
        val service = container.desktopRemoteService
        // 取过就先显示上次的模型，后台刷新；只有第一次才显示读取中。
        val cached = service.cachedModelGroups(machineId)
        desktopModels = DesktopModelsState(machineId, loading = cached == null, groups = cached.orEmpty())
        desktopModelsJob = scope.launch {
            desktopModels = try {
                if (machineId !in menuPairedIds) {
                    service.pairWithAccount(
                        machineId = machineId,
                        deviceName = container.remoteDeviceName,
                        deviceId = container.remoteDeviceId,
                    )
                    menuPairedIds = menuPairedIds + machineId
                }
                DesktopModelsState(machineId, groups = service.modelGroups(machineId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 刷新失败但有上次的结果：继续显示它，不打断选择。
                if (cached != null) {
                    DesktopModelsState(machineId, groups = cached)
                } else {
                    DesktopModelsState(machineId, error = error.message ?: "无法读取电脑上的模型")
                }
            }
        }
    }

    fun openDesktopFromDrawer(row: DesktopSidebarConversation) {
        if (pairingDesktopId != null) return
        if (!row.paired && !row.online) {
            scope.launch { snackbar.showSnackbar("电脑离线，无法配对") }
            return
        }
        if (!row.paired) pairingDesktopId = row.machineId
        scope.launch {
            try {
                if (!row.paired) {
                    try {
                        container.desktopRemoteService.pairWithAccount(
                            machineId = row.machineId,
                            deviceName = container.remoteDeviceName,
                            deviceId = container.remoteDeviceId,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        snackbar.showSnackbar(error.message ?: "配对失败")
                        return@launch
                    }
                    pairedDesktopIds = pairedDesktopIds + row.machineId
                }
                if (!drawerOpen) return@launch
                drawerOpen = false
                desktopTarget = DesktopChatTarget(row.machineId, row.conversation.id, row.machineName)
            } finally {
                if (!row.paired) pairingDesktopId = null
            }
        }
    }

    LaunchedEffect(drawerOpen) {
        val target = if (drawerOpen) 1f else 0f
        // 抽屉点按开合与返回手势共用同一动画曲线，保持触发方式之间的手感一致。
        drawerProgress.animateTo(
            targetValue = target,
            animationSpec = MolaMotion.standardDecelerate(durationMillis = 300),
        )
    }

    // 通知点击/深链：打开指定会话（消费后清空请求）。
    val pendingOpen by container.pendingOpenSessionId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpen) {
        val target = pendingOpen
        if (!target.isNullOrBlank()) {
            currentSessionId = target
            desktopTarget = null
            drawerOpen = false
            container.pendingOpenSessionId.value = null
        }
    }

    // 维护「当前可见会话」给完成通知做抑制；离开聊天页时清空。
    DisposableEffect(currentSessionId, desktopTarget == null) {
        container.setForegroundSession(currentSessionId.takeIf { desktopTarget == null })
        onDispose { container.setForegroundSession(null) }
    }

    // 抽屉返回手势：系统进度映射为 drawerProgress，关闭和回弹都由同一状态驱动。
    // PredictiveBackHandler 需要稳定存在，是否启用由 enabled 控制。
    PredictiveBackHandler(enabled = drawerOpen) { progress ->
        try {
            progress.collect { backEvent ->
                val eased = MolaMotion.StandardDecelerate.transform(backEvent.progress.coerceIn(0f, 1f))
                drawerProgress.snapTo(1f - eased)
            }
            drawerOpen = false
        } catch (e: CancellationException) {
            // 回弹不能跟随手势协程一起取消。
            scope.launch {
                drawerProgress.animateTo(
                    targetValue = if (drawerOpen) 1f else 0f,
                    animationSpec = MolaMotion.standardDecelerate(durationMillis = 260),
                )
            }
            throw e
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 内容：电脑对话或本地聊天页，同在主页上，共用侧边栏。
        val selectedSessionId = currentSessionId
        val shownDesktop = desktopTarget
        if (shownDesktop != null) {
            key(shownDesktop.key) {
                val owner = remember(shownDesktop.key) { remoteChatHost.ownerFor(shownDesktop.key) }
                val remoteVm: RemoteConversationViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    key = shownDesktop.key,
                    factory = ViewModelFactories.remoteConversation(
                        container,
                        shownDesktop.machineId,
                        shownDesktop.conversationId,
                        providerId = shownDesktop.providerId,
                        modelId = shownDesktop.modelId,
                        // 草稿在电脑上建成后记下对话 id：侧边栏能高亮它，进程重建后也按正式对话打开。
                        onSent = { id ->
                            val current = desktopTarget
                            if (current?.key == shownDesktop.key && current.conversationId == null) {
                                desktopTarget = current.copy(conversationId = id)
                            }
                        },
                    ),
                )
                RemoteConversationScreen(
                    vm = remoteVm,
                    machineName = shownDesktop.machineName.ifBlank { "电脑" },
                    enterToSend = settings.enterToSend,
                    onVisible = { visible ->
                        container.foregroundDesktopConversation.value =
                            if (visible) "${shownDesktop.machineId}/${remoteVm.state.value.conversationId}" else null
                    },
                    onOpenDrawer = { drawerOpen = true },
                    onNewConversation = { providerId, modelId ->
                        desktopTarget = shownDesktop.copy(
                            conversationId = null,
                            providerId = providerId,
                            modelId = modelId,
                            draftKey = Ids.newSessionId(),
                        )
                    },
                    onOpenSettings = onOpenSettings,
                    drawerOpen = drawerOpen,
                    // 还没发出首条消息的新对话：模型菜单与首页同一份，可以退回手机上的模型或换一台电脑。
                    draftModelMenu = if (shownDesktop.conversationId == null) {
                        { close ->
                            DesktopDraftModelMenu(
                                container = container,
                                shownDesktop = shownDesktop,
                                remoteVm = remoteVm,
                                localSessionId = currentSessionId,
                                settings = settings,
                                desktopTargets = desktopTargets,
                                desktopModels = desktopModels,
                                onVisible = { modelMenuOpen = it },
                                onOpenDesktopModels = ::openDesktopModels,
                                onPickOtherDesktop = { target, providerId, modelId ->
                                    close()
                                    desktopTarget = DesktopChatTarget(
                                        machineId = target.machineId,
                                        conversationId = null,
                                        machineName = target.name,
                                        providerId = providerId,
                                        modelId = modelId,
                                        draftKey = Ids.newSessionId(),
                                    )
                                },
                                onBackToPhone = { sessionId ->
                                    close()
                                    currentSessionId = sessionId
                                    desktopTarget = null
                                },
                                onNewChatWithModel = { model ->
                                    close()
                                    newChatWithModel(model.id, model.providerId, model.providerKind, null)
                                },
                                onAuthExpired = {
                                    close()
                                    onAuthExpired()
                                },
                                onClose = close,
                            )
                        }
                    } else {
                        null
                    },
                    // 侧边栏推入时，与聊天页一样保留轻微右移视差。
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = size.width * 0.25f * drawerProgress.value },
                )
            }
        } else if (selectedSessionId == null) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            key(selectedSessionId) {
                val chatVm: com.molagpt.app.feature.chat.ChatViewModel =
                    viewModel(
                        key = "chat-$selectedSessionId",
                        factory = ViewModelFactories.chat(
                            container = container,
                            sessionId = selectedSessionId,
                            settings = settings,
                        ),
                    )
                ChatScreen(
                    viewModel = chatVm,
                    enterToSend = settings.enterToSend,
                    htmlAsCard = settings.htmlAsCard,
                    showAgentControlShortcut = settings.showAgentControlShortcut && account.loggedIn,
                    showImageWorkbenchShortcut = settings.showImageWorkbenchShortcut,
                    onOpenDrawer = { drawerOpen = true },
                    onOpenSettings = onOpenSettings,
                    onOpenByokModelSettings = onOpenByokModelSettings,
                    onOpenPersonaManagement = onOpenPersonaManagement,
                    onOpenByokMemory = onOpenByokMemory,
                    onAuthExpired = onAuthExpired,
                    onOpenAgentControl = onOpenAgentControl,
                    onOpenImageWorkbench = onOpenImageWorkbench,
                    onNewChatWithModel = ::newChatWithModel,
                    onNewChat = {
                        currentSessionId = Ids.newSessionId()
                        drawerOpen = false
                    },
                    drawerOpen = drawerOpen,
                    desktopTargets = desktopTargets,
                    desktopModels = desktopModels,
                    onModelMenuVisible = { modelMenuOpen = it },
                    onOpenDesktopModels = ::openDesktopModels,
                    onPickDesktopModel = { target, providerId, modelId ->
                        desktopTarget = DesktopChatTarget(
                            machineId = target.machineId,
                            conversationId = null,
                            machineName = target.name,
                            providerId = providerId,
                            modelId = modelId,
                            draftKey = Ids.newSessionId(),
                        )
                    },
                    showDesktopHint = account.loggedIn && menuDesktopsLoaded &&
                        menuPairedIds.isEmpty() && !settings.desktopHintDismissed,
                    onDismissDesktopHint = {
                        scope.launch { container.settingsStore.setDesktopHintDismissed(true) }
                    },
                    // 侧边栏推入时，聊天页保留轻微右移视差。
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = size.width * 0.25f * drawerProgress.value },
                )
            }
        }

        // 侧边栏常驻 composition 树，通过位移和透明度控制可见性，避免打开时重建列表。
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = drawerProgress.value
                    translationX = (-size.width * (1f - progress)).coerceAtMost(0f)
                    alpha = if (progress < 0.01f) 0f else 1f
                },
        ) {
            val personaLabels by sessionVm.personaLabels.collectAsStateWithLifecycle()
            SessionDrawer(
                sessions = sessionVm.sessionItems,
                personaLabels = personaLabels,
                currentSessionId = currentSessionId.takeIf { desktopTarget == null },
                currentDesktopKey = desktopTarget?.let { target -> target.conversationId?.let { "${target.machineId}/$it" } },
                drawerOpen = drawerOpen,
                onNewChat = {
                    currentSessionId = Ids.newSessionId()
                    desktopTarget = null
                    drawerOpen = false
                },
                onSelect = { id ->
                    currentSessionId = id
                    desktopTarget = null
                    drawerOpen = false
                },
                // 抽屉保持打开：从工作台返回时还在原处。
                onOpenImageTask = onOpenImageTask,
                onOpenDesktopConversation = ::openDesktopFromDrawer,
                desktopLoadError = desktopLoadError,
                desktopLoginRequired = !account.loggedIn,
                desktopPairingInProgress = pairingDesktopId != null,
                onDeleteMany = { ids, nextSessionId ->
                    scope.launch {
                        val deleted = sessionVm.deleteAll(ids)
                        if (currentSessionId in ids) {
                            currentSessionId = nextSessionId ?: Ids.newSessionId()
                        }
                        deleted.forEach { container.syncEngine.schedulePush(it) }
                        container.imageTaskManager.onConversationsDeleted()
                        snackbar.showSnackbar("已删除 ${deleted.size} 个对话")
                    }
                },
                onRequestAllIds = { sessionVm.allVisibleSessionIds(sessionVm.sessionItems.filter.value) },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}

/** 设置页「电脑」一行的状态。 */
private fun desktopStatusText(desktops: List<DesktopInfo>): String {
    if (desktops.isEmpty()) return "尚未连接电脑"
    val online = desktops.count { it.online }
    return if (online > 0) "$online 台电脑在线" else "电脑离线"
}

/**
 * 主页上显示的电脑对话。[conversationId] 为 null 是在电脑上起草的新对话，[draftKey] 区分每一次起草。
 * 草稿发出首条消息后会补上 [conversationId]；[key] 对草稿始终取 [draftKey]，补上后仍是同一个 ViewModel。
 */
internal data class DesktopChatTarget(
    val machineId: String,
    val conversationId: String?,
    val machineName: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val draftKey: String = "",
) {
    val key: String get() = if (draftKey.isNotEmpty()) "$machineId/draft:$draftKey" else "$machineId/$conversationId"
}

private val DesktopChatTargetSaver = Saver<DesktopChatTarget?, ArrayList<String>>(
    save = { target ->
        target?.let {
            arrayListOf(it.machineId, it.conversationId.orEmpty(), it.machineName, it.providerId.orEmpty(), it.modelId.orEmpty(), it.draftKey)
        }
    },
    restore = { saved ->
        DesktopChatTarget(
            machineId = saved[0],
            conversationId = saved[1].ifEmpty { null },
            machineName = saved[2],
            providerId = saved[3].ifEmpty { null },
            modelId = saved[4].ifEmpty { null },
            draftKey = saved[5],
        )
    },
)

/**
 * 主页上电脑对话的 ViewModel 容器。电脑对话的 ViewModel 持有实时连接，不能像本地会话那样一直留在主页的存储里；
 * 放进这里的独立存储，换到别的对话就整个清掉。容器本身随主页存活，跳到二级页再回来、旋转屏幕都保留。
 */
internal class RemoteChatHost : ViewModel() {
    private var key: String? = null
    private var store: ViewModelStore? = null

    fun ownerFor(key: String): ViewModelStoreOwner {
        if (this.key != key) {
            store?.clear()
            store = ViewModelStore()
            this.key = key
        }
        val current = store!!
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = current
        }
    }

    fun clear() {
        store?.clear()
        store = null
        key = null
    }

    override fun onCleared() = clear()
}

/**
 * 电脑上还没发出首条消息的新对话，它的模型菜单。内容与首页模型菜单相同，打开时停在这台电脑的模型页；
 * 返回上一级可改选别的电脑，或手机上的模型——当前本机会话还是空白的就直接切过去，否则用那个模型新建本机对话。
 */
@Composable
private fun DesktopDraftModelMenu(
    container: AppContainer,
    shownDesktop: DesktopChatTarget,
    remoteVm: RemoteConversationViewModel,
    localSessionId: String?,
    settings: AppSettings,
    desktopTargets: List<DesktopRunTarget>,
    desktopModels: DesktopModelsState?,
    onVisible: (Boolean) -> Unit,
    onOpenDesktopModels: (machineId: String) -> Unit,
    onPickOtherDesktop: (target: DesktopRunTarget, providerId: String, modelId: String) -> Unit,
    onBackToPhone: (sessionId: String) -> Unit,
    onNewChatWithModel: (ProviderModel) -> Unit,
    onAuthExpired: () -> Unit,
    onClose: () -> Unit,
) {
    val sessionId = remember { localSessionId ?: Ids.newSessionId() }
    val localVm: ChatViewModel = viewModel(
        key = "chat-$sessionId",
        factory = ViewModelFactories.chat(container = container, sessionId = sessionId, settings = settings),
    )
    val localState by localVm.uiState.collectAsStateWithLifecycle()
    val remoteSettings = remoteVm.state.collectAsStateWithLifecycle().value.settings
    var page by remember { mutableStateOf<String?>(shownDesktop.machineId) }
    // 菜单内容只在打开期间组合：进来即取这台电脑的模型，宿主同时保持连接、刷新电脑列表。
    DisposableEffect(Unit) {
        onVisible(true)
        onOpenDesktopModels(shownDesktop.machineId)
        onDispose { onVisible(false) }
    }
    // 电脑列表还没取到时补上这台，菜单仍能停在它的模型页。
    val targets = remember(desktopTargets, shownDesktop.machineId) {
        if (desktopTargets.any { it.machineId == shownDesktop.machineId }) {
            desktopTargets
        } else {
            listOf(DesktopRunTarget(shownDesktop.machineId, shownDesktop.machineName, online = true, paired = true)) + desktopTargets
        }
    }
    ModelMenuContent(
        state = localState,
        desktopPage = page,
        onDesktopPageChange = { page = it },
        desktopTargets = targets,
        desktopModels = desktopModels,
        onOpenDesktopModels = onOpenDesktopModels,
        onPickDesktopModel = { target, group, model ->
            if (target.machineId == shownDesktop.machineId) {
                onClose()
                remoteVm.selectModel(group, model)
            } else {
                onPickOtherDesktop(target, group.providerId, model.id)
            }
        },
        onPickModel = { model ->
            val blank = localState.messages.isEmpty() &&
                localState.pendingAttachments.isEmpty() &&
                !localState.isLoadingHistory
            when {
                model.loginRequired -> onAuthExpired()
                blank -> {
                    localVm.selectModel(model.id, model.providerId)
                    onBackToPhone(sessionId)
                }
                else -> onNewChatWithModel(model)
            }
        },
        onRefreshModels = {
            if (localState.isMolaModelConfigLoaded) localVm.refreshModels() else localVm.ensureMolaModelsLoaded()
        },
        showSelection = false,
        isDesktopModelSelected = { machineId, providerId, modelId ->
            machineId == shownDesktop.machineId &&
                remoteSettings?.providerId == providerId &&
                remoteSettings.modelId == modelId
        },
    )
}
