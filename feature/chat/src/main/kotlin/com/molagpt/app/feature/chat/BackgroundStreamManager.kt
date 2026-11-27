package com.molagpt.app.feature.chat

import com.molagpt.app.core.common.chunkedTimeWindow
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.RetryAttempt
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.storage.ChatRepository
import com.molagpt.app.core.storage.HistoryIdentity
import com.molagpt.app.core.storage.StreamTaskRecord
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackgroundStreamState(
    val inFlight: ChatMessage? = null,
    val error: String? = null,
    val streamSessionId: String? = null,
    val active: Boolean = false,
    val official: Boolean = false,
) {
    val isStreaming: Boolean get() = active || inFlight?.isStreaming == true
}

/**
 * Application-scoped owner for active chat streams.
 *
 * ViewModel 订阅本管理器、而非自己持有网络 job——这样切换会话/退到后台时流仍继续生成，
 * 前台服务托管同一管理器即可保活；进程被杀后则靠 [start] 落库的 [StreamTaskRecord]
 * 在下次启动对账续传（见 [resume]）。
 */
class BackgroundStreamManager(
    private val chatRepository: ChatRepository,
    private val scope: CoroutineScope,
    /** providerId + modelId → 相对 apiUrl（来自 ModelRegistry）；落库进任务记录，供被杀后续传直接复用。 */
    private val apiUrlResolver: (providerId: String?, modelId: String) -> String,
    private val defaultThrottleMs: Long = 16L,
    /**
     * 首轮回答完成后的自动标题。挂在这里而非 ViewModel，是因为本管理器持有 application scope——
     * 用户答案一出来就退出会话时，绑 viewModelScope 的标题请求会被取消。
     */
    private val titleGenerator: suspend (String, HistoryIdentity) -> Unit = { _, _ -> },
    /**
     * BYOK 回答正常完成后的记忆整理。挂在这里的理由与 [titleGenerator] 相同：
     * 需要 application scope 才能在用户退出会话后继续跑完。
     * 这里只是「回合结束」信号——是否真的发请求由整理器按窗口阈值决定。
     */
    private val memoryConsolidator: suspend (sessionId: String) -> Unit = {},
    private val onOfficialSettled: (String) -> Unit = {},
) {
    /** 一次生成正常完成（COMPLETE）的事件，供完成通知消费。 */
    data class Completion(
        val sessionId: String,
        val conversationId: String,
        val modelDisplayName: String?,
        val officialSession: HistoryIdentity?,
    )

    private class Task(
        val record: StreamTaskRecord,
        val identity: HistoryIdentity,
        val generateTitle: Boolean,
    ) {
        lateinit var owner: Job
        var worker: Job? = null
        var stopRequested = false
        val official get() = record.providerKind == ProviderKind.MOLAGPT
    }

    private val states = ConcurrentHashMap<String, MutableStateFlow<BackgroundStreamState>>()
    private val settledAt = mutableMapOf<String, Long>()
    private val gate = Any()
    private val tasks = mutableMapOf<String, Task>()
    private var signingOut: HistoryIdentity? = null
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _completions = MutableSharedFlow<Completion>(extraBufferCapacity = 32)
    val completions: SharedFlow<Completion> = _completions.asSharedFlow()

    fun observe(sessionId: String): StateFlow<BackgroundStreamState> = synchronized(gate) {
        pruneIdleStatesLocked(excludeSessionId = sessionId)
        stateFor(sessionId)
    }

    fun isStreaming(sessionId: String): Boolean = synchronized(gate) { sessionId in tasks }

    fun currentMessage(sessionId: String): ChatMessage? = states[sessionId]?.value?.inFlight

    fun updateInFlight(sessionId: String, message: ChatMessage) {
        states[sessionId]?.update { state ->
            if (state.inFlight?.messageId == message.messageId) state.copy(inFlight = message) else state
        }
    }

    /**
     * 丢弃已完成的 in-flight 帧，之后完全以库为准。
     *
     * 编辑分支切换会整体换掉时间线并重建消息 id，而 UI 合并 in-flight 是按 messageId 去重的：
     * 残留的旧帧匹配不上任何一条历史，就会作为多余的一条挂在末尾。生成中不清（此时它是唯一数据源）。
     */
    fun clearCompletedInFlight(sessionId: String) = synchronized(gate) {
        if (sessionId in tasks) return@synchronized
        val flow = states[sessionId] ?: return@synchronized
        flow.update { it.copy(inFlight = null) }
        if (flow.subscriptionCount.value == 0 && flow.value.error == null) {
            states.remove(sessionId)
            settledAt.remove(sessionId)
        }
    }

    internal fun trackedStateCount(): Int = states.size

    fun start(
        request: ChatRequest,
        assistantMessageId: String,
        throttleMs: Long,
        priorAttempts: List<RetryAttempt> = emptyList(),
        generateTitleOnFinish: Boolean = false,
        expected: HistoryIdentity = chatRepository.identity.value,
        bodyVersion: Long? = null,
    ): Boolean {
        val record = StreamTaskRecord(
            sessionId = request.sessionId,
            streamSessionId = request.streamSessionId,
            conversationId = request.conversationId,
            assistantMessageId = assistantMessageId,
            modelId = request.modelId,
            modelDisplayName = request.modelDisplayName,
            providerId = request.providerId,
            providerKind = request.providerKind,
            apiUrl = apiUrlResolver(request.providerId, request.modelId),
            createdAt = System.currentTimeMillis(),
        )
        return launchTask(record, expected, throttleMs, generateTitleOnFinish) {
            if (request.providerKind == ProviderKind.MOLAGPT && !chatRepository.persistStreamTask(record, expected, bodyVersion)) {
                error("当前对话已更新，请重试")
            }
            chatRepository.streamAssistant(request, assistantMessageId, priorAttempts, expected)
        }
    }

    fun resume(record: StreamTaskRecord, expected: HistoryIdentity) {
        if (record.providerKind != ProviderKind.MOLAGPT) return
        launchTask(record, expected, defaultThrottleMs, false) {
            chatRepository.resumeAssistant(
                assistantMessageId = record.assistantMessageId,
                sessionId = record.sessionId,
                apiUrl = record.apiUrl,
                streamSessionId = record.streamSessionId,
                model = record.modelId,
                modelDisplayName = record.modelDisplayName,
                scope = expected,
            )
        }
    }

    private fun launchTask(
        record: StreamTaskRecord,
        expected: HistoryIdentity,
        throttleMs: Long,
        generateTitle: Boolean,
        openStream: suspend () -> Flow<ChatMessage>,
    ): Boolean {
        val id = record.sessionId
        val task = Task(record, expected, generateTitle)
        synchronized(gate) {
            if (id in tasks || task.official &&
                (!expected.canReadOfficial || expected != chatRepository.identity.value || signingOut == expected)
            ) return false
            val state = stateFor(id)
            task.owner = scope.launch(start = CoroutineStart.LAZY) {
                coroutineScope {
                    var receivedFrame = false
                    val worker = launch(start = CoroutineStart.LAZY) {
                        try {
                            openStream().chunkedTimeWindow(
                                windowMillis = { StreamRenderPacing.windowMillis(throttleMs) },
                                emitImmediately = {
                                    StreamRenderPacing.windowMillis(throttleMs) <= DIRECT_RENDER_WINDOW_MS ||
                                        it.hasRunningTool()
                                },
                            ).collect { batch ->
                                val message = batch.lastOrNull() ?: return@collect
                                receivedFrame = true
                                synchronized(gate) {
                                    if (tasks[id] === task && !task.stopRequested &&
                                        (!task.official || expected == chatRepository.identity.value)
                                    ) state.update { it.copy(inFlight = message) }
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            synchronized(gate) {
                                if (tasks[id] === task && !task.stopRequested) {
                                    state.update { it.copy(
                                        error = error.message ?: "出错了",
                                        inFlight = it.inFlight?.copy(status = MessageStatus.ERROR, metadata = it.inFlight.metadata - "pending"),
                                    ) }
                                }
                            }
                        }
                    }
                    synchronized(gate) {
                        task.worker = worker
                        if (task.stopRequested) worker.cancel() else worker.start()
                    }
                    try {
                        worker.join()
                    } finally {
                        withContext(NonCancellable) {
                            if (task.official && expected == chatRepository.identity.value) {
                                val stopped = synchronized(gate) { task.stopRequested }
                                if (stopped) {
                                    try {
                                        withTimeoutOrNull(5_000) { chatRepository.stop(id, record.streamSessionId, expected) }
                                    } catch (error: Exception) {
                                        Logger.w("BackgroundStream", "停止服务端任务失败", error)
                                    }
                                }
                                if (!receivedFrame && state.value.error == null) {
                                    try {
                                        chatRepository.removeStreamTask(id, record.streamSessionId, expected)
                                    } catch (error: Exception) {
                                        Logger.w("BackgroundStream", "清理未启动的流任务失败", error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            tasks[id] = task
            settledAt.remove(id)
            state.value = BackgroundStreamState(streamSessionId = record.streamSessionId, active = true, official = task.official)
            _activeCount.value = tasks.size
            task.owner.invokeOnCompletion { cause -> finishTask(task, cause) }
        }
        task.owner.start()
        return true
    }

    fun stop(sessionId: String) {
        synchronized(gate) {
            val task = tasks[sessionId] ?: return
            task.stopRequested = true
            task.worker?.cancel()
        }
    }

    fun cancelOfficialTasks(expected: HistoryIdentity): List<Job> {
        val owners = synchronized(gate) {
            signingOut = expected
            tasks.values.filter { it.official && it.identity == expected }.map { it.owner }
        }
        owners.forEach { it.cancel() }
        return owners
    }

    fun cancelStaleOfficialTasks(current: HistoryIdentity): List<Job> {
        val owners = synchronized(gate) {
            tasks.values.filter { it.official && it.identity != current }.map { it.owner }
        }
        owners.forEach { it.cancel() }
        return owners
    }

    fun forgetHistory(ids: List<String>) = synchronized(gate) {
        (ids + states.filterValues { it.value.official }.keys).forEach {
            settledAt.remove(it)
            states.remove(it)?.value = BackgroundStreamState()
        }
    }

    private fun finishTask(task: Task, cause: Throwable?) {
        val record = task.record
        val id = record.sessionId
        val completed = synchronized(gate) {
            if (tasks[id] !== task) return
            val state = stateFor(id)
            val current = !task.official || task.identity == chatRepository.identity.value
            val stopped = task.stopRequested || cause is CancellationException
            state.update {
                if (!current) BackgroundStreamState()
                else it.copy(
                    active = false,
                    inFlight = it.inFlight?.let { message ->
                        if (stopped && message.status != MessageStatus.ERROR) {
                            message.copy(status = MessageStatus.STOPPED, metadata = message.metadata - setOf("pending", "pendingRecovery"))
                        } else message
                    },
                )
            }
            tasks.remove(id)
            _activeCount.value = tasks.size
            settledAt[id] = System.nanoTime()
            pruneIdleStatesLocked(excludeSessionId = id)
            current && !stopped && cause == null && state.value.inFlight?.status == MessageStatus.COMPLETE
        }
        val official = task.identity.takeIf { task.official }
        if (official != null && official == chatRepository.identity.value) onOfficialSettled(id)
        if (!completed) return
        _completions.tryEmit(Completion(id, record.conversationId, record.modelDisplayName, official))
        if (task.generateTitle) scope.launch { runCatching { titleGenerator(id, task.identity) } }
        if (!task.official) scope.launch { runCatching { memoryConsolidator(id) } }
    }

    /**
     * 惰性回收已落库且无任何观察者的流状态，避免长会话浏览后 inFlight 无限驻留。
     * 仍在观察中的会话保留 inFlight 供 Room Flow 无闪烁交接；无观察者的空状态直接移除，
     * 有已完成内容的无观察者状态最多保留 [MAX_IDLE_SETTLED_STATES] 条最新记录。
     */
    private fun pruneIdleStatesLocked(excludeSessionId: String? = null) {
        val settledUnsubscribed = ArrayList<Pair<String, Long>>()
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val (key, flow) = iterator.next()
            if (key == excludeSessionId || key in tasks || flow.subscriptionCount.value > 0) continue
            val value = flow.value
            if (value.inFlight == null && value.error == null) {
                iterator.remove()
                settledAt.remove(key)
            } else {
                settledUnsubscribed.add(key to (settledAt[key] ?: 0L))
            }
        }
        if (settledUnsubscribed.size > MAX_IDLE_SETTLED_STATES) {
            settledUnsubscribed.sortBy { it.second }
            val excess = settledUnsubscribed.size - MAX_IDLE_SETTLED_STATES
            for (i in 0 until excess) {
                val key = settledUnsubscribed[i].first
                states.remove(key)
                settledAt.remove(key)
            }
        }
    }

    private fun stateFor(sessionId: String): MutableStateFlow<BackgroundStreamState> =
        states.getOrPut(sessionId) { MutableStateFlow(BackgroundStreamState()) }
}

private const val MAX_IDLE_SETTLED_STATES = 4

private fun ChatMessage.hasRunningTool(): Boolean = fragments.any { fragment ->
    fragment is MessageFragment.ToolCall && fragment.status == ToolStatus.RUNNING
}

private const val DIRECT_RENDER_WINDOW_MS = 16L
