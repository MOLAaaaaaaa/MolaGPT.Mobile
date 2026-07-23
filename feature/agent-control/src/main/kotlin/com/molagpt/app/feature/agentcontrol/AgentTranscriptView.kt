package com.molagpt.app.feature.agentcontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.AgentToolStatus
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.render.StreamingMarkdownView
import com.molagpt.app.core.render.ThinkingView
import com.molagpt.app.core.render.ToolCallView
import com.molagpt.app.core.render.PulsingDots

/**
 * 渲染折叠后的 transcript：复用 `:core:render` 的 [StreamingMarkdownView] / [ThinkingView] /
 * [ToolCallView]，与对话页同一视觉。每块用 [AgentBlock.key] 作 LazyColumn 稳定 key。
 */
@Composable
fun AgentTranscriptView(
    blocks: List<AgentBlock>,
    modifier: Modifier = Modifier,
    onPermissionChoice: (permissionId: String, choice: String) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    // 加载/新内容到达时落到**真正的底部**。scrollToItem(last) 只把最后一块的顶部对到视口顶部，
    // 末块若很高（长回答/工具卡）其底部仍在屏外，所以用超大 offset 钳到最末；再重复几次，等
    // markdown / 工具卡异步测量后撑高了也能稳稳停在底。
    LaunchedEffect(blocks.size) {
        if (blocks.isEmpty()) return@LaunchedEffect
        repeat(4) {
            listState.scrollToItem(blocks.size - 1, Int.MAX_VALUE)
            delay(50)
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(blocks, key = { it.key }) { block ->
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                when (block) {
                    is AgentBlock.User -> UserBubble(block.text)
                    is AgentBlock.AssistantText -> StreamingMarkdownView(markdown = block.text)
                    is AgentBlock.Thinking -> ThinkingView(text = block.text)
                    is AgentBlock.Pending -> PendingBlock()
                    is AgentBlock.Tool ->
                        if (isAgentTodoTool(block.name)) {
                            AgentTodoCard(block.argumentsJson)
                        } else {
                            ToolCallView(
                                name = block.name,
                                status = block.status.toRenderStatus(),
                                // Friendly title: "编辑 Foo.kt" / "读取 bar.cs" / "终端 · npm test".
                                // Falls back to the desktop-provided title, then the raw name.
                                label = agentToolLabel(block.name, block.argumentsJson) ?: block.title,
                                resultPreview = block.resultPreview,
                                argsJson = block.argumentsJson,
                            )
                        }
                    is AgentBlock.Permission -> PermissionCard(block, onPermissionChoice)
                    is AgentBlock.Error -> ErrorBubble(block.message)
                    is AgentBlock.Usage -> UsageBar(block)
                }
            }
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
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ErrorBubble(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
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
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        // 决策依据：完整命令/参数。此前只显示 toolName + 描述，argumentsJson 到了手机
        // 却没渲染，等于让用户盲批。默认收起为 8 行，点击展开全文。
        val detail = remember(block.argumentsJson) {
            agentPermissionDetail(block.toolName, block.argumentsJson)
        }
        if (detail != null) {
            var expanded by remember(block.permissionId) { mutableStateOf(false) }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.92f),
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextButton(onClick = { onPermissionChoice(block.permissionId, "Once") }) {
                Text("批准一次")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { onPermissionChoice(block.permissionId, "Always") }) {
                Text("本会话批准")
            }
            Spacer(Modifier.width(4.dp))
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
        else -> "Tokens: —"
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
