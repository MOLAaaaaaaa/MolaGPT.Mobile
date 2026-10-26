package com.molagpt.app.feature.agentcontrol.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.render.MolaScaffold
import com.molagpt.app.core.render.MolaBottomEdge
import com.molagpt.app.core.render.MolaTopBar
import com.molagpt.app.feature.chat.FragmentRenderer
import com.molagpt.app.feature.chat.topBarOverlap

@Composable
internal fun RemoteAgentRow(agent: RemoteAgent, now: Long, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.42f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (agent.isRunning) {
                CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                agent.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                RemoteTasks.statusText(agent.status),
                style = MaterialTheme.typography.labelMedium,
                color = if (agent.status == "failed") cs.error else cs.onSurfaceVariant,
            )
        }
        Text(
            agentSubtitle(agent, now),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        agent.currentAction?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        agent.failure?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun agentSubtitle(agent: RemoteAgent, now: Long): String {
    val elapsed = agent.elapsedMs?.let { RemoteTasks.formatElapsed(if (agent.isRunning) it + (now - agent.receivedAtMs) else it) }
    return listOfNotNull(agent.model.takeIf { it.isNotBlank() }, elapsed).joinToString(" · ")
}

/**
 * 一个子 Agent 做了什么：任务说明、思考、输出、工具调用，按发生顺序。对应电脑右侧面板的子 Agent 详情。
 * 贴在底部时跟着新内容走，用户上滑后不打断。
 */
@Composable
internal fun RemoteAgentDetailScreen(
    agent: RemoteAgent?,
    page: RemoteAgentPage,
    live: Boolean,
    statusLine: @Composable () -> Unit,
    onBack: () -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = agent?.isRunning == true
    val now = rememberNow(live && running)
    val listState = rememberLazyListState()
    val overlapPx = with(LocalDensity.current) { 24.dp.toPx() }
    var follow by remember { mutableStateOf(true) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScroll ->
            if (!canScroll) follow = true
        }
    }
    LaunchedEffect(page.activities) {
        withFrameNanos { }
        val last = listState.layoutInfo.totalItemsCount - 1
        if (follow && last >= 0) {
            listState.scrollToItem(last)
            listState.scrollBy(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size?.toFloat() ?: 0f)
        }
    }

    MolaScaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = {
            Box {
                MolaBottomEdge(Modifier.matchParentSize())
                Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) { statusLine() }
            }
        },
        topBar = {
            MolaTopBar(
                overlap = { listState.topBarOverlap(overlapPx) },
                title = {
                    Column {
                        Text(
                            agent?.label ?: "子 Agent",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = agent?.let { listOf(RemoteTasks.statusText(it.status), agentSubtitle(it, now)) }
                            ?.filter { it.isNotBlank() }?.joinToString(" · ")
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    val taskId = agent?.taskId
                    if (running && taskId != null) {
                        TextButton(onClick = { onStop(taskId) }, enabled = live) { Text("停止") }
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 8.dp, bottom = inner.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (page.hasEarlier) {
                item(key = "earlier") {
                    Text(
                        "更早的活动未显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (page.loading && page.activities.isEmpty()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }
            itemsIndexed(page.activities, key = { _, activity -> activity.id }) { index, activity ->
                AgentActivityItem(activity, streaming = live && running && index == page.activities.lastIndex, ownerStatus = agent?.status)
            }
            agent?.failure?.takeIf { it.isNotBlank() }?.let { failure ->
                item(key = "failure") {
                    Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AgentActivityItem(activity: RemoteAgentActivity, streaming: Boolean, ownerStatus: String?) {
    when (activity.kind) {
        "task" -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(12.dp),
        ) {
            Text(
                "任务",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(activity.text, style = MaterialTheme.typography.bodyMedium)
        }
        "thinking" -> FragmentRenderer(
            fragment = MessageFragment.Thinking(id = "agent:${activity.id}", text = activity.text, collapsed = !streaming),
            streamingTail = streaming,
        )
        "text" -> FragmentRenderer(
            fragment = MessageFragment.Text("agent:${activity.id}", activity.text),
            streamingTail = streaming,
        )
        "tool" -> activity.tool?.let { tool ->
            // 子 Agent 停了，最后一次调用可能还标着运行中，与电脑一样不再转圈。
            val shown = if (ownerStatus != null && ownerStatus != "running" && tool.status == ToolStatus.RUNNING) {
                tool.copy(status = ToolStatus.FAILED, statusLabel = RemoteTasks.statusText(ownerStatus))
            } else {
                tool
            }
            FragmentRenderer(fragment = shown)
        }
    }
}
