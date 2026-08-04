package com.molagpt.app.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.render.CodeBlockView
import com.molagpt.app.core.render.LocalMarkdownImageRenderer
import com.molagpt.app.core.render.FileCardView
import com.molagpt.app.core.render.LatexView
import com.molagpt.app.core.render.SearchResultView
import com.molagpt.app.core.render.StreamingMarkdownView
import com.molagpt.app.core.render.ThinkingView
import com.molagpt.app.core.render.ToolCallView
import com.molagpt.app.feature.webview.MermaidWebView
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
    ) {
    when (fragment) {
        is MessageFragment.Text -> StreamingMarkdownView(
            markdown = fragment.markdown,
            modifier = modifier,
            tailFade = streamingTail,
            mermaidRenderer = { source, itemModifier -> MermaidWebView(source, itemModifier) },
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
        is MessageFragment.Mermaid -> MermaidWebView(fragment.source, modifier)
        is MessageFragment.SearchResult -> SearchResultView(fragment.query, fragment.refs, modifier)
        is MessageFragment.ToolCall -> ToolCallView(
            name = fragment.name,
            status = fragment.status,
            label = fragment.label,
            resultPreview = fragment.resultPreview,
            argsJson = fragment.argsJson,
            provider = fragment.provider,
            modifier = modifier,
        )
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
    val completed = fragments.size - running - failed
    val status = when {
        running > 0 -> ToolStatus.RUNNING
        failed > 0 -> ToolStatus.FAILED
        else -> ToolStatus.SUCCESS
    }
    val preview = fragments.mapIndexed { index, fragment ->
        val query = searchQuery(fragment.argsJson) ?: "搜索请求"
        "${index + 1}. $query"
    }.joinToString("\n")

    return ToolCallGroupPresentation(
        name = first.name,
        status = status,
        title = when {
            status == ToolStatus.RUNNING -> "正在进行 ${fragments.size} 项搜索"
            status == ToolStatus.FAILED -> if (completed > 0) "搜索已结束" else "搜索失败"
            else -> "已完成 ${fragments.size} 次搜索"
        },
        meta = "联网搜索",
        preview = preview,
    )
}

private val toolArgsJson = Json { ignoreUnknownKeys = true }

private fun searchQuery(argsJson: String?): String? = runCatching {
    argsJson
        ?.let(toolArgsJson::parseToJsonElement)
        ?.jsonObject
        ?.get("query")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}.getOrNull()
