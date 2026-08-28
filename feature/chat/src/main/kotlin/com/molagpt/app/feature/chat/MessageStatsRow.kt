package com.molagpt.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.MessageStats
import com.molagpt.app.core.render.MolaMotion
import kotlin.math.roundToInt

/**
 * BYOK 助手消息下方的统计行：常态只有一个极简摘要 chip，点开才展开明细。
 *
 * 之所以不把六项指标全铺在正文下面：那是一条比正文还长的小字，每条消息都来一遍会淹没内容。
 * 摘要给「花了多少」这一个最常看的数，剩下的按需展开。
 *
 * 明细用就地展开而不是 Popup：这行挂在 LazyColumn 的 item 上，浮层要自己处理锚点、翻转和
 * 滚动跟随；就地展开只是多几行高度，列表本来就会跟着长。
 */
@Composable
fun MessageStatsRow(stats: MessageStats, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val bringIntoView = remember { BringIntoViewRequester() }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    // 展开后要一并露出的下方高度。统计行下面那条「复制 / 重新生成」是 LazyColumn 里的**另一个 item**，
    // 只请求本行可见会正好停在它上面。多要一截把它带进来；列表滚到头会自己夹住，多要不会过冲。
    val revealBelowPx = with(LocalDensity.current) { 56.dp.roundToPx() }

    // 展开动画期间整行的高度是一帧一帧长出来的，所以每次高度变化都重新请求一次可见：
    // 新一帧的请求会取消上一帧那次没走完的滚动、按新高度重算，视口就和展开动画同向同步下移。
    //
    // 只在展开时请求一次不行——那一次只能按当时（还很矮）的高度算滚动量，动画放完卡片仍有一截在屏幕外。
    // 高度必须挂在**外层 Column** 上量：AnimatedVisibility 是把子节点按完整尺寸测好再裁剪容器高度的，
    // 量在卡片上会一次就拿到终态高度，逐帧跟随就没了。
    //
    // 收起方向不请求：内容在变少，滚动只会把视线从用户正在看的位置扯走。
    LaunchedEffect(expanded, rowSize) {
        if (!expanded || rowSize.height <= 0) return@LaunchedEffect
        bringIntoView.bringIntoView(
            Rect(
                left = 0f,
                top = 0f,
                right = rowSize.width.toFloat(),
                bottom = (rowSize.height + revealBelowPx).toFloat(),
            ),
        )
    }

    Column(
        modifier = modifier
            .bringIntoViewRequester(bringIntoView)
            .onSizeChanged { rowSize = it },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = summaryLabel(stats),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起统计详情" else "展开统计详情",
                tint = muted,
                modifier = Modifier
                    .size(13.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(MolaMotion.standard()) + expandVertically(MolaMotion.emphasized()),
            exit = fadeOut(MolaMotion.standard()) + shrinkVertically(MolaMotion.emphasized()),
        ) {
            StatsDetailCard(stats)
        }
    }
}

/** 摘要只给总量：总 token 缺失时退回「输入+输出」相加，两者都没有就只报耗时。 */
private fun summaryLabel(stats: MessageStats): String {
    val total = stats.totalTokens
        ?: listOfNotNull(stats.promptTokens, stats.completionTokens)
            .takeIf { it.isNotEmpty() }
            ?.sum()
    val duration = stats.durationMs
    return when {
        total != null -> "${formatTokens(total)} tokens"
        duration != null -> formatDuration(duration)
        else -> "统计"
    }
}

@Composable
private fun StatsDetailCard(stats: MessageStats, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        // 无阴影、无 tonal elevation：这张卡是就地展开的正文附属物，不是浮在内容之上的层。
        // 靠 surfaceVariant 底色跟聊天背景区分即可——和工具卡是同一套语言。
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        // 撑满宽度而不是窄窄一条贴在左边：数值靠右对齐后各行自然成表，扫一眼就能比较。
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            stats.promptTokens?.let { prompt ->
                val cached = stats.cachedTokens?.takeIf { it > 0 }
                StatsDetailLine(
                    icon = Icons.Filled.ArrowUpward,
                    label = "输入",
                    // 缓存命中含在输入内，写成括号补充而不是单列一行，免得被读成额外消耗。
                    value = if (cached != null) {
                        "${formatExact(prompt)} tokens（${formatExact(cached)} 命中缓存）"
                    } else {
                        "${formatExact(prompt)} tokens"
                    },
                )
            }
            stats.completionTokens?.let {
                StatsDetailLine(Icons.Filled.ArrowDownward, "输出", "${formatExact(it)} tokens")
            }
            stats.reasoningTokens?.takeIf { it > 0 }?.let {
                StatsDetailLine(Icons.Filled.Psychology, "思考", "${formatExact(it)} tokens")
            }
            stats.tokensPerSecond?.let {
                StatsDetailLine(Icons.Filled.Bolt, "速度", "${formatOneDecimal(it)} tok/s")
            }
            stats.ttftMs?.let {
                StatsDetailLine(Icons.Filled.Timer, "首字延迟", formatDuration(it))
            }
            stats.durationMs?.let {
                StatsDetailLine(Icons.Filled.Schedule, "总耗时", formatDuration(it))
            }
            if (stats.promptTokens == null && stats.completionTokens == null) {
                // provider 没开 usage 上报时只剩耗时，明说一句，免得被当成 bug。
                Text(
                    text = "该服务商本次未返回 token 用量",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun StatsDetailLine(icon: ImageVector, label: String, value: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = muted.copy(alpha = 0.68f),
                modifier = Modifier.size(14.dp),
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 摘要 chip 用：1000 以下原样，之后用 k（1.2k / 12.3k）——那一行是扫一眼的量级，不是账单。 */
private fun formatTokens(value: Int): String = when {
    value < 1000 -> value.toString()
    else -> "${formatOneDecimal(value / 1000.0)}k"
}

/**
 * 明细卡用：准确到个位，千分位分组。
 *
 * 展开明细的动机就是「到底花了多少」，那里再给约数等于白展开一次。
 * 手写分组而不用 String.format：那个走 locale，某些区域会给出空格或点号作千分位。
 */
private fun formatExact(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else -> "${formatOneDecimal(ms / 1000.0)}s"
}

/** 保留一位小数；整数时去掉小数点（1.0k → 1k）。避免依赖 String.format 的 locale 行为。 */
private fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10).roundToInt()
    val whole = scaled / 10
    val frac = scaled % 10
    return if (frac == 0) whole.toString() else "$whole.$frac"
}
