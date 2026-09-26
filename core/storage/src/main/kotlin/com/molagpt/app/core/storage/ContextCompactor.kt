package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.ContextCompactionMark
import com.molagpt.app.core.model.ContextCompactionPolicy
import com.molagpt.app.core.model.ContextCompactionProgress
import com.molagpt.app.core.model.ContextCompactionReason
import com.molagpt.app.core.model.ContextOverflow
import com.molagpt.app.core.model.ContextTokens
import com.molagpt.app.core.model.ModelPricing
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.calculateCostUsd
import com.molagpt.app.core.network.TextCompletion
import com.molagpt.app.core.storage.dao.ContextCheckpointDao
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.entity.ContextCheckpointEntity
import com.molagpt.app.core.storage.entity.ContextCompactionUsageEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BYOK 对话的上下文压缩。
 *
 * 发送前检查上下文是否达到模型窗口的 [ContextCompactionPolicy.TRIGGER_RATIO]，达到就把较早的
 * 对话总结成摘要，存成检查点；之后的请求里，检查点之前的消息由摘要代替。请求被服务商以超长
 * 拒绝时，压缩一次再重试。用户也可以随时手动压缩（[compactNow]）。
 *
 * 另外每轮都会精简较早轮次：过长的工具结果只留开头，图片改发文字占位（见 [ContextPlanner.slim]）。
 *
 * 压缩只作用于发出去的请求：消息本身一条不删不改，对话列表照常显示全部历史，
 * 只在压缩位置多一条记录。
 */
