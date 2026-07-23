package com.molagpt.app.feature.agentcontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.AgentPhase
import com.molagpt.app.core.model.RelayEnvelope
import com.molagpt.app.core.model.RelayConnectionState
import com.molagpt.app.core.model.RelayEvent
import com.molagpt.app.core.model.RelayMachine
import com.molagpt.app.core.model.RelaySessionMeta
import com.molagpt.app.core.model.isBusy
import com.molagpt.app.core.model.isOnline
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

data class AgentCommandFailure(
    val commandId: String,
    val sessionTitle: String,
    val message: String,
)

/** 新建会话时请求的工作目录在桌面端不存在，被回退到了 Quick Chat 目录——
 *  回退本身是多机场景的刻意设计（别机的路径本机可能无效），但必须让用户看见。 */
data class WorkspaceFallbackNotice(
    val sessionTitle: String,
    val requestedPath: String,
    val actualPath: String,
)

private const val PendingOptionTtlMs = 12_000L

/** 桌面每 10s 发一次 machine snapshot；超过这个窗口不再显示“已连接桌面端”。 */
private const val MachineSnapshotTimeoutMs = 45_000L
private const val HubRefreshIntervalMs = 15_000L

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
    private val onTurnSubmitted: (RelaySessionMeta) -> Unit = {},
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<RelaySessionMeta>>(emptyList())
    val sessions: StateFlow<List<RelaySessionMeta>> = _sessions.asStateFlow()

    private val _machines = MutableStateFlow<List<RelayMachine>>(emptyList())
    val machines: StateFlow<List<RelayMachine>> = _machines.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _connectionState = MutableStateFlow(RelayConnectionState.Connecting)
    val connectionState: StateFlow<RelayConnectionState> = _connectionState.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _selected = MutableStateFlow(AgentSessionUiState())
    val selected: StateFlow<AgentSessionUiState> = _selected.asStateFlow()

    private val _commandFailure = MutableStateFlow<AgentCommandFailure?>(null)
    val commandFailure: StateFlow<AgentCommandFailure?> = _commandFailure.asStateFlow()

    private val _workspaceFallback = MutableStateFlow<WorkspaceFallbackNotice?>(null)
    val workspaceFallback: StateFlow<WorkspaceFallbackNotice?> = _workspaceFallback.asStateFlow()

    private val reducer = AgentTranscriptReducer()
    private var streamJob: Job? = null
    private var hubRefreshJob: Job? = null
    private val pendingOptions = mutableMapOf<String, PendingOptions>()
    private val surfacedCommandFailures = mutableSetOf<String>()

    /** 新建会话时请求的 cwd，等桌面回传真实 meta 后核对是否被回退。 */
    private val pendingWorkspaceChecks = mutableMapOf<String, String>()

    /** 从事件流推导出的选中会话 phase（含时间戳）。外部/桌面驱动的会话在服务器
     *  meta 上恒为 Idle，只有 transcript 能证明它在跑——meta 合并时在时效窗口内
     *  让"忙态推导"压过服务器的空闲值，避免 refresh 把 Running 盖回 Idle。 */
    private var derivedPhase: AgentPhase? = null
    private var derivedPhaseAtMs = 0L
    private var derivedPhaseSession: String? = null

    private companion object {
        /** 推导 phase 的保鲜窗口：超过后回落到服务器 meta（防外部会话死在半截
         *  没写 TurnDone 时永远显示忙）。 */
        private const val DerivedPhaseTtlMs = 90_000L
    }

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

    /** Hub 处于前台时轮询机器快照，避免已离线桌面长期显示为连接状态。 */
    fun setHubActive(active: Boolean) {
        if (!active) {
            hubRefreshJob?.cancel()
            hubRefreshJob = null
            return
        }
        if (hubRefreshJob?.isActive == true) return
        hubRefreshJob = viewModelScope.launch {
            // 立即刷一次：重进 agent 页面时列表里还是退出前的旧 meta（旧逻辑先睡
            // 15s 才首刷），这期间点进会话会拿到过期 phase。
            refresh()
            while (true) {
                delay(HubRefreshIntervalMs)
                refresh()
            }
        }
    }


    /**
     * 判断是否有桌面在线：优先看 machines 表心跳（空闲机器也能注册）；
     * 回退到会话 `updatedAtMs`（兼容还没上报 machines 的老桌面）。
     */
    private fun isDesktopOnline(sessions: List<RelaySessionMeta>, machines: List<RelayMachine>): Boolean {
        val now = System.currentTimeMillis()
        if (machines.any { it.isOnline(now, MachineSnapshotTimeoutMs) }) return true
        return sessions.any { now - it.updatedAtMs < MachineSnapshotTimeoutMs }
    }

    /** 重新拉取会话列表（下拉刷新 / 进入页面 / 重连）。 */
    fun refresh() {
        if (_refreshing.value) return
        Logger.d("AgentControlVM", "refresh() called")
        viewModelScope.launch {
            _refreshing.value = true
            Logger.d("AgentControlVM", "calling service.listSessions()")
            val snapshot = service.listSessions()
            val list = snapshot.sessions
                .sortedByDescending { it.sortAtMs }
                .map(::applyPendingOptions)
            Logger.d(
                "AgentControlVM",
                "listSessions returned ${list.size} sessions, ${snapshot.machines.size} machines",
            )

            _sessions.value = list
            _machines.value = snapshot.machines
            surfaceCommandFailure(list)
            surfaceWorkspaceFallback(list)
            // 若当前选中的会话 meta 有更新（phase 等），同步进 selected。
            _selectedId.value?.let { id ->
                list.firstOrNull { it.conversationId == id }?.let { raw ->
                    val fresh = mergeDerivedPhase(raw)
                    _selected.update { it.copy(meta = fresh, modelOptions = modelOptionsFor(fresh, list)) }
                }
            }
            _refreshing.value = false

            // 更新连接状态：根据桌面端最近的 snapshot / machines 心跳判断
            when {
                isDesktopOnline(list, snapshot.machines) -> {
                    _connectionState.value = RelayConnectionState.Connected
                }
                list.isNotEmpty() || snapshot.machines.isNotEmpty() -> {
                    _connectionState.value = RelayConnectionState.Disconnected
                }
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
        derivedPhase = null
        derivedPhaseSession = null
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

            // 始终跟随增量（轮询自带空闲退避）。以前只有 busy/waiting 才跟随——
            // 但桌面 UI / 外部终端驱动的会话在 relay 上恒为 Idle，进入后画面
            // 就冻结在快照上，看起来像"任务被终止"。现在无条件跟随，桌面端的
            // 历史再投影会把增长的 transcript 持续送过来。
            service.streamEventBatches(mergedMeta.conversationId, sinceSeq = reducer.lastSeq)
                .collect { batch ->
                    applyRelayBatch(mergedMeta.conversationId, batch)
                }
        }
        // 入场 meta 来自 hub 列表，可能是退出前的旧值——并行拉一次服务器最新
        // meta，让 phase/模型/模式在 ~1s 内对齐真实状态（与事件流推导互为兜底）。
        viewModelScope.launch { refreshSelectedMeta(mergedMeta.conversationId) }
    }

    private fun applyRelaySnapshot(sessionId: String, events: List<RelayEnvelope>) {
        reducer.reset()
        applyRelayBatch(sessionId, events)
    }

    private fun applyRelayBatch(sessionId: String, batch: List<RelayEnvelope>) {
        var changed = false
        for (env in batch) {
            if (reducer.apply(env)) changed = true
        }
        derivePhaseFromEvents(batch)?.let { applyPhase(sessionId, it) }
        if (syncActivityIndicator(sessionId)) changed = true
        _selected.update { state ->
            if (changed) state.copy(blocks = reducer.snapshot(), loading = false)
            else if (state.loading) state.copy(loading = false) else state
        }
    }

    /**
     * 把"正在处理"指示器对齐到选中会话的当前 phase。relay 不传 pending 事件，
     * 本地乐观 pending 又活不过一次 reset——所以由 phase 兜底补/撤（见 reducer
     * 的 syncActivityIndicator）。仅作用于当前选中会话。
     */
    private fun syncActivityIndicator(sessionId: String): Boolean {
        if (_selectedId.value != sessionId) return false
        val meta = _selected.value.meta ?: return false
        if (meta.conversationId != sessionId) return false
        return reducer.syncActivityIndicator(busy = meta.isBusy)
    }

    /** meta 变化（服务器刷新 / 乐观置忙）后也要重算指示器，并把结果推进 UI。 */
    private fun refreshActivityIndicator(sessionId: String) {
        if (!syncActivityIndicator(sessionId)) return
        _selected.update { state ->
            if (state.meta?.conversationId == sessionId) state.copy(blocks = reducer.snapshot()) else state
        }
    }

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
        val notificationMeta = _selected.value.meta
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
        // 桌面要过一会儿才把 phase 发布成 Running，这期间 refreshSelectedMeta 会拉回
        // 服务器的 Idle 并抹掉忙态（连带指示器）。把乐观发送也登记为 derived phase。
        markDerivedPhase(id, AgentPhase.Running)
        busyMeta?.let { patched -> patchSessionMeta(id) { patched } }
        ensureLiveSubscription(id)
        // User-initiated foreground work must be registered before the network
        // round-trip, otherwise an immediate Home press can make Android reject
        // the later foreground-service launch.
        notificationMeta?.let(onTurnSubmitted)
        viewModelScope.launch {
            val accepted = service.sendPrompt(id, newCmdId(), text, machineId = selectedMachineId())
            if (accepted) {
                refreshSelectedMetaSoon(id)
            } else {
                refreshSelectedMeta(id)
            }
        }
    }

    /** 打断当前回合。 */
    fun interrupt() {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            service.interrupt(id, newCmdId(), machineId = selectedMachineId())
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
            service.approve(id, newCmdId(), permissionId, choice, machineId = selectedMachineId())
        }
    }

    fun switchModel(model: String?) {
        val id = _selectedId.value ?: return
        rememberPendingOptions(id) {
            it.copy(modelSet = true, model = model, expiresAtMs = pendingOptionExpiry())
        }
        patchSessionMeta(id) { it.copy(model = model) }
        viewModelScope.launch {
            val accepted = service.switchOptions(
                id, newCmdId(), model = model ?: AgentDefaultModelLabel, machineId = selectedMachineId(),
            )
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
            val accepted = service.switchOptions(
                id, newCmdId(), reasoningEffort = reasoningEffort, machineId = selectedMachineId(),
            )
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
            val accepted = service.switchOptions(
                id, newCmdId(), permissionMode = permissionMode, machineId = selectedMachineId(),
            )
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
            val accepted = service.switchOptions(
                id, newCmdId(), approvalPolicy = approvalPolicy, machineId = selectedMachineId(),
            )
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
     * [machineId] 指定目标桌面；多机账号下必填，否则中继无法路由。单机时可由调用方传入
     * 唯一在线机器 id。
     *
     * 建会话前检查连接状态：离线时先触发一次重连尝试，拿到连接后再发 New 命令。
     */
    fun newSession(
        backendId: String,
        workingDirectory: String?,
        title: String? = null,
        model: String? = null,
        machineId: String? = null,
    ) {
        if (_connectionState.value == RelayConnectionState.Disconnected) {
            reconnect()
        }
        val targetMachineId = machineId?.takeIf { it.isNotBlank() }
            ?: resolveDefaultMachineId()
        if (targetMachineId == null) {
            Logger.w("AgentControlVM", "newSession rejected: no target machine")
            return
        }
        val targetMachine = _machines.value.firstOrNull { it.id == targetMachineId }
        val sessionId = UUID.randomUUID().toString()
        val placeholder = RelaySessionMeta(
            conversationId = sessionId,
            backendId = backendId,
            title = title ?: "新会话",
            workingDirectory = workingDirectory.orEmpty(),
            workspaceName = if (workingDirectory.isNullOrBlank()) null
            else workingDirectory.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'),
            model = model,
            machineId = targetMachineId,
            machineName = targetMachine?.name,
            // 新建即可发送：占位用 Idle，而不是 Spawning。Spawning 会被 isBusy 当成"忙"，
            // 让输入区误显示成"停止/已发送"。真正的 spawning 只在首次发消息时由桌面回传。
            phase = AgentPhase.Idle.ordinal,
        )
        _sessions.update { listOf(placeholder) + it }
        if (!workingDirectory.isNullOrBlank()) {
            pendingWorkspaceChecks[sessionId] = workingDirectory
        }
        subscribe(placeholder)
        viewModelScope.launch {
            val accepted = service.createSession(
                sessionId = sessionId,
                cmdId = newCmdId(),
                backendId = backendId,
                workingDirectory = workingDirectory,
                title = title,
                model = model,
                machineId = targetMachineId,
            )
            // 桌面建好后回传真实 meta（工作区/标题等）；拉几次把占位对齐成真态。
            if (accepted) refreshSelectedMetaSoon(sessionId)
        }
    }

    /** 关闭当前会话。 */
    fun closeSelected() {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            service.close(id, newCmdId(), machineId = selectedMachineId())
            deselect()
            refresh()
        }
    }

    private fun selectedMachineId(): String? =
        _selected.value.meta?.machineId?.takeIf { it.isNotBlank() }

    /** 单机场景自动选中唯一机器；多机且未指定时返回 null（由 UI 强制选择）。 */
    private fun resolveDefaultMachineId(): String? {
        val online = _machines.value.filter { it.isOnline(timeoutMs = MachineSnapshotTimeoutMs) }
        if (online.size == 1) return online.first().id
        if (_machines.value.size == 1) return _machines.value.first().id
        // 从已有会话推断唯一机器
        val fromSessions = _sessions.value.mapNotNull { it.machineId?.takeIf(String::isNotBlank) }.distinct()
        if (fromSessions.size == 1) return fromSessions.first()
        return null
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
        val snapshot = service.listSessions()
        val list = snapshot.sessions
            .sortedByDescending { it.sortAtMs }
            .map(::applyPendingOptions)
        _sessions.value = list
        _machines.value = snapshot.machines
        surfaceCommandFailure(list)
        surfaceWorkspaceFallback(list)
        val fresh = list.firstOrNull { it.conversationId == sessionId }?.let(::mergeDerivedPhase) ?: return
        _selected.update { state ->
            if (state.meta?.conversationId == sessionId) {
                state.copy(meta = fresh, modelOptions = modelOptionsFor(fresh, list))
            } else {
                state
            }
        }
        // meta 可能刚把会话从 Idle 翻成 Running（或反之）——指示器跟着走。
        refreshActivityIndicator(sessionId)
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

    private fun surfaceCommandFailure(sessions: List<RelaySessionMeta>) {
        if (_commandFailure.value != null) return
        val failed = sessions.firstOrNull { !it.lastCommandError.isNullOrBlank() } ?: return
        val key = buildString {
            append(failed.conversationId)
            append(':')
            append(failed.lastCommandId.orEmpty())
            append(':')
            append(failed.lastCommandAtMs)
            append(':')
            append(failed.lastCommandError)
        }
        if (!surfacedCommandFailures.add(key)) return
        _commandFailure.value = AgentCommandFailure(
            commandId = failed.lastCommandId ?: "unknown",
            sessionTitle = failed.title.ifBlank { "远程会话" },
            message = failed.lastCommandError.orEmpty(),
        )
    }

    fun dismissCommandFailure() {
        _commandFailure.value = null
    }

    /** 桌面回传真实 meta 后核对：请求的目录是否被静默回退成了别的目录。 */
    private fun surfaceWorkspaceFallback(sessions: List<RelaySessionMeta>) {
        if (pendingWorkspaceChecks.isEmpty()) return
        val iterator = pendingWorkspaceChecks.entries.iterator()
        while (iterator.hasNext()) {
            val (sessionId, requested) = iterator.next()
            val meta = sessions.firstOrNull { it.conversationId == sessionId } ?: continue
            // 真实 meta 尚未从桌面落到 relay（仍是手机乐观占位的复读）时先不判定。
            if (meta.workingDirectory.isBlank()) continue
            iterator.remove()
            if (!samePath(requested, meta.workingDirectory)) {
                _workspaceFallback.value = WorkspaceFallbackNotice(
                    sessionTitle = meta.title.ifBlank { "新会话" },
                    requestedPath = requested,
                    actualPath = meta.workingDirectory,
                )
            }
        }
    }

    fun dismissWorkspaceFallback() {
        _workspaceFallback.value = null
    }

    private fun samePath(left: String, right: String): Boolean {
        fun norm(p: String) = p.trim().trimEnd('/', '\\').replace('\\', '/').lowercase()
        return norm(left) == norm(right)
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
            .filter {
                meta == null ||
                    (it.backendId == meta.backendId &&
                        if (meta.machineId.isNullOrBlank()) {
                            it.machineId.isNullOrBlank()
                        } else {
                            it.machineId == meta.machineId
                        })
            }
            .mapNotNull { explicitModelId(it.model) }
            .forEach(ids::add)

        return buildList {
            add(AgentModelOption(null, AgentDefaultModelLabel))
            ids.forEach { id -> add(AgentModelOption(id, id)) }
        }
    }

    /** 记下"本地已知的最新 phase"，供 [mergeDerivedPhase] 在保鲜期内压过服务器的滞后值。 */
    private fun markDerivedPhase(sessionId: String, phase: AgentPhase) {
        derivedPhase = phase
        derivedPhaseAtMs = System.currentTimeMillis()
        derivedPhaseSession = sessionId
    }

    private fun applyPhase(sessionId: String, phase: AgentPhase) {
        val attention = phase == AgentPhase.Waiting
        markDerivedPhase(sessionId, phase)
        _selected.update { state ->
            val meta = state.meta
            if (meta?.conversationId == sessionId) {
                val patched = meta.copy(phase = phase.ordinal, needsAttention = attention)
                state.copy(meta = patched, modelOptions = modelOptionsFor(patched, _sessions.value))
            } else {
                state
            }
        }
        _sessions.update { list ->
            list.map { meta ->
                if (meta.conversationId == sessionId) {
                    meta.copy(phase = phase.ordinal, needsAttention = attention)
                } else {
                    meta
                }
            }
        }
    }

    /** 服务器 meta 合并进选中会话前，套用仍在保鲜期内的忙态推导。 */
    private fun mergeDerivedPhase(fresh: RelaySessionMeta): RelaySessionMeta {
        val phase = derivedPhase ?: return fresh
        if (derivedPhaseSession != fresh.conversationId) return fresh
        if (phase != AgentPhase.Running && phase != AgentPhase.Waiting) return fresh
        if (System.currentTimeMillis() - derivedPhaseAtMs > DerivedPhaseTtlMs) return fresh
        // 服务器已经认为在忙/待审批就以服务器为准（bridge 拥有的回合两边一致）。
        if (fresh.isBusy || fresh.phaseEnum == AgentPhase.Waiting) return fresh
        return fresh.copy(phase = phase.ordinal, needsAttention = phase == AgentPhase.Waiting)
    }

    override fun onCleared() {
        streamJob?.cancel()
        hubRefreshJob?.cancel()
        super.onCleared()
    }
}

/**
 * 从一段事件流推导会话阶段：**最后一个有意义的事件决定状态**，而不是"批次里
 * 出现过 TurnDone 就算完成"。后者在重进会话时是错的——首屏快照重放整个
 * transcript，历史回合的 TurnDone 会把明明还在跑的会话判成 Completed（composer
 * 变空闲、停止按钮消失），尽管快照末尾就是进行中回合的工具卡。
 *
 * 终态事件（TurnDone/TurnFailed）之后再出现 userPrompt / 工具 / 快照 / 权限，
 * 说明新回合已在飞行中 → Running/Waiting。返回 null 表示批次里没有可判定事件
 * （保持现有 meta 不动）。
 */
internal fun derivePhaseFromEvents(batch: List<RelayEnvelope>): AgentPhase? {
    var phase: AgentPhase? = null
    for (env in batch) {
        when (env.event) {
            is RelayEvent.TurnDone -> phase = AgentPhase.Completed
            is RelayEvent.TurnFailed -> phase = AgentPhase.Failed
            is RelayEvent.UserPrompt -> phase = AgentPhase.Running
            is RelayEvent.ToolProgress -> phase = AgentPhase.Running
            is RelayEvent.AnswerSnapshot -> phase = AgentPhase.Running
            is RelayEvent.ThinkingSnapshot -> phase = AgentPhase.Running
            is RelayEvent.PermissionPrompt -> phase = AgentPhase.Waiting
            RelayEvent.Unknown -> Unit
        }
    }
    return phase
}
