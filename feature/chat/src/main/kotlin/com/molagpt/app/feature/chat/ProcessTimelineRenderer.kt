package com.molagpt.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.ProcessTimelineContainer
import com.molagpt.app.core.render.StreamingMarkdownView
import com.molagpt.app.core.render.TimelineRailStep
import com.molagpt.app.core.render.TimelineToolIndicator
import com.molagpt.app.core.render.readableToolName
import com.molagpt.app.core.render.toolIconVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

@Immutable
internal sealed interface ProcessTimelineStep {
    val key: String

    @Immutable
    data class Thinking(
        override val key: String,
        val text: String,
        val durationMs: Long?,
        val active: Boolean,
    ) : ProcessTimelineStep

    @Immutable
    data class Search(
        override val key: String,
        val tools: List<MessageFragment.ToolCall>,
        val queries: List<String>,
        val status: ToolStatus,
    ) : ProcessTimelineStep {
        /** 官方链路一次工具调用可带多个搜索词，按搜索词计数才与用户感知的「搜了几次」一致。 */
        val count: Int get() = maxOf(tools.size, queries.size)
    }

    @Immutable
    data class Tool(
        override val key: String,
        val fragment: MessageFragment.ToolCall,
    ) : ProcessTimelineStep
}

internal data class ProcessTimelineHeader(
    val title: String,
    val icon: ImageVector,
    val active: Boolean,
    val stopped: Boolean,
)

internal fun processTimelineHeader(
    steps: List<ProcessTimelineStep>,
    streaming: Boolean,
    hasFollowingBody: Boolean,
    messageStopped: Boolean,
    liveThinkingSeconds: Long? = null,
): ProcessTimelineHeader {
    val active = streaming && !hasFollowingBody
    val hasThinking = steps.any { it is ProcessTimelineStep.Thinking }
    val searchToolCount = steps.sumOf { if (it is ProcessTimelineStep.Search) it.count else 0 }
    val otherToolCount = steps.count { it is ProcessTimelineStep.Tool }
    val totalToolCount = searchToolCount + otherToolCount
    val totalThinkingMs = steps
        .filterIsInstance<ProcessTimelineStep.Thinking>()
        .mapNotNull { it.durationMs }
        .takeIf { it.isNotEmpty() }
        ?.sum()

    if (active) {
        val last = steps.lastOrNull()
        return when {
            last is ProcessTimelineStep.Search && last.status == ToolStatus.RUNNING ->
                ProcessTimelineHeader("正在联网搜索…", Icons.Filled.Search, active = true, stopped = false)
            last is ProcessTimelineStep.Tool && last.fragment.status == ToolStatus.RUNNING -> {
                val label = last.fragment.label?.takeIf { it.isNotBlank() && it.length <= 16 }
                    ?: "正在${readableToolName(last.fragment.name)}…"
                ProcessTimelineHeader(
                    title = label,
                    icon = toolIconVector(last.fragment.name, last.fragment.serverTool?.icon),
                    active = true,
                    stopped = false,
                )
            }
            hasThinking -> {
                val title = if (liveThinkingSeconds != null && liveThinkingSeconds >= 1L) {
                    "正在思考… ${liveThinkingSeconds}s"
                } else {
                    "正在思考…"
                }
                ProcessTimelineHeader(title, Icons.Outlined.Lightbulb, active = true, stopped = false)
            }
            else ->
                ProcessTimelineHeader("正在处理…", Icons.Filled.Build, active = true, stopped = false)
        }
    }

    val icon = when {
        hasThinking -> Icons.Outlined.Lightbulb
        otherToolCount == 0 && searchToolCount > 0 -> Icons.Filled.Search
        searchToolCount == 0 && otherToolCount > 0 -> {
            val tools = steps.filterIsInstance<ProcessTimelineStep.Tool>()
            if (tools.map { canonicalToolKind(it.fragment) }.distinct().size == 1) {
                val onlyTool = tools.first().fragment
                toolIconVector(onlyTool.name, onlyTool.serverTool?.icon)
            } else {
                Icons.Filled.Build
            }
        }
        else -> Icons.Filled.Build
    }

    if (messageStopped && !hasFollowingBody) {
        val title = when {
            hasThinking && totalToolCount == 0 -> "思考已停止"
            !hasThinking && otherToolCount == 0 && searchToolCount > 0 -> "联网搜索已停止"
            else -> "过程已停止"
        }
        return ProcessTimelineHeader(title, icon, active = false, stopped = true)
    }

    val seconds = totalThinkingMs?.let { maxOf(1L, (it / 1000.0).roundToLong()) }
    val title = when {
        // 有思考时标题保持克制，只报思考耗时（与 Desktop / 主流客户端一致，不堆砌「x 项工具」计数）。
        hasThinking -> if (seconds != null) "已思考 ${seconds}s" else "思考过程"
        otherToolCount == 0 && searchToolCount > 0 -> {
            val allFailed = steps.filterIsInstance<ProcessTimelineStep.Search>().all { it.status == ToolStatus.FAILED }
            val allCancelled = steps.filterIsInstance<ProcessTimelineStep.Search>().all { it.status == ToolStatus.CANCELLED }
            when {
                allFailed -> "联网搜索失败"
                allCancelled -> "联网搜索已停止"
                searchToolCount == 1 -> "已完成联网搜索"
                else -> "已完成 $searchToolCount 次联网搜索"
            }
        }
        totalToolCount == 1 -> {
            val onlyTool = steps.filterIsInstance<ProcessTimelineStep.Tool>().first().fragment
            val name = onlyTool.label?.takeIf { it.isNotBlank() } ?: readableToolName(onlyTool.name)
            when (onlyTool.status) {
                ToolStatus.FAILED -> formatFailedToolLabel(name)
                ToolStatus.CANCELLED -> if (name.endsWith("停止")) name else "${name}已停止"
                else -> formatCompletedSingleToolTitle(onlyTool)
            }
        }
        else -> {
            val allTools = steps.filterIsInstance<ProcessTimelineStep.Tool>()
            if (searchToolCount == 0 && allTools.isNotEmpty() && allTools.map { canonicalToolKind(it.fragment) }.distinct().size == 1) {
                formatCompletedRepeatedToolTitle(allTools.first().fragment, allTools.size)
            } else {
                "已完成 $totalToolCount 项操作"
            }
        }
    }
    return ProcessTimelineHeader(title, icon, active = false, stopped = false)
}

