package com.molagpt.app.feature.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.markdown.MdBlock
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.storage.EditSnapshots
import com.molagpt.app.core.render.LocalMarkdownImageRenderer
import com.molagpt.app.core.render.MarkdownRenderScheduler
import com.molagpt.app.core.render.MarkdownBlockView
import com.molagpt.app.core.render.RenderCache
import com.molagpt.app.core.storage.RetryAttempts
import com.molagpt.app.feature.file.RemoteImage
import com.molagpt.app.feature.webview.MermaidWebView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onRegenerate: () -> Unit = {},
    onRegenerateWithModel: (String) -> Unit = {},
    onEditUser: (String) -> Unit = {},
    canEdit: Boolean = true,
    models: List<ProviderModel> = emptyList(),
    onNavVersion: (String, Int) -> Unit = { _, _ -> },
    onNavEditSnapshot: (String, Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var autoFollow by remember { mutableStateOf(true) }
    StreamRenderPacingEffect(messages.any(ChatMessage::isStreaming))
    val renderRequests = remember { Channel<MessageRenderRequest>(Channel.CONFLATED) }
    val renderRequest = MessageRenderRequest(messages, models, canEdit)
    SideEffect {
        renderRequests.trySend(renderRequest)
    }
    DisposableEffect(renderRequests) {
        onDispose { renderRequests.close() }
    }
    val initialRows = remember(renderRequests) {
        val initialModelNameOf: (String) -> String = { id ->
            models.firstOrNull { it.id == id }?.displayName ?: id
        }
        messages.toMessageRows(
            parseMarkdown = false,
            modelDisplayNameOf = initialModelNameOf,
            canEditUser = canEdit,
        )
    }
    val rows by produceState(
        initialValue = initialRows,
        key1 = renderRequests,
    ) {
        var renderedRequest: MessageRenderRequest? = null
        for (latest in renderRequests) {
            if (latest == renderedRequest) continue
            val modelNameOf: (String) -> String = { id ->
                latest.models.firstOrNull { it.id == id }?.displayName ?: id
            }
            val renderedRows = withContext(MarkdownRenderScheduler.dispatcher) {
                latest.messages.toMessageRows(
                    parseMarkdown = true,
                    modelDisplayNameOf = modelNameOf,
                    canEditUser = latest.canEdit,
                )
            }
            renderedRequest = latest
            value = renderedRows
        }
    }

    // 跟随判定：滚动时持续按「是否贴底」更新跟随意图。仅在用户手势滚动(isScrollInProgress)时更新——
    // 程序滚动(instant scroll)与流式内容增长都不会置该标志,故不会误改意图:
    // 用户上滑→立即停跟随,滑回底部→立即恢复。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.isAtBottom() }
            .collect { (scrolling, atBottom) ->
                if (scrolling) autoFollow = atBottom
            }
    }

    var lastSeenMessageId by remember { mutableStateOf<String?>(null) }
    val lastMessage = messages.lastOrNull()
    val lastSignature = lastMessage?.let { it.messageId to it.updatedAt }
    LaunchedEffect(lastSignature, rows.size) {
        if (lastMessage == null || rows.isEmpty()) return@LaunchedEffect
        if (lastMessage.messageId != lastSeenMessageId) {
            lastSeenMessageId = lastMessage.messageId
            autoFollow = true
            listState.scrollToBottom()
        } else if (autoFollow) {
            listState.scrollToBottom()
        }
    }

    CompositionLocalProvider(
        LocalMarkdownImageRenderer provides { url, imgModifier ->
            RemoteImage(url, imgModifier)
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            items(
                items = rows,
                key = { it.key },
                contentType = { it.contentType },
            ) { row ->
                val rowModifier = Modifier
                    .fillMaxWidth()
                    .padding(top = row.topPaddingDp.dp)
                when (row) {
                    is MessageListRow.User -> MessageBubble(message = row.message, modifier = rowModifier)
                    is MessageListRow.Pending -> AssistantPendingText(row.text, rowModifier)
                    is MessageListRow.ToolGroup -> ToolCallGroupRenderer(row.fragments, rowModifier)
                    is MessageListRow.Fragment -> FragmentRenderer(
                        fragment = row.fragment,
                        modifier = rowModifier,
                        streamingTail = row.streamingTail,
                    )
                    is MessageListRow.AssistantText -> SelectionContainer(modifier = rowModifier) {
                        Column {
                            row.blocks.forEachIndexed { index, block ->
                                // 渐隐只加在最后一个 block 上：整段套会把每个段落的行尾都淡掉。
                                val tail = row.streamingTail && index == row.blocks.lastIndex
                                when (block) {
                                    is MdBlock.Mermaid -> MermaidWebView(
                                        block.source,
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    )
                                    else -> MarkdownBlockView(
                                        block = block,
                                        modifier = Modifier.fillMaxWidth(),
                                        tailFade = tail,
                                    )
                                }
                            }
                        }
                    }
                    is MessageListRow.StreamingPlaceholder -> AssistantStreamingPlaceholder(rowModifier)
                    is MessageListRow.Actions -> Box(
                        modifier = rowModifier,
                        contentAlignment = if (row.alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        MessageActionBar(
                            onCopy = { clipboard.setText(AnnotatedString(row.text)) },
                            onRegenerate = if (row.canRegenerate) onRegenerate else null,
                            onEdit = if (row.canEdit) {
                                { onEditUser(row.messageId) }
                            } else {
                                null
                            },
                            regenerateModels = if (row.canRegenerate) models else emptyList(),
                            onRegenerateWithModel = onRegenerateWithModel,
                        )
                    }
                    is MessageListRow.Retry -> RetryBar(
                        current = row.current,
                        total = row.total,
                        onPrev = { onNavVersion(row.messageId, -1) },
                        onNext = { onNavVersion(row.messageId, 1) },
                        modifier = rowModifier,
                    )
                    is MessageListRow.EditBranch -> Box(
                        modifier = rowModifier,
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        RetryBar(
                            current = row.current,
                            total = row.total,
                            onPrev = { onNavEditSnapshot(row.messageId, -1) },
                            onNext = { onNavEditSnapshot(row.messageId, 1) },
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListState.isAtBottom(thresholdPx: Int = 48): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + thresholdPx
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom() {
    val lastIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    // 先跳到末项（确保它被测量），再把末项底边顶到视口底边——
    // 处理「末项比视口高时只显示顶部」与 contentPadding，避免流式增长时最新一行被顶到视口下方。
    scrollToItem(lastIndex)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val overshoot = last.offset + last.size - info.viewportEndOffset
    if (overshoot > 0) scrollBy(overshoot.toFloat())
}

private data class MessageRenderRequest(
    val messages: List<ChatMessage>,
    val models: List<ProviderModel>,
    val canEdit: Boolean,
)

private sealed interface MessageListRow {
    val key: String
    val topPaddingDp: Int
    val contentType: String

    data class User(
        val message: ChatMessage,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = message.messageId
        override val contentType = "user"
    }

    data class Pending(
        val messageId: String,
        val text: String,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:pending"
        override val contentType = "assistant-pending"
    }

    data class Fragment(
        val messageId: String,
        val fragment: MessageFragment,
        val streamingTail: Boolean,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:fragment:${fragment.id}"
        override val contentType = fragment::class.simpleName ?: "fragment"
    }

    /** 连续联网搜索仅在展示层合为一行；原始 ToolCall fragments 仍各自保留。 */
    data class ToolGroup(
        val messageId: String,
        val fragments: List<MessageFragment.ToolCall>,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:tool-group:${fragments.first().id}"
        override val contentType = "tool-group"
    }

    /** 助手正文：单个 Text fragment 解析出的全部 markdown block，合并渲染以支持跨段选择。 */
    data class AssistantText(
        val messageId: String,
        val fragmentId: String,
        val blocks: List<MdBlock>,
        /** 是否是流式中消息的最后一个正文片段（尾部渐隐只作用于它）。 */
        val streamingTail: Boolean,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:text:$fragmentId"
        override val contentType = "assistant-text"
    }

    data class StreamingPlaceholder(
        val messageId: String,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:streaming"
        override val contentType = "streaming"
    }

    /** 消息下方操作栏（复制 / 编辑 / 重新生成）。 */
    data class Actions(
        val messageId: String,
        val text: String,
        val canRegenerate: Boolean,
        val canEdit: Boolean = false,
        val alignEnd: Boolean = false,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:actions"
        override val contentType = "actions"
    }

    /** 重试版本切换栏（‹ n/m ›，仅最新助手消息且版本>1）。 */
    data class Retry(
        val messageId: String,
        val current: Int,
        val total: Int,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:retry"
        override val contentType = "retry"
    }

    /** 用户消息的编辑分支切换条；与助手重试版本共用 [RetryBar] 样式。 */
    data class EditBranch(
        val messageId: String,
        val current: Int,
        val total: Int,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:branch"
        override val contentType = "branch"
    }
}

private fun List<ChatMessage>.toMessageRows(
    parseMarkdown: Boolean,
    modelDisplayNameOf: (String) -> String,
    canEditUser: Boolean = true,
): List<MessageListRow> {
    val rows = ArrayList<MessageListRow>()
    val lastAssistantId = lastOrNull { it.role == Role.ASSISTANT }?.messageId
    val lastUserId = lastOrNull { it.role == Role.USER }?.messageId
    val lastEditedUserId = lastOrNull {
        it.role == Role.USER && EditSnapshots.view(it.metadata[EditSnapshots.KEY]) != null
    }?.messageId
    var firstRowInList = true

    fun nextTopPadding(startsMessage: Boolean): Int = when {
        firstRowInList -> 0
        startsMessage -> 10
        else -> 4
    }

    fun addRow(startsMessage: Boolean, row: (Int) -> MessageListRow) {
        rows += row(nextTopPadding(startsMessage))
        firstRowInList = false
    }

    forEach { message ->
        var emittedForMessage = false

        fun addMessageRow(row: (Int) -> MessageListRow) {
            addRow(startsMessage = !emittedForMessage, row = row)
            emittedForMessage = true
        }

        if (message.role == Role.USER) {
            addMessageRow { top -> MessageListRow.User(message, top) }
            val branches = EditSnapshots.view(message.metadata[EditSnapshots.KEY])
            val copyText = message.metadata["displayContent"]?.takeIf { it.isNotBlank() }
                ?: message.rawText.orEmpty()
            if (copyText.isNotBlank() || message.attachments.isNotEmpty()) {
                addRow(startsMessage = false) { top ->
                    MessageListRow.Actions(
                        messageId = message.messageId,
                        text = copyText,
                        canRegenerate = false,
                        // 已有分支的、以及最后一条用户消息可编辑；中途消息不给入口，
                        // 避免在长对话里误编辑（分支条也只挂在最后被编辑的那条上）。
                        canEdit = canEditUser &&
                            (branches != null || message.messageId == lastUserId),
                        alignEnd = true,
                        topPaddingDp = top,
                    )
                }
            }
            // 与 Web 一致：分支条只显示在「最后一条被编辑过的用户消息」上。
            if (branches != null && message.messageId == lastEditedUserId) {
                addRow(startsMessage = false) { top ->
                    MessageListRow.EditBranch(
                        messageId = message.messageId,
                        current = branches.position - 1,
                        total = branches.total,
                        topPaddingDp = top,
                    )
                }
            }
            return@forEach
        }

        val assistantLabel = message.assistantHeaderLabel(modelDisplayNameOf)
        if (assistantLabel != null) {
            addMessageRow { top -> MessageListRow.Pending(message.messageId, assistantLabel, top) }
        }

        // 工具已经返回成功、但模型尚未开始下一步输出时，不另加「分析工具结果」提示；
        // 仅把当前最后一张成功工具卡在展示层继续保持为「进行中」。真实 fragment 状态不变。
        val heldToolId = message.heldToolProgressId()
        var fragmentIndex = 0
        while (fragmentIndex < message.fragments.size) {
            val fragment = message.fragments[fragmentIndex]
            val searchTools = consecutiveWebSearchTools(message.fragments, fragmentIndex)
            if (searchTools.isNotEmpty()) {
                val displayTools = searchTools.map { tool ->
                    if (tool.id == heldToolId) tool.copy(status = ToolStatus.RUNNING) else tool
                }
                addMessageRow { top -> MessageListRow.ToolGroup(message.messageId, displayTools, top) }
                fragmentIndex += searchTools.size
                continue
            }
            val streamingTail = message.isStreamingTail(fragment)
            if (parseMarkdown && fragment is MessageFragment.Text) {
                // 正文 markdown 的所有 block 合并进单个 row，外层套 SelectionContainer：
                // 这样跨段落可连选（长按拖拽 → 系统复制 toolbar）。拆成多行（每 block 一个 LazyColumn
                // item）会让 SelectionContainer 止于单 block，无法跨段选择。
                val blocks = RenderCache.blocks(fragment.markdown)
                // 尾部渐隐只给「流式中消息的最后一个片段」——中间片段已定稿，跟着虚会显得没写完。
                addMessageRow { top ->
                    MessageListRow.AssistantText(
                        messageId = message.messageId,
                        fragmentId = fragment.id,
                        blocks = blocks,
                        streamingTail = streamingTail,
                        topPaddingDp = top,
                    )
                }
            } else {
                val displayFragment = if (fragment is MessageFragment.ToolCall && fragment.id == heldToolId) {
                    fragment.copy(status = ToolStatus.RUNNING)
                } else {
                    fragment
                }
                addMessageRow { top ->
                    MessageListRow.Fragment(
                        messageId = message.messageId,
                        fragment = displayFragment,
                        streamingTail = streamingTail,
                        topPaddingDp = top,
                    )
                }
            }
            fragmentIndex += 1
        }

        if (message.status == MessageStatus.STREAMING && message.fragments.isEmpty()) {
            addMessageRow { top -> MessageListRow.StreamingPlaceholder(message.messageId, top) }
        }

        // 助手消息（非流式、有内容）下方追加操作栏：复制 + 重新生成（仅最后一条可重新生成）。
        if (message.role == Role.ASSISTANT && message.status != MessageStatus.STREAMING &&
            message.status != MessageStatus.PENDING && emittedForMessage
        ) {
            // 重试版本切换栏：仅最新助手消息、且版本数 > 1 时显示。
            if (message.messageId == lastAssistantId) {
                val attempts = RetryAttempts.decode(message.metadata[RetryAttempts.KEY_ATTEMPTS])
                if (attempts.size > 1) {
                    val current = (message.metadata[RetryAttempts.KEY_CURRENT]?.toIntOrNull() ?: attempts.lastIndex)
                        .coerceIn(0, attempts.lastIndex)
                    addRow(startsMessage = false) { top ->
                        MessageListRow.Retry(message.messageId, current, attempts.size, top)
                    }
                }
            }
            val copyText = message.rawText
                ?: message.fragments.filterIsInstance<MessageFragment.Text>().joinToString("\n") { it.markdown }
            if (copyText.isNotBlank()) {
                addRow(startsMessage = false) { top ->
                    MessageListRow.Actions(
                        messageId = message.messageId,
                        text = copyText,
                        canRegenerate = message.messageId == lastAssistantId,
                        topPaddingDp = top,
                    )
                }
            }
        }
    }

    return rows
}

internal fun ChatMessage.isStreamingTail(fragment: MessageFragment): Boolean =
    status == MessageStatus.STREAMING &&
        fragment.id == fragments.lastOrNull()?.id &&
        (fragment is MessageFragment.Text || fragment is MessageFragment.Thinking)

/**
 * 从 [startIndex] 起收集可聚合的连续联网搜索。
 * 任意正文、思考或其他工具都会形成分组边界；只有两次及以上才返回分组，
 * 单次搜索继续使用原有 ToolCall 渲染和运行中展开行为。
 */
internal fun consecutiveWebSearchTools(
    fragments: List<MessageFragment>,
    startIndex: Int,
): List<MessageFragment.ToolCall> {
    if (startIndex !in fragments.indices) return emptyList()
    val tools = fragments
        .asSequence()
        .drop(startIndex)
        .takeWhile { fragment -> fragment is MessageFragment.ToolCall && fragment.isWebSearchTool() }
        .map { it as MessageFragment.ToolCall }
        .toList()
    return tools.takeIf { it.size > 1 }.orEmpty()
}

private fun MessageFragment.ToolCall.isWebSearchTool(): Boolean =
    name == "search_web" || name == "web_search"

/**
 * 返回流式消息当前需要在展示层继续保持「进行中」的工具。
 *
 * 工具真实终态仍会立即写入 fragment；只要模型还没有在它之后输出新的思考或正文，
 * 最后一张成功工具卡就继续转圈。后续工具出现时它会成为新的最后工具，因此前一张会恢复完成；
 * 图片等工具产物不算模型续写，图像生成卡也能覆盖等待模型接续的时间。
 */
internal fun ChatMessage.heldToolProgressId(): String? {
    if (status != MessageStatus.STREAMING) return null
    val toolIndex = fragments.indexOfLast { it is MessageFragment.ToolCall }
    if (toolIndex < 0) return null
    val tool = fragments[toolIndex] as MessageFragment.ToolCall
    if (tool.status != ToolStatus.SUCCESS) return null
    val modelContinued = fragments
        .asSequence()
        .drop(toolIndex + 1)
        .any { it is MessageFragment.Text || it is MessageFragment.Thinking }
    return tool.id.takeUnless { modelContinued }
}

private fun ChatMessage.assistantHeaderLabel(modelDisplayNameOf: (String) -> String): String? {
    if (role != Role.ASSISTANT) return null
    val hasContent = fragments.isNotEmpty()
    // 候选取 metadata 里的(可能是路由后名/原始名)或消息的 model;再统一过一遍映射,
    // 把原始 id 转成选择器 displayName（已是友好名则原样返回）。
    val raw = metadata["modelDisplayName"]?.takeIf { it.isNotBlank() } ?: model?.takeIf { it.isNotBlank() }
    val modelLabel = raw?.let(modelDisplayNameOf)
    return if (hasContent) {
        modelLabel
    } else {
        metadata["pending"]?.takeIf { it.isNotBlank() } ?: modelLabel
    }
}
