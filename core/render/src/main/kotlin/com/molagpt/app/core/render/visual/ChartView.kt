package com.molagpt.app.core.render.visual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.markdown.visual.ChartSpec
import com.molagpt.app.core.markdown.visual.ChartType
import com.molagpt.app.core.markdown.visual.FunctionPlotSpec
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal val CHART_HEIGHT = 220.dp

/**
 * 内嵌数据图表：折线、柱状、面积、散点、饼图，图例点按显隐，按住横向拖动读数。
 * 固定高度，理由同函数图像。
 */
@Composable
internal fun ChartView(spec: ChartSpec, modifier: Modifier = Modifier) {
    val state = remember(spec) { ChartState(spec) }
    val host = LocalVisualHost.current
    VisualFrame(
        title = spec.title?.takeIf { it.isNotBlank() } ?: "图表",
        meta = spec.unit?.takeIf { it.isNotBlank() }?.let { "单位：$it" },
        modifier = modifier,
        onFullscreen = host?.let { h -> { h.openFullscreen { close -> ChartFullscreen(spec, state, close) } } },
    ) {
        ChartSurface(
            spec = spec,
            state = state,
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(start = 8.dp, end = 12.dp, top = 6.dp),
        )
        ChartLegend(spec, state)
    }
}

@Composable
private fun ChartFullscreen(spec: ChartSpec, state: ChartState, close: () -> Unit) {
    val palette = rememberVisualPalette()
    Column(modifier = Modifier.fillMaxSize().background(palette.surface)) {
        FullscreenBar(
            title = spec.title?.takeIf { it.isNotBlank() } ?: "图表",
            hint = spec.unit?.takeIf { it.isNotBlank() }?.let { "单位：$it" },
            onClose = close,
        )
        ChartSurface(spec, state, Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp))
        ChartLegend(spec, state)
        Spacer(Modifier.height(8.dp))
    }
}

@Stable
private class ChartState(spec: ChartSpec) {
    val hidden = mutableStateListOf<Boolean>().apply { addAll(spec.series.map { false }) }
}

@Composable
private fun ChartSurface(spec: ChartSpec, state: ChartState, modifier: Modifier) {
    val palette = rememberVisualPalette()
    val measurer = rememberTextMeasurer(cacheSize = 96)
    var touch by remember(spec) { mutableStateOf<Offset?>(null) }
    val hidden = state.hidden.toList()
    val gestures = remember(spec) {
        object : VisualGestures {
            override fun onTap(position: Offset) {
                touch = if (touch != null) null else position
            }

            override fun onScrub(position: Offset) {
                touch = position
            }
        }
    }
    Canvas(modifier = modifier.clipToBounds().visualGestures(spec, fullscreen = false, handler = gestures)) {
        if (size.width < 40 || size.height < 40) return@Canvas
        when (spec.type) {
            ChartType.PIE -> drawPie(spec, palette, measurer, touch)
            ChartType.SCATTER -> drawScatter(spec, hidden, palette, measurer, touch)
            else -> drawCategorical(spec, hidden, palette, measurer, touch)
        }
    }
}

@Composable
private fun ChartLegend(spec: ChartSpec, state: ChartState) {
    val palette = rememberVisualPalette()
    val entries = ArrayList<Triple<Int, String, Boolean>>()
    if (spec.type == ChartType.PIE) {
        val values = spec.series[0].values
        val total = values.sumOf { v -> if (v != null && v > 0) v else 0.0 }
        for (i in spec.categories.indices) {
            val value = values.getOrNull(i) ?: continue
            if (value <= 0) continue
            entries += Triple(i, "${spec.categories[i]}  ${VisualFormat.percent(value / total)}", false)
        }
    } else if (spec.series.size > 1 || spec.series[0].name.isNotBlank()) {
        spec.series.forEachIndexed { i, s -> entries += Triple(i, s.name, spec.series.size > 1) }
    }
    if (entries.isEmpty()) {
        Spacer(Modifier.height(10.dp))
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEach { (index, text, toggle) ->
            val hidden = toggle && state.hidden.getOrElse(index) { false }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = toggle) { state.hidden[index] = !state.hidden[index] }
                    .alpha(if (hidden) 0.45f else 1f)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(palette.series(index)))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = palette.text)
            }
        }
    }
}

