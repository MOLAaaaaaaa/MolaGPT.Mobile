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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.markdown.visual.CurveKind
import com.molagpt.app.core.markdown.visual.FunctionPlotSpec
import com.molagpt.app.core.markdown.visual.PlotCurve
import com.molagpt.app.core.markdown.visual.PlotParam
import com.molagpt.app.core.render.LatexView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** 对话里函数图像的高度。固定高度：流式时占位就能预留准，图出来时下面的正文不跳。 */
internal val PLOT_HEIGHT = 240.dp

/**
 * 内嵌的函数图像：曲线、参数滑块、图例。
 *
 * 原生绘制而不是塞一个网页：它是对话的一部分，跟着滚动、跟主题、滚出屏幕就不花钱。
 * 对话里只读数不平移——平移和列表滚动抢同一根手指；想拖动、放大就进全屏。
 */
@Composable
internal fun FunctionPlotView(spec: FunctionPlotSpec, modifier: Modifier = Modifier) {
    val params = remember(spec) { PlotParams(spec) }
    val host = LocalVisualHost.current
    VisualFrame(
        title = spec.title?.takeIf { it.isNotBlank() } ?: "函数图像",
        modifier = modifier,
        imageName = "函数图像",
        onFullscreen = host?.let { h -> { h.openFullscreen { close -> FunctionPlotFullscreen(spec, params, close) } } },
    ) {
        PlotSurface(
            spec = spec,
            params = params,
            fullscreen = false,
            modifier = Modifier.fillMaxWidth().height(PLOT_HEIGHT).padding(horizontal = 6.dp, vertical = 4.dp),
        )
        ParamSliders(spec, params)
        PlotLegend(spec, params)
    }
}

@Composable
private fun FunctionPlotFullscreen(spec: FunctionPlotSpec, params: PlotParams, close: () -> Unit) {
    val palette = rememberVisualPalette()
    Column(modifier = Modifier.fillMaxSize().background(palette.surface)) {
        FullscreenBar(
            title = spec.title?.takeIf { it.isNotBlank() } ?: "函数图像",
            hint = "双指缩放 · 拖动平移 · 双击复位",
            onClose = close,
        )
        PlotSurface(
            spec = spec,
            params = params,
            fullscreen = true,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
        )
        ParamSliders(spec, params)
        PlotLegend(spec, params)
        Spacer(Modifier.height(8.dp))
    }
}

/** 滑块值和图例显隐：对话里和全屏共用一份，进出全屏不丢调好的参数。 */
@Stable
internal class PlotParams(spec: FunctionPlotSpec) {
    val values = mutableStateListOf<Double>().apply { addAll(spec.params.map { it.default }) }
    val hidden = mutableStateListOf<Boolean>().apply { addAll(spec.curves.map { false }) }

    fun array(values: List<Double>): DoubleArray {
        val fixed = FunctionPlotSpec.FIXED_VARIABLES.size
        return DoubleArray(fixed + values.size).also { a -> values.forEachIndexed { i, v -> a[fixed + i] = v } }
    }
}

internal data class Viewport(val x0: Double, val x1: Double, val y0: Double, val y1: Double)

/** 视窗和读数：每个画面各一份（全屏的宽高比不同，视窗不能共用）。 */
@Stable
private class PlotViewState {
    var viewport by mutableStateOf<Viewport?>(null)
    var readout by mutableStateOf<Pair<Double, Double>?>(null)
    var home: Viewport? = null
    var size: IntSize = IntSize.Zero
}

