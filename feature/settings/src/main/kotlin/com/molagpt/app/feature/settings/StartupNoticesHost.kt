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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

private const val Tag = "StartupNotices"

/**
 * 每次 App 进入前台（ON_START）检查一次更新；发现新版本则弹出 Markdown changelog 对话框。
 */
@Composable
fun StartupNoticesHost(
    versionName: String,
) {
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()
    val inFlight = remember { AtomicBoolean(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (updateInfo != null) return@LifecycleEventEffect
        if (!inFlight.compareAndSet(false, true)) return@LifecycleEventEffect
        scope.launch {
            try {
                Log.i(Tag, "ON_START checkForUpdate version=$versionName")
                val update = runCatching { checkForUpdate(versionName) }
                    .onFailure { Log.w(Tag, "checkForUpdate failed", it) }
                    .getOrNull()
                Log.i(Tag, "result update=${update?.version}")
                if (update != null) updateInfo = update
            } finally {
                inFlight.set(false)
            }
        }
    }

    updateInfo?.let { info ->
        UpdateAvailableDialog(
            info = info,
            onDismiss = { updateInfo = null },
        )
    }
}
