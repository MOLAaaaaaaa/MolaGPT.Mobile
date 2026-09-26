package com.molagpt.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.RetryBar
import com.molagpt.app.core.render.MolaMotion
import kotlinx.coroutines.launch
import com.molagpt.app.core.markdown.MdBlock
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ContextCompactionMark
import com.molagpt.app.core.model.ContextCompactionProgress
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStats
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.SourceReference
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.storage.EditSnapshots
import com.molagpt.app.core.render.LocalCitationSources
import com.molagpt.app.core.render.LocalFaviconRenderer
import com.molagpt.app.core.render.LocalMarkdownImageRenderer
import com.molagpt.app.core.render.MarkdownRenderScheduler
import com.molagpt.app.core.render.MarkdownBlockView
import com.molagpt.app.core.render.RenderCache
import com.molagpt.app.core.storage.RetryAttempts
import com.molagpt.app.feature.file.RemoteFavicon
import com.molagpt.app.feature.file.RemoteImage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    spend: com.molagpt.app.core.storage.ConversationSpend? = null,
    modifier: Modifier = Modifier,
    onRegenerate: () -> Unit = {},
    onRegenerateWithModel: (String) -> Unit = {},
    onEditUser: (String) -> Unit = {},
    onEditAssistant: (String) -> Unit = {},
    canEdit: Boolean = true,
    /** 编辑回答仅 BYOK 会话开放；用户消息编辑不受此限。 */
    canEditAssistant: Boolean = false,
    models: List<ProviderModel> = emptyList(),
    onNavVersion: (String, Int) -> Unit = { _, _ -> },
    onNavEditSnapshot: (String, Int) -> Unit = { _, _ -> },
    /** 上下文压缩记录，显示在各自发生的时间点。 */
    compactions: List<ContextCompactionMark> = emptyList(),
    /** 正在进行的压缩。 */
    compacting: ContextCompactionProgress? = null,
    onCancelCompaction: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var autoFollow by remember { mutableStateOf(true) }
    StreamRenderPacingEffect(messages.any(ChatMessage::isStreaming))
    val renderRequests = remember { Channel<MessageRenderRequest>(Channel.CONFLATED) }
    val renderRequest = MessageRenderRequest(messages, models, canEdit, canEditAssistant, compactions, compacting)
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
            canEditAssistant = canEditAssistant,
            compactions = compactions,
            compacting = compacting,
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
                    canEditAssistant = latest.canEditAssistant,
                    compactions = latest.compactions,
                    compacting = latest.compacting,
                )
            }
            renderedRequest = latest
            value = renderedRows
        }
    }

    // 贴底判定的容差。原来写死 48 **像素**——在 3x 屏上只有 16dp，手指停在离底一点点的地方
    // 就判成「没贴底」，跟随再也不恢复。改成按密度换算的 dp，手感才和屏幕无关。
    val bottomThresholdPx = with(LocalDensity.current) { 32.dp.roundToPx() }

    var openedCompaction by remember { mutableStateOf<ContextCompactionMark?>(null) }
    openedCompaction?.let { mark ->
        ContextCompactionSheet(
            mark = mark,
            coveredMessages = messages.indexOfFirst { it.messageId == mark.anchorMessageId }
                .takeIf { it >= 0 }
                ?.plus(1),
            onDismiss = { openedCompaction = null },
        )
    }

    // 跟随判定必须绑在**用户手势**上。别拿 isScrollInProgress 当手势的代理——它由
    // ScrollableState.scroll{} 的互斥锁驱动，程序滚动同样会置位（scrollToBottom 自己、统计卡展开时的
    // bringIntoView 都算）。一旦某次程序滚动跨了帧，snapshotFlow 就会在中途采到「正在滚 + 还没贴底」，
    // 把跟随意图误关掉；而且滚完也不会恢复——更新被 if(scrolling) 挡住，滚动结束那一刻不做任何处理。
    // 表现就是内容越高越容易「跟丢」，视角卡住不动（工具卡一次涨几百 dp 时最容易复现）。
    //
    // DragInteraction 只有真实拖拽才产生，程序滚动不发，用它当闸门就没有误判。
    // 闸门保持到滚动**完全停下**为止，这样抬手后的惯性滑行也算用户主导——一甩到底同样能恢复跟随。
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userScrolling = true
                // 抬手/取消时如果已经不在滚（没有惯性可跟），立刻关闸。
                // 兜底而已：正常情况由下面「滚动停下」那一路关闸，那条才覆盖得到惯性滑行。
                // 少了这句，一次没产生任何滚动的拖拽会把闸门永久卡在开启状态。
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    if (!listState.isScrollInProgress) userScrolling = false
                else -> Unit
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.isAtBottom(bottomThresholdPx) }
            .collect { (scrolling, atBottom) ->
                if (!userScrolling) return@collect
                autoFollow = atBottom
                if (!scrolling) userScrolling = false
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

    // 「回到最新」按钮的出现条件：末项底边还在视口下方超过三分之一屏，或者末项压根没进视口。
    // 用比例而不是固定 dp——同样的绝对距离在小屏上是"远得看不见"，在平板上只是"差一点"。
    val showJumpToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf false
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
            listState.distanceToBottomPx() > viewport / 3
        }
    }

    CompositionLocalProvider(
        LocalMarkdownImageRenderer provides { url, imgModifier ->
            RemoteImage(url, imgModifier)
        },
        LocalFaviconRenderer provides { url, imgModifier ->
            RemoteFavicon(url, imgModifier)
        },
    ) {
        Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
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
                    is MessageListRow.Fragment -> CompositionLocalProvider(
                        LocalCitationSources provides row.citationSources,
                    ) {
                        FragmentRenderer(
                            fragment = row.fragment,
                            modifier = rowModifier,
                            streamingTail = row.streamingTail,
                        )
                    }
                    is MessageListRow.AssistantText -> CompositionLocalProvider(
                        LocalCitationSources provides row.citationSources,
                    ) {
                        SelectionContainer(modifier = rowModifier) {
                            Column {
                                row.blocks.forEachIndexed { index, block ->
                                    // 渐隐只加在最后一个 block 上：整段套会把每个段落的行尾都淡掉。
                                    val tail = row.streamingTail && index == row.blocks.lastIndex
                                    MarkdownBlockView(
                                        block = block,
                                        modifier = Modifier.fillMaxWidth(),
                                        tailFade = tail,
                                        // 网页代码块「卡片 / 代码」的单独切换按这个键记在对话页里，
                                        // 滚出屏幕再回来不丢。
                                        blockKey = "${row.messageId}:${row.fragmentId}:$index",
                                    )
                                }
                            }
                        }
                    }
                    is MessageListRow.StreamingPlaceholder -> AssistantStreamingPlaceholder(rowModifier)
                    is MessageListRow.Stats -> MessageStatsRow(row.stats, rowModifier, spend)
                    is MessageListRow.Actions -> Box(
                        modifier = rowModifier,
                        contentAlignment = if (row.alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        MessageActionBar(
                            onCopy = { clipboard.setText(AnnotatedString(row.text)) },
                            onRegenerate = if (row.canRegenerate) onRegenerate else null,
                            onEdit = if (row.canEdit) {
                                { if (row.editsAssistant) onEditAssistant(row.messageId) else onEditUser(row.messageId) }
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
                    is MessageListRow.Compaction -> ContextCompactionLine(
                        mark = row.mark,
                        progress = row.progress,
                        onOpen = { openedCompaction = it },
                        onCancel = onCancelCompaction,
                        modifier = rowModifier,
                    )
                }
            }
        }
        JumpToBottomButton(
            visible = showJumpToBottom,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            onClick = {
                // 点一下 = 明确表达「我要看最新的」，所以顺手把跟随打开：
                // 之后的流式增长会继续贴底，不用再点第二次。
                autoFollow = true
                scope.launch { listState.scrollToBottom(animated = true) }
            },
        )
        }
    }
}