@Composable
private fun PlotSurface(spec: FunctionPlotSpec, params: PlotParams, fullscreen: Boolean, modifier: Modifier) {
    val palette = rememberVisualPalette()
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val view = remember(spec) { PlotViewState() }
    val values = params.values.toList()
    val hidden = params.hidden.toList()
    val viewport = view.viewport
    var sized by remember { mutableStateOf(IntSize.Zero) }
    val cell = with(density) { 2.5.dp.toPx() }
    val stroke = with(density) { 2.2.dp.toPx() }
    val piTicks = remember(spec) { spec.x?.let { PlotEngine.isPiMultiple(it.min) && PlotEngine.isPiMultiple(it.max) } == true }

    val paths = remember(viewport, values, sized) {
        if (viewport == null || sized.width == 0 || sized.height == 0) {
            emptyList()
        } else {
            PlotEngine.buildPaths(spec, params.array(values), viewport, sized.width.toFloat(), sized.height.toFloat(), cell)
        }
    }

    val gestures = remember(view, spec, fullscreen) {
        object : VisualGestures {
            override val canTransform get() = true

            override fun onTap(position: Offset) {
                val vp = view.viewport ?: return
                view.readout = if (view.readout != null && !fullscreen) null else PlotEngine.toWorld(vp, position, view.size)
            }

            override fun onDoubleTap() {
                // 按当前参数重新取景：振幅调大后复位，应该把变高的曲线框进来。
                view.readout = null
                val size = view.size
                if (size.width == 0 || size.height == 0) return
                val home = PlotEngine.computeHome(spec, params.array(params.values.toList()), size.width.toFloat(), size.height.toFloat())
                view.home = home
                view.viewport = home
            }

            override fun onScrub(position: Offset) {
                val vp = view.viewport ?: return
                view.readout = PlotEngine.toWorld(vp, position, view.size)
            }

            override fun onPan(delta: Offset) {
                val vp = view.viewport ?: return
                val w = view.size.width.coerceAtLeast(1)
                val h = view.size.height.coerceAtLeast(1)
                val dx = delta.x / w * (vp.x1 - vp.x0)
                val dy = delta.y / h * (vp.y1 - vp.y0)
                view.viewport = Viewport(vp.x0 - dx, vp.x1 - dx, vp.y0 + dy, vp.y1 + dy)
            }

            override fun onTransform(centroid: Offset, pan: Offset, zoom: Float) {
                val vp = view.viewport ?: return
                val factor = 1.0 / zoom.coerceIn(0.5f, 2f)
                val (ax, ay) = PlotEngine.toWorld(vp, centroid, view.size)
                val next = Viewport(
                    ax - (ax - vp.x0) * factor,
                    ax + (vp.x1 - ax) * factor,
                    ay - (ay - vp.y0) * factor,
                    ay + (vp.y1 - ay) * factor,
                )
                val span = next.x1 - next.x0
                if (span < 1e-6 || span > 1e7) return
                view.viewport = next
                onPan(pan)
            }
        }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                if (size == view.size || size.width == 0 || size.height == 0) return@onSizeChanged
                view.size = size
                val home = PlotEngine.computeHome(spec, params.array(params.values.toList()), size.width.toFloat(), size.height.toFloat())
                view.home = home
                view.viewport = home
                sized = size
            }
            .visualGestures(view, fullscreen, gestures),
    ) {
        val vp = viewport ?: return@Canvas
        val w = size.width
        val h = size.height

        // 网格与刻度
        val xStep = PlotEngine.tickStep(vp.x1 - vp.x0, w / (72.dp.toPx()), piTicks)
        val yStep = FunctionPlotSpec.niceStep((vp.y1 - vp.y0) / max(2f, h / 48.dp.toPx()))
        val origin = PlotEngine.toScreen(vp, 0.0, 0.0, w, h)
        val axisY = origin.y.coerceIn(0f, h)
        val axisX = origin.x.coerceIn(0f, w)
        val labels = ArrayList<Pair<androidx.compose.ui.text.TextLayoutResult, Offset>>()

        var x = ceil(vp.x0 / xStep) * xStep
        var guard = 0
        while (x <= vp.x1 && guard++ < 200) {
            val sx = PlotEngine.toScreen(vp, x, 0.0, w, h).x
            drawLine(palette.grid, Offset(sx, 0f), Offset(sx, h), 1f)
            if (abs(x) >= xStep * 1e-6) {
                val text = measureLabel(measurer, if (piTicks) PlotEngine.formatPi(x) else VisualFormat.tick(x, xStep), palette.muted)
                val ty = if (axisY + 3 + text.size.height > h) axisY - text.size.height - 2 else axisY + 3
                labels += text to Offset((sx - text.size.width / 2f).coerceIn(2f, max(2f, w - text.size.width - 2f)), ty)
            }
            x += xStep
        }
        var y = ceil(vp.y0 / yStep) * yStep
        guard = 0
        while (y <= vp.y1 && guard++ < 200) {
            val sy = PlotEngine.toScreen(vp, 0.0, y, w, h).y
            drawLine(palette.grid, Offset(0f, sy), Offset(w, sy), 1f)
            if (abs(y) >= yStep * 1e-6) {
                val text = measureLabel(measurer, VisualFormat.tick(y, yStep), palette.muted)
                val tx = if (axisX - text.size.width - 4 < 0) axisX + 4 else axisX - text.size.width - 4
                labels += text to Offset(tx, (sy - text.size.height / 2f).coerceIn(0f, max(0f, h - text.size.height)))
            }
            y += yStep
        }
        if (origin.y in 0f..h) drawLine(palette.axis, Offset(0f, origin.y), Offset(w, origin.y), 1.2f)
        if (origin.x in 0f..w) drawLine(palette.axis, Offset(origin.x, 0f), Offset(origin.x, h), 1.2f)

        for ((curve, path) in paths) {
            if (hidden.getOrElse(curve) { false }) continue
            drawPath(path, palette.series(curve), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 刻度字最后画，垫一层底色：曲线穿过数字时不会把它划掉。
        val backing = palette.surface.copy(alpha = 0.82f)
        for ((text, at) in labels) {
            drawRoundRect(backing, Offset(at.x - 2, at.y), Size(text.size.width + 4f, text.size.height.toFloat()), CornerRadius(3f))
            drawText(text, topLeft = at)
        }

        view.readout?.let { (wx, wy) ->
            val lines = ArrayList<ReadoutLine>()
            val sx = PlotEngine.toScreen(vp, wx, 0.0, w, h).x
            drawLine(palette.axis, Offset(sx, 0f), Offset(sx, h), 1f)
            val v = params.array(values)
            v[0] = wx
            spec.curves.forEachIndexed { i, curve ->
                if (lines.size >= 5 || hidden.getOrElse(i) { false }) return@forEachIndexed
                if (curve.error != null || curve.kind != CurveKind.EXPLICIT_Y) return@forEachIndexed
                val value = curve.fn?.eval(v) ?: return@forEachIndexed
                if (!value.isFinite()) return@forEachIndexed
                val point = PlotEngine.toScreen(vp, wx, value, w, h)
                if (point.y in 0f..h) {
                    drawCircle(palette.surface, 5.5.dp.toPx(), point)
                    drawCircle(palette.series(i), 4.dp.toPx(), point)
                }
                val name = curve.label?.takeIf { it.isNotBlank() } ?: "y"
                lines += ReadoutLine("$name = ${VisualFormat.readout(value)}", palette.series(i))
            }
            val head = if (lines.isEmpty()) {
                "x = ${VisualFormat.readout(wx)}   y = ${VisualFormat.readout(wy)}"
            } else {
                "x = ${VisualFormat.readout(wx)}"
            }
            drawReadout(measurer, listOf(ReadoutLine(head, strong = true)) + lines, palette)
        }
    }
}

@Composable
private fun ParamSliders(spec: FunctionPlotSpec, params: PlotParams) {
    if (spec.params.isEmpty()) return
    val palette = rememberVisualPalette()
    Column(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 2.dp)) {
        spec.params.forEachIndexed { i, param ->
            Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.widthIn(min = 20.dp)) {
                    CompositionLocalProvider(LocalContentColor provides palette.text) {
                        LatexView(expr = paramLatex(param.name), display = false)
                    }
                }
                Slider(
                    value = params.values[i].toFloat(),
                    onValueChange = { params.values[i] = snap(it.toDouble(), param) },
                    valueRange = param.min.toFloat()..param.max.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(
                    text = VisualFormat.readout(params.values[i]),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.text,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 44.dp),
                )
            }
        }
    }
}

