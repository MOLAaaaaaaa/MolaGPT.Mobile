package com.molagpt.app.feature.settings

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.storage.SettingsStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val Tag = "StartupNotices"

/**
 * 每次 App 进入前台（ON_START）检查一次更新、运营消息与本地 Promo；
 * 对话完成累计轮数达到里程碑阈值时也会评估一次未读的里程碑 Promo。
 * 优先级：运营消息（服务端下发）> 本地 Promo（功能介绍 / 里程碑引导）> 更新弹窗。
 * 三类各自按 id 只展示一次，互不抢占。
 */
@Composable
fun StartupNoticesHost(
    versionName: String,
    settingsStore: SettingsStore,
    onOpenPersonaManagement: () -> Unit = {},
    onOpenSupport: () -> Unit = {},
) {
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var pendingOpsMessages by remember { mutableStateOf<List<OpsMessage>>(emptyList()) }
    var pendingPromos by remember { mutableStateOf<List<PromoCard>>(emptyList()) }
    val completedTurns by settingsStore.completedTurnCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val inFlight = remember { AtomicBoolean(false) }

    suspend fun loadEligiblePromos(turns: Int): List<PromoCard> = runCatching {
        val seen = settingsStore.seenPromoIds()
        val isDonor = settingsStore.isDonorCached()
        BuiltInPromos.all.filter { promo ->
            if (promo.id in seen) return@filter false
            if (turns < promo.minCompletedTurns) return@filter false
            if (promo.id == BuiltInPromos.AfdianSupportV1.id && isDonor) {
                settingsStore.addSeenPromoId(promo.id)
                return@filter false
            }
            true
        }
    }.getOrDefault(emptyList())

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (updateInfo != null || pendingOpsMessages.isNotEmpty() || pendingPromos.isNotEmpty()) {
            return@LifecycleEventEffect
        }
        if (!inFlight.compareAndSet(false, true)) return@LifecycleEventEffect
        scope.launch {
            try {
                Log.i(Tag, "ON_START check notices version=$versionName")
                coroutineScope {
                    val updateJob = async {
                        runCatching { checkForUpdate(versionName) }
                            .onFailure { Log.w(Tag, "checkForUpdate failed", it) }
                            .getOrNull()
                    }
                    val feedJob = async { fetchOpsMessages() }
                    val promoJob = async { loadEligiblePromos(completedTurns) }
                    val update = updateJob.await()
                    val feed = feedJob.await()
                    Log.i(Tag, "result update=${update?.version} messages=${feed?.size}")
                    if (feed != null) {
                        settingsStore.retainSeenOpsMessageIds(feed.map { it.id }.toSet())
                        val seen = settingsStore.seenOpsMessageIds()
                        pendingOpsMessages = feed.filter { it.id !in seen }
                    }
                    pendingPromos = promoJob.await()
                    if (update != null) updateInfo = update
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    LaunchedEffect(completedTurns) {
        if (completedTurns < 5) return@LaunchedEffect
        if (updateInfo != null || pendingOpsMessages.isNotEmpty() || pendingPromos.isNotEmpty()) {
            return@LaunchedEffect
        }
        val eligible = loadEligiblePromos(completedTurns)
        if (eligible.isNotEmpty()) pendingPromos = eligible
    }

    val currentMessage = pendingOpsMessages.firstOrNull()
    val currentPromo = pendingPromos.firstOrNull()
    if (currentMessage != null) {
        OpsMessageDialog(
            message = currentMessage,
            onDismiss = {
                scope.launch { settingsStore.addSeenOpsMessageId(currentMessage.id) }
                pendingOpsMessages = pendingOpsMessages.drop(1)
            },
        )
    } else if (currentPromo != null) {
        PromoCardSheet(
            card = currentPromo,
            onPrimary = {
                scope.launch { settingsStore.addSeenPromoId(currentPromo.id) }
                pendingPromos = pendingPromos.drop(1)
                when (currentPromo.id) {
                    BuiltInPromos.RoleplayCardsV1.id -> onOpenPersonaManagement()
                    BuiltInPromos.GitHubStarV1.id ->
                        runCatching { uriHandler.openUri(CommunityUrls.MobileGitHubUrl) }
                    BuiltInPromos.AfdianSupportV1.id -> onOpenSupport()
                }
            },
            onDismiss = {
                scope.launch { settingsStore.addSeenPromoId(currentPromo.id) }
                pendingPromos = pendingPromos.drop(1)
            },
        )
    } else {
        updateInfo?.let { info ->
            UpdateAvailableDialog(
                info = info,
                onDismiss = { updateInfo = null },
            )
        }
    }
}
