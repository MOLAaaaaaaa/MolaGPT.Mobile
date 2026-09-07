package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.dao.RecallHitRow
import kotlinx.coroutines.withContext

/**
 * 历史对话回忆：按关键词定位本机 BYOK 会话里的原消息，并读取命中附近的有限上下文。
 *
 * 与长期记忆的分工：长期记忆回答「用户是谁、偏好什么」，这里回答「之前具体聊过什么」。
 * 历史消息不常驻注入，也不复制进记忆表——它们的事实源始终是消息本身。
 */
class ConversationRecallRepository(
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider,
) {

    data class Hit(
        val sessionId: String,
        val messageId: String,
        val title: String,
        val createdAt: Long,
        val snippet: String,
        val context: List<ContextMessage>,
        /** 命中了几个短语。多短语同时命中的结果更可能是用户真正想找的那次对话。 */
        val matchedPhrases: Int,
        /**
         * 命中点是否落在了截断后的正文之外。为 true 时上下文里那条「←命中」的消息**不含关键词**，
         * 调用方必须额外把 [snippet] 给模型看，否则它会对着一段无关正文作答。
         */
        val snippetOutsideBody: Boolean,
    )

    data class ContextMessage(
        val role: String,
        val createdAt: Long,
        val body: String,
        val isHit: Boolean,
    )

    /**
     * 对每个短语分别查，合并去重后按「命中短语数 → 时间倒序」排序。
     *
     * 分开查而不是拼一条 SQL：`instr` 没有 OR 语义，而拆开还能顺带得到「命中了几个短语」这个排序信号。
     * 短语数上限很小（4），本地记录量级也小，多跑几条查询的成本可以忽略。
     */
    suspend fun recall(
        phrases: List<String>,
        excludeSessionId: String?,
        onlySessionId: String? = null,
        limit: Int = DEFAULT_LIMIT,
        contextBefore: Int = DEFAULT_CONTEXT,
        contextAfter: Int = DEFAULT_CONTEXT,
    ): List<Hit> = withContext(dispatchers.io) {
        val cleaned = phrases.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PHRASES)
        if (cleaned.isEmpty()) return@withContext emptyList()

        val boundedLimit = limit.coerceIn(1, DEFAULT_LIMIT)
        // 每个短语多取一些，合并后再截断——否则命中数多的结果会被单短语的 LIMIT 提前挤掉。
        val perPhraseLimit = boundedLimit * MAX_PHRASES

        val byMessage = LinkedHashMap<String, MutableList<RecallHitRow>>()
        cleaned.forEach { phrase ->
            messageDao.searchRecallHits(
                query = phrase,
                excludeSessionId = excludeSessionId,
                onlySessionId = onlySessionId,
                limit = perPhraseLimit,
            ).forEach { row ->
                byMessage.getOrPut(row.messageId) { mutableListOf() }.add(row)
            }
        }
        if (byMessage.isEmpty()) return@withContext emptyList()

        val ranked = byMessage.values
            .map { rows -> rows.first() to rows.size }
            .sortedWith(
                compareByDescending<Pair<RecallHitRow, Int>> { it.second }
                    .thenByDescending { it.first.createdAt },
            )
            .take(boundedLimit)

        // 全局预算：所有结果加起来最多 MAX_TOTAL_MESSAGES 条、MAX_TOTAL_CHARS 字符。
        // 一次回忆本该是"看一眼"，不该把半个会话搬进上下文。
        var messageBudget = MAX_TOTAL_MESSAGES
        var charBudget = MAX_TOTAL_CHARS

        ranked.mapNotNull { (row, matched) ->
            if (messageBudget <= 0 || charBudget <= 0) return@mapNotNull null
            val anchor = messageDao.recallMessage(row.messageId, MAX_BODY_CHARS) ?: return@mapNotNull null
            val before = messageDao
                .recallContextBefore(row.sessionId, row.createdAt, contextBefore.coerceIn(0, MAX_CONTEXT), MAX_BODY_CHARS)
                .reversed()
            val after = messageDao
                .recallContextAfter(row.sessionId, row.createdAt, contextAfter.coerceIn(0, MAX_CONTEXT), MAX_BODY_CHARS)

            val window = mutableListOf<ContextMessage>()
            (before + anchor + after).forEach { message ->
                if (messageBudget <= 0 || charBudget <= 0) return@forEach
                val body = message.body.orEmpty().trim()
                if (body.isEmpty()) return@forEach
                val clipped = body.take(charBudget)
                window += ContextMessage(
                    role = message.role,
                    createdAt = message.createdAt,
                    body = clipped,
                    isHit = message.messageId == row.messageId,
                )
                messageBudget--
                charBudget -= clipped.length
            }
            if (window.isEmpty()) return@mapNotNull null

            // 正文取的是头部 MAX_BODY_CHARS 字符，而命中点可能在更靠后的位置。
            // 这里据实告诉调用方：那条被标成「命中」的正文里到底还有没有关键词。
            val snippet = row.snippet.orEmpty().trim()
            val anchorBody = window.firstOrNull { it.isHit }?.body.orEmpty()

            Hit(
                sessionId = row.sessionId,
                messageId = row.messageId,
                title = row.title,
                createdAt = row.createdAt,
                snippet = snippet,
                context = window,
                matchedPhrases = matched,
                snippetOutsideBody = snippet.isNotEmpty() && !anchorBody.contains(snippet),
            )
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 3
        const val DEFAULT_CONTEXT = 2
        const val MAX_PHRASES = 4
        private const val MAX_CONTEXT = 4
        private const val MAX_TOTAL_MESSAGES = 12
        private const val MAX_TOTAL_CHARS = 6000
        private const val MAX_BODY_CHARS = 600
    }
}
