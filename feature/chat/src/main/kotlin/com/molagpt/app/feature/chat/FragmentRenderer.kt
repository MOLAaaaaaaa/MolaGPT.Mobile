package com.molagpt.app.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.render.CodeBlockView
import com.molagpt.app.core.render.LocalMarkdownImageRenderer
import com.molagpt.app.core.render.FileCardView
import com.molagpt.app.core.render.LatexView
import com.molagpt.app.core.render.SearchResultView
import com.molagpt.app.core.render.StreamingMarkdownView
import com.molagpt.app.core.render.ThinkingView
import com.molagpt.app.core.render.ToolCallView
import com.molagpt.app.feature.webview.MermaidWebView

@Composable
fun FragmentRenderer(fragment: MessageFragment, modifier: Modifier = Modifier) {
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
            mermaidRenderer = { source, itemModifier -> MermaidWebView(source, itemModifier) },
        )
        is MessageFragment.Thinking -> ThinkingView(
            text = fragment.text,
            initiallyCollapsed = fragment.collapsed,
            durationMs = fragment.durationMs,
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