// ---- 分类图（折线 / 柱状 / 面积）----

private fun DrawScope.drawCategorical(
    spec: ChartSpec,
    hidden: List<Boolean>,
    palette: VisualPalette,
    measurer: TextMeasurer,
    touch: Offset?,
) {
    val n = spec.categories.size
    if (n == 0) return
    val visible = spec.series.indices.filter { !hidden.getOrElse(it) { false } }
    val stacked = spec.stacked && (spec.type == ChartType.BAR || spec.type == ChartType.AREA)
    fun value(series: Int, category: Int): Double? = spec.series[series].values.getOrNull(category)

    var lo = 0.0
    var hi = 0.0
    for (c in 0 until n) {
        var positive = 0.0
        var negative = 0.0
        for (s in visible) {
            val v = value(s, c) ?: continue
            if (stacked) {
                if (v >= 0) positive += v else negative += v
            } else {
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
        if (stacked) {
            lo = min(lo, negative)
            hi = max(hi, positive)
        }
    }
    if (hi - lo < 1e-12) hi = lo + 1
    val step = FunctionPlotSpec.niceStep((hi - lo) / 5)
    val yMin = floor(lo / step) * step
    val yMax = ceil(hi / step) * step

    val yLabels = ArrayList<Pair<Double, androidx.compose.ui.text.TextLayoutResult>>()
    var tick = yMin
    while (tick <= yMax + step * 1e-9 && yLabels.size < 40) {
        yLabels += tick to measureLabel(measurer, VisualFormat.tick(tick, step), palette.muted)
        tick += step
    }
    val left = yLabels.maxOf { it.second.size.width } + 8.dp.toPx()
    val bottomBand = 20.dp.toPx()
    val plot = Rect(left, 6.dp.toPx(), size.width - 2.dp.toPx(), size.height - bottomBand)
    fun y(v: Double) = (plot.bottom - (v - yMin) / (yMax - yMin) * plot.height).toFloat()
    val band = plot.width / n
    fun x(c: Int) = plot.left + band * (c + 0.5f)

    for ((v, text) in yLabels) {
        val yy = y(v)
        drawLine(palette.grid, Offset(plot.left, yy), Offset(plot.right, yy), 1f)
        drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), yy - text.size.height / 2f))
    }
    drawLine(palette.axis, Offset(plot.left, y(0.0)), Offset(plot.right, y(0.0)), 1.2f)

    // 分类标签隔 k 个画一个，不会挤在一起。
    val widest = spec.categories.maxOf { measureLabel(measurer, it, palette.muted).size.width } + 8.dp.toPx()
    val every = max(1, ceil(widest / max(1f, band)).toInt())
    var c = 0
    while (c < n) {
        val text = measureLabel(measurer, spec.categories[c], palette.muted)
        drawText(text, topLeft = Offset(x(c) - text.size.width / 2f, plot.bottom + 4.dp.toPx()))
        c += every
    }

    if (spec.type == ChartType.BAR) {
        val group = band * 0.72f
        val positiveBase = DoubleArray(n)
        val negativeBase = DoubleArray(n)
        visible.forEachIndexed { k, s ->
            val fill = palette.series(s)
            for (cat in 0 until n) {
                val v = value(s, cat) ?: continue
                val rect = if (stacked) {
                    val from = if (v >= 0) positiveBase[cat] else negativeBase[cat]
                    val to = from + v
                    if (v >= 0) positiveBase[cat] = to else negativeBase[cat] = to
                    Rect(x(cat) - group / 2, min(y(from), y(to)), x(cat) + group / 2, max(y(from), y(to)))
                } else {
                    val width = group / max(1, visible.size)
                    val left0 = x(cat) - group / 2 + width * k
                    Rect(left0 + 1, min(y(0.0), y(v)), left0 + max(1f, width - 2), max(y(0.0), y(v)))
                }
                drawRoundRect(fill, rect.topLeft, rect.size, CornerRadius(2.dp.toPx()))
            }
        }
    } else {
        val cumulative = DoubleArray(n)
        val stroke = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (s in visible) {
            val points = arrayOfNulls<Offset>(n)
            val lower = ArrayList<Offset>()
            for (cat in 0 until n) {
                val v = value(s, cat) ?: continue
                val from = if (stacked) cumulative[cat] else 0.0
                val to = if (stacked) from + v else v
                if (stacked) cumulative[cat] = to
                points[cat] = Offset(x(cat), y(to))
                lower += Offset(x(cat), y(from))
            }
            val solid = points.filterNotNull()
            if (spec.type == ChartType.AREA && solid.size >= 2) {
                val area = Path().apply {
                    moveTo(solid[0].x, solid[0].y)
                    solid.drop(1).forEach { lineTo(it.x, it.y) }
                    for (i in lower.indices.reversed()) lineTo(lower[i].x, lower[i].y)
                    close()
                }
                drawPath(area, palette.series(s).copy(alpha = 0.22f))
            }
            val line = Path()
            var open = false
            for (p in points) {
                if (p == null) {
                    open = false
                    continue
                }
                if (!open) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                open = true
            }
            drawPath(line, palette.series(s), style = stroke)
            if (n <= 24) {
                for (p in solid) {
                    drawCircle(palette.surface, 3.dp.toPx(), p)
                    drawCircle(palette.series(s), 3.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }

    if (touch != null && touch.x in plot.left..plot.right) {
        val cat = ((touch.x - plot.left) / band).toInt().coerceIn(0, n - 1)
        drawLine(palette.axis, Offset(x(cat), plot.top), Offset(x(cat), plot.bottom), 1f)
        val lines = ArrayList<ReadoutLine>()
        lines += ReadoutLine(spec.categories[cat], strong = true)
        for (s in visible) {
            val v = value(s, cat)
            lines += ReadoutLine("${spec.series[s].name}：${v?.let(VisualFormat::readout) ?: "–"}${spec.unit.orEmpty()}", palette.series(s))
        }
        drawReadout(measurer, lines, palette, Offset(x(cat), touch.y))
    }
}

// ---- 散点 ----

private fun DrawScope.drawScatter(
    spec: ChartSpec,
    hidden: List<Boolean>,
    palette: VisualPalette,
    measurer: TextMeasurer,
    touch: Offset?,
) {
    val visible = spec.series.indices.filter { !hidden.getOrElse(it) { false } }
    val all = visible.flatMap { spec.series[it].points }
    if (all.isEmpty()) return
    val (x0, x1, xStep) = niceRange(all.minOf { it.first }, all.maxOf { it.first })
    val (y0, y1, yStep) = niceRange(all.minOf { it.second }, all.maxOf { it.second })

    val yLabels = ArrayList<Pair<Double, androidx.compose.ui.text.TextLayoutResult>>()
    var v = y0
    while (v <= y1 + yStep * 1e-9 && yLabels.size < 40) {
        yLabels += v to measureLabel(measurer, VisualFormat.tick(v, yStep), palette.muted)
        v += yStep
    }
    val left = yLabels.maxOf { it.second.size.width } + 8.dp.toPx()
    val plot = Rect(left, 6.dp.toPx(), size.width - 6.dp.toPx(), size.height - 20.dp.toPx())
    fun sx(value: Double) = (plot.left + (value - x0) / (x1 - x0) * plot.width).toFloat()
    fun sy(value: Double) = (plot.bottom - (value - y0) / (y1 - y0) * plot.height).toFloat()

    for ((value, text) in yLabels) {
        drawLine(palette.grid, Offset(plot.left, sy(value)), Offset(plot.right, sy(value)), 1f)
        drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), sy(value) - text.size.height / 2f))
    }
    v = x0
    var guard = 0
    while (v <= x1 + xStep * 1e-9 && guard++ < 40) {
        drawLine(palette.grid, Offset(sx(v), plot.top), Offset(sx(v), plot.bottom), 1f)
        val text = measureLabel(measurer, VisualFormat.tick(v, xStep), palette.muted)
        drawText(text, topLeft = Offset(sx(v) - text.size.width / 2f, plot.bottom + 4.dp.toPx()))
        v += xStep
    }

    var nearest: Triple<Float, Int, Pair<Double, Double>>? = null
    val reach = 24.dp.toPx()
    for (s in visible) {
        val fill = palette.series(s).copy(alpha = 0.85f)
        for (p in spec.series[s].points) {
            val screen = Offset(sx(p.first), sy(p.second))
            drawCircle(fill, 3.6.dp.toPx(), screen)
            if (touch != null) {
                val d = hypot(touch.x - screen.x, touch.y - screen.y)
                if (d < reach && (nearest == null || d < nearest.first)) nearest = Triple(d, s, p)
            }
        }
    }
    nearest?.let { (_, s, p) ->
        val screen = Offset(sx(p.first), sy(p.second))
        drawCircle(palette.series(s), 6.dp.toPx(), screen, style = Stroke(2.dp.toPx()))
        drawReadout(
            measurer,
            listOf(
                ReadoutLine(spec.series[s].name, palette.series(s), strong = true),
                ReadoutLine("x = ${VisualFormat.readout(p.first)}   y = ${VisualFormat.readout(p.second)}"),
            ),
            palette,
            screen,
        )
    }
}

