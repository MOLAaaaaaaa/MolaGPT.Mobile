package com.molagpt.app.core.network

import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.StreamEvent
import kotlinx.coroutines.flow.Flow

class RoutingChatService(
    private val molaGpt: ChatService,
    private val byok: ByokChatService,
) : ChatService {
    override fun sendMessage(request: ChatRequest): Flow<StreamEvent> =
        if (request.providerKind == ProviderKind.BYOK) {
            byok.sendMessage(request)
        } else {
            molaGpt.sendMessage(request)
        }

    override suspend fun stopGeneration(streamSessionId: String) {
        molaGpt.stopGeneration(streamSessionId)
        byok.stopGeneration(streamSessionId)
    }

    override fun resumeStream(apiUrl: String, streamSessionId: String, offset: Int): Flow<StreamEvent> =
        molaGpt.resumeStream(apiUrl, streamSessionId, offset)

    override suspend fun checkStreamStatus(streamSessionId: String): StreamStatus? =
        molaGpt.checkStreamStatus(streamSessionId)

    override suspend fun uploadFile(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        conversationId: String,
    ): FileInfo = molaGpt.uploadFile(bytes, fileName, mimeType, conversationId)

    override suspend fun fetchFiles(conversationId: String): List<FileInfo> =
        molaGpt.fetchFiles(conversationId)

    override suspend fun generateTitle(
        sessionId: String,
        firstUserMessage: String,
        assistantMessage: String,
    ): String = molaGpt.generateTitle(sessionId, firstUserMessage, assistantMessage)
}
