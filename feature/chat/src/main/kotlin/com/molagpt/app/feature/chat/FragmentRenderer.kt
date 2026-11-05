package com.molagpt.app.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.render.CodeBlockView
import com.molagpt.app.core.render.LocalFaviconRenderer
import com.molagpt.app.core.render.LocalMarkdownImageRenderer
import com.molagpt.app.core.render.FileCardView
import com.molagpt.app.core.render.LatexView
import com.molagpt.app.core.render.SearchResultView
import com.molagpt.app.core.render.StreamingMarkdownView
import com.molagpt.app.core.render.ThinkingView
import com.molagpt.app.core.render.ToolCallView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun FragmentRenderer(
    fragment: MessageFragment,
    modifier: Modifier = Modifier,
    streamingTail: Boolean = false,
) {
    // 提供 Markdown 行内图片的真实 Coil 渲染器；包住整个 when，使 Thinking/ToolCall 内嵌的
    // StreamingMarkdownView 也能渲染图片（生成图片走正文 Markdown 的 ![](url)，url 含 =imgtemp）。
    CompositionLocalProvider(
        LocalMarkdownImageRenderer provides { url, imgModifier ->
            com.molagpt.app.feature.file.RemoteImage(url, imgModifier)
        },
        LocalFaviconRenderer provides { url, imgModifier ->
            com.molagpt.app.feature.file.RemoteFavicon(url, imgModifier)
        },
    ) {
    when (fragment) {
        is MessageFragment.Text -> StreamingMarkdownView(
            markdown = fragment.markdown,
            modifier = modifier,
            tailFade = streamingTail,
        )
        is MessageFragment.Thinking -> ThinkingView(
            text = fragment.text,
            initiallyCollapsed = fragment.collapsed,
            durationMs = fragment.durationMs,
            streaming = streamingTail,
            modifier = modifier,
        )
        is MessageFragment.CodeBlock -> CodeBlockView(fragment.language, fragment.code, modifier)
        is MessageFragment.Latex -> LatexView(fragment.expr, fragment.display, modifier)
        is MessageFragment.Mermaid -> CodeBlockView("mermaid", fragment.source, modifier)
        is MessageFragment.SearchResult -> SearchResultView(fragment.query, fragment.refs, modifier)
        is MessageFragment.ToolCall -> {
            val links = LocalSubagentLinks.current
            val agentId = fragment.agentId?.takeIf { it in (links?.available ?: emptySet()) }
            if (fragment.name == "server_subagents") {
                val colors = MaterialTheme.colorScheme
                Row(
                    modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.42f))
                        .clickable(enabled = agentId != null) { agentId?.let { links?.open(it) } }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (fragment.status == ToolStatus.RUNNING) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = colors.onSurfaceVariant)
                    } else {
                        Icon(
                            Icons.Filled.Groups,
                            contentDescription = null, modifier = Modifier.size(16.dp),
                            tint = if (fragment.status == ToolStatus.FAILED) colors.error else colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = fragment.label ?: "子代理任务",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    fragment.statusLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.75f))
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            } else Column(modifier) {
                val actions = LocalServerToolActions.current
                val scope = rememberCoroutineScope()
                var actionBusy by remember(fragment.id) { mutableStateOf(false) }
                var actionError by remember(fragment.id) { mutableStateOf<String?>(null) }
                val tool = actions?.results?.get(fragment.id) ?: fragment
                val retryTicket = tool.serverTool?.retryTicket
                val fileTicket = tool.serverTool?.fileTicket
                ToolCallView(
                    name = tool.name,
                    status = tool.status,
                    label = tool.label,
                    resultPreview = tool.resultPreview,
                    argsJson = tool.argsJson,
                    provider = tool.provider,
                    statusLabel = tool.statusLabel,
                    serverTool = tool.serverTool,
                    actionBusy = actionBusy,
                    actionError = actionError,
                    onRetry = if (actions != null && retryTicket != null) ({
                        scope.launch {
                            actionBusy = true
                            actionError = null
                            try { actions.results[fragment.id] = actions.retry(retryTicket) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { actionError = error.message ?: "重试失败" }
                            finally { actionBusy = false }
                        }
                    }) else null,
                    onDownload = if (actions != null && fileTicket != null) ({ actions.download(fileTicket, tool.serverTool?.filename.orEmpty()) }) else null,
                )
                if (agentId != null && links != null) {
                    TextButton(onClick = { links.open(agentId) }) { Text("查看子 Agent") }
                }
            }
        }
        is MessageFragment.FileCard -> FileCardView(fragment.file, modifier)
        is MessageFragment.Image -> com.molagpt.app.feature.file.RemoteImage(fragment.url, modifier)
        is MessageFragment.Tip -> androidx.compose.material3.Text(
            text = fragment.text,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        is MessageFragment.Error -> androidx.compose.material3.Text(
            text = fragment.message,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
    }
    }
}

