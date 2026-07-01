package com.molagpt.app.feature.agentcontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.AgentPhase
import com.molagpt.app.core.model.RelayEnvelope
import com.molagpt.app.core.model.RelayConnectionState
import com.molagpt.app.core.model.RelayEvent
import com.molagpt.app.core.model.RelaySessionMeta
import com.molagpt.app.core.model.isBusy
import com.molagpt.app.core.model.phaseEnum
import com.molagpt.app.core.model.sortAtMs
import com.molagpt.app.core.network.AgentControlService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 选中会话的 UI 状态：元信息 + 折叠后的 transcript + 输入框 + 加载态。 */
data class AgentSessionUiState(
    val meta: RelaySessionMeta? = null,
    val modelOptions: List<AgentModelOption> = emptyList(),
    val blocks: List<AgentBlock> = emptyList(),
    val loading: Boolean = false,
    val input: String = "",
) {
    val phase: AgentPhase get() = meta?.phaseEnum ?: AgentPhase.Idle
}

private const val PendingOptionTtlMs = 12_000L

/** 桌面每 10s 发一次 machine snapshot；超过这个窗口不再显示“已连接桌面端”。 */
private const val MachineSnapshotTimeoutMs = 45_000L

private data class PendingOptions(
    val modelSet: Boolean = false,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val permissionMode: Int? = null,
    val approvalPolicySet: Boolean = false,
    val approvalPolicy: Int? = null,
    val expiresAtMs: Long = 0L,
) {
    val hasAny: Boolean get() =
        modelSet || reasoningEffort != null || permissionMode != null || approvalPolicySet
}

/**
 * Agent 控制 ViewModel——手机作为桌面 agent 会话的瘦遥控器。
 *
 * - [sessions]：账号下会话列表（UI 按 workspace 分 Projects/Chats）。桌面离线也能读。
 * - [selected]：当前会话的 transcript（历史快照一次性折叠；活跃会话继续按 relay 批次追更）。
 * - 控制：[send] / [interrupt] / [newSession] / [closeSelected] 入队命令（桌面命令流在线时执行）。
 *
 * relay 是**粗粒度快照**：答复在回合末整段到达，无逐 token 打字机效果（见计划「风险/取舍」）。
 */