@Composable
private fun PlotLegend(spec: FunctionPlotSpec, params: PlotParams) {
    val palette = rememberVisualPalette()
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        spec.curves.forEachIndexed { i, curve ->
            LegendEntry(curve, palette.series(i), hidden = params.hidden.getOrElse(i) { false }, palette = palette) {
                params.hidden[i] = !params.hidden[i]
            }
        }
    }
}

@Composable
private fun LegendEntry(curve: PlotCurve, color: androidx.compose.ui.graphics.Color, hidden: Boolean, palette: VisualPalette, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = curve.error == null, onClick = onToggle)
            .alpha(if (hidden) 0.45f else 1f)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(color),
        )
        Spacer(Modifier.width(7.dp))
        when {
            curve.error != null -> Text(
                text = "${curve.source}：${curve.error}",
                style = MaterialTheme.typography.labelMedium,
                color = palette.bad,
            )
            !curve.label.isNullOrBlank() -> Text(
                text = curve.label!!,
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
            )
            else -> CompositionLocalProvider(LocalContentColor provides palette.text) {
                LatexView(expr = curve.latex.orEmpty(), display = false)
            }
        }
    }
}

private fun snap(value: Double, param: PlotParam): Double {
    val steps = ((value - param.min) / param.step).roundToInt()
    return (param.min + steps * param.step).coerceIn(param.min, param.max)
}

