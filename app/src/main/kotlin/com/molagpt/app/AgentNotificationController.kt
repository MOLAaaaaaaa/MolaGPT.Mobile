package com.molagpt.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.molagpt.app.core.model.AgentBackendIds

/** Posts actionable Agent permission/completion notifications with session deep links. */
internal class AgentNotificationController(
    val context: Context,
    private val appForeground: () -> Boolean,
    private val foregroundAgentSessionId: () -> String?,
) {
    init {
        ensureChannels()
    }

    fun post(alert: AgentAlert) {
        val nm = NotificationManagerCompat.from(context)
        // A terminal event always clears its earlier approval reminder, even when
        // the result notification itself is suppressed because this session is
        // already visible in the foreground.
        if (alert.kind != AgentAlertKind.Permission) {
            nm.cancel(notificationId(alert.sessionId, AgentAlertKind.Permission))
        }
        if (appForeground() && foregroundAgentSessionId() == alert.sessionId) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!nm.areNotificationsEnabled()) return

        val backend = when (alert.backendId) {
            AgentBackendIds.ClaudeCode -> "Claude Code"
            AgentBackendIds.Codex -> "Codex"
            else -> "远程 Agent"
        }
        val sessionTitle = alert.title.ifBlank { "远程会话" }
        val (channel, headline, body, priority) = when (alert.kind) {
            AgentAlertKind.Permission -> NotificationText(
                PERMISSION_CHANNEL_ID,
                "$backend 等待审批",
                buildString {
                    append('「').append(sessionTitle).append("」需要你的确认")
                    alert.toolName?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
                },
                NotificationCompat.PRIORITY_HIGH,
            )
            AgentAlertKind.Completed -> NotificationText(
                COMPLETION_CHANNEL_ID,
                "$backend 已完成",
                "「$sessionTitle」任务已完成",
                NotificationCompat.PRIORITY_DEFAULT,
            )
            AgentAlertKind.Failed -> NotificationText(
                COMPLETION_CHANNEL_ID,
                "$backend 运行失败",
                alert.message?.takeIf { it.isNotBlank() }
                    ?.let { "「$sessionTitle」：${it.take(120)}" }
                    ?: "「$sessionTitle」任务未完成",
                NotificationCompat.PRIORITY_DEFAULT,
            )
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_AGENT_SESSION, alert.sessionId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(alert.sessionId, alert.kind),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_molagpt)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(headline)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(priority)
            .setCategory(
                if (alert.kind == AgentAlertKind.Permission) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_STATUS,
            )
            .build()
        runCatching { nm.notify(notificationId(alert.sessionId, alert.kind), notification) }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PERMISSION_CHANNEL_ID,
                "Agent 权限审批",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "远程 Claude Code 或 Codex 等待权限确认时提醒" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "Agent 任务结果",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "远程 Claude Code 或 Codex 完成或失败时提醒" },
        )
    }

    private fun notificationId(sessionId: String, kind: AgentAlertKind): Int =
        sessionId.hashCode() xor when (kind) {
            AgentAlertKind.Permission -> 0x41A60001
            AgentAlertKind.Completed, AgentAlertKind.Failed -> 0x41A60002
        }

    private data class NotificationText(
        val channel: String,
        val headline: String,
        val body: String,
        val priority: Int,
    )

    companion object {
        const val EXTRA_OPEN_AGENT_SESSION = "open_agent_session_id"
        private const val PERMISSION_CHANNEL_ID = "mola_agent_permission"
        private const val COMPLETION_CHANNEL_ID = "mola_agent_completion"
    }
}
