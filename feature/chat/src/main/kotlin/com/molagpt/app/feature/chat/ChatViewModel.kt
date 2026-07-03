package com.molagpt.app.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
import com.molagpt.app.core.model.ProviderIds
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.PromptVariables
import com.molagpt.app.core.model.RetryAttempt
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.SystemPromptComposer
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.storage.ChatRepository
import com.molagpt.app.core.storage.PersonaRepository
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
    private val personaRepository: PersonaRepository,
    private val syncEngine: SyncEngine,
    private val dispatchers: DispatcherProvider,
    private val modelsFlow: StateFlow<List<ProviderModel>>,
    private val modelRefreshingFlow: StateFlow<Boolean>,
    private val modelRefresher: suspend (ProviderKind) -> List<ProviderModel>,
    private val settingsFlow: StateFlow<com.molagpt.app.core.storage.AppSettings>,
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
    private val _conversationProviderId = MutableStateFlow<String?>(ProviderIds.MOLAGPT)
    private val _conversationProviderKind = MutableStateFlow(ProviderKind.MOLAGPT)
    private val _conversationPersonaId = MutableStateFlow<String?>(null)
    private val _conversationSystemPrompt = MutableStateFlow<String?>(null)
    private val _conversationSystemPromptMode = MutableStateFlow<String?>(null)
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
        viewModelScope.launch {
            restoreConversationModel()
        }
        // 懒加载：若该会话是云端「占位」(仅元数据)，进入时按需拉取消息正文。非占位/游客内部直接跳过。
        // onFetchStart 仅在「确认占位、即将发起网络请求」时回调 → 据此转圈；普通本地会话不会误闪。
        viewModelScope.launch {
            runCatching {
                syncEngine.loadConversationIfNeeded(sessionId) { _loadingHistory.value = true }
            }
            restoreConversationModel()
            _loadingHistory.value = false
        }
    }

    private val controls = combine(
        _error, _authExpired, _enabledTools, _useThinking, _reasoningEffort,
    ) { error, authExpired, enabledTools, thinking, effort ->
        ChatControlState(error, authExpired, enabledTools, thinking, effort)
    }

    /** 已启用的 MCP 服务器是否存在——门控对话内 MCP 工具开关，避免空配置时仍向模型暴露 MCP。 */
    private val hasMcpServersFlow: StateFlow<Boolean> = settingsFlow
        .map { s -> s.byokMcpServers.any { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val providerState = combine(
        _conversationProviderId, _conversationProviderKind,
    ) { providerId, providerKind -> ConversationProviderState(providerId, providerKind) }

    /** 应用级角色列表（包含内置 + 用户自定义），供选择器展示。 */
    val personas: StateFlow<List<Persona>> = personaRepository
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前会话绑定的角色（仅 BYOK 生效）。personaId 为空时回退内置「通用助手」；随角色编辑/删除自动刷新。 */
    val activePersona: StateFlow<Persona?> = combine(
        _conversationPersonaId, personas,
    ) { id, all ->
        val target = id ?: Persona.BUILTIN_DEFAULT_ID
        all.firstOrNull { it.id == target } ?: all.firstOrNull { it.id == Persona.BUILTIN_DEFAULT_ID }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 切换当前会话角色：更新内存态并写本地会话 personaId（仅 BYOK 会话使用）。 */
    fun selectPersona(personaId: String?) {
        _conversationPersonaId.value = personaId
        viewModelScope.launch { sessionRepository.updatePersona(sessionId, personaId) }
    }

    /** 当前会话标题（随重命名实时刷新）；空/缺省回退「新对话」。 */
    private val conversationTitleFlow: StateFlow<String> = sessionRepository.observe(sessionId)
        .map { it?.title?.takeIf { t -> t.isNotBlank() } ?: "新对话" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "新对话")

    private val uiMetaCore = combine(
        controls, modelRefreshingFlow, _loadingHistory, providerState, conversationTitleFlow,
    ) { controls, refreshing, loadingHistory, provider, title ->
        ChatUiMetaCore(controls, refreshing, loadingHistory, provider.providerId, provider.providerKind, title)
    }

    private val uiMeta = combine(uiMetaCore, _pendingAttachments) { meta, pending ->
        ChatUiMeta(
            controls = meta.controls,
            pendingAttachments = pending,
            isModelRefreshing = meta.isModelRefreshing,
            isLoadingHistory = meta.isLoadingHistory,
            providerId = meta.providerId,
            providerKind = meta.providerKind,
            title = meta.title,
        )
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
        val visibleModels = models.filter { it.providerKind == meta.providerKind && it.supportsChat }
        val selectedModel = selectedModelFor(model, meta.providerId, visibleModels)
        val selectedModelId = selectedModel?.id ?: normalizeSavedModelId(model)
        val inFlight = streamState.inFlight
        // 合并历史与 in-flight：若 in-flight 的 id 已在历史里（已落库），以 in-flight 为准覆盖。
        val merged = if (inFlight == null) history
        else (history.filterNot { it.messageId == inFlight.messageId } + inFlight)
        ChatUiState(
            sessionId = sessionId,
            title = meta.title,
            messages = merged,
            models = visibleModels,
            modelGroups = buildModelGroups(models),
            selectedModelId = selectedModelId,
            selectedModel = selectedModel,
            providerKind = meta.providerKind,
            hasMolaGptModels = models.any { it.providerKind == ProviderKind.MOLAGPT && it.supportsChat },
            hasByokModels = models.any { it.providerKind == ProviderKind.BYOK && it.supportsChat },
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

    /** 把全量模型按阵营→提供商分组：若用户已配置 BYOK，则 BYOK 各提供商置顶，MolaGPT 随后；否则仅显示 MolaGPT。 */
    private fun buildModelGroups(models: List<ProviderModel>): List<ModelGroup> {
        val chatModels = models.filter { it.supportsChat }
        val groups = mutableListOf<ModelGroup>()
        val byokGroups = chatModels.filter { it.providerKind == ProviderKind.BYOK }
            .groupBy { it.providerId }
            .map { (providerId, list) ->
                ModelGroup(
                    kind = ProviderKind.BYOK,
                    providerId = providerId,
                    title = "自定义 API · ${list.firstOrNull()?.providerName ?: providerId}",
                    models = list,
                )
            }
        val molaGroup = chatModels.filter { it.providerKind == ProviderKind.MOLAGPT }
            .takeIf { it.isNotEmpty() }
            ?.let { ModelGroup(ProviderKind.MOLAGPT, null, "MolaGPT", it) }
        groups.addAll(byokGroups)
        molaGroup?.let { groups.add(it) }
        return groups
    }

    fun selectModel(modelId: String, providerId: String? = null) {
        val chatModels = modelsFlow.value.filter { it.supportsChat }
        val selected = chatModels.firstOrNull { it.id == modelId && (providerId == null || it.providerId == providerId) }
            ?: chatModels.firstOrNull { it.id == modelId }
            ?: return
        _selectedModel.value = selected.id
        _conversationProviderId.value = selected.providerId
        _conversationProviderKind.value = selected.providerKind
        adaptThinkingStateTo(selected)
    }

    /**
     * 切换模型后把推理状态适配到新模型：
     * - 非推理模型（thinkingConfig==NONE 且不支持 reasoning_effort）：关闭推理。
     * - 有 effortLevels 的 kind：当前档位不在可选集时重置为该 kind 默认档位。
     * - 仅开/关的 kind（如 Kimi）：保留 useThinking，不强制档位。
     */
    private fun adaptThinkingStateTo(model: ProviderModel) {
        val tc = model.thinkingConfig
        val kind = tc?.kind ?: com.molagpt.app.core.model.ThinkingParamKind.NONE
        if (kind == com.molagpt.app.core.model.ThinkingParamKind.NONE) {
            if (!model.supportsReasoningEffort) {
                _useThinking.value = false
            } else if (_reasoningEffort.value !in listOf("low", "medium", "high")) {
                _reasoningEffort.value = "medium"
            }
            return
        }
        val levels = com.molagpt.app.core.model.ThinkingKinds.effortLevelsFor(kind)
        if (levels.isNotEmpty() && _reasoningEffort.value !in levels) {
            _reasoningEffort.value = com.molagpt.app.core.model.ThinkingKinds.defaultEffortFor(kind)
        }
    }

    fun refreshModels() {
        if (modelRefreshingFlow.value) return
        viewModelScope.launch {
            // 只刷新 MolaGPT 可用模型：refresh 内部按当前账户（登录走 registered_user_limits、
            // 游客走 guest_limits）过滤可见模型。BYOK 模型由用户在服务详情页精选管理、且注册表
            // 已响应式跟踪落库列表，刷新按钮不应改动它——故不再调 modelRefresher(BYOK)。
            // 成功后 registry 的 StateFlow 自动 emit，uiState 实时重组——无需手动 tick。
            runCatching {
                withContext(dispatchers.io) {
                    modelRefresher(ProviderKind.MOLAGPT)
                }
            }.onFailure { e ->
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

    fun setUseThinking(enabled: Boolean) {
        _useThinking.value = enabled
    }

    fun setReasoningEffort(effort: String) {
        _reasoningEffort.value = effort
    }

    /** 选取附件后即时处理：MolaGPT 走上传，BYOK 只在发送前临时转 data URL，落库永远只存轻量 URI。 */
    fun attachFile(uri: Uri) {
        val tempId = Ids.newFragmentId()
        viewModelScope.launch {
            val meta = withContext(dispatchers.io) { readFile(uri) }
            if (meta == null) {
                _error.value = "无法读取所选附件"
                return@launch
            }
            val (bytes, name, mime) = meta
            val isByokAttachment = _conversationProviderKind.value == ProviderKind.BYOK
            if (isByokAttachment && !isByokSupportedAttachmentMime(mime)) {
                _pendingAttachments.update {
                    it + FileInfo(
                        id = tempId,
                        name = name,
                        mimeType = mime,
                        sizeBytes = bytes.size.toLong(),
                        uploadStatus = UploadStatus.FAILED,
                    )
                }
                _error.value = "BYOK 支持图片、文本和 PDF 附件"
                return@launch
            }
            _pendingAttachments.update {
                it + FileInfo(
                    id = tempId,
                    name = name,
                    mimeType = mime,
                    sizeBytes = bytes.size.toLong(),
                    uploadStatus = UploadStatus.UPLOADING,
                )
            }
            if (isByokAttachment) {
                _pendingAttachments.update { list ->
                    list.map {
                        if (it.id == tempId) {
                            it.copy(
                                url = uri.toString(),
                                localPath = uri.toString(),
                                uploadStatus = UploadStatus.UPLOADED,
                            )
                        } else {
                            it
                        }
                    }
                }
                return@launch
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

    fun attachImage(uri: Uri) = attachFile(uri)

    fun removeAttachment(id: String) {
        _pendingAttachments.update { list -> list.filterNot { it.id == id } }
    }

    private fun readFile(uri: Uri): Triple<ByteArray, String, String>? = runCatching {
        val resolver = appContext.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = queryDisplayName(uri) ?: "attachment_${System.currentTimeMillis()}.${mimeExt(mime)}"
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
        mime.contains("json") -> "json"
        mime.contains("markdown") -> "md"
        mime.startsWith("text/") -> "txt"
        mime.contains("pdf") -> "pdf"
        else -> "bin"
    }

    fun send(text: String) {
        val content = text.trim()
        val hasReadyAttachment = _pendingAttachments.value.any {
            it.uploadStatus == UploadStatus.UPLOADED && (!it.url.isNullOrBlank() || !it.sandboxPath.isNullOrBlank())
        }
        if ((content.isEmpty() && !hasReadyAttachment) || backgroundStreams.isStreaming(sessionId)) return
        val visibleModels = modelsFlow.value.filter { it.providerKind == _conversationProviderKind.value && it.supportsChat }
        val selectedModel = selectedModelFor(_selectedModel.value, _conversationProviderId.value, visibleModels)
            ?: return
        val modelId = selectedModel.id
        _selectedModel.value = modelId
        _error.value = null

        // 非视觉 BYOK 模型上传图片时，必须开启外挂视觉工具，否则上游 API 会拒收 image_url。
        val hasImageAttachment = _pendingAttachments.value.any {
            it.uploadStatus == UploadStatus.UPLOADED &&
                it.mimeType?.startsWith("image/") == true &&
                (!it.url.isNullOrBlank() || !it.sandboxPath.isNullOrBlank())
        }
        if (selectedModel.providerKind == ProviderKind.BYOK &&
            hasImageAttachment &&
            selectedModel.supportsVision != true &&
            !settingsFlow.value.visionProxyEnabled
        ) {
            _error.value = "当前 BYOK 模型不支持视觉输入，请在「BYOK 工具」设置中开启外挂视觉，或切换到支持视觉的模型"
            return
        }

        viewModelScope.launch {
            val shouldGenerateTitle = chatRepository.messageCount(sessionId) == 0
            val titleSeed = content.ifBlank { _pendingAttachments.value.firstOrNull()?.name ?: "附件" }
            sessionRepository.ensure(
                sessionId = sessionId,
                title = fallbackTitle(titleSeed),
                model = modelId,
                providerId = selectedModel.providerId,
                providerKind = selectedModel.providerKind,
                personaId = _conversationPersonaId.value.takeIf { selectedModel.providerKind == ProviderKind.BYOK },
            )
            sessionRepository.updateModel(sessionId, modelId, selectedModel.providerId, selectedModel.providerKind)
            _conversationProviderId.value = selectedModel.providerId
            _conversationProviderKind.value = selectedModel.providerKind
            val now = System.currentTimeMillis()
            val storedReady = _pendingAttachments.value
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
            val requestReady = if (selectedModel.providerKind == ProviderKind.BYOK) {
                withContext(dispatchers.io) { hydrateByokAttachments(storedReady) }
            } else {
                storedReady
            }
            val attachmentHint = buildAttachmentHint(requestReady, selectedModel.providerKind)
            val sendContent = attachmentHint?.let { appendHiddenSystemHint(content, it) } ?: content
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
                attachments = storedReady,
                metadata = messageMetadata,
            )
            chatRepository.persistUserMessage(userMsg)
            syncEngine.schedulePush(sessionId)
            _pendingAttachments.value = emptyList()
            startStream(
                modelId = modelId,
                latestUserMessage = userMsg.copy(attachments = requestReady),
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

    private fun buildAttachmentHint(attachments: List<Attachment>, providerKind: ProviderKind): String? {
        if (providerKind == ProviderKind.BYOK) return buildByokAttachmentHint(attachments)
        return buildSandboxHint(attachments)
    }

    private fun buildSandboxHint(attachments: List<Attachment>): String? {
        if (attachments.isEmpty()) return null
        val lines = attachments.mapIndexed { index, attachment ->
            val urlInfo = attachment.remoteUrl?.takeIf { it.isNotBlank() }?.let { "\n   公网URL: $it" } ?: ""
            "${index + 1}. ${attachment.typeLabel()}：${attachment.name} → Python访问路径: ${attachment.sandboxInputPath()}$urlInfo"
        }
        return "用户已上传以下文件到沙箱：\n${lines.joinToString("\n")}"
    }

    private fun buildByokAttachmentHint(attachments: List<Attachment>): String? {
        val fileSections = attachments
            .filterNot { it.mimeType.startsWith("image/") }
            .mapIndexed { index, attachment ->
                buildString {
                    append(index + 1)
                    append(". ")
                    append(attachment.name)
                    append(" (")
                    append(attachment.mimeType)
                    append(")")
                    val text = decodeTextDataUrl(attachment.remoteUrl, attachment.mimeType)
                    if (!text.isNullOrBlank()) {
                        append("\n")
                        append(text.take(12_000))
                    } else if (isPdfMime(attachment.mimeType)) {
                        append("\nPDF 已随消息附加。")
                    }
                }
            }
        if (fileSections.isEmpty()) return null
        return "用户已附加以下文件内容：\n${fileSections.joinToString("\n\n")}"
    }

    private fun decodeTextDataUrl(value: String?, mimeType: String): String? {
        if (value.isNullOrBlank() || !isTextLikeMime(mimeType)) return null
        val comma = value.indexOf(',')
        if (!value.startsWith("data:", ignoreCase = true) || comma <= 5) return null
        val meta = value.substring(5, comma)
        if (!meta.contains(";base64", ignoreCase = true)) return null
        val encoded = value.substring(comma + 1)
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun isTextLikeMime(mimeType: String): Boolean {
        val lower = mimeType.lowercase(Locale.US)
        return lower.startsWith("text/") ||
            lower.contains("json") ||
            lower.contains("xml") ||
            lower.contains("csv") ||
            lower.contains("yaml") ||
            lower.contains("markdown") ||
            lower.contains("javascript")
    }

    private fun isPdfMime(mimeType: String): Boolean =
        mimeType.lowercase(Locale.US).contains("pdf")

    private fun isByokSupportedAttachmentMime(mimeType: String): Boolean =
        mimeType.startsWith("image/") || isTextLikeMime(mimeType) || isPdfMime(mimeType)

    private fun hydrateByokAttachments(attachments: List<Attachment>): List<Attachment> =
        attachments.map { attachment ->
            val source = attachment.remoteUrl?.takeIf { it.isNotBlank() } ?: return@map attachment
            if (source.startsWith("data:", ignoreCase = true) || source.startsWith("http", ignoreCase = true)) {
                return@map attachment
            }
            val bytes = readUriBytes(source) ?: return@map attachment.copy(remoteUrl = null, thumbnailUrl = null)
            val dataUrl = "data:${attachment.mimeType};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            attachment.copy(remoteUrl = dataUrl, thumbnailUrl = null)
        }

    private fun readUriBytes(value: String): ByteArray? = runCatching {
        val uri = Uri.parse(value)
        when (uri.scheme?.lowercase(Locale.US)) {
            "content", "android.resource" -> appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            "file" -> java.io.File(requireNotNull(uri.path)).takeIf { it.exists() }?.readBytes()
            else -> null
        }
    }.getOrNull()

    private fun Attachment.typeLabel(): String =
        when {
            mimeType.startsWith("image/") -> "图片文件"
            isPdfMime(mimeType) -> "PDF 文件"
            else -> "数据文件"
        }

    private fun Attachment.sandboxInputPath(): String {
        val fileName = sandboxPath
            ?.replace('\\', '/')
            ?.split('/')
            ?.lastOrNull { it.isNotBlank() }
            ?: name
        return "/input/$fileName"
    }

    private fun attachmentLabel(file: FileInfo): String =
        when {
            file.mimeType?.startsWith("image/") == true -> "图片"
            file.mimeType?.let { isPdfMime(it) } == true -> "PDF"
            else -> file.name.substringAfterLast('.', "文件").uppercase(Locale.US)
        }

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
            val selectedModel = uiState.value.selectedModel
            sessionRepository.updateModel(
                sessionId,
                modelId,
                selectedModel?.providerId ?: _conversationProviderId.value,
                selectedModel?.providerKind ?: _conversationProviderKind.value,
            )
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
        viewModelScope.launch {
            chatRepository.updateMessage(updated)
            syncEngine.schedulePush(sessionId)
        }
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
        viewModelScope.launch {
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
            val providerModel = modelsFlow.value.firstOrNull {
                it.id == modelId &&
                    it.providerKind == _conversationProviderKind.value &&
                    (_conversationProviderId.value == null || it.providerId == _conversationProviderId.value)
            } ?: modelsFlow.value.firstOrNull {
                it.id == modelId && it.providerKind == _conversationProviderKind.value
            }
            val modelDisplayName = providerModel?.displayName ?: modelId
            val requestTools = resolveRequestTools(providerModel)
            // 角色注入：仅 BYOK 模型，把当前角色的 system prompt（插值后）作为首条 system 消息 prepend。
            // 官方账号模型不注入（服务端已有系统提示 + 个性化记忆）。该 system 消息不落库，仅用于本次请求。
            val effectiveKind = providerModel?.providerKind ?: _conversationProviderKind.value
            val requestHistory = if (effectiveKind == ProviderKind.BYOK) {
                withContext(dispatchers.io) {
                    history.map { message ->
                        if (message.attachments.isEmpty()) message
                        else message.copy(attachments = hydrateByokAttachments(message.attachments))
                    }
                }
            } else {
                history
            }
            val messages = if (effectiveKind == ProviderKind.BYOK) {
                val sysText = SystemPromptComposer.compose(
                    personaPrompt = activePersona.value?.systemPrompt,
                    conversationPrompt = _conversationSystemPrompt.value,
                    mode = _conversationSystemPromptMode.value,
                    vars = buildPromptVariables(providerModel, modelDisplayName),
                )
                if (!sysText.isNullOrBlank()) listOf(systemMessage(sysText)) + requestHistory else requestHistory
            } else {
                requestHistory
            }
            val request = ChatRequest(
                modelId = modelId,
                modelDisplayName = modelDisplayName,
                providerId = providerModel?.providerId ?: _conversationProviderId.value ?: ProviderIds.MOLAGPT,
                providerKind = providerModel?.providerKind ?: _conversationProviderKind.value,
                messages = messages,
                sessionId = sessionId,
                streamSessionId = streamSessionId,
                conversationId = conversationId,
                temperature = temperature,
                useThinking = _useThinking.value,
                reasoningEffort = _reasoningEffort.value,
                enabledTools = requestTools,
            )
            backgroundStreams.start(request, assistantId, throttleMs, priorAttempts)
            observeTitleAfterFirstTurn(assistantId, titleUserMessage)
            // 说明：流正常结束后 manager 保留最终 in-flight 帧；combine 的合并逻辑按 messageId 去重，
            // Room 落库的同一条消息不会与之重复显示，也避免“清空→回灌”的瞬时闪烁。
        }
    }

    /** 角色注入用的 system 消息（仅本次请求，不落库）。各 BYOK provider 均读 rawText。 */
    private fun systemMessage(text: String): ChatMessage = ChatMessage(
        messageId = "persona-system-$sessionId",
        sessionId = sessionId,
        role = Role.SYSTEM,
        status = MessageStatus.COMPLETE,
        createdAt = 0L,
        updatedAt = 0L,
        rawText = text,
    )

    /** 组装 {{var}} 插值上下文：本地日期/时间 + 当前模型/服务商。username 首版暂不接（→「用户」）。 */
    private fun buildPromptVariables(providerModel: ProviderModel?, modelDisplayName: String): PromptVariables {
        val now = java.util.Date()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        return PromptVariables(
            date = date,
            time = time,
            datetime = "$date $time",
            modelDisplayName = modelDisplayName,
            modelId = providerModel?.id,
            providerName = providerModel?.providerName,
            username = null,
        )
    }

    /**
     * 计算本次请求实际启用的工具：以 composer 当前开关为基础，按阵营与模型能力裁剪，
     * 杜绝「设置里开了但当前阵营/模型不支持」造成的静默失效。
     * - MolaGPT：清零 mcp/vision/imageGeneration（wire 层本就不传，避免脏状态）。
     * - BYOK：清零 codeExecution（无执行路径）；工具类需模型 supportsToolCalling；
     *   network/网页拉取沿用 composer 开关；mcp/vision/imageGeneration **已无 composer 开关**，
     *   改由 BYOK 工具设置页实时驱动（visionProxyEnabled / imageGenEnabled / 已启用的 MCP 服务器），
     *   实时读 [settingsFlow] 而非创建时的 [_enabledTools] 快照——避免对话存在期间去设置页开启后不生效。
     */
    private fun resolveRequestTools(providerModel: ProviderModel?): EnabledTools {
        val enabled = _enabledTools.value
        return when (_conversationProviderKind.value) {
            ProviderKind.MOLAGPT -> enabled.copy(mcp = false, vision = false, imageGeneration = false)
            ProviderKind.BYOK -> {
                val canTool = providerModel?.supportsToolCalling == true
                val settings = settingsFlow.value
                enabled.copy(
                    codeExecution = false,
                    network = enabled.network && canTool,
                    steelBrowser = enabled.steelBrowser && canTool,
                    mcp = canTool && hasMcpServersFlow.value,
                    vision = settings.visionProxyEnabled && canTool,
                    imageGeneration = settings.imageGenEnabled && canTool,
                )
            }
        }
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
        // BYOK 没有 MolaGPT 的标题生成端点：用首条用户消息派生标题（会话行在新建时占位为「新对话」，这里改名）。
        if (_conversationProviderKind.value == ProviderKind.BYOK) {
            val title = fallbackTitle(firstUserMessage)
            if (title.isNotBlank()) {
                viewModelScope.launch { sessionRepository.rename(sessionId, title) }
            }
            return
        }
        val assistantText = assistantMessage.rawText
            ?: assistantMessage.fragments
                .filterIsInstance<com.molagpt.app.core.model.MessageFragment.Text>()
                .joinToString("\n") { it.markdown }
                .takeIf { it.isNotBlank() }
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

    private suspend fun restoreConversationModel() {
        val conversation = sessionRepository.get(sessionId) ?: return
        _conversationProviderId.value = conversation.providerId ?: ProviderIds.MOLAGPT
        _conversationProviderKind.value = conversation.providerKind
        _conversationPersonaId.value = conversation.personaId
        _conversationSystemPrompt.value = conversation.systemPrompt
        _conversationSystemPromptMode.value = conversation.systemPromptMode
        conversation.model?.takeIf { it.isNotBlank() }?.let { _selectedModel.value = it }
        // 恢复后把推理档位适配到该模型（模型列表可能已加载）。
        val model = modelsFlow.value.firstOrNull {
            it.id == _selectedModel.value && it.providerKind == _conversationProviderKind.value
        }
        model?.let { adaptThinkingStateTo(it) }
    }

    private fun selectedModelFor(
        candidate: String?,
        providerId: String?,
        models: List<ProviderModel>,
    ): ProviderModel? {
        val normalized = normalizeSavedModelId(candidate)
        if (normalized != null) {
            models.firstOrNull { it.id == normalized && providerId != null && it.providerId == providerId }?.let { return it }
            models.firstOrNull { it.id == normalized }?.let { return it }
        }
        return models.firstOrNull()
    }

    private fun normalizeSavedModelId(modelId: String?): String? = when (modelId?.trim()) {
        null, "" -> null
        "autoLLM" -> "auto"
        "default" -> defaultModelId ?: "auto"
        else -> modelId.trim()
    }
}

private data class ChatControlState(
    val error: String?,
    val authExpired: Boolean,
    val enabledTools: EnabledTools,
    val useThinking: Boolean,
    val reasoningEffort: String,
)

private data class ChatUiMetaCore(
    val controls: ChatControlState,
    val isModelRefreshing: Boolean,
    val isLoadingHistory: Boolean,
    val providerId: String?,
    val providerKind: ProviderKind,
    val title: String,
)

private data class ChatUiMeta(
    val controls: ChatControlState,
    val pendingAttachments: List<FileInfo>,
    val isModelRefreshing: Boolean,
    val isLoadingHistory: Boolean,
    val providerId: String?,
    val providerKind: ProviderKind,
    val title: String,
)

private data class ConversationProviderState(
    val providerId: String?,
    val providerKind: ProviderKind,
)
