package com.molagpt.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps relay polling alive only while at least one remote Agent turn is active. */
class AgentMonitorForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(intent?.getStringExtra(EXTRA_SESSION_ID)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(sessionId: String?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_molagpt)
        .setColor(ContextCompat.getColor(this, R.color.brand))
        .setContentTitle("远程 Agent 运行中")
        .setContentText("正在等待任务完成或权限审批")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    sessionId?.let { putExtra(AgentNotificationController.EXTRA_OPEN_AGENT_SESSION, it) }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent 后台监控",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "远程 Agent 运行时保持完成和权限提醒在线" },
        )
    }

    companion object {
        private const val CHANNEL_ID = "mola_agent_monitor"
        private const val NOTIFICATION_ID = 0x41A60010
        private const val ACTION_START = "com.molagpt.app.agent.monitor.START"
        private const val EXTRA_SESSION_ID = "agent_monitor_session_id"

        fun start(context: Context, sessionId: String?): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentMonitorForegroundService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_SESSION_ID, sessionId),
                )
            }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentMonitorForegroundService::class.java)) }
        }
    }
}
