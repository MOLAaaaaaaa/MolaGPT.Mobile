package com.molagpt.app.core.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.markdown.MdBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StreamingMarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    mermaidRenderer: @Composable (source: String, modifier: Modifier) -> Unit = { source, itemModifier ->
        CodeBlockView(language = "mermaid", code = source, modifier = itemModifier)
    },
) {
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }

    LaunchedEffect(markdown) {
        val parsed = withContext(Dispatchers.Default) {
            RenderCache.blocks(markdown)
        }
        blocks = parsed
    }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Mermaid -> mermaidRenderer(block.source, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                else -> MarkdownBlockView(block = block, modifier = Modifier.fillMaxWidth(), textScale = textScale)
            }
        }
    }
}