private fun formatFailedToolLabel(label: String): String =
    if (label.endsWith("失败")) label else "${label}失败"

private fun canonicalToolKind(tool: MessageFragment.ToolCall): String {
    val iconKey = tool.serverTool?.icon?.takeIf { it.isNotBlank() }
    val normName = when (tool.name.lowercase()) {
        "search_web", "web_search" -> "search_web"
        "web_fetch", "fetch_url" -> "web_fetch"
        "execute_python_code", "python" -> "python"
        else -> tool.name.lowercase()
    }
    return iconKey?.let { "$normName:$it" } ?: normName
}

private fun baseToolActionLabel(tool: MessageFragment.ToolCall): String {
    val label = tool.label?.trim()?.takeIf { it.isNotBlank() && it.length <= 16 }
    val cleanLabel = label?.takeUnless {
        it.startsWith("已") || it.startsWith("正在") ||
            it.endsWith("完成") || it.endsWith("失败") || it.endsWith("停止")
    }
    return cleanLabel ?: readableToolName(tool.name)
}

private fun formatCompletedSingleToolTitle(tool: MessageFragment.ToolCall): String {
    val raw = tool.label?.trim()?.takeIf { it.isNotBlank() && it.length <= 16 }
    if (raw != null && (raw.startsWith("已") || raw.endsWith("完成"))) return raw
    val base = baseToolActionLabel(tool)
    return if (base.startsWith("已") || base.endsWith("完成")) base else "已完成$base"
}

private fun formatCompletedRepeatedToolTitle(tool: MessageFragment.ToolCall, count: Int): String {
    val base = baseToolActionLabel(tool)
    val sep = if (base.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true) " " else ""
    return "已完成 $count 次$sep$base"
}

private sealed interface PureToolUnit {
    val key: String
    val isSuccess: Boolean
    val isRunning: Boolean
    val isFailed: Boolean
    val isCancelled: Boolean
    val opCount: Int