/** 视线离最新内容太远时浮出的「回到最新」圆钮。 */
@Composable
private fun JumpToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(MolaMotion.standard()) + scaleIn(MolaMotion.emphasized(), initialScale = 0.8f),
        exit = fadeOut(MolaMotion.standard()) + scaleOut(MolaMotion.standard(), targetScale = 0.8f),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            shadowElevation = 4.dp,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "回到最新消息",
                modifier = Modifier.padding(6.dp).size(22.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListState.isAtBottom(thresholdPx: Int = 48): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + thresholdPx
}

/**
 * 视线离「最新内容」有多远（px）。末项还没进视口就返回 [Int.MAX_VALUE]——
 * 下面整整还有一项没看到，谈不上「差一点点」。
 */
private fun androidx.compose.foundation.lazy.LazyListState.distanceToBottomPx(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
    return (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0)
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom(animated: Boolean = false) {
    val lastIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    // 先跳到末项（确保它被测量），再把末项底边顶到视口底边——
    // 处理「末项比视口高时只显示顶部」与 contentPadding，避免流式增长时最新一行被顶到视口下方。
    if (animated) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val overshoot = last.offset + last.size - info.viewportEndOffset
    if (overshoot > 0) {
        if (animated) animateScrollBy(overshoot.toFloat()) else scrollBy(overshoot.toFloat())
    }
}

private data class MessageRenderRequest(
    val messages: List<ChatMessage>,
    val models: List<ProviderModel>,
    val canEdit: Boolean,
    val canEditAssistant: Boolean,
    val compactions: List<ContextCompactionMark>,
    val compacting: ContextCompactionProgress?,
)

internal sealed interface MessageListRow {
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
        /** 同条消息的联网来源，供正文里的引用角标解编号。 */
        val citationSources: List<SourceReference> = emptyList(),
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
        /** 同条消息的联网来源，供正文里的引用角标解编号。 */
        val citationSources: List<SourceReference> = emptyList(),
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

    /** 单次请求的 token / 速度统计（仅 BYOK、且用户没关掉显示时才生成此行）。 */
    data class Stats(
        val messageId: String,
        val stats: MessageStats,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "$messageId:stats"
        override val contentType = "stats"
    }

    /** 消息下方操作栏（复制 / 编辑 / 重新生成）。 */
    data class Actions(
        val messageId: String,
        val text: String,
        val canRegenerate: Boolean,
        val canEdit: Boolean = false,
        /** 编辑的是助手回答：改写后存成一个新版本，不重新请求模型。 */
        val editsAssistant: Boolean = false,
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

    /**
     * 上下文压缩：进行中时 [mark] 为 null、[progress] 非空，完成后换成记录。
     * 两种状态共用同一个 key（即检查点 id），完成时这一行原地变化，而不是换一行。
     */
    data class Compaction(
        val id: String,
        val mark: ContextCompactionMark?,
        val progress: ContextCompactionProgress?,
        override val topPaddingDp: Int,
    ) : MessageListRow {
        override val key = "compaction:$id"
        override val contentType = "compaction"
    }
}

internal fun List<ChatMessage>.toMessageRows(
    parseMarkdown: Boolean,
    modelDisplayNameOf: (String) -> String,
    canEditUser: Boolean = true,
    canEditAssistant: Boolean = false,
    compactions: List<ContextCompactionMark> = emptyList(),
    compacting: ContextCompactionProgress? = null,
): List<MessageListRow> {
    val rows = ArrayList<MessageListRow>()
    val slots = placeCompactions(this, compactions)
    // 回答开始前的自动压缩：进度显示在这条回答的位置，回答本身等压缩完再出现。
    val compactingBefore = compacting
        ?.takeIf { it.running && it.reason.duringReply }
        ?.let { lastOrNull()?.takeIf { last -> last.role == Role.ASSISTANT && last.isStreaming } }
    var compactingPlaced = compacting == null || !compacting.running
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

    fun addCompactionRow(mark: ContextCompactionMark) {
        addRow(startsMessage = true) { top -> MessageListRow.Compaction(mark.id, mark, null, top) }
    }

    fun addCompactionProgressRow(progress: ContextCompactionProgress) {
        addRow(startsMessage = true) { top -> MessageListRow.Compaction(progress.id, null, progress, top) }
    }

    slots.leading.forEach { mark -> addCompactionRow(mark) }

    forEach { message ->
        var emittedForMessage = false

        fun addMessageRow(row: (Int) -> MessageListRow) {
            addRow(startsMessage = !emittedForMessage, row = row)
            emittedForMessage = true
        }

        // 压缩记录排在这条消息的全部行（含操作栏）之后：它标的是「之后」，不属于这条消息。
        fun addCompactions() {
            slots.after[message.messageId]?.forEach { mark -> addCompactionRow(mark) }
        }

        slots.before[message.messageId]?.forEach { mark -> addCompactionRow(mark) }
        if (compacting != null && message.messageId == compactingBefore?.messageId) {
            addCompactionProgressRow(compacting)
            compactingPlaced = true
            if (message.fragments.isEmpty()) return@forEach
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
            addCompactions()
            return@forEach
        }

        val assistantLabel = message.assistantHeaderLabel(modelDisplayNameOf)
        if (assistantLabel != null) {
            addMessageRow { top -> MessageListRow.Pending(message.messageId, assistantLabel, top) }
        }

        // 工具已经返回成功、但模型尚未开始下一步输出时，不另加「分析工具结果」提示；
        // 仅把当前最后一张成功工具卡在展示层继续保持为「进行中」。真实 fragment 状态不变。
        val heldToolId = message.heldToolProgressId()
        // 来源列表整条消息共用：正文里的 <ref source="N" /> 要靠它把编号解成链接，
        // 而它往往比正文晚到（后端在 [DONE] 前才发 molagpt_sources）。
        val searchResults = message.fragments.filterIsInstance<MessageFragment.SearchResult>()
        val citationSources = searchResults.flatMap { it.refs }
        // 来源片段不按到达位置渲染，一律挪到消息末尾。它是整条回答的汇总，不是发生在某个
        // 时刻的事件——BYOK 自带搜索一搜完就发来源，照原位画会夹在两张搜索卡中间，还会把
        // 「连续搜索合成一张卡」的判定切断（那个判定看的是相邻片段）。
        val bodyFragments = message.fragments.filter { it !is MessageFragment.SearchResult }
        var fragmentIndex = 0
        while (fragmentIndex < bodyFragments.size) {
            val fragment = bodyFragments[fragmentIndex]
            val searchTools = consecutiveWebSearchTools(bodyFragments, fragmentIndex)
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
                        citationSources = citationSources,
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
                        citationSources = citationSources,
                        topPaddingDp = top,
                    )
                }
            }
            fragmentIndex += 1
        }

        // 来源胶囊要等这条回答写完才出现。BYOK 一搜完就把来源发下来，此时正文还在往下长，
        // 提前画出来就成了「正文中间插了一条汇总」，还会在后续搜索里跳着改数字。等写完再画，
        // 它和底下的统计行、操作栏是同一批出现的收尾信息。正文里的 <ref> 角标不受影响——
        // 那条通道靠 citationSources 解编号，来源一到就能点开。
        val sourcesSettled = message.status != MessageStatus.STREAMING &&
            message.status != MessageStatus.PENDING
        if (sourcesSettled && searchResults.isNotEmpty() && citationSources.isNotEmpty()) {
            // 多个来源片段合成一枚胶囊。同一条消息里通常只有一个（SetSources 是就地更新），
            // 合并只是为了两条链路真出现两个时不画成两枚。
            val merged = searchResults.first().copy(
                query = searchResults.mapNotNull { it.query.takeIf(String::isNotBlank) }
                    .distinct()
                    .joinToString(" / "),
                refs = citationSources,
            )
            addMessageRow { top ->
                MessageListRow.Fragment(
                    messageId = message.messageId,
                    fragment = merged,
                    streamingTail = false,
                    citationSources = citationSources,
                    topPaddingDp = top,
                )
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
            // 统计行排在操作栏之上：先「这次花了多少」，再「拿它做什么」。
            // durationMs 只有 ChatRepository 的 BYOK 分支会写，所以拿它当「这条属于 BYOK」的判据——
            // 光看 tokens 不行：那个键 MolaGPT 链路也会写，而官方链路按次计费，摆 token 数会误导。
            val stats = MessageStats.from(message.metadata)?.takeIf { it.durationMs != null }
            if (stats != null) {
                addRow(startsMessage = false) { top ->
                    MessageListRow.Stats(message.messageId, stats, top)
                }
            }
            if (copyText.isNotBlank()) {
                addRow(startsMessage = false) { top ->
                    MessageListRow.Actions(
                        messageId = message.messageId,
                        text = copyText,
                        // 开场白是卡里写好的台词，不是模型生成的，「重新生成」对它没有意义
                        // （此时也还没有任何用户消息可供重答）。备选开场白走下面的版本栏切换。
                        canRegenerate = message.messageId == lastAssistantId && !message.isRoleGreeting,
                        // 只给最新一条回答开编辑口子：改中途的回答不会重新生成后文，
                        // 留在那里的只会是一段和上下文对不上的历史。
                        canEdit = canEditAssistant && message.messageId == lastAssistantId,
                        editsAssistant = true,
                        topPaddingDp = top,
                    )
                }
            }
        }
        addCompactions()
    }
    if (!compactingPlaced && compacting != null) addCompactionProgressRow(compacting)

    return rows
}

