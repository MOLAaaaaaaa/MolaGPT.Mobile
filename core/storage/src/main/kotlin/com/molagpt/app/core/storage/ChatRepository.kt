package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.DeltaCommand
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.RetryAttempt
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.TitleRequest
import com.molagpt.app.core.network.ChatService
import com.molagpt.app.core.network.ChatStreamController
import com.molagpt.app.core.network.webTypingPaced
import com.molagpt.app.core.storage.dao.ConversationDao
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.dao.StreamTaskDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
            EditSnapshots.append(prior, EditSnapshots.timelineOf(all), target.createdAt)
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
                liveTimeline = EditSnapshots.timelineOf(all),
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
            metadata = mapOf(
                "pending" to "正在连接…",
                "modelDisplayName" to (request.modelDisplayName ?: request.modelId),
            ),
        )
        emit(msg)
        try {
            chatService.sendMessage(request).webTypingPaced().collect { event ->
                controller.toCommands(event).forEach { cmd -> msg = applyCommand(msg, cmd) }
                emit(msg.copy(updatedAt = System.currentTimeMillis()))
            }
            if (msg.status == MessageStatus.STREAMING) msg = msg.copy(status = MessageStatus.COMPLETE)
            // 重生成：把本次答案追加为新版本，让 in-flight 帧立即带上版本信息(切换栏才会显示)。
            if (priorAttempts.isNotEmpty()) msg = msg.withAttempts(priorAttempts)
            emit(msg)
        } finally {
            var finalMsg = msg.copy(
                status = if (msg.status == MessageStatus.STREAMING) MessageStatus.STOPPED else msg.status,
                rawText = visibleText(msg),
                updatedAt = System.currentTimeMillis(),
                metadata = msg.metadata - "pending",
            )
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
        try {
            chatService.resumeStream(apiUrl, streamSessionId, 0).webTypingPaced().collect { event ->
                controller.toCommands(event).forEach { cmd -> msg = applyCommand(msg, cmd) }
                emit(msg.copy(updatedAt = System.currentTimeMillis()))
            }
            if (msg.status == MessageStatus.STREAMING) msg = msg.copy(status = MessageStatus.COMPLETE)
            emit(msg)
        } finally {
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
        val all = prior + RetryAttempt(
            fragments = fragments,
            rawText = rawText ?: visibleText(this),
            model = model,
            modelDisplayName = metadata["modelDisplayName"],
            status = status.name,
        )
        return copy(
            metadata = metadata + mapOf(
                RetryAttempts.KEY_ATTEMPTS to RetryAttempts.encode(all),
                RetryAttempts.KEY_CURRENT to all.lastIndex.toString(),
            ),
        )
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
                    query = existing?.query ?: "",
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
            is DeltaCommand.Complete -> {
                status = MessageStatus.COMPLETE
                collapseThinking(frags)
                meta = meta - "pending"
                cmd.usage?.totalTokens?.let { meta = meta + ("tokens" to it.toString()) }
                // 记录服务端上报的推理 token 数，供运行时自校正判定「隐藏思考但确实推理了」。
                cmd.usage?.reasoningTokens?.let { meta = meta + ("reasoningTokens" to it.toString()) }
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
