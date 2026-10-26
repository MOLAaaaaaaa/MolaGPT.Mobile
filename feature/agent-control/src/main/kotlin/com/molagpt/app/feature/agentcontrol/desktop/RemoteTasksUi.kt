package com.molagpt.app.feature.agentcontrol.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.molagpt.app.core.render.molaGlass
import kotlinx.coroutines.delay

/** 与 ViewModel 的单调时钟相同，离线或无运行任务时不计时。 */
@Composable
internal fun rememberNow(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    LaunchedEffect(running) {
        while (running) {
            now = System.nanoTime() / 1_000_000
            delay(1_000)
        }
    }
    return now
}

/** 子 Agent 平时只显示胶囊，点击后在其上方查看列表与状态。 */
@Composable
internal fun RemoteTasksPanel(
    tasks: List<RemoteRunningTask>,
    agents: List<RemoteAgent>,
    enabled: Boolean,
    onOpenAgent: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    val pythonTasks = tasks.filter { it.kind == "python" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pythonTasks.isNotEmpty()) {
            val now = rememberNow(enabled)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pythonTasks.forEach { task ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .molaGlass(CircleShape, elevation = 2.dp)
                            .heightIn(min = 34.dp)
                            .padding(start = 12.dp, end = 2.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Python · ${task.label}",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = RemoteTasks.formatElapsed(task.elapsedMs + (now - task.receivedAtMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onStop(task.id) }, enabled = enabled, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "停止任务", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        if (agents.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            val position = remember(gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset(
                        x = (anchorBounds.center.x - popupContentSize.width / 2)
                            .coerceIn(0, windowSize.width - popupContentSize.width),
                        y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0),
                    )
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box {
                    StatusPill(
                        model = StatusModel("${agents.size} 个子 Agent", progress = agents.any { it.isRunning }),
                        icon = Icons.Outlined.SmartToy,
                        onClick = { expanded = !expanded },
                    )
                    if (expanded) {
                        Popup(
                            popupPositionProvider = position,
                            onDismissRequest = { expanded = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Surface(
                                modifier = Modifier.width(300.dp).heightIn(max = 360.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 8.dp,
                            ) {
                                val now = rememberNow(enabled && expanded && agents.any { it.isRunning })
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                                    agents.forEach { agent ->
                                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                            RemoteAgentRow(agent, now) {
                                                expanded = false
                                                onOpenAgent(agent.id)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
