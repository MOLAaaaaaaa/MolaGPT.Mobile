package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.ProviderIds
import com.molagpt.app.core.model.TitleRequest
import com.molagpt.app.core.model.titleWindow
import kotlinx.coroutines.withContext

/**
 * 会话自动标题的编排层：取消息尾窗口 → 让模型总结 → 回写会话名。
 *
 * 有意跑在 **application scope**（由 BackgroundStreamManager 触发），而不是聊天页 ViewModel：
 * 用户经常回答一出来就退出会话，绑在 viewModelScope 上的请求会被取消、标题永远停在占位值。
 *
 * 阵营无关——MolaGPT 会话打服务端 generateTitle 端点，BYOK 会话打用户自己的 provider，
 * 分派由 RoutingChatService 按 [TitleRequest.providerKind] 完成。
 */
class ConversationTitler(
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val dispatchers: DispatcherProvider,
) {
    /** 为 [sessionId] 生成标题并回写。返回是否真的改名了；任何一步不满足都静默放弃。 */
    suspend fun generate(sessionId: String): Boolean = withContext(dispatchers.io) {
        val before = sessionRepository.get(sessionId) ?: return@withContext false
        val modelId = before.model?.trim().orEmpty()
        if (modelId.isBlank()) return@withContext false
        val window = titleWindow(chatRepository.allMessages(sessionId))
        if (window.isEmpty()) return@withContext false

        val title = runCatching {
            chatRepository.generateTitle(
                TitleRequest(
                    sessionId = sessionId,
                    providerKind = before.providerKind,
                    providerId = before.providerId ?: ProviderIds.MOLAGPT,
                    modelId = modelId,
                    messages = window,
                ),
            )
        }.getOrElse {
            Logger.w(TAG, "生成标题失败：${it.message}", it)
            return@withContext false
        }
        // 生成失败时各实现返回的就是占位标题本身，等值即代表「没真的生成出来」。
        if (title.isBlank() || title == before.title) return@withContext false

        // 生成期间会话可能已被删除、或用户已手动改名：重新取一次再决定写不写。
        val after = sessionRepository.get(sessionId) ?: return@withContext false
        if (after.title != before.title) return@withContext false

        sessionRepository.rename(sessionId, title)
        true
    }

    private companion object {
        const val TAG = "ConversationTitler"
    }
}
