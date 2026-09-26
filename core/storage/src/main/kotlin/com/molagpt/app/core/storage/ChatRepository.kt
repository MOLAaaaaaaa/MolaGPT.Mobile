package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.ContextOverflow
import com.molagpt.app.core.model.DeltaCommand
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ResponseTextProcessor
import com.molagpt.app.core.model.RetryAttempt
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.TitleRequest
import com.molagpt.app.core.model.Usage
import com.molagpt.app.core.network.ChatService
import com.molagpt.app.core.network.ChatStreamController
import com.molagpt.app.core.network.webTypingPaced
import com.molagpt.app.core.storage.dao.ConversationDao
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.dao.StreamTaskDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withContext

/**
 * 唯一读写消息缓存的地方：协调 [ChatService]（网络）与 Room（本地）。
 *
 * 关键设计：流式期间助手消息在内存里累积（[streamAssistant] 逐步 emit 更新后的 ChatMessage），
 * **不逐 token 落库**；仅在结束/出错/停止时落库一次（见 finally）。UI 历史来自 [observeMessages]，
 * 在流期间由 ViewModel 把内存中的 in-flight 消息叠加显示，结束后历史流自然接管。
 */
class ChatRepository(
    private val chatService: ChatService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val streamTaskDao: StreamTaskDao,
    private val dispatchers: DispatcherProvider,
    private val pricingResolver: suspend (String, String) -> com.molagpt.app.core.model.ModelPricing? = { _, _ -> null },
    /** 回答后处理（设置 → 后处理）。默认原样返回，测试与预览可以不接。 */
    private val postProcessor: ResponseTextProcessor = ResponseTextProcessor { it },
    /** BYOK 上下文压缩。为 null 时请求原样发出。 */
    private val contextCompactor: ContextCompactor? = null,
) {
    private val controller = ChatStreamController()

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        messageDao.observeBySession(sessionId).map { rows -> rows.map { it.toDomain() } }

    suspend fun persistUserMessage(message: ChatMessage) = withContext(dispatchers.io) {
        messageDao.upsert(message.toEntity())
        conversationDao.touch(message.sessionId, System.currentTimeMillis(), message.rawText?.take(60))
    }

    suspend fun stop(streamSessionId: String) = chatService.stopGeneration(streamSessionId)

    suspend fun messageCount(sessionId: String): Int =
        withContext(dispatchers.io) { messageDao.count(sessionId) }

    /** 用户说过几句。角色会话开场就有一条助手开场白，「是不是第一轮」不能再看总条数。 */
    suspend fun userMessageCount(sessionId: String): Int =
        withContext(dispatchers.io) { messageDao.countUserMessages(sessionId) }

    /**
     * 写入角色开场白。会话里已经有任何消息就不写——开场白只在空白会话上出现一次，
     * 重复调用（切角色、重新进入页面）必须是幂等的。
     */
    suspend fun persistGreeting(message: ChatMessage): Boolean = withContext(dispatchers.io) {
        if (messageDao.count(message.sessionId) > 0) return@withContext false
        messageDao.upsert(message.toEntity())
        conversationDao.refreshListVisibility(message.sessionId)
        true
    }

    suspend fun generateTitle(request: TitleRequest): String = chatService.generateTitle(request)

    /** 一次性取整会话消息（标题生成取尾窗口用）。 */
    suspend fun allMessages(sessionId: String): List<ChatMessage> =
        withContext(dispatchers.io) { messageDao.getAllBySession(sessionId).map { it.toDomain() } }

    /** 图片上传转发（基础版）：直接转 [ChatService.uploadFile]，结果用于待发送附件条与消息附件。 */
    suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        conversationId: String,
    ): FileInfo = chatService.uploadFile(bytes, fileName, mimeType, conversationId)

    /**
     * 当前仍被消息引用的本地附件相对路径。供启动时回收孤儿文件——
     * 会话删除、编辑截断都会留下没人引用的托管副本，不清理会一直占着磁盘。
     */
    suspend fun referencedAttachmentPaths(): Set<String> = withContext(dispatchers.io) {
        messageDao.allMetadataWithAttachments()
            .asSequence()
            .flatMap { metadataJson ->
                MessageJson.decodeAttachments(MessageJson.decodeMeta(metadataJson)["attachments"]).asSequence()
            }
            .mapNotNull { it.localPath?.takeIf(String::isNotBlank) }
            .toSet()
    }

    /** regenerate / edit 的本地裁剪：删掉指定时间点及之后的消息，随后由 ViewModel 重新发起。 */
    suspend fun deleteMessagesFrom(sessionId: String, fromCreatedAt: Long) =
        withContext(dispatchers.io) {
            messageDao.deleteFrom(sessionId, fromCreatedAt)
            conversationDao.refreshListVisibility(sessionId)
        }

    /**
     * 编辑重发前：把当前整条时间线打包成一个历史分支。
     *
     * 返回应挂到**新**用户消息上的 editSnapshots 串——被编辑的那条随后会被
     * [deleteMessagesFrom] 一并删掉，所以分支元数据不能写在它身上。
     * 返回 null 表示该消息后面没有内容，无需建分支（对齐 Web 的 hasFollowingMessages 判定）。
     */
    suspend fun snapshotBeforeEdit(sessionId: String, messageId: String): String? =
        withContext(dispatchers.io) {
            val all = messageDao.getAllBySession(sessionId)
            val target = all.firstOrNull { it.messageId == messageId } ?: return@withContext null
            if (all.none { it.createdAt > target.createdAt }) return@withContext null
            val prior = MessageJson.decodeMeta(target.metadataJson)[EditSnapshots.KEY]
            val byok = conversationDao.getById(sessionId)?.providerKind == ProviderKind.BYOK.name
            EditSnapshots.append(prior, EditSnapshots.timelineOf(all, includeLocalStats = byok), target.createdAt)
        }

    /**
     * 切换 [messageId] 的编辑分支（delta = -1/+1）：整体换回该分支的时间线。
     * 返回 false 表示越界或该消息没有分支。
     */
    suspend fun navigateEditSnapshot(
        sessionId: String,
        messageId: String,
        delta: Int,
        persistBack: Boolean = false,
    ): Boolean =
        withContext(dispatchers.io) {
            val all = messageDao.getAllBySession(sessionId)
            val timelineNow = all.filterNot { it.role.equals(Role.SYSTEM.name, ignoreCase = true) }
            // 锚点按「非 system 时间线中的下标」定位：各版本共享前缀，故该下标跨版本稳定；
            // createdAt 则会随每次编辑重发而变，不能用来匹配。
            val anchorAt = timelineNow.indexOfFirst { it.messageId == messageId }
            if (anchorAt < 0) return@withContext false
            val target = timelineNow[anchorAt]
            val nav = EditSnapshots.navigate(
                raw = MessageJson.decodeMeta(target.metadataJson)[EditSnapshots.KEY],
                delta = delta,
                liveTimeline = EditSnapshots.timelineOf(all, includeLocalStats = persistBack),
                persistBack = persistBack,
            ) ?: return@withContext false

            val restored = EditSnapshots.messagesOf(sessionId, nav.timeline)
            if (restored.isEmpty() || anchorAt > restored.lastIndex) return@withContext false
            // 快照内部不带分支元数据（避免嵌套膨胀），切换后要挂回锚点那条用户消息。
            val rebound = restored.mapIndexed { i, m ->
                if (i != anchorAt) {
                    m
                } else {
                    val meta = MessageJson.decodeMeta(m.metadataJson).toMutableMap()
                        .apply { put(EditSnapshots.KEY, nav.metadata) }
                    m.copy(metadataJson = MessageJson.encodeMeta(meta))
                }
            }
            messageDao.replaceAll(sessionId, rebound)
            conversationDao.refreshListVisibility(sessionId)
            true
        }

    /**
     * 发起助手回复流。逐步 emit 已应用 [DeltaCommand] 的助手消息；调用方负责节流后刷新 UI。
     * 无论正常结束、出错还是被取消（用户停止），finally 都会把最终（或部分）消息落库。
     */
    fun streamAssistant(
        request: ChatRequest,
        assistantMessageId: String,
        priorAttempts: List<RetryAttempt> = emptyList(),
    ): Flow<ChatMessage> = flow {
        val start = System.currentTimeMillis()
        var msg = ChatMessage(
            messageId = assistantMessageId,
            sessionId = request.sessionId,
            role = Role.ASSISTANT,
            status = MessageStatus.STREAMING,
            createdAt = start,
            updatedAt = start,
            model = request.modelId,
            // 不写 pending 文案：等首 token 的反馈交给正文位置的骨架屏，头部行从头到尾都显示模型名，
            // 首 token 到达时头部零变化。pending 字段留给真正有增量信息的场景——重试进度、工具命令标签。
            metadata = mapOf(
                "modelDisplayName" to (request.modelDisplayName ?: request.modelId),
            ),
        )
        emit(msg)
        var firstTokenAt: Long? = null
        var pricing: com.molagpt.app.core.model.ModelPricing? = null
        val usage = AtomicReference<Usage?>(null)
        var processed = false
        var checkpointId: String? = null
        var slimTurns = 0
        try {
            if (request.providerKind == ProviderKind.BYOK) pricing = pricingResolver(request.providerId, request.modelId)
            // 压缩进度不写进这条回答：对话页从压缩器读，显示在回答上方。
            val compactor = contextCompactor.takeIf { request.providerKind == ProviderKind.BYOK }
            var outgoing = request
            if (compactor != null) {
                // 压缩出任何意外都不该挡住这次发送：退回原样发出。
                val prepared = try {
                    compactor.prepare(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ContextCompactor.Prepared(request, null)
                }
                outgoing = prepared.request
                checkpointId = prepared.checkpointId
                slimTurns = prepared.slimTurns
            }
            // 被以超长拒绝时压缩一次再重试。只在回答还没有任何内容时重试：
            // 工具循环中途超长的话，已经执行过的工具不能再跑一遍。
            var overflowRetried = false
            while (true) {
                var overflow: String? = null
                chatService.sendMessage(outgoing).onEach { event ->
                    // 在打字动画排队之前保存用量，停止显示时仍保留上游已完成请求的费用。
                    when (event) {
                        is com.molagpt.app.core.model.StreamEvent.UsageUpdate -> usage.set(event.usage)
                        is com.molagpt.app.core.model.StreamEvent.Finish -> event.usage?.let { usage.set(it) }
                        else -> Unit
                    }
                }.webTypingPaced().collect { event ->
                    if (
                        event is com.molagpt.app.core.model.StreamEvent.Failed &&
                        compactor != null &&
                        msg.fragments.isEmpty() &&
                        ContextOverflow.matches(event.message)
                    ) {
                        if (!overflowRetried) {
                            overflow = event.message
                            return@collect
                        }
                        controller.toCommands(
                            com.molagpt.app.core.model.StreamEvent.Failed(OVERFLOW_NOTICE + "\n\n" + event.message),
                        ).forEach { cmd -> msg = applyCommand(msg, cmd) }
                        emit(msg.copy(updatedAt = System.currentTimeMillis()))
                        return@collect
                    }
                    controller.toCommands(event).forEach { cmd -> msg = applyCommand(msg, cmd) }
                    // 首个 fragment 落地 = 用户看到第一个字，作为 TTFT 的终点。
                    if (firstTokenAt == null && msg.fragments.isNotEmpty()) {
                        firstTokenAt = System.currentTimeMillis()
                    }
                    emit(msg.copy(updatedAt = System.currentTimeMillis()))
                }
                val error = overflow ?: break
                overflowRetried = true
                val recovered = try {
                    compactor?.recoverFromOverflow(request, error)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (recovered == null) {
                    controller.toCommands(
                        com.molagpt.app.core.model.StreamEvent.Failed(OVERFLOW_NOTICE + "\n\n" + error),
                    ).forEach { cmd -> msg = applyCommand(msg, cmd) }
                    emit(msg.copy(updatedAt = System.currentTimeMillis()))
                    break
                }
                emit(msg.copy(updatedAt = System.currentTimeMillis()))
                outgoing = recovered.request
                checkpointId = recovered.checkpointId
                slimTurns = recovered.slimTurns
                usage.set(null)
            }
            if (msg.status == MessageStatus.STREAMING) msg = msg.copy(status = MessageStatus.COMPLETE)
            // 后处理必须赶在版本快照之前：晚一步，存进 retryAttempts 的就是没改写过的原文，
            // 切回这一版时替换会凭空消失。
            msg = withContext(NonCancellable) { applyPostProcessing(msg) }
            processed = true
            msg = msg.withRequestStats(request.providerKind, usage.get(), start, firstTokenAt, pricing, checkpointId, slimTurns)
            // 重生成：把本次答案追加为新版本，让 in-flight 帧立即带上版本信息(切换栏才会显示)。
            if (priorAttempts.isNotEmpty()) msg = msg.withAttempts(priorAttempts)
            emit(msg)
        } finally {
            // 用户停止或流中出错时上面的分支没跑到：半截文本同样要落成用户看到的样子。
            if (!processed) msg = withContext(NonCancellable) { applyPostProcessing(msg) }
            var finalMsg = msg.copy(
                status = if (msg.status == MessageStatus.STREAMING) MessageStatus.STOPPED else msg.status,
                rawText = visibleText(msg),
                updatedAt = System.currentTimeMillis(),
                metadata = msg.metadata - "pending",
            )
            if (!processed) finalMsg = finalMsg.withRequestStats(request.providerKind, usage.get(), start, firstTokenAt, pricing, checkpointId, slimTurns)
            // 用户停止时上面的 success 分支未跑到，这里兜底补版本，避免丢失旧版本。
            if (priorAttempts.isNotEmpty() && !finalMsg.metadata.containsKey(RetryAttempts.KEY_ATTEMPTS)) {
                finalMsg = finalMsg.withAttempts(priorAttempts)
            }
            withContext(NonCancellable + dispatchers.io) {
                messageDao.upsert(finalMsg.toEntity())
                conversationDao.touch(request.sessionId, System.currentTimeMillis(), finalMsg.rawText?.take(60))
            }
        }
    }.flowOn(dispatchers.io)

    /** 持久化单条消息（版本切换后落库）。 */
    suspend fun updateMessage(message: ChatMessage) = withContext(dispatchers.io) {
        messageDao.upsert(message.toEntity())
        conversationDao.refreshListVisibility(message.sessionId)
    }

    // —— 进程死亡恢复：在途任务持久化 + 续接 ——

    suspend fun persistStreamTask(record: StreamTaskRecord) =
        withContext(dispatchers.io) { streamTaskDao.upsert(record.toEntity()) }

    suspend fun removeStreamTask(sessionId: String) =
        withContext(dispatchers.io) { streamTaskDao.delete(sessionId) }

    suspend fun loadStreamTasks(): List<StreamTaskRecord> =
        withContext(dispatchers.io) { streamTaskDao.getAll().map { it.toRecord() } }

    /**
     * 续接一个被中断的助手回复：用 [ChatService.resumeStream] 从 offset=0 回放服务端缓存。
     * 服务端 resume 对「已完成」会回放全文 + sources + [DONE]，对「进行中」会回放已有并继续等待，
     * 故 offset=0 同时覆盖两种恢复场景。结束/出错都经 finally 落库一次。
     */
    fun resumeAssistant(
        assistantMessageId: String,
        sessionId: String,
        apiUrl: String,
        streamSessionId: String,
        model: String?,
        modelDisplayName: String?,
    ): Flow<ChatMessage> = flow {
        val start = System.currentTimeMillis()
        var msg = ChatMessage(
            messageId = assistantMessageId,
            sessionId = sessionId,
            role = Role.ASSISTANT,
            status = MessageStatus.STREAMING,
            createdAt = start,
            updatedAt = start,
            model = model,
            metadata = mapOf(
                "pending" to "正在恢复…",
                "modelDisplayName" to (modelDisplayName ?: model ?: ""),
            ),
        )
        emit(msg)
        var processed = false
        try {
            chatService.resumeStream(apiUrl, streamSessionId, 0).webTypingPaced().collect { event ->
                controller.toCommands(event).forEach { cmd -> msg = applyCommand(msg, cmd) }
                emit(msg.copy(updatedAt = System.currentTimeMillis()))
            }
            if (msg.status == MessageStatus.STREAMING) msg = msg.copy(status = MessageStatus.COMPLETE)
            msg = withContext(NonCancellable) { applyPostProcessing(msg) }
            processed = true
            emit(msg)
        } finally {
            if (!processed) msg = withContext(NonCancellable) { applyPostProcessing(msg) }
            val finalMsg = msg.copy(
                status = if (msg.status == MessageStatus.STREAMING) MessageStatus.STOPPED else msg.status,
                rawText = visibleText(msg),
                updatedAt = System.currentTimeMillis(),
                metadata = msg.metadata - "pending",
            )
            withContext(NonCancellable + dispatchers.io) {
                messageDao.upsert(finalMsg.toEntity())
                conversationDao.touch(sessionId, System.currentTimeMillis(), finalMsg.rawText?.take(60))
            }
        }
    }.flowOn(dispatchers.io)

    /** 把「当前消息内容」作为新版本追加到 [prior] 之后，写入 metadata（KEY_ATTEMPTS/KEY_CURRENT）。 */
    private fun ChatMessage.withAttempts(prior: List<RetryAttempt>): ChatMessage {
        val all = prior + RetryAttempts.from(this.copy(rawText = rawText ?: visibleText(this)))
        return copy(
            metadata = metadata + mapOf(
                RetryAttempts.KEY_ATTEMPTS to RetryAttempts.encode(all),
                RetryAttempts.KEY_CURRENT to all.lastIndex.toString(),
            ),
        )
    }

    /**
     * 把本次请求的统计信息写进元数据，供聊天页的统计行展示（[com.molagpt.app.core.model.MessageStats]）。
     *
     * 只对 BYOK 生效：MolaGPT 官方链路计费口径是「次数」而不是 token，展示 token 统计会误导。
     * usage 缺失（provider 没上报、或用户中途停止）时只写耗时，各字段互不依赖。
     */
    private fun ChatMessage.withRequestStats(
        providerKind: ProviderKind,
        usage: Usage?,
        startedAt: Long,
        firstTokenAt: Long?,
        pricing: com.molagpt.app.core.model.ModelPricing?,
        checkpointId: String? = null,
        slimTurns: Int = 0,
    ): ChatMessage {
        if (providerKind != ProviderKind.BYOK) return this
        val stats = buildMap {
            if (pricing == null) put(ChatMessageMetadataKeys.PRICING_MISSING, "true")
            com.molagpt.app.core.model.calculateCostUsd(usage, pricing)?.let { cost ->
                put(ChatMessageMetadataKeys.COST_ID, messageId)
                put(ChatMessageMetadataKeys.COST_USD, cost.toString())
                put(ChatMessageMetadataKeys.COST_MODEL, model.orEmpty())
                pricing?.source?.let { put(ChatMessageMetadataKeys.PRICING_SOURCE, it) }
            }
            usage?.totalTokens?.let { put(ChatMessageMetadataKeys.TOTAL_TOKENS, it.toString()) }
            usage?.reasoningTokens?.let { put(ChatMessageMetadataKeys.REASONING_TOKENS, it.toString()) }
            usage?.promptTokens?.let { put(ChatMessageMetadataKeys.PROMPT_TOKENS, it.toString()) }
            usage?.completionTokens?.let { put(ChatMessageMetadataKeys.COMPLETION_TOKENS, it.toString()) }
            usage?.cachedTokens?.let { put(ChatMessageMetadataKeys.CACHED_TOKENS, it.toString()) }
            put(ChatMessageMetadataKeys.DURATION_MS, (System.currentTimeMillis() - startedAt).toString())
            firstTokenAt?.let { put(ChatMessageMetadataKeys.TTFT_MS, (it - startedAt).toString()) }
            usage?.contextSize?.let { put(ChatMessageMetadataKeys.CONTEXT_TOKENS, it.toString()) }
            checkpointId?.let { put(ChatMessageMetadataKeys.CONTEXT_CHECKPOINT, it) }
            if (slimTurns > 0) put(ChatMessageMetadataKeys.CONTEXT_SLIM, slimTurns.toString())
        }
        return copy(metadata = metadata + stats)
    }

    // —— 把局部增量命令合并进当前消息的 fragment 列表（绝不整段重建）——
    private fun applyCommand(msg: ChatMessage, cmd: DeltaCommand): ChatMessage {
        val frags = msg.fragments.toMutableList()
        var meta = msg.metadata
        var status = msg.status
        when (cmd) {
            is DeltaCommand.AppendText -> {
                // 仅当**末尾**片段是 Text 才续写，否则新建到末尾——保持 think/tool/text 的到达顺序，
                // 不跨越中间的工具卡片把正文并回更早的 Text 片段。
                val tail = frags.lastOrNull()
                if (tail is MessageFragment.Text) {
                    frags[frags.lastIndex] = tail.copy(markdown = tail.markdown + cmd.chunk)
                } else {
                    frags.add(MessageFragment.Text(Ids.newFragmentId(), cmd.chunk))
                }
                // 正文一旦开始，收起此前展开的思考块（思考结束的信号）。
                collapseThinking(frags)
                if (meta.containsKey("pending")) meta = meta - "pending"
            }
            is DeltaCommand.AppendThinking -> {
                // 仅续写**末尾**的 Thinking；否则新建到末尾（不再 add(0) 强插顶部）。
                // 这样「思考→调用工具→再思考」得到 [Thinking, ToolCall, Thinking] 的交错顺序，
                // 新思考出现在工具卡片下面，保持片段到达顺序。
                val tail = frags.lastOrNull()
                if (tail is MessageFragment.Thinking) {
                    frags[frags.lastIndex] = tail.copy(text = tail.text + cmd.chunk, collapsed = false)
                } else {
                    frags.add(MessageFragment.Thinking(Ids.newFragmentId(), cmd.chunk, collapsed = false))
                }
                // 已收到真实推理内容，连接/工具结果处理占位已结束。
                if (meta.containsKey("pending")) meta = meta - "pending"
            }
            is DeltaCommand.UpsertTool -> {
                // 工具作为独立顶层 fragment。已存在则原地更新（流式状态变更），否则追加到末尾，
                // 与思考/正文按到达顺序交错。绝不嵌进 Thinking。
                val i = frags.indexOfFirst { it is MessageFragment.ToolCall && it.id == cmd.tool.id }
                if (i >= 0) frags[i] = cmd.tool else frags.add(cmd.tool)
                // 工具生命周期本身就是当前活动指示，不再同时显示旧 pending。
                if (meta.containsKey("pending")) meta = meta - "pending"
            }
            is DeltaCommand.SetSources -> {
                val i = frags.indexOfFirst { it is MessageFragment.SearchResult }
                val existing = frags.getOrNull(i) as? MessageFragment.SearchResult
                val sr = MessageFragment.SearchResult(
                    id = existing?.id ?: Ids.newFragmentId(),
                    // BYOK 自带搜索会带上搜索词；自家后端的来源表不带，保留已有的。
                    query = cmd.query ?: existing?.query ?: "",
                    refs = cmd.refs,
                )
                if (i >= 0) frags[i] = sr else frags.add(sr)
            }
            is DeltaCommand.SetPending -> {
                val selectedModelName = cmd.detail?.takeIf { it.isNotBlank() }
                if (cmd.label == "已选择模型" && selectedModelName != null) {
                    meta = meta + ("modelDisplayName" to selectedModelName)
                }
                meta = meta + ("pending" to (cmd.detail?.let { "${cmd.label} · $it" } ?: cmd.label))
            }
            is DeltaCommand.AddImage -> {
                frags.add(MessageFragment.Image(Ids.newFragmentId(), cmd.url, cmd.prompt))
            }
            is DeltaCommand.SetMetadata -> {
                meta = meta + (cmd.key to cmd.value)
            }
            is DeltaCommand.Complete -> {
                status = MessageStatus.COMPLETE
                collapseThinking(frags)
                meta = meta - "pending"
                cmd.usage?.totalTokens?.let { meta = meta + (ChatMessageMetadataKeys.TOTAL_TOKENS to it.toString()) }
                // 记录服务端上报的推理 token 数，供运行时自校正判定「隐藏思考但确实推理了」。
                cmd.usage?.reasoningTokens?.let {
                    meta = meta + (ChatMessageMetadataKeys.REASONING_TOKENS to it.toString())
                }
            }
            is DeltaCommand.Fail -> {
                status = MessageStatus.ERROR
                collapseThinking(frags)
                frags.add(MessageFragment.Error(Ids.newFragmentId(), cmd.message))
                meta = meta - "pending"
            }
        }
        return msg.copy(fragments = frags, status = status, metadata = meta)
    }

    private fun collapseThinking(frags: MutableList<MessageFragment>) {
        frags.forEachIndexed { index, fragment ->
            if (fragment is MessageFragment.Thinking && !fragment.collapsed) {
                frags[index] = fragment.copy(collapsed = true)
            }
        }
    }

    /**
     * 对已完成（或已中断）的回答施加后处理规则。
     *
     * 只碰 [MessageFragment.Text]：思考、工具卡片、代码块、公式都是各自独立的片段，
     * 规则够不到它们——这也是移动端不必像桌面端那样为工具标记做偏移重映射的原因。
     * 正文变了的话，下一轮回放用的协议快照由 [ResponseRewrite] 一并跟上。
     *
     * **无论如何都不会抛**：规则是用户自己写的，写坏了顶多是不改写，绝不能让一条回答
     * 因此落不了库。
     */
    private suspend fun applyPostProcessing(msg: ChatMessage): ChatMessage =
        runCatching { ResponseRewrite.apply(msg) { postProcessor.process(it) } }.getOrDefault(msg)

    private companion object {
        const val OVERFLOW_NOTICE = "对话超出模型上下文长度"
    }

    private fun visibleText(msg: ChatMessage): String = buildString {
        msg.fragments.forEach { frag ->
            when (frag) {
                is MessageFragment.Text -> append(frag.markdown)
                is MessageFragment.CodeBlock -> append("\n```").append(frag.language ?: "").append('\n').append(frag.code).append("\n```\n")
                is MessageFragment.Latex -> append(if (frag.display) "$$${frag.expr}$$" else "$${frag.expr}$")
                is MessageFragment.Mermaid -> append("\n```mermaid\n").append(frag.source).append("\n```\n")
                else -> Unit
            }
        }
    }.trim()
}
