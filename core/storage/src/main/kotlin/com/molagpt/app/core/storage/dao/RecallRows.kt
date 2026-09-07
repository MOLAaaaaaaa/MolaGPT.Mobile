package com.molagpt.app.core.storage.dao

/**
 * 历史回忆的轻量投影。刻意不复用 `MessageEntity`：那是整行读取，
 * 而回忆只需要几个字段和一段已在 SQL 侧截断的正文。
 */
data class RecallHitRow(
    val messageId: String,
    val sessionId: String,
    val title: String,
    val role: String,
    val createdAt: Long,
    /** 命中点前 40 字起的 160 字窗口。 */
    val snippet: String?,
)

data class RecallMessageRow(
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val body: String?,
)
