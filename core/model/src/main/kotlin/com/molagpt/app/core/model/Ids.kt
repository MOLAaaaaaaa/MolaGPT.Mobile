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

    /** BYOK 本地记忆条目 / 候选 / 证据。短前缀便于在整理提示词里回传给模型时省 token。 */
    fun newMemoryId(): String = "mem_${randomSuffix()}"

    fun newMemoryTopicId(): String = "topic_${randomSuffix()}"

    private fun randomSuffix(): String =
        UUID.randomUUID().toString().replace("-", "").take(9)
}
