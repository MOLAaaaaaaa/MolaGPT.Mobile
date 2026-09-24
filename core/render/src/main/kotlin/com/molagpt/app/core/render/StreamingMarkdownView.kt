package com.molagpt.app.core.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.molagpt.app.core.markdown.MdBlock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

@Composable
fun StreamingMarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    tailFade: Boolean = false,
) {
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    val updates = remember { Channel<String>(Channel.CONFLATED) }

    SideEffect {
        updates.trySend(markdown)
    }
    DisposableEffect(updates) {
        onDispose { updates.close() }
    }
    LaunchedEffect(updates) {
        var renderedMarkdown: String? = null
        for (latestMarkdown in updates) {
            if (latestMarkdown == renderedMarkdown) continue
            val parsed = withContext(MarkdownRenderScheduler.dispatcher) {
                RenderCache.blocks(latestMarkdown)
            }
            renderedMarkdown = latestMarkdown
            blocks = parsed
        }
    }

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            MarkdownBlockView(
                block = block,
                modifier = Modifier.fillMaxWidth(),
                textScale = textScale,
                tailFade = tailFade && index == blocks.lastIndex,
            )
        }
    }
}
