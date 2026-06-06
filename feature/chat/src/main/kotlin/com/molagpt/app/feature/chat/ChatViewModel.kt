package com.molagpt.app.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.EnabledTools
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.RetryAttempt
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.storage.ChatRepository
import com.molagpt.app.core.storage.RetryAttempts
import com.molagpt.app.core.storage.SessionRepository
import com.molagpt.app.core.storage.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 聊天页 ViewModel：组织 [ChatUiState]、对流式 token 做时间窗节流、编排发送/停止/重发。
 *
 * 数据流：Room 历史(observeMessages) ⊕ application-scoped 后台流任务 → combine → UI。
 * 流式网络 job 不绑定 VM，切换会话时仍能继续生成；结束/停止/出错时由仓库落库一次。
 */
class ChatViewModel(
    private val sessionId: String,
    private val chatRepository: ChatRepository,
    private val backgroundStreams: BackgroundStreamManager,
    private val sessionRepository: SessionRepository,
    private val syncEngine: SyncEngine,
    private val dispatchers: DispatcherProvider,
    private val modelsFlow: StateFlow<List<ProviderModel>>,
    private val modelRefreshingFlow: StateFlow<Boolean>,
    private val modelRefresher: suspend () -> List<ProviderModel>,
    private val defaultModelId: String?,
    private val tools: EnabledTools,
    useThinking: Boolean,
    reasoningEffort: String,
    private val temperature: Double,
    private val throttleMs: Long,
    private val appContext: Context,
) : ViewModel() {

    private val conversationId = Ids.conversationIdForSession(sessionId)
    private val _selectedModel = MutableStateFlow(defaultModelId)
    private val _error = MutableStateFlow<String?>(null)
    private val _authExpired = MutableStateFlow(false)
    private val _enabledTools = MutableStateFlow(tools)
    // 推理开关/强度为**运行时**会话级状态（初值取自设置）：composer 可即时切换，不回写设置。
    private val _useThinking = MutableStateFlow(useThinking)
    private val _reasoningEffort = MutableStateFlow(reasoningEffort)
    private val _pendingAttachments = MutableStateFlow<List<FileInfo>>(emptyList())
    // 占位会话懒加载期间为 true（仅在确认要发网络请求时才置位）→ 驱动聊天页居中转圈。
    private val _loadingHistory = MutableStateFlow(false)

    init {
        // 懒加载：若该会话是云端「占位」(仅元数据)，进入时按需拉取消息正文。非占位/游客内部直接跳过。
        // onFetchStart 仅在「确认占位、即将发起网络请求」时回调 → 据此转圈；普通本地会话不会误闪。
        viewModelScope.launch {
            runCatching {
                syncEngine.loadConversationIfNeeded(sessionId) { _loadingHistory.value = true }
            }
            _loadingHistory.value = false
        }
    }

    private val controls = combine(
        _error, _authExpired, _enabledTools, _useThinking, _reasoningEffort,
    ) { error, authExpired, enabledTools, thinking, effort ->
        ChatControlState(error, authExpired, enabledTools, thinking, effort)
    }

    private val uiMeta = combine(
        controls, _pendingAttachments, modelRefreshingFlow, _loadingHistory,
    ) { controls, pending, refreshing, loadingHistory ->
        ChatUiMeta(controls, pending, refreshing, loadingHistory)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        chatRepository.observeMessages(sessionId),
        backgroundStreams.observe(sessionId),
        _selectedModel,
        uiMeta,
        modelsFlow,
    ) { history, streamState, model, meta, models ->
        val controls = meta.controls
        val pending = meta.pendingAttachments
        val selectedModelId = resolvedModelId(model, models)
        val selectedModel = models.firstOrNull { it.id == selectedModelId }
        val inFlight = streamState.inFlight
        // 合并历史与 in-flight：若 in-flight 的 id 已在历史里（已落库），以 in-flight 为准覆盖。
        val merged = if (inFlight == null) history
        else (history.filterNot { it.messageId == inFlight.messageId } + inFlight)
        ChatUiState(
            sessionId = sessionId,
            messages = merged,
            models = models,
            selectedModelId = selectedModelId,
            selectedModel = selectedModel,
            isModelRefreshing = meta.isModelRefreshing,
            isStreaming = streamState.isStreaming,
            inputEnabled = !streamState.isStreaming,
            enabledTools = controls.enabledTools,
            useThinking = controls.useThinking,
            reasoningEffort = controls.reasoningEffort,
            pendingAttachments = pending,
            error = controls.error ?: streamState.error,
            authExpired = controls.authExpired,
            isLoadingHistory = meta.isLoadingHistory,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(sessionId))

    fun selectModel(modelId: String) {
        _selectedModel.value = modelId
    }

    fun refreshModels() {
        if (modelRefreshingFlow.value) return
        viewModelScope.launch {
            // 成功后 registry 的 StateFlow 自动 emit，uiState 实时重组——无需手动 tick。
            runCatching { withContext(dispatchers.io) { modelRefresher() } }
                .onSuccess { models ->
                    if (models.isEmpty()) {
                        _error.value = "未获取到模型列表，请稍后重试"
                    }
                }
                .onFailure { e ->
                    _error.value = e.message ?: "模型列表刷新失败"
                }
        }
    }

    fun setNetworkTool(enabled: Boolean) {
        _enabledTools.value = _enabledTools.value.copy(network = enabled)
    }

    fun setSteelTool(enabled: Boolean) {
        _enabledTools.value = _enabledTools.value.copy(steelBrowser = enabled)
    }

    fun setCodeTool(enabled: Boolean) {
        _enabledTools.value = _enabledTools.value.copy(codeExecution = enabled)
    }

    /** 「网络访问」一键开关：同时控制联网搜索(network)与网页阅读(steelBrowser)。 */
    fun setNetworkAccess(enabled: Boolean) {
        _enabledTools.value = _enabledTools.value.copy(network = enabled, steelBrowser = enabled)
    }

    fun setUseThinking(enabled: Boolean) {
        _useThinking.value = enabled
    }

    fun setReasoningEffort(effort: String) {
        _reasoningEffort.value = effort
    }

    /** 选图后即时上传：先放 UPLOADING 占位条，成功回填 url 转 UPLOADED，失败置 FAILED。 */
    fun attachImage(uri: Uri) {
        val tempId = Ids.newFragmentId()
        viewModelScope.launch {
            val meta = withContext(dispatchers.io) { readImage(uri) }
            if (meta == null) {
                _error.value = "无法读取所选图片"
                return@launch
            }
            val (bytes, name, mime) = meta
            _pendingAttachments.update {
                it + FileInfo(
                    id = tempId,
                    name = name,
                    mimeType = mime,
                    sizeBytes = bytes.size.toLong(),
                    uploadStatus = UploadStatus.UPLOADING,
                )
            }
            runCatching { chatRepository.uploadImage(bytes, name, mime, conversationId) }
                .onSuccess { info ->
                    _pendingAttachments.update { list ->
                        list.map {
                            if (it.id == tempId) {
                                info.copy(
                                    id = tempId,
                                    uploadStatus = if (!info.url.isNullOrBlank() || !info.sandboxPath.isNullOrBlank()) {
                                        UploadStatus.UPLOADED
                                    } else {
                                        UploadStatus.FAILED
                                    },
                                )
                            } else {
                                it
                            }
                        }
                    }
                }
                .onFailure {
                    _pendingAttachments.update { list ->
                        list.map { if (it.id == tempId) it.copy(uploadStatus = UploadStatus.FAILED) else it }
                    }
                }
        }
    }

    fun removeAttachment(id: String) {
        _pendingAttachments.update { list -> list.filterNot { it.id == id } }
    }

    private fun readImage(uri: Uri): Triple<ByteArray, String, String>? = runCatching {
        val resolver = appContext.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        val mime = resolver.getType(uri) ?: "image/*"
        val name = queryDisplayName(uri) ?: "image_${System.currentTimeMillis()}.${mimeExt(mime)}"
        Triple(bytes, name, mime)
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private fun mimeExt(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        else -> "jpg"
    }

    fun send(text: String) {
        val content = text.trim()
        val hasReadyAttachment = _pendingAttachments.value.any {
            it.uploadStatus == UploadStatus.UPLOADED && (!it.url.isNullOrBlank() || !it.sandboxPath.isNullOrBlank())
        }
        if ((content.isEmpty() && !hasReadyAttachment) || backgroundStreams.isStreaming(sessionId)) return
        val modelId = resolvedModelId(_selectedModel.value, modelsFlow.value) ?: return
        _selectedModel.value = modelId
        _error.value = null

        viewModelScope.launch {
            val shouldGenerateTitle = chatRepository.messageCount(sessionId) == 0
            val titleSeed = content.ifBlank { _pendingAttachments.value.firstOrNull()?.name ?: "附件" }
            sessionRepository.ensure(sessionId, title = fallbackTitle(titleSeed), model = modelId)
            val now = System.currentTimeMillis()
            val ready = _pendingAttachments.value
                .filter { it.uploadStatus == UploadStatus.UPLOADED && (!it.url.isNullOrBlank() || !it.sandboxPath.isNullOrBlank()) }
                .map {
                    Attachment(
                        id = it.id,
                        name = it.name,
                        mimeType = it.mimeType ?: "image/*",
                        remoteUrl = it.url,
                        sandboxPath = it.sandboxPath,
                        label = attachmentLabel(it),
                        thumbnailUrl = it.url,
                        sizeBytes = it.sizeBytes,
                    )
                }
            val sandboxHint = buildSandboxHint(ready)
            val sendContent = sandboxHint?.let { appendHiddenSystemHint(content, it) } ?: content
            val messageMetadata = buildMap {
                if (sendContent != content) {
                    put("sendContent", sendContent)
                    put("displayContent", content)
                }
            }
            val userMsg = ChatMessage(
                messageId = Ids.newMessageId(),
                sessionId = sessionId,
                role = Role.USER,
                status = MessageStatus.COMPLETE,
                createdAt = now,
                updatedAt = now,
                rawText = content,
                fragments = if (content.isBlank()) {
                    emptyList()
                } else {
                    listOf(com.molagpt.app.core.model.MessageFragment.Text(Ids.newFragmentId(), content))
                },
                attachments = ready,
                metadata = messageMetadata,
            )
            chatRepository.persistUserMessage(userMsg)
            _pendingAttachments.value = emptyList()
            startStream(
                modelId = modelId,
                latestUserMessage = userMsg,
                titleUserMessage = titleSeed.takeIf { shouldGenerateTitle },
            )
        }
    }

    private fun appendHiddenSystemHint(content: String, hint: String): String =
        if (content.isBlank()) {
            "✝[系统提示: $hint]✝"
        } else {
            "$content\n\n✝[系统提示: $hint]✝"
        }

    private fun buildSandboxHint(attachments: List<Attachment>): String? {
        if (attachments.isEmpty()) return null
        val lines = attachments.mapIndexed { index, attachment ->
            val urlInfo = attachment.remoteUrl?.takeIf { it.isNotBlank() }?.let { "\n   公网URL: $it" } ?: ""
            "${index + 1}. ${attachment.typeLabel()}：${attachment.name} → Python访问路径: ${attachment.sandboxInputPath()}$urlInfo"
        }
        return "用户已上传以下文件到沙箱：\n${lines.joinToString("\n")}"
    }

    private fun Attachment.typeLabel(): String =
        if (mimeType.startsWith("image/")) "图片文件" else "数据文件"

    private fun Attachment.sandboxInputPath(): String {
        val fileName = sandboxPath
            ?.replace('\\', '/')
            ?.split('/')
            ?.lastOrNull { it.isNotBlank() }
            ?: name
        return "/input/$fileName"
    }

    private fun attachmentLabel(file: FileInfo): String =
        if (file.mimeType?.startsWith("image/") == true) "图片" else file.name.substringAfterLast('.', "文件").uppercase(Locale.US)

    /** 重发：保留旧答案为一个版本，重新生成一版并切到新版本（可在版本间切换）。 */
    fun regenerateLast() {
        val msgs = uiState.value.messages
        val lastAssistant = msgs.lastOrNull { it.role == Role.ASSISTANT } ?: return
        val modelId = uiState.value.selectedModelId ?: return
        _selectedModel.value = modelId
        // 已有版本则全部带上；否则把现有答案作为 v0。
        val prior = RetryAttempts.decode(lastAssistant.metadata[RetryAttempts.KEY_ATTEMPTS])
            .ifEmpty { listOf(attemptOf(lastAssistant)) }
        viewModelScope.launch {
            chatRepository.deleteMessagesFrom(sessionId, lastAssistant.createdAt)
            startStream(modelId, priorAttempts = prior)
        }
    }

    /** 在重试版本间切换（delta = -1/+1）。改 in-flight 帧并落库,头部模型名随版本变化。 */
    fun navVersion(messageId: String, delta: Int) {
        val msg = uiState.value.messages.firstOrNull { it.messageId == messageId } ?: return
        val attempts = RetryAttempts.decode(msg.metadata[RetryAttempts.KEY_ATTEMPTS])
        if (attempts.size <= 1) return
        val current = msg.metadata[RetryAttempts.KEY_CURRENT]?.toIntOrNull() ?: attempts.lastIndex
        val next = (current + delta).coerceIn(0, attempts.lastIndex)
        if (next == current) return
        val v = attempts[next]
        val modelDisplayName = v.modelDisplayName
        val meta = msg.metadata.toMutableMap().apply {
            put(RetryAttempts.KEY_CURRENT, next.toString())
            if (modelDisplayName != null) put("modelDisplayName", modelDisplayName) else remove("modelDisplayName")
        }
        val updated = msg.copy(
            fragments = v.fragments,
            rawText = v.rawText,
            model = v.model,
            status = runCatching { MessageStatus.valueOf(v.status) }.getOrDefault(MessageStatus.COMPLETE),
            metadata = meta,
        )
        backgroundStreams.updateInFlight(sessionId, updated)
        viewModelScope.launch { chatRepository.updateMessage(updated) }
    }

    private fun attemptOf(m: ChatMessage): RetryAttempt = RetryAttempt(
        fragments = m.fragments,
        rawText = m.rawText,
        model = m.model,
        modelDisplayName = m.metadata["modelDisplayName"],
        status = m.status.name,
    )

    fun stop() {
        backgroundStreams.stop(sessionId)
    }

    private fun startStream(
        modelId: String,
        latestUserMessage: ChatMessage? = null,
        titleUserMessage: String? = null,
        priorAttempts: List<RetryAttempt> = emptyList(),
    ) {
        // 取代同会话上一条流由 backgroundStreams.start() 内部完成（取消旧 job + 停旧服务端流 + 落新任务记录）；
        // 此处不再额外 stop()，否则其异步 removeStreamTask 会与 start 的 persistStreamTask 竞争、误删新任务。
        // 拉历史 + 刚落库的用户消息作为上下文。
        val historySnapshot = uiState.value.messages.filter { it.role != Role.SYSTEM }
        val history = if (latestUserMessage == null) {
            historySnapshot
        } else {
            historySnapshot.filterNot { it.messageId == latestUserMessage.messageId } + latestUserMessage
        }
        val assistantId = Ids.newMessageId()
        val streamSessionId = Ids.newSessionId()
        val modelDisplayName = modelsFlow.value.firstOrNull { it.id == modelId }?.displayName ?: modelId
        val request = ChatRequest(
            modelId = modelId,
            modelDisplayName = modelDisplayName,
            messages = history,
            sessionId = sessionId,
            streamSessionId = streamSessionId,
            conversationId = conversationId,
            temperature = temperature,
            useThinking = _useThinking.value,
            reasoningEffort = _reasoningEffort.value,
            enabledTools = _enabledTools.value,
        )
        backgroundStreams.start(request, assistantId, throttleMs, priorAttempts)
        observeTitleAfterFirstTurn(assistantId, titleUserMessage)
        // 说明：流正常结束后 manager 保留最终 in-flight 帧；combine 的合并逻辑按 messageId 去重，
        // Room 落库的同一条消息不会与之重复显示，也避免“清空→回灌”的瞬时闪烁。
    }

    private fun observeTitleAfterFirstTurn(assistantId: String, firstUserMessage: String?) {
        if (firstUserMessage == null) return
        viewModelScope.launch {
            val assistant = backgroundStreams.observe(sessionId)
                .map { it.inFlight }
                .filter { it != null && it.messageId == assistantId && it.status == MessageStatus.COMPLETE }
                .first()
                ?: return@launch
            generateTitleAfterFirstTurn(firstUserMessage, assistant)
        }
    }

    private fun generateTitleAfterFirstTurn(firstUserMessage: String, assistantMessage: ChatMessage) {
        val assistantText = assistantMessage.rawText
            ?: assistantMessage.fragments
                ?.filterIsInstance<com.molagpt.app.core.model.MessageFragment.Text>()
                ?.joinToString("\n") { it.markdown }
            ?: return
        if (assistantText.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(dispatchers.io) {
                    chatRepository.generateTitle(sessionId, firstUserMessage, assistantText)
                }
            }.onSuccess { title ->
                if (title.isNotBlank()) sessionRepository.rename(sessionId, title)
            }
        }
    }

    private fun fallbackTitle(content: String): String =
        content.replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .let { if (it.length <= 25) it else it.take(25).trim() + "..." }
            .ifBlank { "无标题对话" }

    private fun resolvedModelId(candidate: String?, models: List<ProviderModel>): String? =
        candidate?.takeIf { id -> models.any { it.id == id } }
            ?: models.firstOrNull()?.id
            ?: candidate
}

private data class ChatControlState(
    val error: String?,
    val authExpired: Boolean,
    val enabledTools: EnabledTools,
    val useThinking: Boolean,
    val reasoningEffort: String,
)

private data class ChatUiMeta(
    val controls: ChatControlState,
    val pendingAttachments: List<FileInfo>,
    val isModelRefreshing: Boolean,
    val isLoadingHistory: Boolean,
)