private fun paramLatex(name: String): String = when {
    name in setOf("alpha", "beta", "gamma", "delta", "lambda", "mu", "sigma", "omega", "phi", "rho", "kappa") -> "\\$name"
    name.length > 1 && name[0].isLetter() && name.drop(1).all { it.isDigit() } -> "${name[0]}_{${name.drop(1)}}"
    name.length == 1 -> name
    else -> "\\mathit{$name}"
}

/** 取样与坐标换算。移植自桌面端 PlotSurface，算法一致。 */
internal object PlotEngine {
    fun toWorld(vp: Viewport, p: Offset, size: IntSize): Pair<Double, Double> {
        val w = size.width.coerceAtLeast(1)
        val h = size.height.coerceAtLeast(1)
        return (vp.x0 + p.x / w * (vp.x1 - vp.x0)) to (vp.y1 - p.y / h * (vp.y1 - vp.y0))
    }

    fun toScreen(vp: Viewport, x: Double, y: Double, w: Float, h: Float): Offset {
        val sx = (x - vp.x0) / (vp.x1 - vp.x0) * w
        val sy = (vp.y1 - y) / (vp.y1 - vp.y0) * h
        // 远处的点也保持有限且有界，路径才不会出怪。
        return Offset(sx.coerceIn(-4.0 * w, 5.0 * w).toFloat(), sy.coerceIn(-4.0 * h, 5.0 * h).toFloat())
    }

    fun tickStep(span: Double, slots: Float, pi: Boolean): Double {
        val raw = span / max(2f, slots)
        return if (pi) piStep(raw) else FunctionPlotSpec.niceStep(raw)
    }

    fun computeHome(spec: FunctionPlotSpec, values: DoubleArray, width: Float, height: Float): Viewport {
        val aspect = height / max(1f, width).toDouble()
        val hasExplicit = spec.curves.any { it.error == null && it.kind == CurveKind.EXPLICIT_Y }

        val x: Pair<Double, Double>
        val given = spec.x
        if (given != null) {
            x = given.min to given.max
        } else {
            val box = if (!hasExplicit) extent(spec, values) else null
            if (box != null) {
                // 闭合图形（圆、玫瑰线、参数环）：把它框进画面。
                val cx = (box.x0 + box.x1) / 2
                val cy = (box.y0 + box.y1) / 2
                var half = max((box.x1 - box.x0) / 2, (box.y1 - box.y0) / 2 / aspect) * 1.15
                if (half <= 0) half = 1.0
                return Viewport(cx - half, cx + half, cy - half * aspect, cy + half * aspect)
            }
            x = -10.0 to 10.0
        }

        spec.y?.let { return Viewport(x.first, x.second, it.min, it.max) }
        if (hasExplicit) sampleRange(spec, values, x.first, x.second)?.let { return Viewport(x.first, x.second, it.first, it.second) }

        // 两轴等比例，圆才像圆。
        val halfY = (x.second - x.first) * aspect / 2
        return Viewport(x.first, x.second, -halfY, halfY)
    }

    private fun sampleRange(spec: FunctionPlotSpec, values: DoubleArray, x0: Double, x1: Double): Pair<Double, Double>? {
        val ys = ArrayList<Double>()
        val v = values.copyOf()
        for (curve in spec.curves) {
            val fn = curve.fn
            if (curve.error != null || curve.kind != CurveKind.EXPLICIT_Y || fn == null) continue
            for (i in 0..400) {
                v[0] = x0 + (x1 - x0) * i / 400
                val y = fn.eval(v)
                if (y.isFinite()) ys += y
            }
        }
        if (ys.size < 2) return null
        ys.sort()
        // 去掉两头的极端值，一条渐近线不至于把其余部分压扁。
        var lo = ys[(ys.size * 0.02).toInt()]
        var hi = ys[min(ys.size - 1, (ys.size * 0.98).toInt())]
        if (hi - lo < 1e-9) {
            lo -= 1
            hi += 1
        }
        val pad = (hi - lo) * 0.12
        return (lo - pad) to (hi + pad)
    }

