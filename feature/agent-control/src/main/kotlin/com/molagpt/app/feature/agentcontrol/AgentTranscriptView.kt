package com.molagpt.app.feature.agentcontrol

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.AgentToolStatus
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.PulsingDots
import com.molagpt.app.core.render.StreamingMarkdownView
import com.molagpt.app.core.render.ThinkingView
import com.molagpt.app.core.render.ToolCallView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 渲染折叠后的 transcript：复用 `:core:render` 的 [StreamingMarkdownView] / [ThinkingView] /
 * [ToolCallView]，与对话页同一视觉。每块用 [AgentBlock.key] 作 LazyColumn 稳定 key。
 */
@Composable
fun AgentTranscriptView(
    blocks: List<AgentBlock>,
    modifier: Modifier = Modifier,
    pendingCommandId: String? = null,
    pendingText: String? = null,
    deliveryStatus: String? = null,
    canRetrySend: Boolean = false,
    onRetrySend: () -> Unit = {},
    onPermissionChoice: (permissionId: String, choice: String) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bottomThresholdPx = with(LocalDensity.current) { 32.dp.roundToPx() }
    var autoFollow by remember { mutableStateOf(true) }
    var userScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userScrolling = true
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    if (!listState.isScrollInProgress) userScrolling = false
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

    val pendingUser = pendingText?.let { text ->
        blocks.asReversed().filterIsInstance<AgentBlock.User>().firstOrNull { block ->
            if (pendingCommandId != null) block.commandId == pendingCommandId
            else block.commandId == null && block.text == text
        }
    }
    val appendPendingUser = pendingText != null && pendingUser == null
    val contentVersion = transcriptContentVersion(blocks, pendingCommandId, pendingText, deliveryStatus)
    val itemCount = blocks.size + if (appendPendingUser) 1 else 0

    // Markdown 在后台解析后会再次长高；重复校准几帧，确保跟随时最后一行仍在视口内。
    LaunchedEffect(contentVersion, autoFollow) {
        if (!autoFollow || itemCount == 0) return@LaunchedEffect
        repeat(4) {
            listState.scrollToBottom()
            delay(50)
        }
    }

    val showJumpToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf false
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
            listState.distanceToBottomPx() > viewport / 3
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            items(blocks, key = { it.key }) { block ->
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    when (block) {
                        is AgentBlock.User -> UserMessageBlock(
                            text = block.text,
                            deliveryStatus = deliveryStatus.takeIf { block.key == pendingUser?.key },
                            canRetrySend = canRetrySend && block.key == pendingUser?.key,
                            onRetrySend = onRetrySend,
                        )
                        is AgentBlock.AssistantText -> AssistantTextBlock(block.text)
                        is AgentBlock.Thinking -> SelectionContainer { ThinkingView(text = block.text) }
                        is AgentBlock.Pending -> PendingBlock()
                        is AgentBlock.Tool -> SelectionContainer {
                            if (isAgentTodoTool(block.name)) {
                                AgentTodoCard(block.argumentsJson)
                            } else {
                                ToolCallView(
                                    name = block.name,
                                    status = block.status.toRenderStatus(),
                                    label = agentToolLabel(block.name, block.argumentsJson) ?: block.title,
                                    resultPreview = block.resultPreview,
                                    argsJson = block.argumentsJson,
                                )
                            }
                        }
                        is AgentBlock.Permission -> PermissionCard(block, onPermissionChoice)
                        is AgentBlock.Error -> ErrorBlock(block.message)
                        is AgentBlock.Usage -> SelectionContainer { UsageBar(block) }
                    }
                }
            }
            if (appendPendingUser) {
                item(key = "pending-user-${pendingCommandId ?: pendingText.hashCode()}") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        UserMessageBlock(
                            text = checkNotNull(pendingText),
                            deliveryStatus = deliveryStatus,
                            canRetrySend = canRetrySend,
                            onRetrySend = onRetrySend,
                        )
                    }
                }
            }
        }
        JumpToLatestButton(
            visible = showJumpToBottom,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            onClick = {
                autoFollow = true
                scope.launch { listState.scrollToBottom(animated = true) }
            },
        )
    }
}

@Composable
private fun AssistantTextBlock(text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        SelectionContainer { StreamingMarkdownView(markdown = text) }
        CopyAction(text)
    }
}

@Composable
private fun UserMessageBlock(
    text: String,
    deliveryStatus: String?,
    canRetrySend: Boolean,
    onRetrySend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        UserBubble(text)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            deliveryStatus?.let {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canRetrySend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                if (canRetrySend) {
                    TextAction("重试", onRetrySend)
                }
            }
            CopyAction(text)
        }
    }
}

