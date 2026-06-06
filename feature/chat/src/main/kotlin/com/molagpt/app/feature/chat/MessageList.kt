package com.molagpt.app.feature.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.molagpt.app.core.render.LocalMarkdownImageRenderer
import com.molagpt.app.core.render.MarkdownBlockView
import com.molagpt.app.core.render.RenderCache
import com.molagpt.app.core.storage.RetryAttempts
import com.molagpt.app.feature.file.RemoteImage
import com.molagpt.app.feature.webview.MermaidWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onRegenerate: () -> Unit = {},
    models: List<ProviderModel> = emptyList(),
    onNavVersion: (String, Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var autoFollow by remember { mutableStateOf(true) }
    // modelId → 选择器同款 displayName(历史消息/原始 id 也映射成友好名)；models 变化时一并重算。
    val modelNameOf: (String) -> String = { id -> models.firstOrNull { it.id == id }?.displayName ?: id }
    val rows by produceState(
        initialValue = messages.toMessageRows(parseMarkdown = false, modelDisplayNameOf = modelNameOf),
        key1 = messages,
        key2 = models,
    ) {
        value = withContext(Dispatchers.Default) {
            messages.toMessageRows(parseMarkdown = true, modelDisplayNameOf = modelNameOf)
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
                    is MessageListRow.Fragment -> FragmentRenderer(fragment = row.fragment, modifier = rowModifier)
                    is MessageListRow.MarkdownBlock -> when (val block = row.block) {
                        is MdBlock.Mermaid -> MermaidWebView(block.source, rowModifier)
                        else -> MarkdownBlockView(block = block, modifier = rowModifier)
                    }
                    is MessageListRow.StreamingPlaceholder -> AssistantStreamingPlaceholder(rowModifier)
                    is MessageListRow.Actions -> MessageActionBar(
                        onCopy = { clipboard.setText(AnnotatedString(row.text)) },
                        onRegenerate = if (row.canRegenerate) onRegenerate else null,
                        modifier = rowModifier,
                    )
                    is MessageListRow.Retry -> RetryBar(
                        current = row.current,
                        total = row.total,
                        onPrev = { onNavVersion(row.messageId, -1) },
                        onNext = { onNavVersion(row.messageId, 1) },
                        modifier = rowModifier,
                    )
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
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:fragment:${fragment.id}"
        override val contentType = fragment::class.simpleName ?: "fragment"
    }

    data class MarkdownBlock(
        val messageId: String,
        val fragmentId: String,
        val blockIndex: Int,
        val block: MdBlock,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:markdown:$fragmentId:$blockIndex"
        override val contentType = block::class.simpleName ?: "markdown"
    }

    data class StreamingPlaceholder(
        val messageId: String,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:streaming"
        override val contentType = "streaming"
    }

    /** 助手消息下方操作栏（复制 / 重新生成）。 */
    data class Actions(
        val messageId: String,
        val text: String,
        val canRegenerate: Boolean,
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
}

private fun List<ChatMessage>.toMessageRows(
    parseMarkdown: Boolean,
    modelDisplayNameOf: (String) -> String,
): List<MessageListRow> {
    val rows = ArrayList<MessageListRow>()
    val lastAssistantId = lastOrNull { it.role == Role.ASSISTANT }?.messageId
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
            return@forEach
        }

        val assistantLabel = message.assistantHeaderLabel(modelDisplayNameOf)
        if (assistantLabel != null) {
            addMessageRow { top -> MessageListRow.Pending(message.messageId, assistantLabel, top) }
        }

        message.fragments.forEach { fragment ->
            if (parseMarkdown && fragment is MessageFragment.Text) {
                RenderCache.blocks(fragment.markdown).forEachIndexed { index, block ->
                    addMessageRow { top ->
                        MessageListRow.MarkdownBlock(
                            messageId = message.messageId,
                            fragmentId = fragment.id,
                            blockIndex = index,
                            block = block,
                            topPaddingDp = top,
                        )
                    }
                }
            } else {
                addMessageRow { top -> MessageListRow.Fragment(message.messageId, fragment, top) }
            }
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

private fun ChatMessage.assistantHeaderLabel(modelDisplayNameOf: (String) -> String): String? {
    if (role != Role.ASSISTANT) return null
    val hasContent = fragments.isNotEmpty()
    // 候选取 metadata 里的(可能是路由后名/原始名)或消息的 model;再统一过一遍映射,
    // 把原始 id 转成选择器同款 displayName(已是友好名则原样返回)。
    val raw = metadata["modelDisplayName"]?.takeIf { it.isNotBlank() } ?: model?.takeIf { it.isNotBlank() }
    val modelLabel = raw?.let(modelDisplayNameOf)
    return if (hasContent) {
        modelLabel
    } else {
        metadata["pending"]?.takeIf { it.isNotBlank() } ?: modelLabel
    }
}
