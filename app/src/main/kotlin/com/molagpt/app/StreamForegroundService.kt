package com.molagpt.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class StreamForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() } ?: Kind.REPLY
        startForeground(NOTIFICATION_ID, buildNotification(kind))
        // 进程被杀后不自动重建：流已随进程消亡，恢复改由 App 启动时对账(check_stream_status)完成。
        // 若返回 START_STICKY，系统会以 null intent 重建服务、在无任务时留下僵尸「生成中」通知。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台生成",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "MolaGPT 正在生成回复或图片时保持连接"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(kind: Kind): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_molagpt)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setContentTitle(if (kind == Kind.IMAGE) "MolaGPT 正在生成图片" else "MolaGPT 正在生成回复")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** 通知文案按在跑的内容区分；回复和图片同时在跑时按回复显示。 */
    enum class Kind { REPLY, IMAGE }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val CHANNEL_ID = "mola_stream_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.molagpt.app.action.STOP_STREAM_FOREGROUND"

        fun start(context: Context, kind: Kind = Kind.REPLY) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamForegroundService::class.java).putExtra(EXTRA_KIND, kind.name),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StreamForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