@Composable
private fun PendingBlock() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDots()
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val bubbleShape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 4.dp,
            bottomEnd = 16.dp,
            bottomStart = 16.dp,
        )
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), bubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        CopyAction(message)
    }
}

@Composable
private fun CopyAction(text: String) {
    val clipboard = LocalClipboardManager.current
    TextAction("复制") { clipboard.setText(AnnotatedString(text)) }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 权限提示卡：待决时显示完整卡片（命令 + 三按钮）；已处理后折叠为一行紧凑提示。
 */
@Composable
private fun PermissionCard(
    block: AgentBlock.Permission,
    onPermissionChoice: (permissionId: String, choice: String) -> Unit,
) {
    // 折叠态：批准/拒绝/被后续事件自动处理后，收成一行小字，不再占用大卡片与命令详情。
    if (block.resolved != null) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                permissionResolvedLabel(block.resolved),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text("· ${block.toolName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(14.dp),
    ) {
        Text(
            "权限请求 · ${block.toolName}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        block.description?.takeIf { it.isNotBlank() }?.let {
            SelectionContainer {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        // 决策依据：完整命令/参数。此前只显示 toolName + 描述，argumentsJson 到了手机
        // 却没渲染，等于让用户盲批。默认收起为 8 行，点击展开全文。
        val detail = remember(block.argumentsJson) {
            agentPermissionDetail(block.toolName, block.argumentsJson)
        }
        if (detail != null) {
            var expanded by remember(block.permissionId) { mutableStateOf(false) }
            val expandable = detail.length > 480 || detail.count { it == '\n' } >= 8
            SelectionContainer {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.92f),
                    maxLines = if (expanded || !expandable) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (expandable) {
                    TextAction(if (expanded) "收起" else "展开") { expanded = !expanded }
                }
                CopyAction(detail)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onPermissionChoice(block.permissionId, "Once") },
                modifier = Modifier.weight(1f),
            ) {
                Text("批准一次")
            }
            FilledTonalButton(
                onClick = { onPermissionChoice(block.permissionId, "Always") },
                modifier = Modifier.weight(1f),
            ) {
                Text("本会话批准")
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = { onPermissionChoice(block.permissionId, "Deny") }) {
                Text("拒绝")
            }
        }
    }
}

private fun permissionResolvedLabel(choice: String): String = when (choice) {
    "Once" -> "✓ 已批准一次"
    "Always" -> "✓ 本会话已批准"
    "Deny" -> "✕ 已拒绝"
    else -> "✓ 权限请求已处理"
}

/** Read-only token usage display at turn end. Context is managed internally by Claude/Code. */
@Composable
private fun UsageBar(block: AgentBlock.Usage) {
    val label = when {
        block.totalTokens != null -> "Tokens: ${block.totalTokens}"
        block.inputTokens != null || block.outputTokens != null -> buildString {
            append("Tokens: in=${block.inputTokens ?: "?"} out=${block.outputTokens ?: "?"}")
        }
        else -> "Tokens: -"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun AgentToolStatus.toRenderStatus(): ToolStatus = when (this) {
    AgentToolStatus.Started, AgentToolStatus.Running -> ToolStatus.RUNNING
    AgentToolStatus.Completed -> ToolStatus.SUCCESS
    AgentToolStatus.Failed -> ToolStatus.FAILED
}

private fun transcriptContentVersion(
    blocks: List<AgentBlock>,
    pendingCommandId: String?,
    pendingText: String?,
    deliveryStatus: String?,
): String = buildString {
    append(blocks.size)
    blocks.takeLast(3).forEach { block ->
        append(':').append(block.key).append(':').append(block.hashCode())
    }
    append(':').append(pendingCommandId)
    append(':').append(pendingText?.hashCode())
    append(':').append(deliveryStatus)
}

@Composable
private fun JumpToLatestButton(
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

private fun androidx.compose.foundation.lazy.LazyListState.isAtBottom(thresholdPx: Int): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + thresholdPx
}

private fun androidx.compose.foundation.lazy.LazyListState.distanceToBottomPx(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
    return (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0)
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom(animated: Boolean = false) {
    val lastIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    if (animated) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    val last = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val overshoot = last.offset + last.size - layoutInfo.viewportEndOffset
    if (overshoot > 0) {
        if (animated) animateScrollBy(overshoot.toFloat()) else scrollBy(overshoot.toFloat())
    }
}
