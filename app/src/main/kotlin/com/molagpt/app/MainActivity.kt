package com.molagpt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
        handleOpenConversationIntent(intent)
        setContent {
            val settings by container.settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = com.molagpt.app.core.storage.AppSettings())
            // App 主题可独立于系统（themeMode），据此同步系统状态栏/导航栏图标明暗，
            // 否则暗色界面下仍是深色图标看不清。
            val dark = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()))
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                MolaTheme(themeMode = settings.themeMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        MolaNavHost(settings = settings, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    /** singleTask：通知点击复用同一实例，新 intent 走这里。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenConversationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        container.setAppForeground(false)
    }

    private fun handleOpenConversationIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(NotificationController.EXTRA_OPEN_SESSION)
        if (!sessionId.isNullOrBlank()) container.requestOpenConversation(sessionId)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