    data class SearchGroup(val step: ProcessTimelineStep.Search) : PureToolUnit {
        override val key: String get() = step.key
        override val isRunning: Boolean get() = step.status == ToolStatus.RUNNING
        override val isFailed: Boolean get() = step.status == ToolStatus.FAILED
        override val isCancelled: Boolean get() = step.status == ToolStatus.CANCELLED
        override val isSuccess: Boolean get() = step.status == ToolStatus.SUCCESS
        override val opCount: Int get() = step.tools.size.coerceAtLeast(1)
    }

    data class SameToolGroup(
        override val key: String,
        val tools: List<ProcessTimelineStep.Tool>,
    ) : PureToolUnit {
        override val isRunning: Boolean get() = tools.any { it.fragment.status == ToolStatus.RUNNING }
        override val isFailed: Boolean get() = !isRunning && tools.any { it.fragment.status == ToolStatus.FAILED }
        override val isCancelled: Boolean get() = !isRunning && !isFailed && tools.all { it.fragment.status == ToolStatus.CANCELLED }
        override val isSuccess: Boolean get() = !isRunning && !isFailed && !isCancelled
        override val opCount: Int get() = tools.size
    }
}

/** 将纯工具步骤按「连续同类成功」归并；失败 / 运行中 / 子代理独立成组以便平级直出。 */
private fun groupPureToolUnits(steps: List<ProcessTimelineStep>): List<PureToolUnit> {
    val result = ArrayList<PureToolUnit>(steps.size)
    var i = 0
    while (i < steps.size) {
        when (val step = steps[i]) {
            is ProcessTimelineStep.Search -> {
                result += PureToolUnit.SearchGroup(step)
                i++
            }
            is ProcessTimelineStep.Tool -> {
                val first = step.fragment
                if (first.status == ToolStatus.SUCCESS && first.name != "server_subagents") {
                    val kind = canonicalToolKind(first)
                    val group = ArrayList<ProcessTimelineStep.Tool>()
                    group += step
                    var j = i + 1
                    while (j < steps.size) {
                        val candidate = steps[j] as? ProcessTimelineStep.Tool ?: break
                        if (candidate.fragment.status == ToolStatus.SUCCESS &&
                            candidate.fragment.name != "server_subagents" &&
                            canonicalToolKind(candidate.fragment) == kind
                        ) {
                            group += candidate
                            j++
                        } else {
                            break
                        }
                    }
                    result += PureToolUnit.SameToolGroup(key = step.key, tools = group)
                    i = j
                } else {
                    result += PureToolUnit.SameToolGroup(key = step.key, tools = listOf(step))
                    i++
                }
            }
            is ProcessTimelineStep.Thinking -> i++
        }
    }
    return result
}

/** 活跃思考段在进程内的本地起始时间（按 timelineKey|stepKey 索引，避免列表回收滚回后秒表归零）。 */
private val liveThinkingStartMs = ConcurrentHashMap<String, Long>()

