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
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.DesktopConversation
import com.molagpt.app.core.network.DesktopRemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 电脑上的对话需要手机时提醒：等待审批（总是提醒），以及手机发起的回合结束。
 *
 * 读的是电脑上报的对话列表（标题、是否在回答、待审批数），不读内容。应用在前台且正看着
 * 那个对话时不提醒。应用进程被系统回收后收不到——那时回到应用会直接看到电脑的状态。
 */
internal class DesktopNotificationMonitor(
    private val context: Context,
    private val service: DesktopRemoteService,
    private val scope: CoroutineScope,
    private val notifyEnabled: () -> Boolean,
    private val isAuthenticated: () -> Boolean,
    private val appForeground: () -> Boolean,
    private val foregroundConversation: () -> String?,
) {
    private data class Seen(val approvals: Int, val busy: Boolean)

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val seen = HashMap<String, Seen>()
    /** 手机发起、尚未结束的回合：key 为 machineId/conversationId，值为是否已看到它开始回答。 */
    private val watched = HashMap<String, Boolean>()

    init {
        ensureChannels()
        scope.launch { runLoop() }
        scope.launch { service.listChanged.collect { wake.trySend(Unit) } }
    }

    /** 手机刚在这个对话发出消息：回合结束时提醒。 */
    fun watch(machineId: String, conversationId: String) {
        synchronized(watched) { watched[key(machineId, conversationId)] = false }
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        while (true) {
            if (notifyEnabled() && isAuthenticated() && service.hasAnyPair()) {
                runCatching { poll() }.onFailure { Logger.w(TAG, "poll failed: ${it.message}") }
            } else {
                seen.clear()
                synchronized(watched) { watched.clear() }
            }
            val active = synchronized(watched) { watched.isNotEmpty() } || seen.values.any { it.busy || it.approvals > 0 }
            withTimeoutOrNull(if (active) ACTIVE_POLL_MS else IDLE_POLL_MS) { wake.receive() }
        }
    }

    private suspend fun poll() {
        val desktops = service.listDesktops()
        val present = HashSet<String>()
        for (desktop in desktops) {
            if (!service.isPaired(desktop.machineId)) continue
            for (conversation in desktop.conversations) {
                val key = key(desktop.machineId, conversation.id)
                present += key
                val busy = desktop.online && conversation.busy
                val approvals = if (desktop.online) conversation.pendingApprovals else 0
                val before = seen[key]
                seen[key] = Seen(approvals, busy)

                if (approvals > (before?.approvals ?: 0)) post(desktop.machineName, desktop.machineId, conversation, Kind.Approval)
                if (approvals == 0 && (before?.approvals ?: 0) > 0) cancel(key, Kind.Approval)

                val finished = synchronized(watched) {
                    val started = watched[key] ?: return@synchronized false
                    when {
                        busy -> { watched[key] = true; false }
                        // 回答很短时可能两次轮询之间就结束了：没看到开始，只要不忙了也算结束。
                        started || before?.busy == true || !desktop.online -> { watched.remove(key); true }
                        else -> false
                    }
                }
                if (finished && desktop.online) post(desktop.machineName, desktop.machineId, conversation, Kind.Finished)
            }
        }
        seen.keys.retainAll(present)
    }

    private enum class Kind { Approval, Finished }

    private fun post(machineName: String, machineId: String, conversation: DesktopConversation, kind: Kind) {
        val key = key(machineId, conversation.id)
        if (appForeground() && foregroundConversation() == key) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (kind == Kind.Finished) cancel(key, Kind.Approval)

        val (channel, title, body) = when (kind) {
            Kind.Approval -> Triple(APPROVAL_CHANNEL, "电脑对话等待审批", "「${conversation.title}」 · $machineName")
            Kind.Finished -> Triple(RESULT_CHANNEL, "电脑已完成回答", "「${conversation.title}」 · $machineName")
        }
        val tap = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_DESKTOP_CONVERSATION, key)
        }
        val pending = PendingIntent.getActivity(
            context,
            id(key, kind),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_molagpt)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(if (kind == Kind.Approval) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (kind == Kind.Approval) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { manager.notify(id(key, kind), notification) }
    }

    private fun cancel(key: String, kind: Kind) {
        NotificationManagerCompat.from(context).cancel(id(key, kind))
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(APPROVAL_CHANNEL, "电脑对话审批", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "工具调用需要审批时提醒" },
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, "电脑对话结果", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "手机发起的电脑对话回答完成时提醒" },
        )
    }

    private fun key(machineId: String, conversationId: String) = "$machineId/$conversationId"

    private fun id(key: String, kind: Kind): Int = key.hashCode() xor if (kind == Kind.Approval) 0x4D0C0001 else 0x4D0C0002

    companion object {
        const val EXTRA_OPEN_DESKTOP_CONVERSATION = "open_desktop_conversation"
        private const val TAG = "DesktopNotify"
        private const val APPROVAL_CHANNEL = "mola_desktop_approval"
        private const val RESULT_CHANNEL = "mola_desktop_result"
        private const val ACTIVE_POLL_MS = 4_000L
        private const val IDLE_POLL_MS = 20_000L
    }
}
