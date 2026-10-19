package com.molagpt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.nav.MolaNavHost
import com.molagpt.app.ui.theme.MolaTheme

/** 唯一 Activity（单 Activity + Compose Navigation 架构）。 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as MolaApp).container }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果忽略：仅影响是否展示通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        handleOpenNotificationIntent(intent)
        setContent {
            val settings by container.settingsFlow.collectAsStateWithLifecycle()
            val byokModelsReady by container.byokModelsReady.collectAsStateWithLifecycle()
            val themeMode = settings?.themeMode ?: "auto"
            // App 主题可独立于系统（themeMode）：系统栏图标明暗和三键导航的遮罩都按 App 主题来，
            // 否则 App 深色、系统浅色时图标看不清，三键导航还会盖一条白色遮罩。
            val dark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark },
                )
                window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()))
                onDispose {}
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                MolaTheme(themeMode = themeMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        val loadedSettings = settings
                        if (loadedSettings != null && byokModelsReady) {
                            MolaNavHost(settings = loadedSettings, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** 三键导航的半透明遮罩，与 enableEdgeToEdge 默认值一致。 */
        val LIGHT_NAV_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }

    /** singleTask：通知点击复用同一实例，新 intent 走这里。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        container.setAppForeground(false)
    }

    private fun handleOpenNotificationIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(NotificationController.EXTRA_OPEN_SESSION)
        if (!sessionId.isNullOrBlank()) container.requestOpenConversation(sessionId)
        container.requestOpenImageTask(intent?.getStringExtra(NotificationController.EXTRA_OPEN_IMAGE_TASK))
        val agentSessionId = intent?.getStringExtra(AgentNotificationController.EXTRA_OPEN_AGENT_SESSION)
        if (!agentSessionId.isNullOrBlank()) container.requestOpenAgentSession(agentSessionId)
        container.requestOpenDesktopConversation(intent?.getStringExtra(DesktopNotificationMonitor.EXTRA_OPEN_DESKTOP_CONVERSATION))
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