    private fun extent(spec: FunctionPlotSpec, values: DoubleArray): Viewport? {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var any = false
        val v = values.copyOf()
        for (curve in spec.curves) {
            val fn = curve.fn
            if (curve.error != null || fn == null) continue
            when (curve.kind) {
                CurveKind.POLAR, CurveKind.PARAMETRIC -> for (i in 0..720) {
                    val t = curve.tMin + (curve.tMax - curve.tMin) * i / 720
                    setT(v, t)
                    val (px, py) = polarOrParametric(curve, v, t)
                    if (!px.isFinite() || !py.isFinite()) continue
                    minX = min(minX, px); maxX = max(maxX, px)
                    minY = min(minY, py); maxY = max(maxY, py)
                    any = true
                }
                CurveKind.IMPLICIT -> {
                    // 在 [-20, 20] 的粗网格上找零点集在哪里。
                    val n = 120
                    val cell = 40.0 / n
                    for (i in 0 until n) for (j in 0 until n) {
                        val x = -20 + cell * i
                        val y = -20 + cell * j
                        v[0] = x; v[1] = y
                        val a = fn.eval(v)
                        v[0] = x + cell
                        val b = fn.eval(v)
                        v[0] = x; v[1] = y + cell
                        val c = fn.eval(v)
                        if (!a.isFinite()) continue
                        if ((b.isFinite() && sign(a) != sign(b)) || (c.isFinite() && sign(a) != sign(c))) {
                            minX = min(minX, x); maxX = max(maxX, x)
                            minY = min(minY, y); maxY = max(maxY, y)
                            any = true
                        }
                    }
                }
                else -> Unit
            }
        }
        return if (any) Viewport(minX, maxX, minY, maxY) else null
    }

    fun buildPaths(spec: FunctionPlotSpec, values: DoubleArray, vp: Viewport, w: Float, h: Float, cell: Float): List<Pair<Int, Path>> {
        val result = ArrayList<Pair<Int, Path>>()
        val v = values.copyOf()
        spec.curves.forEachIndexed { i, curve ->
            val fn = curve.fn
            if (curve.error != null || fn == null) return@forEachIndexed
            val path = Path()
            when (curve.kind) {
                CurveKind.EXPLICIT_Y -> traceExplicit(path, fn, v, vp, w, h, alongX = true)
                CurveKind.EXPLICIT_X -> traceExplicit(path, fn, v, vp, w, h, alongX = false)
                CurveKind.IMPLICIT -> traceImplicit(path, fn, v, vp, w, h, cell)
                else -> traceParametric(path, curve, v, vp, w, h)
            }
            result += i to path
        }
        return result
    }

    /**
     * y = f(x) 按像素列取样（x = g(y) 按行）。一次跳变超过整个视窗、且中点不在两个样本之间，
     * 是渐近线而不是陡坡——在那里抬笔，不画穿过无穷远的竖线。
     */
    private fun traceExplicit(path: Path, fn: com.molagpt.app.core.markdown.visual.MathFn, v: DoubleArray, vp: Viewport, w: Float, h: Float, alongX: Boolean) {
        val pixels = if (alongX) w else h
        val samples = max(64, (pixels * 1.2f).toInt())
        val from = if (alongX) vp.x0 else vp.y0
        val to = if (alongX) vp.x1 else vp.y1
        val span = if (alongX) vp.y1 - vp.y0 else vp.x1 - vp.x0
        val slot = if (alongX) 0 else 1
        var open = false
        var prevT = 0.0
        var prevV = 0.0
        for (i in 0..samples) {
            val t = from + (to - from) * i / samples
            v[slot] = t
            val value = fn.eval(v)
            if (!value.isFinite()) {
                open = false
                continue
            }
            if (open && abs(value - prevV) > span * 1.5) {
                v[slot] = (t + prevT) / 2
                val mid = fn.eval(v)
                val lo = min(value, prevV) - span * 0.1
                val hi = max(value, prevV) + span * 0.1
                if (!mid.isFinite() || mid < lo || mid > hi) open = false
            }
            val point = if (alongX) toScreen(vp, t, value, w, h) else toScreen(vp, value, t, w, h)
            if (!open) {
                path.moveTo(point.x, point.y)
                open = true
            } else {
                path.lineTo(point.x, point.y)
            }
            prevT = t
            prevV = value
        }
    }