class ContextCompactor(
    private val checkpointDao: ContextCheckpointDao,
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider,
    /** 应用级作用域：压缩结束后收尾用，不随某个页面或某次回答结束。 */
    private val scope: CoroutineScope,
    /** 模型的上下文窗口（token）。 */
    private val windowOf: suspend (providerId: String, modelId: String) -> Int,
    private val summarize: suspend (
        providerId: String,
        modelId: String,
        system: String,
        prompt: String,
        maxOutputTokens: Int,
    ) -> TextCompletion,
    private val autoEnabled: () -> Boolean,
    /** 精简较早轮次的工具结果与图片。 */
    private val slimEnabled: () -> Boolean,
    /** 设置里指定的摘要模型 (providerId, modelId)；null 用当前对话模型。 */
    private val summaryModel: suspend () -> Pair<String, String>?,
    private val pricingOf: suspend (providerId: String, modelId: String) -> ModelPricing?,
    /** 超长报错里写明了真实窗口，且比已知的小。 */
    private val onWindowDiscovered: suspend (providerId: String, modelId: String, window: Int) -> Unit = { _, _, _ -> },
) {
    /** 本次请求实际要发的内容、其中生效的检查点，以及精简到了第几个用户轮。 */
    data class Prepared(val request: ChatRequest, val checkpointId: String?, val slimTurns: Int = 0)

    /** 生效检查点覆盖的消息：它们不会发出，组装请求时不必加载附件。 */
    data class Coverage(val checkpointId: String, val coveredIds: Set<String>)

    private data class Active(val checkpoint: ContextCheckpointEntity, val anchorIndex: Int)

    private data class Loaded(val timeline: List<ChatMessage>, val active: Active?)

    private sealed interface Outcome {
        data class Done(val summary: String, val inputTokens: Int?, val outputTokens: Int?) : Outcome
        data class Failed(val reason: String) : Outcome
        data object Cancelled : Outcome
    }

    private val _notices = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    /** (sessionId, 提示)。压缩失败时发出，对话照常继续。 */
    val notices: SharedFlow<Pair<String, String>> = _notices.asSharedFlow()

    private val _progress = MutableStateFlow<Map<String, ContextCompactionProgress>>(emptyMap())
    private val summarizing = ConcurrentHashMap<String, Job>()

    /** 会话里正在进行的压缩；没有时为 null。 */
    fun progress(sessionId: String): Flow<ContextCompactionProgress?> =
        _progress.map { it[sessionId] }.distinctUntilChanged()

    /** 停止会话里正在进行的摘要。发送前的自动压缩被停止时，这次照原样发送。 */
    fun cancel(sessionId: String) {
        summarizing[sessionId]?.cancel()
    }

    fun observeMarks(sessionId: String): Flow<List<ContextCompactionMark>> =
        checkpointDao.observeValid(sessionId).map { rows ->
            rows.map { row ->
                ContextCompactionMark(
                    id = row.id,
                    anchorMessageId = row.anchorMessageId,
                    summary = row.summary,
                    tokensBefore = row.tokensBefore,
                    tokensAfter = row.tokensAfter,
                    createdAt = row.createdAt,
                    reason = reasonOf(row.reason),
                )
            }
        }

    fun observeCostUsd(sessionId: String): Flow<Double> = checkpointDao.observeCostUsd(sessionId)

    suspend fun coverage(sessionId: String, history: List<ChatMessage>): Coverage? = withContext(dispatchers.io) {
        val loaded = load(sessionId, history)
        val active = loaded.active ?: return@withContext null
        Coverage(
            checkpointId = active.checkpoint.id,
            coveredIds = loaded.timeline.subList(0, active.anchorIndex + 1).mapTo(HashSet()) { it.messageId },
        )
    }

    /**
     * 套用已有检查点并精简较早轮次；上下文达到阈值时先压缩。进度见 [progress]。
     * 调用方本身已在 IO 线程，这里不再切换。
     */
    suspend fun prepare(request: ChatRequest): Prepared {
        val loaded = load(request.sessionId, request.messages)
        val applied = loaded.applyTo(request)
        val ready = loaded.slimmed(applied)
        if (!autoEnabled()) return ready
        val window = windowOf(request.providerId, request.modelId)
        val before = ContextPlanner.measure(applied.request, loaded.timeline, applied.checkpointId, ready.slimTurns)
        if (before.tokens < ContextCompactionPolicy.threshold(window)) return ready
        return compact(request, loaded, window, before, ContextCompactionReason.THRESHOLD) ?: ready
    }

    /**
     * 手动压缩：不必等到阈值。保留区按常规预算留；对话太短留不出来时只保留最后一轮。
     * 失败原因经 [notices] 发出。
     *
     * @param history 会话当前显示的消息。
     * @return 是否生成了新的检查点。
     */
    suspend fun compactNow(
        sessionId: String,
        providerId: String,
        modelId: String,
        history: List<ChatMessage>,
        rolePlay: Boolean,
    ): Boolean = withContext(dispatchers.io) {
        val request = ChatRequest(
            modelId = modelId,
            providerId = providerId,
            providerKind = com.molagpt.app.core.model.ProviderKind.BYOK,
            messages = history.filter { it.role != Role.SYSTEM },
            sessionId = sessionId,
            conversationId = "",
            rolePlay = rolePlay,
        )
        val loaded = load(sessionId, request.messages)
        val applied = loaded.applyTo(request)
        val ready = loaded.slimmed(applied)
        val window = windowOf(providerId, modelId)
        val before = ContextPlanner.measure(applied.request, loaded.timeline, applied.checkpointId, ready.slimTurns)
        compact(request, loaded, window, before, ContextCompactionReason.MANUAL) != null
    }

    /**
     * 请求被以超长拒绝：尽可能多地压缩后返回新请求；关闭了自动压缩或压不动时返回 null。
     * 报错里写明的窗口比已知的小时，以报错为准并记下来。
     */
    suspend fun recoverFromOverflow(request: ChatRequest, error: String): Prepared? {
        if (!autoEnabled()) return null
        var window = windowOf(request.providerId, request.modelId)
        ContextOverflow.declaredLimit(error)?.takeIf { it < window }?.let { declared ->
            runCatching { onWindowDiscovered(request.providerId, request.modelId, declared) }
            window = declared
        }
        val loaded = load(request.sessionId, request.messages)
        val applied = loaded.applyTo(request)
        val slimTurns = loaded.slimmed(applied).slimTurns
        val before = ContextPlanner.measure(applied.request, loaded.timeline, applied.checkpointId, slimTurns)
        return compact(request, loaded, window, before, ContextCompactionReason.OVERFLOW)
    }

    private suspend fun load(sessionId: String, messages: List<ChatMessage>): Loaded {
        val persisted = messageDao.idsBySession(sessionId).toHashSet()
        val timeline = ContextPlanner.timelineOf(messages, persisted)
        return Loaded(timeline, resolveActive(sessionId, timeline))
    }

    /**
     * 最新的一个仍然成立的检查点：锚点在这条时间线上、后面紧跟一条用户消息（或者后面还没有消息：
     * 刚手动把整段收进摘要），且锚点及之前的内容与压缩时一致。
     * 内容对不上的标为过期，对得上了（切回原分支）再恢复。
     */
    private suspend fun resolveActive(sessionId: String, timeline: List<ChatMessage>): Active? {
        if (timeline.isEmpty()) return null
        for (checkpoint in checkpointDao.bySession(sessionId)) {
            val anchor = timeline.indexOfFirst { it.messageId == checkpoint.anchorMessageId }
            if (anchor < 0) continue
            if (anchor < timeline.lastIndex && timeline[anchor + 1].role != Role.USER) continue
            val matches = ContextPlanner.digest(timeline.subList(0, anchor + 1)) == checkpoint.coveredDigest
            if (matches != !checkpoint.stale) checkpointDao.setStale(checkpoint.id, !matches)
            if (matches) return Active(checkpoint, anchor)
        }
        return null
    }

    private fun Loaded.slimTurns(): Int = if (slimEnabled()) ContextPlanner.slimTurns(timeline) else 0

    private fun Loaded.slimmed(applied: Prepared): Prepared {
        val turns = slimTurns()
        return applied.copy(request = ContextPlanner.slim(applied.request, timeline, turns), slimTurns = turns)
    }

    private fun Loaded.applyTo(request: ChatRequest): Prepared {
        // 锚点之后还没有消息时，这次请求里没有地方放摘要，照原样发。
        val active = active?.takeIf { it.anchorIndex < timeline.lastIndex }
            ?: return Prepared(request.copy(contextCheckpointId = null), null)
        val applied = ContextPlanner.apply(
            request = request,
            timeline = timeline,
            anchorIndex = active.anchorIndex,
            checkpointId = active.checkpoint.id,
            summary = active.checkpoint.summary,
        )
        return Prepared(applied, active.checkpoint.id)
    }

    private suspend fun compact(
        request: ChatRequest,
        loaded: Loaded,
        window: Int,
        before: ContextPlanner.Measurement,
        reason: ContextCompactionReason,
    ): Prepared? {
        val timeline = loaded.timeline
        val start = loaded.active?.let { it.anchorIndex + 1 } ?: 0
        fun cut(aggressive: Boolean) = ContextPlanner.firstKeptIndex(
            timeline = timeline,
            startIndex = start,
            keepTokens = ContextCompactionPolicy.keepRecentTokens(window),
            scale = before.scale,
            aggressive = aggressive,
            providerId = request.providerId,
            modelId = request.modelId,
        )
        val firstKept = when (reason) {
            ContextCompactionReason.THRESHOLD -> cut(aggressive = false)
            ContextCompactionReason.OVERFLOW -> cut(aggressive = true)
            ContextCompactionReason.MANUAL -> ContextPlanner.manualKeptIndex(
                timeline = timeline,
                startIndex = start,
                keepTokens = ContextCompactionPolicy.keepRecentTokens(window),
                scale = before.scale,
                providerId = request.providerId,
                modelId = request.modelId,
            )
        }
        if (firstKept == null) {
            if (reason == ContextCompactionReason.MANUAL) _notices.tryEmit(request.sessionId to "没有可压缩的内容")
            return null
        }

        val (targetProvider, targetModel) = summaryModel() ?: (request.providerId to request.modelId)
        val summaryWindow = if (targetProvider == request.providerId && targetModel == request.modelId) {
            window
        } else {
            windowOf(targetProvider, targetModel)
        }
        val sessionId = request.sessionId
        val id = UUID.randomUUID().toString()
        val started = ContextCompactionProgress(id, reason, startedAt = System.currentTimeMillis())
        _progress.update { it + (sessionId to started) }
        var finished = false
        try {
            val outcome = try {
                coroutineScope {
                    val job = async {
                        summarizeRange(
                            sessionId = sessionId,
                            messages = timeline.subList(start, firstKept),
                            previousSummary = loaded.active?.checkpoint?.summary,
                            providerId = targetProvider,
                            modelId = targetModel,
                            summaryWindow = summaryWindow,
                            rolePlay = request.rolePlay,
                            onStep = { step, total ->
                                _progress.update { current ->
                                    val entry = current[sessionId]?.takeIf { it.id == id } ?: return@update current
                                    current + (sessionId to entry.copy(step = step, total = total))
                                }
                            },
                        )
                    }
                    summarizing[sessionId] = job
                    try {
                        job.await()
                    } finally {
                        summarizing.remove(sessionId, job)
                    }
                }
            } catch (e: CancellationException) {
                // 调用方自己被取消（停止回答、离开页面）时照常抛出；只是摘要被停止则放弃这次压缩。
                currentCoroutineContext().ensureActive()
                Outcome.Cancelled
            }
            when (outcome) {
                is Outcome.Failed -> {
                    _notices.tryEmit(sessionId to "对话压缩失败：${outcome.reason}")
                    return null
                }
                Outcome.Cancelled -> return null
                is Outcome.Done -> Unit
            }
            outcome as Outcome.Done

            val anchor = firstKept - 1
            val slimTurns = loaded.slimTurns()
            val current = ContextPlanner.slim(loaded.applyTo(request).request, timeline, slimTurns)
            val next = ContextPlanner.slim(ContextPlanner.apply(request, timeline, anchor, id, outcome.summary), timeline, slimTurns)
            // 按「前后估算差」换算，而不是直接估算压缩后的请求：手动压缩时手里没有角色提示和工具定义，
            // 两边同样缺，相减就抵消了。整段收进摘要时摘要还没拼进任何消息，单独算上。
            val pendingSummary = if (anchor == timeline.lastIndex) ContextPlanner.summaryTokens(outcome.summary) else 0
            val saved = (
                (ContextPlanner.estimateRequest(current) - ContextPlanner.estimateRequest(next) - pendingSummary) *
                    before.scale
                ).toInt()
            val after = (before.tokens - saved).coerceAtLeast(0)
            val entity = ContextCheckpointEntity(
                id = id,
                sessionId = sessionId,
                anchorMessageId = timeline[anchor].messageId,
                summary = outcome.summary,
                coveredDigest = ContextPlanner.digest(timeline.subList(0, anchor + 1)),
                tokensBefore = before.tokens,
                tokensAfter = after,
                reason = reason.name,
                providerId = targetProvider,
                modelId = targetModel,
                inputTokens = outcome.inputTokens,
                outputTokens = outcome.outputTokens,
                stale = false,
                createdAt = System.currentTimeMillis(),
            )
            checkpointDao.upsert(entity)
            val mark = ContextCompactionMark(
                id = id,
                anchorMessageId = entity.anchorMessageId,
                summary = entity.summary,
                tokensBefore = entity.tokensBefore,
                tokensAfter = entity.tokensAfter,
                createdAt = entity.createdAt,
                reason = reason,
            )
            _progress.update { it + (sessionId to started.copy(result = mark)) }
            finished = true
            releaseWhenObserved(sessionId, id)
            return Prepared(next, id, slimTurns)
        } finally {
            if (!finished) release(sessionId, id)
        }
    }

    /** 记录从数据库读回、对话页也拿到之后，才撤掉顶替它的进度。 */
    private fun releaseWhenObserved(sessionId: String, id: String) {
        scope.launch {
            withTimeoutOrNull(RELEASE_TIMEOUT_MS) {
                checkpointDao.observeValid(sessionId).first { rows -> rows.any { it.id == id } }
            }
            delay(RELEASE_DELAY_MS)
            release(sessionId, id)
        }
    }

    private fun release(sessionId: String, id: String) {
        _progress.update { current -> if (current[sessionId]?.id == id) current - sessionId else current }
    }

    private fun reasonOf(name: String): ContextCompactionReason =
        ContextCompactionReason.entries.firstOrNull { it.name == name } ?: ContextCompactionReason.THRESHOLD

    /**
     * 按摘要模型的窗口把记录分段，逐段滚动更新同一份摘要。
     * 某段被以超长拒绝时把分段减半重来；总调用次数有上限，避免一次压缩无休止地花钱。
     */
    private suspend fun summarizeRange(
        sessionId: String,
        messages: List<ChatMessage>,
        previousSummary: String?,
        providerId: String,
        modelId: String,
        summaryWindow: Int,
        rolePlay: Boolean,
        onStep: (step: Int, total: Int) -> Unit,
    ): Outcome {
        val transcript = messages.map(ContextPlanner::transcript).filter { it.isNotBlank() }.joinToString("\n\n")
        if (transcript.isBlank()) return Outcome.Failed("没有可压缩的内容")
        val targetChars = (ContextTokens.estimate(transcript) / 10).coerceIn(MIN_SUMMARY_CHARS, MAX_SUMMARY_CHARS)
        val maxOutput = (targetChars * 2).coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
        var budget = maxOf(MIN_CHUNK_TOKENS, (summaryWindow * CHUNK_WINDOW_SHARE).toInt())
        val pricing = pricingOf(providerId, modelId)

        var summary = previousSummary
        var offset = 0
        var calls = 0
        var completed = 0
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        while (offset < transcript.length) {
            val chunks = ContextPlanner.splitTranscript(transcript, budget, offset)
            val chunk = chunks.first()
            if (++calls > MAX_CALLS) return Outcome.Failed("对话过长")
            onStep(completed + 1, completed + chunks.size)

            val prompt = ContextPlanner.summaryPrompt(
                transcript = chunk,
                previousSummary = summary,
                rolePlay = rolePlay,
                targetChars = targetChars,
            )
            // 读超时是无限的（流式需要），单次摘要自己设上限，卡住时放弃压缩、照常发送。
            val result = withTimeoutOrNull(SUMMARY_TIMEOUT_MS) {
                summarize(providerId, modelId, ContextPlanner.summarySystemPrompt(), prompt, maxOutput)
            } ?: return Outcome.Failed("请求超时")
            when (result) {
                is TextCompletion.Success -> {
                    calculateCostUsd(result.usage, pricing)?.let { cost ->
                        checkpointDao.recordUsage(ContextCompactionUsageEntity(UUID.randomUUID().toString(), sessionId, cost))
                    }
                    summary = result.text
                    result.usage?.promptTokens?.let { inputTokens = (inputTokens ?: 0) + it }
                    result.usage?.completionTokens?.let { outputTokens = (outputTokens ?: 0) + it }
                    offset += chunk.length
                    completed++
                }
                is TextCompletion.Failure -> {
                    if (!result.overflow || budget <= MIN_CHUNK_TOKENS) return Outcome.Failed(result.message)
                    budget = maxOf(MIN_CHUNK_TOKENS, budget / 2)
                }
            }
        }
        return Outcome.Done(summary.orEmpty(), inputTokens, outputTokens)
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 5_000L
        const val RELEASE_DELAY_MS = 1_000L
        const val MAX_CALLS = 24
        const val MIN_CHUNK_TOKENS = 4_000
        const val CHUNK_WINDOW_SHARE = 0.5
        const val MIN_SUMMARY_CHARS = 1_500
        const val MAX_SUMMARY_CHARS = 8_000
        const val MIN_OUTPUT_TOKENS = 4_096
        const val MAX_OUTPUT_TOKENS = 16_384
        const val SUMMARY_TIMEOUT_MS = 5 * 60_000L
    }
}