class AgentControlViewModel(
    private val service: AgentControlService,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<RelaySessionMeta>>(emptyList())
    val sessions: StateFlow<List<RelaySessionMeta>> = _sessions.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _connectionState = MutableStateFlow(RelayConnectionState.Connecting)
    val connectionState: StateFlow<RelayConnectionState> = _connectionState.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _selected = MutableStateFlow(AgentSessionUiState())
    val selected: StateFlow<AgentSessionUiState> = _selected.asStateFlow()

    private val reducer = AgentTranscriptReducer()
    private var streamJob: Job? = null
    private val pendingOptions = mutableMapOf<String, PendingOptions>()

    init {
        Logger.d("AgentControlVM", "ViewModel created, calling refresh()")
        refresh()
    }

    /** 手动触发重连。 */
    fun reconnect() {
        if (_connectionState.value == RelayConnectionState.Connecting ||
            _connectionState.value == RelayConnectionState.Reconnecting
        ) return
        _connectionState.value = RelayConnectionState.Reconnecting
        refresh()
    }


    /**
     * 判断桌面端是否在线：根据会话的 `updatedAtMs`。
     * 桌面每 10 秒上报一次 machine snapshot，会更新所有会话的时间戳。
     * 超过 [MachineSnapshotTimeoutMs] 没有任何新 snapshot，就认为桌面端已断开。
     */
    private fun isDesktopOnline(sessions: List<RelaySessionMeta>): Boolean {
        val now = System.currentTimeMillis()
        return sessions.any { now - it.updatedAtMs < MachineSnapshotTimeoutMs }
    }

    /** 重新拉取会话列表（下拉刷新 / 进入页面 / 重连）。 */
    fun refresh() {
        Logger.d("AgentControlVM", "refresh() called")
        viewModelScope.launch {
            _refreshing.value = true
            Logger.d("AgentControlVM", "calling service.listSessions()")
            val list = service.listSessions()
                .sortedByDescending { it.sortAtMs }
                .map(::applyPendingOptions)
            Logger.d("AgentControlVM", "listSessions returned ${list.size} sessions")

            _sessions.value = list
            // 若当前选中的会话 meta 有更新（phase 等），同步进 selected。
            _selectedId.value?.let { id ->
                list.firstOrNull { it.conversationId == id }?.let { fresh ->
                    _selected.update { it.copy(meta = fresh, modelOptions = modelOptionsFor(fresh, list)) }
                }
            }
            _refreshing.value = false

            // 更新连接状态：根据桌面端最近的 snapshot 上报判断
            when {
                // 有新鲜 machine snapshot → 桌面在线
                isDesktopOnline(list) -> {
                    _connectionState.value = RelayConnectionState.Connected
                }
                // 有会话但都是老的 → 显示离线（说明历史会话在服务器，但桌面没连）
                list.isNotEmpty() -> {
                    _connectionState.value = RelayConnectionState.Disconnected
                }
                // 没会话：可能是新用户，也可能是服务器问题，保持 Reconnecting 状态
                else -> {
                    _connectionState.value = RelayConnectionState.Disconnected
                }
            }
        }
    }

    /** 选中一个会话：历史先按快照一次折叠，只有活跃会话继续追增量。 */
    fun select(meta: RelaySessionMeta) {
        if (_selectedId.value == meta.conversationId) return
        subscribe(meta)
    }

    private fun subscribe(meta: RelaySessionMeta) {
        val mergedMeta = applyPendingOptions(meta)
        streamJob?.cancel()
        reducer.reset()
        _selectedId.value = mergedMeta.conversationId
        _selected.value = AgentSessionUiState(
            meta = mergedMeta,
            modelOptions = modelOptionsFor(mergedMeta, _sessions.value),
            loading = true,
        )
        streamJob = viewModelScope.launch {
            val snapshot = runCatching {
                service.eventHistory(mergedMeta.conversationId, sinceSeq = 0L)
            }.getOrElse { ex ->
                Logger.w("AgentControlVM", "event history load failed: ${ex.message}")
                emptyList()
            }
            applyRelaySnapshot(mergedMeta.conversationId, snapshot)

            if (!shouldFollowLive(mergedMeta)) return@launch

            service.streamEventBatches(mergedMeta.conversationId, sinceSeq = reducer.lastSeq)
                .collect { batch ->
                    applyRelayBatch(mergedMeta.conversationId, batch)
                }
        }
    }

    private fun applyRelaySnapshot(sessionId: String, events: List<RelayEnvelope>) {
        reducer.reset()
        applyRelayBatch(sessionId, events)
    }

    private fun applyRelayBatch(sessionId: String, batch: List<RelayEnvelope>) {
        var changed = false
        var phase: AgentPhase? = null
        for (env in batch) {
            if (reducer.apply(env)) changed = true
            when (env.event) {
                is RelayEvent.TurnDone -> phase = AgentPhase.Completed
                is RelayEvent.TurnFailed -> phase = AgentPhase.Failed
                else -> Unit
            }
        }
        phase?.let { applyPhase(sessionId, it) }
        _selected.update { state ->
            if (changed) state.copy(blocks = reducer.snapshot(), loading = false)
            else if (state.loading) state.copy(loading = false) else state
        }
    }

    private fun shouldFollowLive(meta: RelaySessionMeta): Boolean =
        meta.isBusy || meta.phaseEnum == AgentPhase.Waiting

    private fun ensureLiveSubscription(sessionId: String) {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            service.streamEventBatches(sessionId, sinceSeq = reducer.lastSeq)
                .collect { batch -> applyRelayBatch(sessionId, batch) }
        }
    }

    /** 返回列表（停止当前订阅）。保留 [_selected] 内容，让返回转场期间会话页不至于闪空；
     * 下次 [subscribe] 会整体重置，不会串台。 */
    fun deselect() {
        streamJob?.cancel()
        streamJob = null
        _selectedId.value = null
    }

    fun updateInput(text: String) {
        _selected.update { it.copy(input = text) }
    }

    /** 发送一条用户提示。乐观清空输入框；答复由 relay 增量事件流回。 */
    fun send() {
        val id = _selectedId.value ?: return
        val text = _selected.value.input.trim()
        if (text.isEmpty()) return
        val busyMeta = _selected.value.meta?.copy(phase = AgentPhase.Running.ordinal, needsAttention = false)
        val optimisticBlocks = reducer.beginOptimisticTurn(text)
        _selected.update {
            it.copy(
                input = "",
                meta = busyMeta ?: it.meta,
                blocks = optimisticBlocks,
                loading = false,
            )
        }
        busyMeta?.let { patched -> patchSessionMeta(id) { patched } }
        ensureLiveSubscription(id)
        viewModelScope.launch {
            val accepted = service.sendPrompt(id, newCmdId(), text)
            if (!accepted) {
                refreshSelectedMeta(id)
            }
        }
    }

    /** 打断当前回合。 */
    fun interrupt() {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            service.interrupt(id, newCmdId())
        }
    }

    fun approvePermission(permissionId: String, choice: String) {
        val id = _selectedId.value ?: return
        if (permissionId.isBlank()) return
        // Optimistic local feedback: collapse the card's buttons immediately. The
        // relay sends no "resolved" event, so without this the card looks dead even
        // though the choice was delivered to the desktop.
        reducer.resolvePermission(permissionId, choice)?.let { blocks ->
            _selected.update { it.copy(blocks = blocks) }
        }
        ensureLiveSubscription(id) // keep streaming so the post-approval tool output flows back
        viewModelScope.launch {
            service.approve(id, newCmdId(), permissionId, choice)
        }
    }

    fun switchModel(model: String?) {
        val id = _selectedId.value ?: return
        rememberPendingOptions(id) {
            it.copy(modelSet = true, model = model, expiresAtMs = pendingOptionExpiry())
        }
        patchSessionMeta(id) { it.copy(model = model) }
        viewModelScope.launch {
            val accepted = service.switchOptions(id, newCmdId(), model = model ?: AgentDefaultModelLabel)
            if (accepted) {
                refreshSelectedMetaSoon(id)
            } else {
                clearPendingOptions(id)
                refreshSelectedMeta(id)
            }
        }
    }

    fun switchReasoningEffort(reasoningEffort: String) {
        val id = _selectedId.value ?: return
        rememberPendingOptions(id) {
            it.copy(reasoningEffort = reasoningEffort, expiresAtMs = pendingOptionExpiry())
        }
        patchSessionMeta(id) { it.copy(reasoningEffort = reasoningEffort) }
        viewModelScope.launch {
            val accepted = service.switchOptions(id, newCmdId(), reasoningEffort = reasoningEffort)
            if (accepted) {
                refreshSelectedMetaSoon(id)
            } else {
                clearPendingOptions(id)
                refreshSelectedMeta(id)
            }
        }
    }

    fun switchPermissionMode(permissionMode: Int) {
        val id = _selectedId.value ?: return
        rememberPendingOptions(id) {
            it.copy(permissionMode = permissionMode, expiresAtMs = pendingOptionExpiry())
        }
        patchSessionMeta(id) { it.copy(permissionMode = permissionMode) }
        viewModelScope.launch {
            val accepted = service.switchOptions(id, newCmdId(), permissionMode = permissionMode)
            if (accepted) {
                refreshSelectedMetaSoon(id)
            } else {
                clearPendingOptions(id)
                refreshSelectedMeta(id)
            }
        }
    }

    fun switchApprovalPolicy(approvalPolicy: Int) {
        val id = _selectedId.value ?: return
        rememberPendingOptions(id) {
            it.copy(approvalPolicySet = true, approvalPolicy = approvalPolicy, expiresAtMs = pendingOptionExpiry())
        }
        patchSessionMeta(id) { it.copy(approvalPolicy = approvalPolicy) }
        viewModelScope.launch {
            val accepted = service.switchOptions(id, newCmdId(), approvalPolicy = approvalPolicy)
            if (accepted) {
                refreshSelectedMetaSoon(id)
            } else {
                clearPendingOptions(id)
                refreshSelectedMeta(id)
            }
        }
    }

    /**
     * 新建会话：客户端预生成 id，发 New 命令，并**乐观订阅**该 id——桌面用同 id 建 session 后，
     * meta + 事件经同一 relay 流回。`workingDirectory` 为空即 Quick Chat（桌面建 rootless 目录）。
     *
     * 建会话前检查连接状态：离线时先触发一次重连尝试，拿到连接后再发 New 命令。
     */
    fun newSession(
        backendId: String,
        workingDirectory: String?,
        title: String? = null,
        model: String? = null,
    ) {
        if (_connectionState.value == RelayConnectionState.Disconnected) {
            reconnect()
        }
        val sessionId = UUID.randomUUID().toString()
        val placeholder = RelaySessionMeta(
            conversationId = sessionId,
            backendId = backendId,
            title = title ?: "新会话",
            workingDirectory = workingDirectory.orEmpty(),
            workspaceName = if (workingDirectory.isNullOrBlank()) null
            else workingDirectory.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'),
            model = model,
            // 新建即可发送：占位用 Idle，而不是 Spawning。Spawning 会被 isBusy 当成"忙"，
            // 让输入区误显示成"停止/已发送"。真正的 spawning 只在首次发消息时由桌面回传。
            phase = AgentPhase.Idle.ordinal,
        )
        _sessions.update { listOf(placeholder) + it }
        subscribe(placeholder)
        viewModelScope.launch {
            val accepted = service.createSession(
                sessionId = sessionId,
                cmdId = newCmdId(),
                backendId = backendId,
                workingDirectory = workingDirectory,
                title = title,
                model = model,
            )
            // 桌面建好后回传真实 meta（工作区/标题等）；拉几次把占位对齐成真态。
            if (accepted) refreshSelectedMetaSoon(sessionId)
        }
    }

    /** 关闭当前会话。 */
    fun closeSelected() {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            service.close(id, newCmdId())
            deselect()
            refresh()
        }
    }

    private fun newCmdId(): String = "app-" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun pendingOptionExpiry(): Long = System.currentTimeMillis() + PendingOptionTtlMs

    private fun rememberPendingOptions(
        sessionId: String,
        update: (PendingOptions) -> PendingOptions,
    ) {
        val current = pendingOptions[sessionId] ?: PendingOptions()
        val next = update(current)
        if (next.hasAny) pendingOptions[sessionId] = next else pendingOptions.remove(sessionId)
    }

    private fun clearPendingOptions(sessionId: String) {
        pendingOptions.remove(sessionId)
    }

    private fun patchSessionMeta(sessionId: String, patch: (RelaySessionMeta) -> RelaySessionMeta) {
        _sessions.update { list ->
            list.map { meta -> if (meta.conversationId == sessionId) patch(meta) else meta }
        }
        _selected.update { state ->
            val meta = state.meta
            if (meta?.conversationId == sessionId) {
                val patched = patch(meta)
                state.copy(meta = patched, modelOptions = modelOptionsFor(patched, _sessions.value))
            } else {
                state
            }
        }
    }

    private suspend fun refreshSelectedMetaSoon(sessionId: String) {
        delay(800)
        refreshSelectedMeta(sessionId)
        delay(1600)
        refreshSelectedMeta(sessionId)
        delay(PendingOptionTtlMs)
        refreshSelectedMeta(sessionId)
    }

    private suspend fun refreshSelectedMeta(sessionId: String) {
        val list = service.listSessions()
            .sortedByDescending { it.sortAtMs }
            .map(::applyPendingOptions)
        _sessions.value = list
        val fresh = list.firstOrNull { it.conversationId == sessionId } ?: return
        _selected.update { state ->
            if (state.meta?.conversationId == sessionId) {
                state.copy(meta = fresh, modelOptions = modelOptionsFor(fresh, list))
            } else {
                state
            }
        }
    }

    private fun applyPendingOptions(meta: RelaySessionMeta): RelaySessionMeta {
        var pending = pendingOptions[meta.conversationId] ?: return meta
        if (pending.expiresAtMs <= System.currentTimeMillis()) {
            pendingOptions.remove(meta.conversationId)
            return meta
        }

        var merged = meta
        if (pending.modelSet) {
            if (sameModel(meta.model, pending.model)) {
                pending = pending.copy(modelSet = false)
            } else {
                merged = merged.copy(model = pending.model)
            }
        }

        pending.reasoningEffort?.let { effort ->
            if (meta.reasoningEffort.equals(effort, ignoreCase = true)) {
                pending = pending.copy(reasoningEffort = null)
            } else {
                merged = merged.copy(reasoningEffort = effort)
            }
        }

        pending.permissionMode?.let { mode ->
            if (meta.permissionMode == mode) {
                pending = pending.copy(permissionMode = null)
            } else {
                merged = merged.copy(permissionMode = mode)
            }
        }

        if (pending.approvalPolicySet) {
            if (meta.approvalPolicy == pending.approvalPolicy) {
                pending = pending.copy(approvalPolicySet = false)
            } else {
                merged = merged.copy(approvalPolicy = pending.approvalPolicy)
            }
        }

        if (pending.hasAny) pendingOptions[meta.conversationId] = pending else pendingOptions.remove(meta.conversationId)
        return merged
    }

    private fun sameModel(left: String?, right: String?): Boolean =
        normalizeModel(left) == normalizeModel(right)

    private fun normalizeModel(value: String?): String =
        value?.trim()?.takeUnless { it == AgentDefaultModelLabel }.orEmpty()

    private fun explicitModelId(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it != AgentDefaultModelLabel }

    private fun modelOptionsFor(
        meta: RelaySessionMeta?,
        sessions: List<RelaySessionMeta>,
    ): List<AgentModelOption> {
        // Prefer the live catalog the desktop discovered (Claude initialize.models /
        // Codex model/list): real display names + descriptions, exact switch ids.
        val catalog = meta?.availableModels.orEmpty()
        if (catalog.isNotEmpty()) {
            return buildList {
                // A backend-provided "default" entry already covers the CLI default;
                // only synthesize the sentinel when the catalog doesn't mark one.
                if (catalog.none { it.isDefault }) add(AgentModelOption(null, AgentDefaultModelLabel))
                catalog.forEach { m ->
                    add(AgentModelOption(
                        id = if (m.isDefault) null else m.id,
                        label = m.displayName.ifBlank { m.id },
                        description = m.description,
                    ))
                }
            }
        }

        // Fallback: default sentinel + model ids observed across same-backend sessions.
        val ids = LinkedHashSet<String>()
        explicitModelId(meta?.model)?.let(ids::add)
        sessions.asSequence()
            .filter { meta == null || it.backendId == meta.backendId }
            .mapNotNull { explicitModelId(it.model) }
            .forEach(ids::add)

        return buildList {
            add(AgentModelOption(null, AgentDefaultModelLabel))
            ids.forEach { id -> add(AgentModelOption(id, id)) }
        }
    }

    private fun applyPhase(sessionId: String, phase: AgentPhase) {
        _selected.update { state ->
            val meta = state.meta
            if (meta?.conversationId == sessionId) {
                val patched = meta.copy(phase = phase.ordinal, needsAttention = false)
                state.copy(meta = patched, modelOptions = modelOptionsFor(patched, _sessions.value))
            } else {
                state
            }
        }
        _sessions.update { list ->
            list.map { meta ->
                if (meta.conversationId == sessionId) {
                    meta.copy(phase = phase.ordinal, needsAttention = false)
                } else {
                    meta
                }
            }
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