private fun niceRange(min0: Double, max0: Double): Triple<Double, Double, Double> {
    var lo = min0
    var hi = max0
    if (hi - lo < 1e-12) {
        lo -= 1
        hi += 1
    }
    val step = FunctionPlotSpec.niceStep((hi - lo) / 5)
    return Triple(floor(lo / step) * step, ceil(hi / step) * step, step)
}

// ---- 饼图（环形）----

private fun DrawScope.drawPie(spec: ChartSpec, palette: VisualPalette, measurer: TextMeasurer, touch: Offset?) {
    val values = spec.series[0].values
    val slices = ArrayList<Pair<Int, Double>>()
    for (i in values.indices) {
        if (i >= spec.categories.size) break
        val v = values[i] ?: continue
        if (v > 0) slices += i to v
    }
    val total = slices.sumOf { it.second }
    if (total <= 0) return

    val center = Offset(size.width / 2, size.height / 2)
    val radius = min(size.width, size.height) / 2 - 10.dp.toPx()
    val thickness = radius * 0.44f

    var hovered: Int? = null
    if (touch != null) {
        val dx = touch.x - center.x
        val dy = touch.y - center.y
        val r = hypot(dx, dy)
        if (r >= radius - thickness - 8.dp.toPx() && r <= radius + 12.dp.toPx()) {
            val angle = ((atan2(dy, dx) + PI / 2 + 2 * PI) % (2 * PI))
            var acc = 0.0
            for ((index, value) in slices) {
                val sweep = value / total * 2 * PI
                if (angle >= acc && angle < acc + sweep) {
                    hovered = index
                    break
                }
                acc += sweep
            }
        }
    }

    var start = -90f
    for ((index, value) in slices) {
        val sweep = (value / total * 360).toFloat()
        val grow = if (hovered == index) 5.dp.toPx() else 0f
        val r = radius - thickness / 2 + grow / 2
        drawArc(
            color = palette.series(index),
            startAngle = start,
            sweepAngle = max(0.1f, sweep - 0.6f),
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(thickness + grow),
        )
        start += sweep
    }

    hovered?.let { h ->
        val value = values[h]!!
        drawReadout(
            measurer,
            listOf(
                ReadoutLine(spec.categories[h], palette.series(h), strong = true),
                ReadoutLine("${VisualFormat.readout(value)}${spec.unit.orEmpty()}（${VisualFormat.percent(value / total)}）"),
            ),
            palette,
            touch,
        )
    }
}