    /**
     * F(x, y) = 0 用 marching squares。一条边上变号、而边中点的绝对值比两端都大，是极点
     * （tan、1/x），跳过不画。
     */
    private fun traceImplicit(path: Path, fn: com.molagpt.app.core.markdown.visual.MathFn, v: DoubleArray, vp: Viewport, w: Float, h: Float, cell: Float) {
        val nx = ceil(w / cell).toInt()
        val ny = ceil(h / cell).toInt()
        val field = Array(nx + 1) { DoubleArray(ny + 1) }
        val wx = DoubleArray(nx + 1) { vp.x0 + it * cell / w * (vp.x1 - vp.x0) }
        val wy = DoubleArray(ny + 1) { vp.y1 - it * cell / h * (vp.y1 - vp.y0) }
        for (i in 0..nx) for (j in 0..ny) {
            v[0] = wx[i]
            v[1] = wy[j]
            field[i][j] = fn.eval(v)
        }

        val xs = FloatArray(4)
        val ys = FloatArray(4)
        fun crossing(ai: Int, aj: Int, bi: Int, bj: Int, slot: Int): Boolean {
            val a = field[ai][aj]
            val b = field[bi][bj]
            if (!a.isFinite() || !b.isFinite() || sign(a) == sign(b) || a == b) return false
            v[0] = (wx[ai] + wx[bi]) / 2
            v[1] = (wy[aj] + wy[bj]) / 2
            val mid = fn.eval(v)
            if (!mid.isFinite() || abs(mid) > max(abs(a), abs(b))) return false
            val t = (a / (a - b)).toFloat()
            xs[slot] = (ai + (bi - ai) * t) * cell
            ys[slot] = (aj + (bj - aj) * t) * cell
            return true
        }

        for (i in 0 until nx) for (j in 0 until ny) {
            var n = 0
            if (crossing(i, j, i + 1, j, n)) n++
            if (crossing(i + 1, j, i + 1, j + 1, n)) n++
            if (crossing(i, j + 1, i + 1, j + 1, n)) n++
            if (crossing(i, j, i, j + 1, n)) n++
            var k = 0
            while (k + 1 < n) {
                path.moveTo(xs[k], ys[k])
                path.lineTo(xs[k + 1], ys[k + 1])
                k += 2
            }
        }
    }

    private fun traceParametric(path: Path, curve: PlotCurve, v: DoubleArray, vp: Viewport, w: Float, h: Float) {
        val range = curve.tMax - curve.tMin
        val samples = (range / (2 * PI) * 900).toInt().coerceIn(400, 6000)
        val limit = 3 * max(w, h)
        var open = false
        var previous = Offset.Zero
        for (i in 0..samples) {
            val t = curve.tMin + range * i / samples
            setT(v, t)
            val (x, y) = polarOrParametric(curve, v, t)
            if (!x.isFinite() || !y.isFinite()) {
                open = false
                continue
            }
            val point = toScreen(vp, x, y, w, h)
            if (open && (abs(point.x - previous.x) > limit || abs(point.y - previous.y) > limit)) open = false
            if (!open) {
                path.moveTo(point.x, point.y)
                open = true
            } else {
                path.lineTo(point.x, point.y)
            }
            previous = point
        }
    }

    private fun polarOrParametric(curve: PlotCurve, v: DoubleArray, t: Double): Pair<Double, Double> {
        val fnY = curve.fnY
        if (curve.kind == CurveKind.PARAMETRIC && fnY != null) return curve.fn!!.eval(v) to fnY.eval(v)
        val r = curve.fn!!.eval(v)
        return r * cos(t) to r * sin(t)
    }

    private fun setT(v: DoubleArray, t: Double) {
        v[2] = t
        v[3] = t
        v[4] = t
    }

    // ---- π 刻度 ----

    fun isPiMultiple(value: Double): Boolean {
        if (abs(value) < 1e-9) return true
        val quarters = value / (PI / 4)
        return abs(quarters - quarters.roundToInt()) < 1e-3
    }

    private fun piStep(raw: Double): Double {
        val quarter = PI / 4
        val multiple = 2.0.pow(ceil(log2(max(raw / quarter, 1.0))))
        return multiple * quarter
    }

    fun formatPi(value: Double): String {
        val quarters = (value / (PI / 4)).roundToInt()
        if (quarters == 0) return "0"
        val divisor = gcd(abs(quarters), 4)
        val numerator = quarters / divisor
        val denominator = 4 / divisor
        val head = when (numerator) {
            1 -> "π"
            -1 -> "-π"
            else -> "${numerator}π"
        }
        return if (denominator == 1) head else "$head/$denominator"
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
