package com.molagpt.app.feature.chat

import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.UploadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** 切换会话时保留本机聊天的未发送文字与附件。 */
class SessionDraftStore {
    private val drafts = ConcurrentHashMap<String, SessionDraft>()

    internal fun getOrCreate(sessionId: String, restoredText: String): SessionDraft =
        drafts.getOrPut(sessionId) { SessionDraft(restoredText) }

    fun hasDraft(sessionId: String): Boolean = drafts[sessionId]?.let {
        it.text.value.isNotEmpty() || it.attachments.value.isNotEmpty()
    } == true

    fun clearOfficial(sessionIds: List<String>) {
        // Also discard drafts for blank chats which don't have a Room row yet.
        clearAll(sessionIds + drafts.filterValues { !it.byok }.keys)
    }

    fun clearAll(sessionIds: Collection<String>) {
        sessionIds.forEach { id ->
            drafts.remove(id)?.let {
                it.setText("")
                it.attachments.value = emptyList()
            }
        }
    }
}

internal class SessionDraft(restoredText: String) {
    @Volatile var byok = false
    val text = MutableStateFlow(restoredText)
    val attachments = MutableStateFlow<List<FileInfo>>(emptyList())
    var textRevision = 0L
        private set

    fun setText(value: String) {
        textRevision++
        text.value = value
    }

    fun clearText(expectedRevision: Long): Boolean {
        if (textRevision != expectedRevision) return false
        setText("")
        return true
    }
}

internal val FileInfo.isReadyToSend: Boolean
    get() = uploadStatus == UploadStatus.UPLOADED &&
        (!url.isNullOrBlank() || !sandboxPath.isNullOrBlank() || !localPath.isNullOrBlank())
