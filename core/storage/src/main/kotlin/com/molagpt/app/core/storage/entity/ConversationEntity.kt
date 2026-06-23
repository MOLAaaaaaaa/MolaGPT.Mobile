package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 会话表。 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["deletedAt", "pinned", "updatedAt"]),
        Index(value = ["deletedAt", "visibleInList", "pinned", "updatedAt"]),
        Index(value = ["placeholder"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey val sessionId: String,
    val title: String,
    val model: String?,
    val providerId: String? = "molagpt",
    val providerKind: String = "MOLAGPT",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val lastMessagePreview: String? = null,
    /** Local message rows known for this conversation. Kept on the conversation row so the drawer never has to probe messages. */
    val messageCount: Int = 0,
    /** True when the conversation should appear in the drawer: cloud placeholder or at least one local message. */
    val visibleInList: Boolean = false,
    /** 云同步：本地有未推送的改动（标题/消息/置顶等）。push 成功后清零。 */
    val dirty: Boolean = false,
    /** 云同步：软删墓碑时间（ms）。非空表示已删除、待向云端 push delete，确认后才硬删。 */
    val deletedAt: Long? = null,
    /**
     * 云同步「占位会话」：只同步了元数据、本地尚无消息正文（懒加载）。
     * 侧边栏照常显示；点开时才 fetch_conversation 拉消息并置回 false。登出时占位会被清理。
     */
    val placeholder: Boolean = false,
)
