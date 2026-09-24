package com.molagpt.app.core.render.visual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molagpt.app.core.markdown.visual.StatGridSpec
import com.molagpt.app.core.markdown.visual.StatItem
import com.molagpt.app.core.markdown.visual.StatTone
import com.molagpt.app.core.markdown.visual.StatTrend
import kotlin.math.roundToInt

/**
 * 关键指标：数值和单位、变化、有历史时一条小折线，按住横向拖动可读出任一期的值。
 * 颜色看 [StatTone]（好坏），箭头看 [StatTrend]（方向），两者分开，理由见 StatTone。
 */
@Composable
internal fun StatGridView(spec: StatGridSpec, modifier: Modifier = Modifier) {
    VisualFrame(title = spec.title, modifier = modifier, imageName = "指标") {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
            // 竖屏两列；横屏或平板按最小卡宽 150dp 排，最多四列。
            val columns = (maxWidth / 150.dp).toInt().coerceIn(2, 4).coerceAtMost(spec.items.size.coerceAtLeast(1))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.items.chunked(columns).forEach { row ->
                    // 同一排的卡片等高，页脚对齐。
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { item ->
                            StatCard(item, spec.periods, Modifier.weight(1f).fillMaxHeight())
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(item: StatItem, periods: List<String>, modifier: Modifier) {
    val palette = rememberVisualPalette()
    var scrub by remember(item) { mutableStateOf<Int?>(null) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .border(1.dp, palette.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            scrub?.let { i ->
                // 期数标签和历史值都以最近一期结尾，从末尾对齐：少一个标签时近期的值仍有标签。
                val p = i + periods.size - item.history.size
                Text(
                    text = periods.getOrNull(p) ?: "#${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        val shown = scrub?.let { VisualFormat.readout(item.history[it]) } ?: item.value
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.text)) { append(shown) }
                item.unit?.let { withStyle(SpanStyle(fontSize = 12.sp, color = palette.muted)) { append(" $it") } }
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (item.history.size > 1) {
            Sparkline(item.history, toneColor(item.tone, palette, fallback = palette.series(0)), scrub, palette) { scrub = it }
        }
        Spacer(Modifier.weight(1f))
        if (item.delta != null || item.note != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                item.delta?.let { delta ->
                    // 持平不加符号：「持平」「0%」前面一道横线会被读成负号。
                    val arrow = when (item.trend) {
                        StatTrend.UP -> "▲ "
                        StatTrend.DOWN -> "▼ "
                        else -> ""
                    }
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontSize = 9.sp)) { append(arrow) }
                            append(delta)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = toneColor(item.tone, palette, fallback = palette.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.note?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun toneColor(tone: StatTone, palette: VisualPalette, fallback: Color): Color = when (tone) {
    StatTone.GOOD -> palette.good
    StatTone.BAD -> palette.bad
    StatTone.NEUTRAL -> fallback
}

@Composable
private fun Sparkline(values: List<Double>, color: Color, scrub: Int?, palette: VisualPalette, onScrub: (Int?) -> Unit) {
    val current by rememberUpdatedState(scrub)
    val report by rememberUpdatedState(onScrub)
    val gestures = remember(values) {
        object : VisualGestures {
            var width = 1f

            fun index(x: Float) = ((x / width).coerceIn(0f, 1f) * (values.size - 1)).roundToInt()

            override fun onScrub(position: Offset) = report(index(position.x))

            override fun onTap(position: Offset) = report(if (current != null) null else index(position.x))
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(30.dp)
            .visualGestures(values, fullscreen = false, handler = gestures),
    ) {
        gestures.width = size.width.coerceAtLeast(1f)
        val lo = values.min()
        var span = values.max() - lo
        if (span <= 0) span = 1.0
        fun x(i: Int) = i * size.width / (values.size - 1)
        fun y(v: Double) = (3.dp.toPx() + (1 - (v - lo) / span) * (size.height - 6.dp.toPx())).toFloat()
        val path = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }
        drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (scrub != null && scrub in values.indices) {
            drawLine(palette.border, Offset(x(scrub), 0f), Offset(x(scrub), size.height), 1f)
            drawCircle(palette.surface, 4.5.dp.toPx(), Offset(x(scrub), y(values[scrub])))
            drawCircle(color, 3.2.dp.toPx(), Offset(x(scrub), y(values[scrub])))
        } else {
            drawCircle(color, 2.5.dp.toPx(), Offset(x(values.lastIndex), y(values.last())))
        }
    }
}
