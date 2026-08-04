package com.molagpt.app.feature.chat

import com.molagpt.app.core.common.chunkedTimeWindow
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.RetryAttempt
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.storage.ChatRepository
import com.molagpt.app.core.storage.StreamTaskRecord
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackgroundStreamState(
    val inFlight: ChatMessage? = null,
    val error: String? = null,
    val streamSessionId: String? = null,
    val active: Boolean = false,
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
) {
    /** 一次生成正常完成（COMPLETE）的事件，供完成通知消费。 */
    data class Completion(
        val sessionId: String,
        val conversationId: String,
        val modelDisplayName: String?,
    )

    private data class Task(
        val sessionId: String,
        val streamSessionId: String,
        val providerKind: ProviderKind,
        val job: Job,
    )

    private val states = ConcurrentHashMap<String, MutableStateFlow<BackgroundStreamState>>()
    private val tasks = ConcurrentHashMap<String, Task>()
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _completions = MutableSharedFlow<Completion>(extraBufferCapacity = 32)
    val completions: SharedFlow<Completion> = _completions.asSharedFlow()

    fun observe(sessionId: String): StateFlow<BackgroundStreamState> = stateFor(sessionId)

    fun isStreaming(sessionId: String): Boolean = tasks[sessionId]?.job?.isActive == true

    fun currentMessage(sessionId: String): ChatMessage? = stateFor(sessionId).value.inFlight

    fun updateInFlight(sessionId: String, message: ChatMessage) {
        stateFor(sessionId).update { state ->
            if (state.inFlight?.messageId == message.messageId) state.copy(inFlight = message) else state
        }
    }

    /**
     * 丢弃已完成的 in-flight 帧，之后完全以库为准。
     *
     * 编辑分支切换会整体换掉时间线并重建消息 id，而 UI 合并 in-flight 是按 messageId 去重的：
     * 残留的旧帧匹配不上任何一条历史，就会作为多余的一条挂在末尾。生成中不清（此时它是唯一数据源）。
     */
    fun clearCompletedInFlight(sessionId: String) {
        if (isStreaming(sessionId)) return
        stateFor(sessionId).update { state ->
            if (state.inFlight == null) state else state.copy(inFlight = null)
        }
    }

    fun start(
        request: ChatRequest,
        assistantMessageId: String,
        throttleMs: Long,
        priorAttempts: List<RetryAttempt> = emptyList(),
    ) {
        // 取代同会话上一条流：本地取消旧 job（不走 stop() 的异步 removeStreamTask，避免与下面的 persist 竞争）。
        val previous = tasks.remove(request.sessionId)
        previous?.job?.cancel()
        val previousStreamId = previous?.streamSessionId

        val state = stateFor(request.sessionId)
        state.update { it.copy(error = null, streamSessionId = request.streamSessionId, active = true) }
        val apiUrl = apiUrlResolver(request.providerId, request.modelId)

        // 先停旧服务端流、再持久化新任务，二者在同一协程里顺序执行，避免 remove/persist 乱序。
        scope.launch {
            if (
                previousStreamId != null &&
                previousStreamId != request.streamSessionId &&
                previous.providerKind == ProviderKind.MOLAGPT
            ) {
                runCatching { chatRepository.stop(previousStreamId) }
            }
            chatRepository.persistStreamTask(
                StreamTaskRecord(
                    sessionId = request.sessionId,
                    streamSessionId = request.streamSessionId,
                    conversationId = request.conversationId,
                    assistantMessageId = assistantMessageId,
                    modelId = request.modelId,
                    modelDisplayName = request.modelDisplayName,
                    providerId = request.providerId,
                    providerKind = request.providerKind,
                    apiUrl = apiUrl,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        val job = chatRepository.streamAssistant(request, assistantMessageId, priorAttempts)
            .chunkedTimeWindow(
                windowMillis = { StreamRenderPacing.windowMillis(throttleMs) },
                emitImmediately = {
                    StreamRenderPacing.windowMillis(throttleMs) <= DIRECT_RENDER_WINDOW_MS ||
                        it.hasRunningTool()
                },
            )
            .onEach { batch ->
                batch.lastOrNull()?.let { msg ->
                    state.value = BackgroundStreamState(
                        inFlight = msg,
                        error = null,
                        streamSessionId = request.streamSessionId,
                        active = true,
                    )
                }
            }
            .catch { e -> markError(request.sessionId, e) }
            .onCompletion {
                finishTask(request.sessionId, request.streamSessionId, request.conversationId, request.modelDisplayName)
            }
            .launchIn(scope)

        tasks[request.sessionId] = Task(request.sessionId, request.streamSessionId, request.providerKind, job)
        publishActiveCount()
    }

    /** 启动对账：续接一个进程死亡前未完成的任务（resume from offset=0，覆盖已完成/进行中两种）。 */
    fun resume(record: StreamTaskRecord) {
        if (isStreaming(record.sessionId)) return
        val state = stateFor(record.sessionId)
        state.update { it.copy(error = null, streamSessionId = record.streamSessionId, active = true) }

        val job = chatRepository.resumeAssistant(
            assistantMessageId = record.assistantMessageId,
            sessionId = record.sessionId,
            apiUrl = record.apiUrl,
            streamSessionId = record.streamSessionId,
            model = record.modelId,
            modelDisplayName = record.modelDisplayName,
        )
            .chunkedTimeWindow(
                windowMillis = { StreamRenderPacing.windowMillis(defaultThrottleMs) },
                emitImmediately = {
                    StreamRenderPacing.windowMillis(defaultThrottleMs) <= DIRECT_RENDER_WINDOW_MS ||
                        it.hasRunningTool()
                },
            )
            .onEach { batch ->
                batch.lastOrNull()?.let { msg ->
                    state.value = BackgroundStreamState(
                        inFlight = msg,
                        error = null,
                        streamSessionId = record.streamSessionId,
                        active = true,
                    )
                }
            }
            .catch { e -> markError(record.sessionId, e) }
            .onCompletion {
                finishTask(record.sessionId, record.streamSessionId, record.conversationId, record.modelDisplayName)
            }
            .launchIn(scope)

        tasks[record.sessionId] = Task(record.sessionId, record.streamSessionId, ProviderKind.MOLAGPT, job)
        publishActiveCount()
    }

    fun stop(sessionId: String) {
        val task = tasks.remove(sessionId) ?: return
        publishActiveCount()
        markStopped(sessionId)
        task.job.cancel()
        scope.launch {
            if (task.providerKind == ProviderKind.MOLAGPT) {
                chatRepository.stop(task.streamSessionId)
            }
            chatRepository.removeStreamTask(sessionId)
        }
    }

    private fun finishTask(
        sessionId: String,
        streamSessionId: String,
        conversationId: String,
        modelDisplayName: String?,
    ) {
        // 仅当本 job 仍是该会话的当前任务时才收尾——否则它已被新一轮 start 取代，不可误删新任务记录。
        val current = tasks[sessionId]
        if (current?.streamSessionId != streamSessionId) return
        tasks.remove(sessionId)
        publishActiveCount()
        stateFor(sessionId).update { it.copy(active = false) }
        scope.launch { chatRepository.removeStreamTask(sessionId) }
        if (stateFor(sessionId).value.inFlight?.status == MessageStatus.COMPLETE) {
            _completions.tryEmit(Completion(sessionId, conversationId, modelDisplayName))
        }
    }

    private fun markError(sessionId: String, e: Throwable) {
        // 抛出型错误（如 401 透传）必须把 in-flight 退出流式态，否则
        // isStreaming = active || inFlight.isStreaming 会卡在 true、输入框永久禁用。
        stateFor(sessionId).update { st ->
            st.copy(
                error = e.message ?: "出错了",
                active = false,
                inFlight = st.inFlight?.let { m ->
                    m.copy(status = MessageStatus.ERROR, metadata = m.metadata - "pending")
                },
            )
        }
    }

    private fun markStopped(sessionId: String) {
        stateFor(sessionId).update { state ->
            val current = state.inFlight ?: return@update state.copy(active = false)
            state.copy(
                inFlight = current.copy(
                    status = MessageStatus.STOPPED,
                    updatedAt = System.currentTimeMillis(),
                    metadata = current.metadata - "pending",
                ),
                active = false,
            )
        }
    }

    private fun publishActiveCount() {
        _activeCount.value = tasks.size
    }

    private fun stateFor(sessionId: String): MutableStateFlow<BackgroundStreamState> =
        states.getOrPut(sessionId) { MutableStateFlow(BackgroundStreamState()) }
}

private fun ChatMessage.hasRunningTool(): Boolean = fragments.any { fragment ->
    fragment is MessageFragment.ToolCall && fragment.status == ToolStatus.RUNNING
}

private const val DIRECT_RENDER_WINDOW_MS = 16L
