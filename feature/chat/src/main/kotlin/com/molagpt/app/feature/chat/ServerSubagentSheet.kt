package com.molagpt.app.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.ServerSubagent
import com.molagpt.app.core.render.SubagentTaskRow
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerSubagentSheet(agents: List<ServerSubagent>, batch: String?, onClose: () -> Unit) {
    val visible = if (batch == null) agents else agents.filter { it.batch == batch }
    var selected by remember(batch) { mutableStateOf(visible.singleOrNull()?.id) }
    val agent = visible.firstOrNull { it.id == selected }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = visible.any { it.running }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BackHandler(agent != null) { selected = null }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (agent != null) IconButton(onClick = { selected = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表")
                }
                Text(agent?.text("label") ?: "${visible.size} 个子代理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "关闭") }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (agent == null) {
                    items(visible, key = { it.id }) { item ->
                        SubagentTaskRow(
                            title = item.text("label"), status = item.statusText, running = item.running,
                            failed = item.text("status") in listOf("failed", "timeout", "rejected"),
                            subtitle = item.elapsed(now) + (item.text("tools").takeIf { it != "0" && it.isNotEmpty() }?.let { " · 工具调用 $it 次" } ?: ""),
                            action = item.text("step").takeIf { item.running }, failure = item.text("error"),
                            onClick = { selected = item.id },
                        )
                    }
                } else {
                    item("status") { Text("${agent.statusText} · ${agent.elapsed(now)}", style = MaterialTheme.typography.labelMedium) }
                    item("task") { FragmentRenderer(MessageFragment.Text("task", agent.text("task"))) }
                    if (agent.running && agent.text("step").isNotBlank()) item("step") { Text(agent.text("step")) }
                    if (agent.report().isNotBlank()) item("report") { FragmentRenderer(MessageFragment.Text("report", agent.report())) }
                    if (agent.text("error").isNotBlank()) item("error") { Text(agent.text("error"), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
