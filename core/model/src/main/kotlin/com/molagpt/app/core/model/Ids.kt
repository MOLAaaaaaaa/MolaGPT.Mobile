package com.molagpt.app.core.model

import java.util.UUID

/**
 * 统一 id 生成。会话 / 对话 id 使用 `sess_<millis>_<rand>` / `chat_<millis>_<rand>` 格式，
 * 便于断点续传时按 session_id 定位服务端缓存。
 */
object Ids {
    fun newSessionId(): String = "sess_${System.currentTimeMillis()}_${randomSuffix()}"

    fun newConversationId(): String = "chat_${System.currentTimeMillis()}_${randomSuffix()}"

    fun conversationIdForSession(sessionId: String): String =
        if (sessionId.startsWith("sess_")) "chat_" + sessionId.removePrefix("sess_") else "chat_$sessionId"

    fun newMessageId(): String = "msg_${System.currentTimeMillis()}_${randomSuffix()}"

    fun newFragmentId(): String = "frag_${randomSuffix()}"

    private fun randomSuffix(): String =
        UUID.randomUUID().toString().replace("-", "").take(9)
}
