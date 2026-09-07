package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.ByokMemoryDigestEntry
import com.molagpt.app.core.model.ByokMemoryEntry
import com.molagpt.app.core.model.ByokMemoryOp
import com.molagpt.app.core.model.ByokMemoryOrigin
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.normalizeMemoryKey
import com.molagpt.app.core.network.ByokMemoryAnalyzer
import com.molagpt.app.core.storage.ByokMemoryWindow.Window
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 记忆整理：按**窗口**而不是按轮次做。
 *
 * 一次回答结束不再等于一次整理请求——攒够 [TURNS_THRESHOLD] 个回合、或距上次整理超过
 * [INTERVAL_MILLIS]、或用户手动触发时才跑一次，一次看完这一段。这样做除了省钱，
 * 更重要的是能看到同一段里的自我修正（「下周去上海」→「改成下下周」），
 * 逐轮各看各的只会记下两条互相矛盾的事实。
 *
 * 跑在 **application scope**（由触发方保证）：用户经常聊完就退出，绑 viewModelScope 的请求会被取消。
 */
class ByokMemoryConsolidator(
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val memoryRepository: ByokMemoryRepository,
    private val analyzer: ByokMemoryAnalyzer,
    private val settingsStore: SettingsStore,
    private val dispatchers: DispatcherProvider,
    /** 记忆已更新的轻量提示，交给 UI 决定要不要展示。 */
    private val onConsolidated: (sessionId: String, result: Result) -> Unit = { _, _ -> },
) {

    /**
     * 一次整理的结果。写入与待确认分开计数——「已更新」不该把只是排队等确认的也算进去。
     *
     * 后三个计数只为「立即整理」的回执服务：整理有一半的结束方式是「压根没跑」，
     * 全都折叠成「没有需要记录的新内容」会让用户对着一个其实没开的功能反复点。
     */
    data class Result(
        val written: Int = 0,
        val pending: Int = 0,
        /** 真的把窗口交给模型看过的会话数。为 0 说明这次一个请求都没发出去。 */
        val examined: Int = 0,
        /** 请求失败或读不懂模型回答的会话数。 */
        val failed: Int = 0,
        /** 记忆开关关着而跳过的会话数。 */
        val disabled: Int = 0,
    ) {
        /** 只看有没有写进东西：会话内那条轻量提示读它。 */
        val isEmpty: Boolean get() = written == 0 && pending == 0

        operator fun plus(other: Result) = Result(
            written = written + other.written,
            pending = pending + other.pending,
            examined = examined + other.examined,
            failed = failed + other.failed,
            disabled = disabled + other.disabled,
        )
    }

    /** 一个窗口的处理结果。[advanced] 为真表示水位线动了，同一个会话还可以接着往下整理。 */
    private data class Step(val result: Result, val advanced: Boolean)

    /**
     * 单飞锁。前台触发、流结束触发和手动触发可能同时到达，
     * 并发跑同一个会话会让两次整理看到同一个窗口、付两次钱。
     */
    private val mutex = Mutex()

    /**
     * 窗口失败计数，键是「会话 + 窗口末尾时间」。放内存不落库：
     * 重启后重试一次是可以接受的，为它建一张表不值得。
     */
    private val failures = mutableMapOf<String, Int>()

    /** 回答结束时调用。只在攒够回合数时才真的跑，因此绝大多数轮次零开销。 */
    suspend fun onTurnFinished(sessionId: String) {
        val step = run(sessionId = sessionId, ignoreTurnThreshold = false, manual = false)
        if (step.advanced || step.result.failed > 0) {
            settingsStore.setByokMemoryLastConsolidatedAt(System.currentTimeMillis())
        }
    }

    /**
     * 应用回到前台时调用。距上次整理超过间隔才扫一遍——这条覆盖的是「聊两句就放下手机」，
     * 那种会话永远攒不够回合数，只靠回合阈值会永远学不到。
     */
    suspend fun onAppForegrounded() {
        val settings = settingsStore.settings.first()
        if (!settings.byokMemoryMasterEnabled || !settings.byokMemoryAutoLearn) return
        val since = System.currentTimeMillis() - settings.byokMemoryLastConsolidatedAt
        if (settings.byokMemoryLastConsolidatedAt > 0L && since < INTERVAL_MILLIS) return
        // 间隔到了就不再看回合数。再卡一次阈值的话，短会话仍然过不去，
        // 这条路径存在的唯一理由就没了——间隔本身已经是这次整理的成本闸门。
        sweep(ignoreTurnThreshold = true, exhaustive = false, manual = false)
    }

    /**
     * 记忆页的「立即整理」。手动触发意味着「现在就把该记的都记下来」，
     * 所以它扫全部待整理的会话、并在每个会话上一直整理到水位线追平，不受自动扫描上限约束。
     *
     * 用户选择完整范围时，这次操作会持续推进到所有待整理窗口处理完毕；
     * 失败的窗口仍停在原水位线，并通过结果明确返回失败。
     */
    suspend fun consolidateNow(): Result = sweep(ignoreTurnThreshold = true, exhaustive = true, manual = true)

    private suspend fun sweep(ignoreTurnThreshold: Boolean, exhaustive: Boolean, manual: Boolean): Result =
        withContext(dispatchers.io) {
            var total = Result()
            var attemptedOrAdvanced = false
            val limit = if (exhaustive) Int.MAX_VALUE else MAX_SESSIONS_PER_SWEEP
            val sessions = sessionRepository.sessionsPendingMemory(limit)
            for (sessionId in sessions) {
                do {
                    val step = run(sessionId = sessionId, ignoreTurnThreshold = ignoreTurnThreshold, manual = manual)
                    total += step.result
                    attemptedOrAdvanced = attemptedOrAdvanced || step.advanced || step.result.failed > 0
                    // 水位线没动就别再试同一个会话：失败、没到阈值、开关关着都属于这一类，
                    // 再跑一次只会得到同样的结果，还白花一次额度。
                    if (!exhaustive || !step.advanced) break
                } while (true)
            }
            if (attemptedOrAdvanced) {
                settingsStore.setByokMemoryLastConsolidatedAt(System.currentTimeMillis())
            }
            total
        }

    private suspend fun run(sessionId: String, ignoreTurnThreshold: Boolean, manual: Boolean): Step =
        withContext(dispatchers.io) {
            mutex.withLock { runLocked(sessionId, ignoreTurnThreshold, manual) }
        }

    private suspend fun runLocked(sessionId: String, ignoreTurnThreshold: Boolean, manual: Boolean): Step {
        val settings = settingsStore.settings.first()
        if (!settings.byokMemoryMasterEnabled) return Step(Result(disabled = 1), advanced = false)
        if (!settings.byokMemoryAutoLearn) return Step(Result(), advanced = false)

        val conversation = sessionRepository.get(sessionId) ?: return Step(Result(), advanced = false)
        if (conversation.providerKind != ProviderKind.BYOK) return Step(Result(), advanced = false)
        // 只有显式关掉这个会话的记忆才不学。会话级覆盖为 null 时跟随的是自动学习开关本身，
        // 不是「记忆注入」——三项子功能现在是平级的，注入关着不代表不该继续攒记忆。
        if (conversation.byokMemoryEnabled == false) return Step(Result(disabled = 1), advanced = false)

        val target = resolveModel(settings.byokMemoryModelKey)
            ?: return Step(Result(), advanced = false)

        val window = ByokMemoryWindow.build(
            messages = chatRepository.allMessages(sessionId).sortedBy { it.createdAt },
            watermarkAt = conversation.byokMemoryWatermarkAt,
            resetAt = settings.byokMemoryResetAt,
            capFirstWindow = !settings.byokMemoryFullScan,
        )
        if (window.endAt <= conversation.byokMemoryWatermarkAt) return Step(Result(), advanced = false)
        if (!ignoreTurnThreshold && window.assistantTurns < TURNS_THRESHOLD) {
            return Step(Result(), advanced = false)
        }

        // 剥完什么都没剩（整段都是代码或链接）：不值得发请求，但水位线要推进，
        // 否则这段消息会在每次整理时被反复扫描。
        if (window.isEmpty || window.userEntries.isEmpty()) {
            sessionRepository.advanceByokMemoryWatermark(sessionId, window.endAt)
            return Step(Result(), advanced = true)
        }

        val failureKey = "$sessionId@${window.endAt}"
        // 自动整理停止反复请求同一个坏窗口；用户主动点整理时仍允许重新尝试。
        if (!manual && (failures[failureKey] ?: 0) >= MAX_WINDOW_FAILURES) {
            return Step(Result(), advanced = false)
        }

        val input = ByokMemoryAnalyzer.Input(
            providerId = target.first,
            modelId = target.second,
            window = window.entries.map {
                ByokMemoryAnalyzer.WindowMessage(
                    messageId = it.messageId,
                    isUser = it.role == Role.USER,
                    text = it.text,
                )
            },
            existing = memoryRepository.entries()
                .sortedByDescending { it.effectiveConfidence(System.currentTimeMillis()) }
                .take(MAX_DIGEST_ENTRIES)
                .map { ByokMemoryDigestEntry(id = it.id, section = it.section, text = it.text) },
            suppressed = memoryRepository.suppressedTexts(),
        )

        val ops = try {
            // 守门为 false 也算看过了：水位线推进，这一段不会被再看一次。
            if (!analyzer.gate(input)) {
                failures.remove(failureKey)
                sessionRepository.advanceByokMemoryWatermark(sessionId, window.endAt)
                return Step(Result(examined = 1), advanced = true)
            }
            analyzer.extract(input)
        } catch (error: Exception) {
            // 请求失败或读不懂模型的回答：**不推进水位线**，否则会越过一段根本没被看过的对话。
            Logger.w(TAG, "记忆整理失败：${error.message}", error)
            failures[failureKey] = (failures[failureKey] ?: 0) + 1
            return Step(Result(failed = 1), advanced = false)
        }

        val result = apply(ops, sessionId, window, settings.byokMemoryAllowSensitive)
            .copy(examined = 1)
        failures.remove(failureKey)
        sessionRepository.advanceByokMemoryWatermark(sessionId, window.endAt)
        if (!result.isEmpty) onConsolidated(sessionId, result)
        return Step(result, advanced = true)
    }

    /**
     * 应用侧校验。模型输出一律当不可信数据：逐条判定，不合格丢掉这一条，其余照写。
     *
     * 全丢会让一次小失误废掉整个窗口，全收又会让一次注入永久污染画像——逐条是唯一合理的粒度。
     */
    private suspend fun apply(
        ops: List<ByokMemoryOp>,
        sessionId: String,
        window: Window,
        allowSensitive: Boolean,
    ): Result {
        var written = 0
        var pending = 0
        ops.take(ByokMemoryOp.MAX_OPS).forEach { op ->
            when (op.type) {
                ByokMemoryOp.Type.ADD, ByokMemoryOp.Type.CANDIDATE, ByokMemoryOp.Type.SUPERSEDE -> {
                    val text = op.text?.trim().orEmpty()
                    val quote = op.quote?.trim().orEmpty()
                    val section = op.section ?: return@forEach
                    if (text.isEmpty() || text.length > ByokMemoryEntry.MAX_TEXT_CHARS) return@forEach
                    // 证据必须来自窗口里的某条**用户**消息。助手回复、模型读到的网页、工具输出
                    // 都不在窗口的用户消息里，因此它们里面的「请记住…」永远过不了这一关。
                    val source = window.sourceOf(quote) ?: return@forEach
                    if (!allowSensitive && ByokMemoryGuards.looksSensitive(text)) return@forEach

                    // 纠正必须指向一条真实存在的旧记忆，否则它只是一次伪装成纠正的新增。
                    // 用户亲手写下或亲自确认过的条目不接受自动纠正——记忆页写着它们不会被自动学习改写。
                    val superseded = if (op.type == ByokMemoryOp.Type.SUPERSEDE) {
                        val target = entryById(op.targetId) ?: return@forEach
                        if (target.isUserAuthored) return@forEach
                        target
                    } else {
                        null
                    }

                    // 受保护特征一律先进候选，不管置信度多高：这类事实可能确实是用户要求记的，
                    // 但不该在用户不知情的情况下被写进一份会随每轮发出去的画像。
                    val protected = !allowSensitive && ByokMemoryGuards.looksProtected(text)
                    val promote = !protected &&
                        op.type != ByokMemoryOp.Type.CANDIDATE &&
                        op.confidence >= ByokMemoryOp.PROMOTE_CONFIDENCE
                    if (promote) {
                        val result = memoryRepository.saveFromModel(
                            text = text,
                            section = section,
                            profileKey = op.profileKey,
                            sessionId = sessionId,
                            messageId = source.messageId,
                            quote = quote,
                            confidence = op.confidence,
                            origin = ByokMemoryOrigin.AUTOMATIC,
                            replaceEntryIds = setOfNotNull(superseded?.id),
                        )
                        if (
                            result is ByokMemoryRepository.SaveResult.Added ||
                            result is ByokMemoryRepository.SaveResult.Reinforced
                        ) {
                            written++
                        }
                    } else {
                        val candidate = memoryRepository.addCandidate(
                            text = text,
                            section = section,
                            profileKey = op.profileKey,
                            sessionId = sessionId,
                            messageId = source.messageId,
                            quote = quote,
                            confidence = op.confidence,
                        )
                        if (candidate != null) pending++
                    }
                }

                ByokMemoryOp.Type.REINFORCE -> {
                    val entry = entryById(op.targetId) ?: return@forEach
                    val quote = op.quote?.trim().orEmpty()
                    // 没有逐字引文就不强化。退回「窗口里最后一条用户消息」看着宽容，
                    // 实际是在溯源里写下一句与这条记忆无关的原话：用户点开来源会看到对不上的引用，
                    // 而且这正好绕开了 ADD 那条「证据必须来自用户」的检查。
                    val source = window.sourceOf(quote) ?: return@forEach
                    val result = memoryRepository.saveFromModel(
                        text = entry.text,
                        section = entry.section,
                        profileKey = entry.profileKey,
                        sessionId = sessionId,
                        messageId = source.messageId,
                        quote = quote,
                        confidence = entry.confidence,
                        origin = entry.origin,
                    )
                    if (result is ByokMemoryRepository.SaveResult.Reinforced) written++
                }

                // 遗忘只接受用户明确表态。模型自行推断「这条大概过时了」不足以删记忆——
                // 删除不可逆，而且会连带写压制记录，让这条事实再也自动回不来。
                ByokMemoryOp.Type.FORGET -> {
                    val quote = op.quote?.trim().orEmpty()
                    if (quote.isEmpty()) return@forEach
                    if (window.sourceOf(quote) == null) return@forEach
                    if (!ByokMemoryGuards.looksLikeDenial(quote)) return@forEach
                    val target = entryById(op.targetId) ?: return@forEach
                    // 手写与用户确认过的条目只能由用户在记忆页删。引文只证明用户说过一句否认的话，
                    // 并不证明这句话说的就是这条记忆——凭这个删掉用户亲手写的东西，代价不可逆。
                    if (target.isUserAuthored) return@forEach
                    memoryRepository.deleteEntry(target.id, suppress = true)
                    written++
                }
            }
        }
        return Result(written = written, pending = pending)
    }

    private suspend fun entryById(id: String?): ByokMemoryEntry? {
        val clean = id?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return memoryRepository.entries().firstOrNull { it.id == clean }
    }

    /**
     * 整理模型必须由用户显式指定，没有回退到会话模型这条路。
     *
     * 整理是用户视线之外的额外请求：跟随会话模型意味着换一个贵模型聊天，整理就跟着用贵模型跑。
     * 未指定就不跑，记忆页会明确显示自动学习没在运行。
     */
    private fun resolveModel(modelKey: String?): Pair<String, String>? {
        if (modelKey.isNullOrBlank() || !modelKey.contains("::")) return null
        val providerId = modelKey.substringBefore("::").takeIf { it.isNotBlank() } ?: return null
        val modelId = modelKey.substringAfter("::").takeIf { it.isNotBlank() } ?: return null
        return providerId to modelId
    }

    private companion object {
        const val TAG = "ByokMemoryConsolidator"

        /** 攒够几个助手回合触发一次整理。 */
        const val TURNS_THRESHOLD = 3

        /** 时间阈值：覆盖「聊两句就放下手机」，那种会话永远攒不够回合数。 */
        const val INTERVAL_MILLIS = 6 * 60 * 60 * 1000L

        /** 自动扫描一次最多处理几个会话。长期没打开后回来时，积压必须封顶。 */
        const val MAX_SESSIONS_PER_SWEEP = 10

        /** 同一个窗口最多失败几次。整理模型持续返回垃圾时，无限重试就是无限计费。 */
        const val MAX_WINDOW_FAILURES = 3

        const val MAX_DIGEST_ENTRIES = 30
    }
}

/**
 * 引文归属：逐字出自窗口里的哪一条用户消息。找不到就不是合法证据。
 *
 * 归一化后再比对，是因为模型转述时常改动标点或空白；但仍要求是子串，
 * 足以保证这句话确实出自用户，而不是助手回复或模型读到的外部内容。
 */
/** 用户亲手写下或亲自确认过的条目。自动整理只读不改。 */
private val ByokMemoryEntry.isUserAuthored: Boolean
    get() = origin == ByokMemoryOrigin.MANUAL || origin == ByokMemoryOrigin.CONFIRMED

private fun Window.sourceOf(quote: String): ByokMemoryWindow.Entry? {
    if (quote.isEmpty()) return null
    val needle = normalizeMemoryKey(quote)
    if (needle.isEmpty()) return null
    return userEntries.firstOrNull { normalizeMemoryKey(it.text).contains(needle) }
}
