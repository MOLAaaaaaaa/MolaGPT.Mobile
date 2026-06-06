package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 在途流式任务的「被杀恢复」记录。
 *
 * 进程被系统杀死时 [com.molagpt.app.core.storage.ChatRepository.streamAssistant] 的 finally
 * 不会执行（kill 不走 finally），内存态 task 与 partial 都会丢失。故每次发起流时把恢复所需的
 * 最小信息落库；App 下次启动据此向服务端 `stream_cache`（check_stream_status / action:resume）对账续传。
 */
@Entity(tableName = "stream_tasks")
data class StreamTaskEntity(
    @PrimaryKey val sessionId: String,
    val streamSessionId: String,
    val conversationId: String,
    val assistantMessageId: String,
    val modelId: String,
    val modelDisplayName: String?,
    /** 发起时已解析好的相对 apiUrl（恢复时直接复用，免在启动早期依赖未就绪的模型注册表）。 */
    val apiUrl: String,
    val createdAt: Long,
)