/** [placeCompactions] 的结果：记录挂在哪条消息之前或之后。 */
internal class CompactionSlots(
    val leading: List<ContextCompactionMark>,
    val before: Map<String, List<ContextCompactionMark>>,
    val after: Map<String, List<ContextCompactionMark>>,
)

/**
 * 压缩记录显示在压缩发生的时间点：压缩时已有的最后一条消息之后。
 * 例外是回答开始前的自动压缩——那条回答在压缩前就建好了（正在生成中），
 * 记录要放在它前面，也就是用户消息和回答之间。
 */
internal fun placeCompactions(
    messages: List<ChatMessage>,
    marks: List<ContextCompactionMark>,
): CompactionSlots {
    if (marks.isEmpty()) return CompactionSlots(emptyList(), emptyMap(), emptyMap())
    val leading = ArrayList<ContextCompactionMark>()
    val before = HashMap<String, MutableList<ContextCompactionMark>>()
    val after = HashMap<String, MutableList<ContextCompactionMark>>()
    for (mark in marks.sortedBy { it.createdAt }) {
        val index = messages.indexOfLast { it.createdAt <= mark.createdAt }
        if (index < 0) {
            leading += mark
            continue
        }
        val message = messages[index]
        val duringThisReply = mark.reason.duringReply &&
            message.role == Role.ASSISTANT &&
            (message.isStreaming || message.updatedAt >= mark.createdAt)
        val slot = if (duringThisReply) before else after
        slot.getOrPut(message.messageId) { ArrayList() } += mark
    }
    return CompactionSlots(leading, before, after)
}

/**
 * 渐隐只给「正在写的那一段」。末尾的来源片段不算——它渲染时被挪到了最后，而且它从来不是
 * 正在写的东西；拿它当末尾会让真正在写的正文失去渐隐。
 */
internal fun ChatMessage.isStreamingTail(fragment: MessageFragment): Boolean =
    status == MessageStatus.STREAMING &&
        fragment.id == fragments.lastOrNull { it !is MessageFragment.SearchResult }?.id &&
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