@Composable
internal fun ProcessTimelineRenderer(
    timelineKey: String,
    steps: List<ProcessTimelineStep>,
    streaming: Boolean,
    hasFollowingBody: Boolean,
    messageStopped: Boolean,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    // 纯工具过程（无思考）：走方向 A 扁平层级（同类合并用专属图标、失败平级外露、≥3 组不同成功操作才收进「已完成 N 项操作」）。
    if (steps.none { it is ProcessTimelineStep.Thinking }) {
        PureToolTimelineRenderer(
            timelineKey = timelineKey,
            steps = steps,
            streaming = streaming,
            hasFollowingBody = hasFollowingBody,
            messageStopped = messageStopped,
            modifier = modifier,
        )
        return
    }
    val activeNow = streaming && !hasFollowingBody
    val activeThinking = remember(steps, activeNow) {
        if (!activeNow) null
        else steps.lastOrNull { it is ProcessTimelineStep.Thinking && it.active } as? ProcessTimelineStep.Thinking
    }
    val priorThinkingMs = remember(steps) {
        steps.filterIsInstance<ProcessTimelineStep.Thinking>().sumOf { it.durationMs ?: 0L }
    }
    var liveThinkingSeconds by remember(timelineKey) { mutableStateOf<Long?>(null) }

    LaunchedEffect(timelineKey, activeNow, activeThinking?.key, priorThinkingMs) {
        if (!activeNow || activeThinking == null) {
            liveThinkingSeconds = null
            if (!activeNow) {
                val prefix = "$timelineKey|"
                liveThinkingStartMs.keys.removeIf { it.startsWith(prefix) }
            }
            return@LaunchedEffect
        }
        if (liveThinkingStartMs.size > 64) {
            liveThinkingStartMs.clear()
        }
        val stepId = "$timelineKey|${activeThinking.key}|$priorThinkingMs"
        val startedAt = liveThinkingStartMs.getOrPut(stepId) { System.currentTimeMillis() }
        while (true) {
            val elapsedMs = priorThinkingMs + (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            liveThinkingSeconds = elapsedMs / 1000L
            val delayMs = (1000L - (elapsedMs % 1000L)).coerceAtLeast(100L)
            delay(delayMs)
        }
    }

    val header = remember(steps, streaming, hasFollowingBody, messageStopped, liveThinkingSeconds) {
        processTimelineHeader(steps, streaming, hasFollowingBody, messageStopped, liveThinkingSeconds)
    }
    // 过程正在进行中默认展开，正文开始流出或历史消息默认收起；用户手动点过则始终尊重用户选择。
    var expanded by rememberSaveable(timelineKey) { mutableStateOf(header.active) }
    var userToggled by rememberSaveable(timelineKey) { mutableStateOf(false) }

    LaunchedEffect(header.active) {
        if (!userToggled) {
            expanded = header.active
        }
    }

    ProcessTimelineContainer(
        title = header.title,
        icon = header.icon,
        active = header.active,
        stopped = header.stopped,
        expanded = expanded,
        onToggle = {
            userToggled = true
            expanded = !expanded
        },
        modifier = modifier,
    ) {
        // 只有一个工具时标题已经写着它的名字，步骤行不再重复，改显示来源 / 详情入口。
        val soloTool = steps.size == 1 && steps.first() is ProcessTimelineStep.Tool
        steps.forEachIndexed { index, step ->
            val isLast = index == steps.lastIndex
            when (step) {
                is ProcessTimelineStep.Thinking -> ThinkingStepRow(step, isLast)
                is ProcessTimelineStep.Search -> SearchStepRow(step, isLast)
                is ProcessTimelineStep.Tool -> GeneralToolStepRow(step, isLast, showTitle = !soloTool)
            }
        }
    }
}

@Composable
private fun PureToolTimelineRenderer(
    timelineKey: String,
    steps: List<ProcessTimelineStep>,
    streaming: Boolean,
    hasFollowingBody: Boolean,
    messageStopped: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeNow = streaming && !hasFollowingBody
    val units = remember(steps) { groupPureToolUnits(steps) }
    val okUnits = remember(units) { units.filter { it.isSuccess } }
    val nonOkUnits = remember(units) { units.filter { !it.isSuccess } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!activeNow && okUnits.size >= 3) {
            val totalOps = okUnits.sumOf { it.opCount }
            val summaryKey = "$timelineKey|ops"
            var expanded by rememberSaveable(summaryKey) { mutableStateOf(false) }
            ProcessTimelineContainer(
                title = "已完成 $totalOps 项操作",
                icon = Icons.Filled.Build,
                active = false,
                stopped = false,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            ) {
                val innerSteps = okUnits.flatMap { unit ->
                    when (unit) {
                        is PureToolUnit.SearchGroup -> listOf(unit.step)
                        is PureToolUnit.SameToolGroup -> unit.tools
                    }
                }
                innerSteps.forEachIndexed { idx, step ->
                    val isLast = idx == innerSteps.lastIndex
                    when (step) {
                        is ProcessTimelineStep.Search -> SearchStepRow(step, isLast)
                        is ProcessTimelineStep.Tool -> GeneralToolStepRow(step, isLast, showTitle = true)
                        is ProcessTimelineStep.Thinking -> Unit
                    }
                }
            }
            nonOkUnits.forEach { unit ->
                PureToolUnitRow(
                    timelineKey = timelineKey,
                    unit = unit,
                    activeTail = false,
                    stoppedTail = messageStopped && !hasFollowingBody,
                )
            }
        } else {
            units.forEachIndexed { index, unit ->
                val isTail = index == units.lastIndex
                PureToolUnitRow(
                    timelineKey = timelineKey,
                    unit = unit,
                    activeTail = activeNow && isTail,
                    stoppedTail = messageStopped && !hasFollowingBody && isTail,
                )
            }
        }
    }
}