/**
 * 连续联网搜索的紧凑展示。底层 ToolCall fragment 保持独立，只在渲染层聚合；
 * 点击卡片仍可展开查看每次搜索词。
 */
@Composable
internal fun ToolCallGroupRenderer(
    fragments: List<MessageFragment.ToolCall>,
    modifier: Modifier = Modifier,
) {
    val presentation = toolCallGroupPresentation(fragments) ?: return
    ToolCallView(
        name = presentation.name,
        status = presentation.status,
        label = presentation.title,
        resultPreview = presentation.preview,
        provider = presentation.meta,
        expandWhileRunning = false,
        modifier = modifier,
    )
}

internal data class ToolCallGroupPresentation(
    val name: String,
    val status: ToolStatus,
    val title: String,
    val meta: String,
    val preview: String,
)

internal fun toolCallGroupPresentation(
    fragments: List<MessageFragment.ToolCall>,
): ToolCallGroupPresentation? {
    val first = fragments.firstOrNull() ?: return null
    val running = fragments.count { it.status == ToolStatus.RUNNING }
    val failed = fragments.count { it.status == ToolStatus.FAILED }
    val cancelled = fragments.count { it.status == ToolStatus.CANCELLED }
    val completed = fragments.size - running - failed - cancelled
    val status = when {
        running > 0 -> ToolStatus.RUNNING
        failed > 0 -> ToolStatus.FAILED
        cancelled > 0 -> ToolStatus.CANCELLED
        else -> ToolStatus.SUCCESS
    }
    val preview = fragments.mapIndexed { index, fragment ->
        val query = searchQuery(fragment.argsJson) ?: fragment.resultPreview?.takeIf { it.isNotBlank() } ?: "搜索请求"
        "${index + 1}. $query"
    }.joinToString("\n")

    return ToolCallGroupPresentation(
        name = first.name,
        status = status,
        title = when {
            status == ToolStatus.RUNNING -> "正在进行 ${fragments.size} 项搜索"
            status == ToolStatus.FAILED -> if (completed > 0) "搜索已结束" else "搜索失败"
            status == ToolStatus.CANCELLED -> "搜索已停止"
            else -> "已完成 ${fragments.size} 次搜索"
        },
        meta = "联网搜索",
        preview = preview,
    )
}

private val toolArgsJson = Json { ignoreUnknownKeys = true }

private fun searchQuery(argsJson: String?): String? = runCatching {
    val root = argsJson?.let(toolArgsJson::parseToJsonElement)?.jsonObject
    // MolaGPT 内置搜索一次提交多条查询（queries 数组）；其余服务商是单个 query。
    root?.get("query")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: (root?.get("queries") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { item ->
                (item as? kotlinx.serialization.json.JsonObject)?.get("query")?.jsonPrimitive?.contentOrNull
                    ?: (item as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" / ")
            ?.takeIf { it.isNotEmpty() }
}.getOrNull()
