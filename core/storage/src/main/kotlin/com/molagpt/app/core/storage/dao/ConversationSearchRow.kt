package com.molagpt.app.core.storage.dao

import androidx.room.Embedded
import com.molagpt.app.core.storage.entity.ConversationEntity

/**
 * 搜索结果行：会话本体 + 命中的正文片段。
 *
 * 片段由 SQL 侧 `substr` 截好再进游标，**不要**改成回传整条 rawText —— 见 MIGRATION_9_10，
 * 肥消息行曾让 CursorWindow 抛 SQLiteBlobTooBigException。
 * 仅标题命中（或会话是云端占位）时 [matchedSnippet] 为 null。
 */
data class ConversationSearchRow(
    @Embedded val conversation: ConversationEntity,
    val matchedSnippet: String?,
)