@Composable
private fun PureToolUnitRow(
    timelineKey: String,
    unit: PureToolUnit,
    activeTail: Boolean,
    stoppedTail: Boolean,
) {
    val rowKey = "$timelineKey|${unit.key}"
    when (unit) {
        is PureToolUnit.SearchGroup -> {
            val step = unit.step
            if (step.status == ToolStatus.FAILED) {
                FlatFailedSearchRow(step)
                return
            }
            val isRunning = step.status == ToolStatus.RUNNING
            val isStopped = step.status == ToolStatus.CANCELLED || (stoppedTail && step.status != ToolStatus.SUCCESS)
            val title = when {
                isRunning -> "正在联网搜索…"
                isStopped -> "联网搜索已停止"
                step.count == 1 -> "已完成联网搜索"
                else -> "已完成 ${step.count} 次联网搜索"
            }
            var expanded by rememberSaveable(rowKey) { mutableStateOf(isRunning) }
            var userToggled by rememberSaveable(rowKey) { mutableStateOf(false) }
            LaunchedEffect(isRunning) {
                if (!userToggled) expanded = isRunning
            }
            ProcessTimelineContainer(
                title = title,
                icon = Icons.Filled.Search,
                active = isRunning || (activeTail && isRunning),
                stopped = isStopped,
                expanded = expanded,
                onToggle = {
                    userToggled = true
                    expanded = !expanded
                },
            ) {
                if (isRunning) {
                    SearchStepRow(step, isLast = true)
                } else if (step.queries.isNotEmpty()) {
                    val cs = MaterialTheme.colorScheme
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        step.queries.forEachIndexed { idx, query ->
                            Text(
                                text = if (step.queries.size == 1) query else "${idx + 1}. $query",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        is PureToolUnit.SameToolGroup -> {
            val firstStep = unit.tools.first()
            val first = firstStep.fragment
            if (unit.tools.size == 1 && first.status == ToolStatus.FAILED) {
                FlatFailedToolRow(firstStep)
                return
            }
            if (unit.tools.size == 1 && first.name == "server_subagents") {
                GeneralToolStepRow(firstStep, isLast = true, showTitle = true)
                return
            }
            val isRunning = unit.isRunning
            val isStopped = unit.isCancelled || (stoppedTail && !unit.isSuccess)
            val icon = toolIconVector(first.name, first.serverTool?.icon)
            val title = when {
                isRunning -> first.label?.takeIf { it.isNotBlank() && it.length <= 16 }
                    ?: "正在${baseToolActionLabel(first)}…"
                isStopped -> {
                    val base = baseToolActionLabel(first)
                    if (base.endsWith("停止")) base else "${base}已停止"
                }
                unit.tools.size == 1 -> formatCompletedSingleToolTitle(first)
                else -> formatCompletedRepeatedToolTitle(first, unit.tools.size)
            }
            var expanded by rememberSaveable(rowKey) { mutableStateOf(isRunning) }
            var userToggled by rememberSaveable(rowKey) { mutableStateOf(false) }
            LaunchedEffect(isRunning) {
                if (!userToggled) expanded = isRunning
            }
            ProcessTimelineContainer(
                title = title,
                icon = icon,
                active = isRunning || (activeTail && isRunning),
                stopped = isStopped,
                expanded = expanded,
                onToggle = {
                    userToggled = true
                    expanded = !expanded
                },
            ) {
                unit.tools.forEachIndexed { idx, toolStep ->
                    GeneralToolStepRow(
                        step = toolStep,
                        isLast = idx == unit.tools.lastIndex,
                        showTitle = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlatFailedSearchRow(step: ProcessTimelineStep.Search) {
    val cs = MaterialTheme.colorScheme
    val err = step.tools.firstNotNullOfOrNull { t ->
        t.serverTool?.error?.takeIf { it.isNotBlank() } ?: extractToolErrorSummary(t)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp, bottom = 5.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = cs.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "联网搜索",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = cs.error,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "失败",
                style = MaterialTheme.typography.labelSmall,
                color = cs.error,
            )
        }
        if (err != null) {
            Text(
                text = err,
                style = MaterialTheme.typography.labelSmall,
                color = cs.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun FlatFailedToolRow(step: ProcessTimelineStep.Tool) {
    val cs = MaterialTheme.colorScheme
    val links = LocalSubagentLinks.current
    val actions = LocalServerToolActions.current
    val scope = rememberCoroutineScope()
    var actionBusy by remember(step.key) { mutableStateOf(false) }
    var actionError by remember(step.key) { mutableStateOf<String?>(null) }

    val tool = actions?.results?.get(step.fragment.id) ?: step.fragment
    val failed = tool.status == ToolStatus.FAILED
    val title = tool.label?.takeIf { it.isNotBlank() } ?: readableToolName(tool.name)
    val subtitle = tool.serverTool?.metadata?.takeIf { it.isNotBlank() }
        ?: extractToolArgPreview(tool)
        ?: tool.provider?.takeIf { it.isNotBlank() && it != title }
    val preview = tool.resultPreview?.takeIf { it.isNotBlank() }
        ?: tool.argsJson?.takeIf { it.isNotBlank() }?.let { "参数：\n```json\n$it\n```" }
    val retryTicket = tool.serverTool?.retryTicket
    val fileTicket = tool.serverTool?.fileTicket
    val agentId = tool.agentId?.takeIf { it in (links?.available ?: emptySet()) }
    val errorText = actionError
        ?: tool.serverTool?.error?.takeIf { it.isNotBlank() }
        ?: if (failed) extractToolErrorSummary(tool) else null
    var detailsOpen by rememberSaveable(step.key) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .then(if (preview != null) Modifier.clickable { detailsOpen = !detailsOpen } else Modifier)
                .padding(top = 5.dp, bottom = 5.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = toolIconVector(tool.name, tool.serverTool?.icon),
                contentDescription = null,
                tint = if (failed) cs.error else cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (failed) cs.error else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val statusBadge = tool.statusLabel ?: when (tool.status) {
                ToolStatus.FAILED -> "失败"
                ToolStatus.RUNNING -> "进行中"
                ToolStatus.CANCELLED -> "已停止"
                ToolStatus.SUCCESS -> null
            }
            if (statusBadge != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) cs.error else cs.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            if (preview != null) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (detailsOpen) "收起详情" else "展开详情",
                    tint = (if (failed) cs.error else cs.onSurfaceVariant).copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = if (detailsOpen) 180f else 0f },
                )
            }
        }
        Column(modifier = Modifier.padding(start = 24.dp, end = 4.dp, bottom = 4.dp)) {
            if (subtitle != null && subtitle != title) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if ((actions != null && (retryTicket != null || fileTicket != null)) || (agentId != null && links != null)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (actions != null && retryTicket != null) {
                        TextButton(
                            enabled = !actionBusy,
                            onClick = {
                                scope.launch {
                                    actionBusy = true
                                    actionError = null
                                    try {
                                        actions.results[step.fragment.id] = actions.retry(retryTicket)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        actionError = error.message ?: "重试失败"
                                    } finally {
                                        actionBusy = false
                                    }
                                }
                            },
                        ) { Text(if (actionBusy) "正在重试" else "重试") }
                    }
                    if (actions != null && fileTicket != null) {
                        TextButton(
                            enabled = !actionBusy,
                            onClick = { actions.download(fileTicket, tool.serverTool?.filename.orEmpty()) },
                        ) { Text("下载文件") }
                    }
                    if (agentId != null && links != null) {
                        TextButton(onClick = { links.open(agentId) }) { Text("查看子 Agent") }
                    }
                }
            }
            AnimatedVisibility(visible = detailsOpen && preview != null) {
                CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) {
                    StreamingMarkdownView(
                        markdown = preview.orEmpty().trim(),
                        textScale = 0.72f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingStepRow(step: ProcessTimelineStep.Thinking, isLast: Boolean) {
    val cs = MaterialTheme.colorScheme
    if (step.text.isNotBlank()) {
        CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) {
            StreamingMarkdownView(
                markdown = step.text,
                textScale = 0.78f,
                tailFade = step.active,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchStepRow(step: ProcessTimelineStep.Search, isLast: Boolean) {
    val cs = MaterialTheme.colorScheme
    val running = step.status == ToolStatus.RUNNING
    val failed = step.status == ToolStatus.FAILED
    val cancelled = step.status == ToolStatus.CANCELLED
    var detailsOpen by rememberSaveable(step.key) { mutableStateOf(false) }

    // 多个搜索词进行中时每 2 秒轮播（对齐 chat-demo ReplyArea 的体验）。
    var ticker by remember(step.key) { mutableIntStateOf(0) }
    LaunchedEffect(running, step.queries.size) {
        if (!running || step.queries.size <= 1) return@LaunchedEffect
        while (true) {
            delay(2000)
            ticker += 1
        }
    }

    val expandable = !running && step.queries.isNotEmpty()
    TimelineRailStep(
        isLast = isLast,
        indicator = {
            TimelineToolIndicator(
                name = "search_web",
                icon = "search",
                running = running,
                failed = failed,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .then(if (expandable) Modifier.clickable { detailsOpen = !detailsOpen } else Modifier)
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                running && step.queries.isNotEmpty() -> {
                    Text(
                        text = "正在搜索：",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    val shownQuery = step.queries[ticker % step.queries.size]
                    AnimatedContent(
                        targetState = shownQuery,
                        transitionSpec = { fadeIn(MolaMotion.standard()) togetherWith fadeOut(MolaMotion.standard()) },
                        label = "searchQueryTicker",
                        modifier = Modifier.weight(1f),
                    ) { q ->
                        Text(
                            text = q,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                running -> {
                    Text(
                        text = "正在联网搜索…",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                cancelled -> {
                    Text(
                        text = "联网搜索已停止",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                failed -> {
                    Text(
                        text = "联网搜索失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                        modifier = Modifier.weight(1f),
                    )
                }
                step.queries.size == 1 -> {
                    Text(
                        text = "搜索：${step.queries.first()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = if (detailsOpen) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> {
                    Text(
                        text = "已搜索 ${step.count} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (expandable && step.queries.size > 1) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (detailsOpen) "收起搜索词" else "展开搜索词",
                    tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = if (detailsOpen) 180f else 0f },
                )
            }
        }

        AnimatedVisibility(visible = detailsOpen && step.queries.size > 1) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                step.queries.forEachIndexed { idx, query ->
                    Text(
                        text = "${idx + 1}. $query",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralToolStepRow(step: ProcessTimelineStep.Tool, isLast: Boolean, showTitle: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val links = LocalSubagentLinks.current
    val actions = LocalServerToolActions.current
    val scope = rememberCoroutineScope()
    var actionBusy by remember(step.key) { mutableStateOf(false) }
    var actionError by remember(step.key) { mutableStateOf<String?>(null) }

    val tool = actions?.results?.get(step.fragment.id) ?: step.fragment
    val agentId = tool.agentId?.takeIf { it in (links?.available ?: emptySet()) }
    val running = tool.status == ToolStatus.RUNNING
    val failed = tool.status == ToolStatus.FAILED

    if (tool.name == "server_subagents") {
        TimelineRailStep(
            isLast = isLast,
            indicator = {
                TimelineToolIndicator(
                    name = tool.name,
                    icon = tool.serverTool?.icon,
                    running = running,
                    failed = failed,
                )
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = agentId != null) { agentId?.let { links?.open(it) } }
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.label ?: "子代理任务",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                tool.statusLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (agentId != null) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        return
    }

    val title = tool.label?.takeIf { it.isNotBlank() } ?: readableToolName(tool.name)
    val subtitle = tool.serverTool?.metadata?.takeIf { it.isNotBlank() }
        ?: extractToolArgPreview(tool)
        ?: tool.provider?.takeIf { it.isNotBlank() && it != title }
    val preview = tool.resultPreview?.takeIf { it.isNotBlank() }
        ?: tool.argsJson?.takeIf { it.isNotBlank() }?.let { "参数：\n```json\n$it\n```" }
    val retryTicket = tool.serverTool?.retryTicket
    val fileTicket = tool.serverTool?.fileTicket
    val errorText = actionError
        ?: tool.serverTool?.error?.takeIf { it.isNotBlank() }
        ?: if (failed) extractToolErrorSummary(tool) else null

    val primaryText = when {
        showTitle -> title
        subtitle != null -> subtitle
        preview != null -> "查看详情"
        else -> title
    }

    var detailsOpen by rememberSaveable(step.key) { mutableStateOf(false) }

    TimelineRailStep(
        isLast = isLast,
        indicator = {
            TimelineToolIndicator(
                name = tool.name,
                icon = tool.serverTool?.icon,
                running = running,
                failed = failed,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .then(if (preview != null) Modifier.clickable { detailsOpen = !detailsOpen } else Modifier)
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (failed) cs.error else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val statusBadge = tool.statusLabel ?: when (tool.status) {
                ToolStatus.RUNNING -> "进行中"
                ToolStatus.FAILED -> "失败"
                ToolStatus.CANCELLED -> "已停止"
                ToolStatus.SUCCESS -> null
            }
            if (statusBadge != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) cs.error else cs.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            if (preview != null) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (detailsOpen) "收起详情" else "展开详情",
                    tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = if (detailsOpen) 180f else 0f },
                )
            }
        }

        if (subtitle != null && subtitle != primaryText) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.labelSmall,
                color = cs.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if ((actions != null && (retryTicket != null || fileTicket != null)) || (agentId != null && links != null)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actions != null && retryTicket != null) {
                    TextButton(
                        enabled = !actionBusy,
                        onClick = {
                            scope.launch {
                                actionBusy = true
                                actionError = null
                                try {
                                    actions.results[step.fragment.id] = actions.retry(retryTicket)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    actionError = error.message ?: "重试失败"
                                } finally {
                                    actionBusy = false
                                }
                            }
                        },
                    ) { Text(if (actionBusy) "正在重试" else "重试") }
                }
                if (actions != null && fileTicket != null) {
                    TextButton(
                        enabled = !actionBusy,
                        onClick = { actions.download(fileTicket, tool.serverTool?.filename.orEmpty()) },
                    ) { Text("下载文件") }
                }
                if (agentId != null && links != null) {
                    TextButton(onClick = { links.open(agentId) }) { Text("查看子 Agent") }
                }
            }
        }

        AnimatedVisibility(visible = detailsOpen && preview != null) {
            CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) {
                StreamingMarkdownView(
                    markdown = preview.orEmpty().trim(),
                    textScale = 0.72f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

private val timelineToolJson = Json { ignoreUnknownKeys = true }

private val primaryToolArgKeys = listOf(
    "url", "path", "file_path", "filePath", "filename", "command", "pattern", "query", "prompt", "title", "symbol",
)

/** 从通用工具参数 JSON 中抽取单行摘要（如网页阅读的 URL、文件操作的路径），对齐桌面端信息密度。 */
private fun extractToolArgPreview(tool: MessageFragment.ToolCall): String? {
    val raw = tool.argsJson?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val obj = timelineToolJson.parseToJsonElement(raw).jsonObject
        primaryToolArgKeys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(120)
        }
    }.getOrNull()
}

/** 工具失败且无服务端独立错误字段时，从结果预览中抽取一句可读失败原因。 */
private fun extractToolErrorSummary(tool: MessageFragment.ToolCall): String? {
    val raw = tool.resultPreview?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("{")) {
        val fromJson = runCatching {
            val obj = timelineToolJson.parseToJsonElement(raw).jsonObject
            listOf("error", "message", "detail").firstNotNullOfOrNull { key ->
                (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
        if (fromJson != null) return fromJson.replace(Regex("""\s+"""), " ").take(140)
    }
    return raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
        ?.take(140)
}

/** 从搜索工具参数中抽出搜索词列表（支持单 query 与 MolaGPT 的 queries 数组）。 */
internal fun extractSearchQueries(tool: MessageFragment.ToolCall): List<String> {
    val fromArgs = runCatching {
        val root = tool.argsJson?.let(timelineToolJson::parseToJsonElement)?.jsonObject ?: return@runCatching emptyList()
        val single = root["query"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (single != null) return@runCatching listOf(single)
        (root["queries"] as? JsonArray)?.mapNotNull { item ->
            ((item as? JsonObject)?.get("query")?.jsonPrimitive?.contentOrNull
                ?: (item as? JsonPrimitive)?.contentOrNull)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }.getOrDefault(emptyList())
    if (fromArgs.isNotEmpty() || tool.status == ToolStatus.FAILED) return fromArgs
    // 官方链路不带参数，结果预览即搜索词，多个词以换行分隔；失败时预览是错误信息，不当搜索词。
    return tool.resultPreview.orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length <= 120 }
        .toList()
}
