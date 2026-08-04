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
 * 每次 App 进入前台（ON_START）检查一次更新与运营消息。
 * 运营消息按 id 去重逐条弹出（优先），更新弹窗垫后。
 */
@Composable
fun StartupNoticesHost(
    versionName: String,
    settingsStore: SettingsStore,
) {
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var pendingOpsMessages by remember { mutableStateOf<List<OpsMessage>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val inFlight = remember { AtomicBoolean(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (updateInfo != null || pendingOpsMessages.isNotEmpty()) return@LifecycleEventEffect
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
                    val update = updateJob.await()
                    val feed = feedJob.await()
                    Log.i(Tag, "result update=${update?.version} messages=${feed?.size}")
                    if (feed != null) {
                        settingsStore.retainSeenOpsMessageIds(feed.map { it.id }.toSet())
                        val seen = settingsStore.seenOpsMessageIds()
                        pendingOpsMessages = feed.filter { it.id !in seen }
                    }
                    if (update != null) updateInfo = update
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    val currentMessage = pendingOpsMessages.firstOrNull()
    if (currentMessage != null) {
        OpsMessageDialog(
            message = currentMessage,
            onDismiss = {
                scope.launch { settingsStore.addSeenOpsMessageId(currentMessage.id) }
                pendingOpsMessages = pendingOpsMessages.drop(1)
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
