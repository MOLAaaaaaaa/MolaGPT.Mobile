package com.molagpt.app.core.network

import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.TitleRequest
import kotlinx.coroutines.flow.Flow

/**
 * 服务端聊天能力抽象。唯一实现 [MolaGptChatService]：对接真实 MolaGPT SSE/HTTP。
 *
 * 会话/历史等本地能力不在此处——见 :core:storage 的 SessionRepository。
 * regenerate/edit/continue 由 ChatRepository 编排「改本地消息 → 重新 sendMessage」表达。
 */
interface ChatService {
    /** 发起流式对话。SSE 原始数据在实现内部解析，对外只产出 [StreamEvent]。 */
    fun sendMessage(request: ChatRequest): Flow<StreamEvent>

    /** 用户停止生成（真实：POST stop_stream.php）。 */
    suspend fun stopGeneration(streamSessionId: String)

    /** 按服务端 stream_cache 的行 offset 恢复进行中的流。 */
    fun resumeStream(apiUrl: String, streamSessionId: String, offset: Int): Flow<StreamEvent>

    /** 查询某个 stream_cache 会话的状态（check_stream_status.php），用于启动时对账。null=不存在/已过期。 */
    suspend fun checkStreamStatus(streamSessionId: String): StreamStatus?

    /** 上传文件（真实：multipart 到 batchUpload.php）。 */
    suspend fun uploadFile(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        conversationId: String,
    ): FileInfo

    /** 拉取会话已上传文件列表。 */
    suspend fun fetchFiles(conversationId: String): List<FileInfo>

    /**
     * 生成会话标题。按 [TitleRequest.providerKind] 分派：
     * MolaGPT 走 generateTitle.php；BYOK 走用户自己的 provider（见 [ByokChatService.generateTitle]）。
     */
    suspend fun generateTitle(request: TitleRequest): String
}

/** stream_cache 会话状态快照（check_stream_status.php 的单条结果）。 */
data class StreamStatus(
    val status: String,
    val conversationId: String?,
    val chunksCount: Int,
)
