package com.molagpt.app.core.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 思考与工具调用的统一时间线外壳（参考 chat-demo 的 ReplyArea）。
 *
 * 刻意不套厚重灰底卡片（遵循视觉层次规则，避免「卡中卡」）：
 * 顶行是一条轻量的可折叠标题，展开后直接以左侧细竖线 + 圆点/图标串联所有过程步骤。
 */
@Composable
fun ProcessTimelineContainer(
    title: String,
    icon: ImageVector,
    active: Boolean,
    stopped: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MolaMotion.emphasized(),
        label = "timelineChevron",
    )
    val iconTint = when {
        active -> cs.primary
        stopped -> cs.onSurfaceVariant.copy(alpha = 0.6f)
        else -> cs.onSurfaceVariant
    }
    val chevColor = cs.onSurfaceVariant
    val railColor = cs.outlineVariant

    // 统一水平网格（对齐桌面端 16 / 8 / *）：
    //   0..16dp：顶层图标槽（中心 8dp，左边与正文齐平）
    //   8dp    ：展开后的 1dp 渐隐竖轨中心
    //   24dp.. ：标题文字与展开正文/子项左边缘（严格齐平）
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(top = 5.dp, bottom = 5.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            ProcessTimelineTitle(
                text = title,
                shimmer = active,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Canvas(
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
            ) {
                val w = size.width
                val h = size.height
                val sw = w * 0.13f
                drawLine(
                    chevColor,
                    Offset(w * 0.25f, h * 0.40f),
                    Offset(w * 0.50f, h * 0.64f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    chevColor,
                    Offset(w * 0.75f, h * 0.40f),
                    Offset(w * 0.50f, h * 0.64f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(MolaMotion.emphasized()) + fadeIn(MolaMotion.standard(MolaMotion.Medium)),
            exit = shrinkVertically(MolaMotion.emphasized()) + fadeOut(MolaMotion.standard()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = 8.dp.toPx()
                        val top = 2.dp.toPx()
                        val bottom = (size.height - 4.dp.toPx()).coerceAtLeast(top)
                        if (bottom > top) {
                            drawLine(
                                brush = Brush.verticalGradient(
                                    0f to railColor.copy(alpha = 0.85f),
                                    0.85f to railColor.copy(alpha = 0.65f),
                                    1f to Color.Transparent,
                                    startY = top,
                                    endY = bottom,
                                ),
                                start = Offset(x, top),
                                end = Offset(x, bottom),
                                strokeWidth = 1.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    .padding(start = 24.dp, top = 2.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ProcessTimelineTitle(
    text: String,
    shimmer: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    if (!shimmer) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "timelineTitleShimmer")
    val phase by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "timelineTitlePhase",
    )
    val base = cs.onSurfaceVariant
    val peak = cs.onSurface
    val brush = Brush.linearGradient(
        colors = listOf(base, peak, base),
        start = Offset(phase * 260f - 90f, 0f),
        end = Offset(phase * 260f + 90f, 0f),
    )
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(brush = brush),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 时间线里的单个工具/带图标步骤：外层 [ProcessTimelineContainer] 已在 x=8dp 绘制统一的渐隐竖轨
 * 并将内容整体缩进至 x=24dp，这里只负责「16dp 图标槽 + 8dp 间距 + 主体列」的对齐，避免双轨嵌套。
 */
@Composable
fun TimelineRailStep(
    isLast: Boolean,
    indicator: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            indicator()
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            content = content,
        )
    }
}

/** 思考或过渡说明步骤的左侧圆点：进行中柔和脉动，结束后沉静置灰。 */
@Composable
fun TimelineDot(active: Boolean) {
    val cs = MaterialTheme.colorScheme
    if (!active) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(cs.onSurfaceVariant.copy(alpha = 0.45f)),
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "timelineDotPulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = MolaMotion.Standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "timelineDotScale",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(cs.primary),
    )
}

/** 工具步骤的左侧图标：进行中为紧凑转圈，其余为对应语义图标。 */
@Composable
fun TimelineToolIndicator(
    name: String,
    icon: String?,
    running: Boolean,
    failed: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    if (running) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.6.dp,
            color = cs.primary,
        )
    } else {
        Icon(
            imageVector = toolIconVector(name, icon),
            contentDescription = null,
            tint = if (failed) cs.error else cs.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}
