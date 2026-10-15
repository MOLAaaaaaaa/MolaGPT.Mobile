package com.molagpt.app.feature.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.render.MolaMotion

/** 一轮对话在轮次条上的样子：开启这一轮的用户消息，和它的摘要。 */
internal data class TurnMark(val messageId: String, val preview: String)

internal const val TURN_RAIL_MIN_TURNS = 1

/** 摘要只读提问的开头：粘进来的长文可能有几十万字，气泡里只放得下两行。 */
private const val PREVIEW_SCAN = 400

internal fun List<ChatMessage>.toTurnMarks(): List<TurnMark> =
    filter { it.role == Role.USER }.map { message ->
        val text = message.rawText.orEmpty().take(PREVIEW_SCAN).collapseWhitespace()
        TurnMark(
            messageId = message.messageId,
            preview = text.ifEmpty { message.attachments.joinToString("、") { it.name } },
        )
    }

private fun String.collapseWhitespace(): String {
    val out = StringBuilder(length)
    var space = false
    for (c in this) {
        if (c.isWhitespace()) {
            space = out.isNotEmpty()
            continue
        }
        if (space) out.append(' ')
        space = false
        out.append(c)
    }
    return out.toString()
}

/** 刻度间距上限；轮次多到放不下时整体压缩。 */
private val PreferredPitch = 8.dp

/**
 * 轮次条的高度上限。系统每条边最多只让应用排除 200dp 的返回手势区——超过的部分照样会被
 * 侧滑返回截走，按住刻度往下拖就可能变成退出页面。宁可刻度挤一点，也要整条都在排除范围内。
 */
private val MaxRailHeight = 200.dp
private val RailWidth = 28.dp
private val RailPadY = 8.dp
private val TickInset = 4.dp
private val TickLength = 8.dp
private val TickLengthLit = 14.dp
private val TickThickness = 2.dp

/**
 * 长对话右缘的轮次条：每个用户提问一格，正在读的那一轮画得更重。
 *
 * 平时不占屏：只在用户亲手滚动时浮现，停下片刻淡出（由调用方的 [visible] 决定）。
 * 浮现时按住即可拖动选轮——列表跟着手指实时滚到那一轮，左侧气泡显示提问摘要，每跨过一轮
 * 轻震一次。手指下的轮次按高度直接算出，刻度挤得再密也只会落在唯一一轮上。
 *
 * [modifier] 应铺满列表的可视区（已扣掉顶栏和输入框），轮次条在其中垂直居中。
 */
@Composable
internal fun TurnRail(
    turns: List<TurnMark>,
    activeIndex: Int,
    visible: Boolean,
    onScrubbingChange: (Boolean) -> Unit,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MolaMotion.standard(),
        label = "turnRailAlpha",
    )
    if (turns.isEmpty() || (alpha == 0f && !visible)) return

    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubbingChange by rememberUpdatedState(onScrubbingChange)
    var scrubIndex by remember { mutableIntStateOf(-1) }

    val tick = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val active = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val lit = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val count = turns.size
        val railHeight = min(min(maxHeight, MaxRailHeight), PreferredPitch * count + RailPadY * 2)
        val pitch: Dp = (railHeight - RailPadY * 2) / count
        val railTop = (maxHeight - railHeight) / 2
        val pitchPx = with(density) { pitch.toPx() }
        val padPx = with(density) { RailPadY.toPx() }

        fun indexAt(y: Float): Int =
            ((y - padPx) / pitchPx).toInt().coerceIn(0, count - 1)

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(RailWidth)
                .height(railHeight)
                .graphicsLayer { this.alpha = alpha }
                .then(
                    if (!visible) {
                        Modifier
                    } else {
                        Modifier
                            .systemGestureExclusion()
                            .pointerInput(count, pitchPx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    currentOnScrubbingChange(true)
                                    var index = indexAt(down.position.y)
                                    scrubIndex = index
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    currentOnScrub(index)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val next = indexAt(change.position.y)
                                        if (next != index) {
                                            index = next
                                            scrubIndex = next
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                            currentOnScrub(next)
                                        }
                                    }
                                    scrubIndex = -1
                                    currentOnScrubbingChange(false)
                                }
                            }
                    },
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (scrubIndex >= 0) {
                    drawRoundRect(track, cornerRadius = CornerRadius(size.width / 2))
                }
                for (i in 0 until count) {
                    if (i != activeIndex && i != scrubIndex) drawTick(i, padPx, pitchPx, TickLength, tick)
                }
                // 亮的刻度最后画：刻度挤得比自身还密时，邻居会把它盖住。
                if (activeIndex in 0 until count && activeIndex != scrubIndex) {
                    drawTick(activeIndex, padPx, pitchPx, TickLengthLit, active)
                }
                if (scrubIndex in 0 until count) {
                    drawTick(scrubIndex, padPx, pitchPx, TickLengthLit, lit)
                }
            }
        }

        val shown = scrubIndex
        if (shown in 0 until count) {
            val centerPx = with(density) { (railTop + RailPadY).toPx() + (shown + 0.5f) * pitchPx }
            TurnPreviewBubble(
                ordinal = shown + 1,
                preview = turns[shown].preview,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = RailWidth + 8.dp)
                    .widthIn(max = 260.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0))
                        val y = (centerPx - placeable.height / 2f).toInt()
                            .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                        layout(placeable.width, constraints.maxHeight) { placeable.place(0, y) }
                    },
            )
        }
    }
}

private fun DrawScope.drawTick(index: Int, padPx: Float, pitchPx: Float, length: Dp, color: Color) {
    val thickness = TickThickness.toPx()
    val len = length.toPx()
    val center = padPx + (index + 0.5f) * pitchPx
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - TickInset.toPx() - len, center - thickness / 2),
        size = Size(len, thickness),
        cornerRadius = CornerRadius(thickness / 2),
    )
}

@Composable
private fun TurnPreviewBubble(ordinal: Int, preview: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "第 $ordinal 轮",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = preview.ifEmpty { "无文字内容" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
