package com.molagpt.app.feature.settings

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.molagpt.app.core.storage.SettingsStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val Tag = "StartupNotices"

/**
 * 每次 App 进入前台（ON_START）检查一次更新、运营消息与本地 Promo。
 * 优先级：运营消息（服务端下发）> 本地 Promo（内置功能介绍）> 更新弹窗。
 * 三类各自按 id 只展示一次，互不抢占。
 */
@Composable
fun StartupNoticesHost(
    versionName: String,
    settingsStore: SettingsStore,
    onOpenByokMemory: () -> Unit = {},
) {
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var pendingOpsMessages by remember { mutableStateOf<List<OpsMessage>>(emptyList()) }
    var pendingPromos by remember { mutableStateOf<List<PromoCard>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val inFlight = remember { AtomicBoolean(false) }

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
                    // 本地 Promo 纯读 DataStore，不发请求。
                    val promoJob = async {
                        runCatching {
                            val seen = settingsStore.seenPromoIds()
                            BuiltInPromos.all.filter { it.id !in seen }
                        }.getOrDefault(emptyList())
                    }
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
                onOpenByokMemory()
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
