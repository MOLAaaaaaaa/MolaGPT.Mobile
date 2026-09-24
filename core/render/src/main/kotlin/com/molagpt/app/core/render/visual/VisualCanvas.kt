package com.molagpt.app.core.render.visual

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 图像类组件的触屏手势。
 *
 * 嵌在对话里时（[fullscreen] = false）：单指横向拖动是读数，竖向拖动一律让给列表滚动；
 * 双指缩放；点按在点按处读数，双击复位。全屏时单指拖动是平移。
 * 判定在越过触摸阈值的那一刻做一次：先横着走就归组件，先竖着走就整段手势都不碰，
 * 列表才不会被一张满宽的图卡住。
 */
internal interface VisualGestures {
    val canTransform: Boolean get() = false
    fun onTap(position: Offset) {}
    fun onDoubleTap() {}
    fun onScrub(position: Offset) {}
    fun onPan(delta: Offset) {}
    fun onTransform(centroid: Offset, pan: Offset, zoom: Float) {}
}

internal fun Modifier.visualGestures(key: Any?, fullscreen: Boolean, handler: VisualGestures): Modifier =
    pointerInput(key, fullscreen) {
        val slop = viewConfiguration.touchSlop
        var lastTap = 0L
        var lastTapAt = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 0 未定、1 读数 / 平移、2 双指、-1 让给列表
            var mode = 0
            var travel = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2 && handler.canTransform && mode != -1) {
                    mode = 2
                    handler.onTransform(event.calculateCentroid(useCurrent = true), event.calculatePan(), event.calculateZoom())
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (mode == 2) {
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (mode == -1) continue
                val change = pressed.first()
                val delta = change.positionChange()
                travel += delta
                if (mode == 0) {
                    mode = when {
                        fullscreen && travel.getDistance() > slop -> 1
                        !fullscreen && abs(travel.x) > slop && abs(travel.x) > abs(travel.y) -> 1
                        !fullscreen && abs(travel.y) > slop -> -1
                        else -> 0
                    }
                }
                if (mode == 1) {
                    if (fullscreen) handler.onPan(delta) else handler.onScrub(change.position)
                    change.consume()
                }
            }
            if (mode == 0) {
                val now = System.currentTimeMillis()
                if (now - lastTap < viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapAt).getDistance() < slop * 3) {
                    handler.onDoubleTap()
                    lastTap = 0L
                } else {
                    handler.onTap(down.position)
                    lastTap = now
                    lastTapAt = down.position
                }
            }
        }
    }

internal class ReadoutLine(val text: String, val swatch: Color? = null, val strong: Boolean = false)

internal val LabelStyle = TextStyle(fontSize = 10.5.sp)

internal fun DrawScope.measureLabel(measurer: TextMeasurer, text: String, color: Color, style: TextStyle = LabelStyle): TextLayoutResult =
    measurer.measure(text, style.copy(color = color), maxLines = 1)

/**
 * 读数框。[anchor] 为空时放在右上角；否则放在锚点旁，右边放不下就放左边。
 */
internal fun DrawScope.drawReadout(
    measurer: TextMeasurer,
    lines: List<ReadoutLine>,
    palette: VisualPalette,
    anchor: Offset? = null,
) {
    if (lines.isEmpty()) return
    val pad = 7.sp.toPx()
    val swatchSpace = 12.sp.toPx()
    val layouts = lines.map { line ->
        measurer.measure(
            line.text,
            TextStyle(
                fontSize = if (line.strong) 11.5.sp else 11.sp,
                fontWeight = if (line.strong) FontWeight.SemiBold else FontWeight.Normal,
                color = palette.text,
            ),
            maxLines = 1,
        )
    }
    val width = layouts.indices.maxOf { layouts[it].size.width + if (lines[it].swatch != null) swatchSpace else 0f } + pad * 2
    val height = layouts.sumOf { it.size.height } + pad * 1.4f
    val margin = 8.sp.toPx()
    val x = when {
        anchor == null -> size.width - width - margin
        anchor.x + margin * 1.5f + width <= size.width -> anchor.x + margin * 1.5f
        else -> (anchor.x - margin * 1.5f - width).coerceAtLeast(2f)
    }
    val y = when (anchor) {
        null -> margin
        else -> (anchor.y - height / 2).coerceIn(2f, (size.height - height - 2f).coerceAtLeast(2f))
    }
    val corner = CornerRadius(6.sp.toPx())
    drawRoundRect(palette.elevated, Offset(x, y), Size(width, height), corner)
    drawRoundRect(palette.border, Offset(x, y), Size(width, height), corner, style = Stroke(1f))
    var ty = y + pad * 0.7f
    lines.forEachIndexed { i, line ->
        var tx = x + pad
        val layout = layouts[i]
        line.swatch?.let { color ->
            val r = 3.5.sp.toPx()
            drawCircle(color, r, Offset(tx + r, ty + layout.size.height / 2f))
            tx += swatchSpace
        }
        drawText(layout, topLeft = Offset(tx, ty))
        ty += layout.size.height
    }
}
