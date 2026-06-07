package com.molagpt.app.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.molagpt.app.LocalAppContainer
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.feature.auth.AuthViewModel
import com.molagpt.app.feature.auth.LoginScreen
import com.molagpt.app.feature.chat.ChatScreen
import com.molagpt.app.feature.session.SessionDrawer
import com.molagpt.app.feature.session.SessionViewModel
import com.molagpt.app.feature.settings.AboutScreen
import com.molagpt.app.feature.settings.PersonalizationScreen
import com.molagpt.app.feature.settings.PersonalizationViewModel
import com.molagpt.app.feature.settings.SettingsScreen
import com.molagpt.app.feature.settings.SettingsViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

private object Routes {
    const val LOGIN = "login"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PERSONALIZATION = "personalization"
    const val ABOUT = "about"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MolaNavHost(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    // 游客可直接进入聊天；登录入口由设置页提供。
    val start = Routes.CHAT

    NavHost(
        navController = navController,
        startDestination = start,
        modifier = modifier.fillMaxSize(),
        // 全局转场：二级页面从右侧推入，返回时当前页滑出，下层页面带轻微视差。
        // 返回手势会驱动 popExitTransition 的进度；目标页面不应额外拦截 BackHandler。
        enterTransition = { slideInHorizontally(MolaMotion.standardDecelerate()) { it } },
        exitTransition = { slideOutHorizontally(MolaMotion.standardDecelerate()) { -it / 4 } },
        popEnterTransition = { slideInHorizontally(MolaMotion.standardDecelerate()) { -it / 4 } },
        popExitTransition = { slideOutHorizontally(MolaMotion.standardDecelerate()) { it } },
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

        composable(Routes.CHAT) {
            LaunchedEffect(Unit) {
                if (container.modelRegistry.all().isEmpty()) {
                    runCatching { container.modelApi.refresh() }
                }
            }

            ChatHost(
                settings = settings,
                onOpenSettings = {
                    if (navController.currentDestination?.route != Routes.SETTINGS) {
                        navController.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    }
                },
                onAuthExpired = {
                    // 游客模式无「登录过期」概念；短 token 失败已由聊天错误条提示，不强制跳登录。
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            val account by container.authService.account.collectAsStateWithLifecycle()
            SettingsScreen(
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
                    if (navController.currentDestination?.route == Routes.SETTINGS) {
                        val returnedToPrevious = navController.popBackStack()
                        if (!returnedToPrevious) {
                            navController.navigate(Routes.CHAT) {
                                launchSingleTop = true
                            }
                        }
                    }
                },
                onOpenPersonalization = {
                    if (navController.currentDestination?.route != Routes.PERSONALIZATION) {
                        navController.navigate(Routes.PERSONALIZATION) { launchSingleTop = true }
                    }
                },
                onOpenAbout = {
                    if (navController.currentDestination?.route != Routes.ABOUT) {
                        navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                    }
                },
                buildLabel = "MolaGPT v${com.molagpt.app.BuildConfig.VERSION_NAME} · 构建 ${com.molagpt.app.BuildConfig.BUILD_TIME}",
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHost(
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onAuthExpired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val sessionVm: SessionViewModel = viewModel(factory = ViewModelFactories.session(container))

    val scope = rememberCoroutineScope()

    // 当前会话需要跨二级页面往返和进程重建恢复。
    var currentSessionId by rememberSaveable { mutableStateOf<String?>(Ids.newSessionId()) }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerProgress = remember { Animatable(0f) }

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
            drawerOpen = false
            container.pendingOpenSessionId.value = null
        }
    }

    // 维护「当前可见会话」给完成通知做抑制；离开聊天页时清空。
    DisposableEffect(currentSessionId) {
        container.setForegroundSession(currentSessionId)
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
            drawerProgress.animateTo(1f, MolaMotion.standardDecelerate(durationMillis = 260))
            throw e
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 内容（聊天页）
        val selectedSessionId = currentSessionId
        if (selectedSessionId == null) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            key(selectedSessionId) {
                val chatVm: com.molagpt.app.feature.chat.ChatViewModel =
                    viewModel(
                        key = "chat-$selectedSessionId",
                        factory = ViewModelFactories.chat(container, selectedSessionId, settings),
                    )
                ChatScreen(
                    viewModel = chatVm,
                    enterToSend = settings.enterToSend,
                    onOpenDrawer = { drawerOpen = true },
                    onOpenSettings = onOpenSettings,
                    onAuthExpired = onAuthExpired,
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
            SessionDrawer(
                sessions = sessionVm.sessionItems,
                currentSessionId = currentSessionId,
                onNewChat = {
                    currentSessionId = Ids.newSessionId()
                    drawerOpen = false
                },
                onSelect = { id ->
                    currentSessionId = id
                    drawerOpen = false
                },
                onDelete = { id, nextSessionId ->
                    scope.launch {
                        sessionVm.delete(id)
                        if (id == currentSessionId) {
                            currentSessionId = nextSessionId ?: Ids.newSessionId()
                        }
                        container.syncEngine.schedulePush(id)
                    }
                },
            )
        }
    }
}
