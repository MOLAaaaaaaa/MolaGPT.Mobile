package com.molagpt.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.molagpt.app.core.storage.SessionRepository
import com.molagpt.app.core.storage.ImageVersionStatus
import com.molagpt.app.feature.chat.BackgroundStreamManager
import com.molagpt.app.feature.imagegen.ImageTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 后台对话完成通知。
 *
 * 订阅 [BackgroundStreamManager.completions]：当 App 在前台且正看着该会话时不打扰，
 * 否则发系统通知，点击经 `molagpt://`/Intent extra 深链回该会话。受个人中心开关控制。
 */
class NotificationController(
    private val appContext: Context,
    private val manager: BackgroundStreamManager,
    private val imageTasks: ImageTaskManager,
    private val sessionRepository: SessionRepository,
    private val scope: CoroutineScope,
    private val notifyEnabled: () -> Boolean,
    private val appForeground: () -> Boolean,
    private val foregroundSessionId: () -> String?,
    private val foregroundImageTaskId: () -> String?,
) {
    init {
        ensureChannel()
        manager.completions
            .onEach { handle(it) }
            .launchIn(scope)
        imageTasks.completions
            .onEach { handleImage(it) }
            .launchIn(scope)
    }

    /** 画图任务跑完。停止的不提醒——那是用户自己点的。 */
    private fun handleImage(c: ImageTaskManager.Completion) {
        if (!notifyEnabled()) return
        if (c.status == ImageVersionStatus.CANCELED) return
        if (appForeground() && foregroundImageTaskId() == c.taskId) return
        val title = c.title.ifBlank { "画图任务" }
        val body = if (c.status == ImageVersionStatus.SUCCESS) "「$title」已生成 ${c.imageCount} 张" else "「$title」生成失败"
        post(key = c.taskId, headline = "画图工作", body = body, extra = EXTRA_OPEN_IMAGE_TASK)
    }

    private suspend fun handle(c: BackgroundStreamManager.Completion) {
        if (!notifyEnabled()) return
        // 当前会话可见且 App 在前台 → 用户已在看，不打扰。
        if (appForeground() && foregroundSessionId() == c.sessionId) return
        val title = runCatching { sessionRepository.get(c.sessionId)?.title }.getOrNull()
        val body = if (title.isNullOrBlank()) "回复已完成" else "「$title」回复已完成"
        post(c.sessionId, c.modelDisplayName?.takeIf { it.isNotBlank() } ?: "MolaGPT", body, EXTRA_OPEN_SESSION)
    }

    private fun post(key: String, headline: String, body: String, extra: String) {
        val nm = NotificationManagerCompat.from(appContext)
        if (!nm.areNotificationsEnabled()) return
        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(extra, key)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            key.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_molagpt)
            .setColor(ContextCompat.getColor(appContext, R.color.brand))
            .setContentTitle(headline)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { nm.notify(key.hashCode(), notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "生成完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "后台生成的回复或图片完成时提醒"
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "mola_completion"
        const val EXTRA_OPEN_SESSION = "open_conversation_session_id"
        const val EXTRA_OPEN_IMAGE_TASK = "open_image_task_id"
    }
}
