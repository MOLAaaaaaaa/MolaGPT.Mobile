package com.molagpt.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.markdown.MdBlock
import com.molagpt.app.core.model.ContextCompactionMark
import com.molagpt.app.core.model.ContextCompactionPolicy
import com.molagpt.app.core.model.ContextCompactionProgress
import com.molagpt.app.core.render.MarkdownBlockView
import com.molagpt.app.core.render.MarkdownRenderScheduler
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.RenderCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 气泡之间居中的一行灰字，标出压缩发生的时间点。
 *
 * 压缩中和压缩完是同一行：先显示进度，完成后原地换成结果，列表里不会先消失再出现。
 * 自动压缩发生在回答开始之前，所以这一行在用户消息和回答之间；手动压缩在两轮对话之间。
 */
@Composable
internal fun ContextCompactionLine(
    mark: ContextCompactionMark?,
    progress: ContextCompactionProgress?,
    onOpen: (ContextCompactionMark) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 完成后 progress 立即变为 null；留住最后一次进度，淡出时还有内容可画。
    val lastProgress = remember { arrayOfNulls<ContextCompactionProgress>(1) }
    if (progress != null) lastProgress[0] = progress
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = mark,
            // 只在「进行中 → 完成」时过渡；分段进度变化不重播。
            contentKey = { it != null },
            transitionSpec = {
                fadeIn(MolaMotion.standard(MolaMotion.Medium)) togetherWith
                    fadeOut(MolaMotion.standard(MolaMotion.Short)) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.Center,
            label = "compactionLine",
        ) { done ->
            if (done != null) {
                CompactionResult(done, onOpen)
            } else {
                lastProgress[0]?.let { CompactionRunning(it, onCancel) }
            }
        }
    }
}

@Composable
private fun CompactionResult(mark: ContextCompactionMark, onOpen: (ContextCompactionMark) -> Unit) {
    Text(
        text = compactionLabel(mark),
        style = MaterialTheme.typography.bodySmall,
        color = lineColor(),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onOpen(mark) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 「正在压缩对话 · 第 2/3 段 · 12 秒」。一次压缩常要几十秒，只有转圈时和卡住分不清，
 * 读秒让它看得出在走。[progress] 为 null（手动压缩还在准备）时只显示前半句。
 */
@Composable
internal fun compactionProgressText(progress: ContextCompactionProgress?): String {
    var elapsedSeconds by remember(progress?.id) { mutableLongStateOf(0L) }
    LaunchedEffect(progress?.id) {
        val startedAt = progress?.startedAt ?: return@LaunchedEffect
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1_000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    return buildString {
        append(ContextCompactionPolicy.PROGRESS_LABEL)
        if (progress == null) return@buildString
        if (progress.total > 1) append(" · 第 ${progress.step}/${progress.total} 段")
        if (elapsedSeconds > 0) append(" · $elapsedSeconds 秒")
    }
}

@Composable
private fun CompactionRunning(progress: ContextCompactionProgress, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = compactionProgressText(progress),
            style = MaterialTheme.typography.bodySmall,
            color = lineColor(),
            modifier = Modifier
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                .textShimmer(MaterialTheme.colorScheme.onSurface),
        )
        Text(
            text = "取消",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun lineColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)

/** 一道亮光从左到右扫过文字，只落在字形上。 */
private fun Modifier.textShimmer(highlight: Color): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "textShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "textShimmerX",
    )
    this
        // 离屏合成后 SrcAtop 才只覆盖已画出的字形，不会在字间空白处画出一条亮带。
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = size.width * 0.45f
            val x = -band + (size.width + band * 2) * progress
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, highlight.copy(alpha = 0.9f), Color.Transparent),
                    startX = x - band,
                    endX = x + band,
                ),
                topLeft = Offset.Zero,
                size = size,
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** 压缩记录详情：前后大小、覆盖范围与摘要全文。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextCompactionSheet(
    mark: ContextCompactionMark,
    /** 由摘要代替的消息条数；锚点已不在列表里时为 null。 */
    coveredMessages: Int?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val blocks by produceState<List<MdBlock>>(emptyList(), mark.summary) {
        value = withContext(MarkdownRenderScheduler.dispatcher) { RenderCache.blocks(mark.summary) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                "对话摘要",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            Text(
                coveredMessages?.let { "发送时以此摘要替代较早的 $it 条消息" } ?: "发送时以此摘要替代较早的消息",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.45f))
                    .padding(vertical = 12.dp),
            ) {
                SummaryStat("压缩前", "约 ${formatTokenCount(mark.tokensBefore)}", Modifier.weight(1f))
                SummaryStat("压缩后", "约 ${formatTokenCount(mark.tokensAfter)}", Modifier.weight(1f))
                SummaryStat("节省", "约 ${formatTokenCount(mark.tokensSaved)}", Modifier.weight(1f), highlight = true)
            }
            SelectionContainer(modifier = Modifier.padding(top = 12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.45f))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    blocks.forEachIndexed { index, block ->
                        MarkdownBlockView(
                            block = block,
                            modifier = Modifier.fillMaxWidth(),
                            blockKey = "compaction:${mark.id}:$index",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) cs.primary else cs.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun compactionLabel(mark: ContextCompactionMark): String =
    if (mark.tokensSaved > 0) {
        "已压缩 · 节省约 ${formatTokenCount(mark.tokensSaved)} Token"
    } else {
        "已压缩"
    }

/** 12,345 → 12K，1,234,567 → 1.2M。不走 String.format，避免区域设置改变小数点与千分位。 */
internal fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val tenths = (tokens + 50_000) / 100_000
        if (tenths % 10 == 0) "${tenths / 10}M" else "${tenths / 10}.${tenths % 10}M"
    }
    tokens >= 1_000 -> "${(tokens + 500) / 1_000}K"
    else -> tokens.toString()
}
