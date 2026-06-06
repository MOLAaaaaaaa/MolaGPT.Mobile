package com.molagpt.app.core.render

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一动画模块。集中声明 Material 3 motion 缓动、时长与可复用动画原语。
 */
object MolaMotion {
    /** 标准/强调缓动（M3 motion token）。 */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Predictive back 拖动官方推荐曲线：STANDARD_DECELERATE = PathInterpolator(0,0,0,1)。
     *  快起缓收——手势一开始就有明显位移、收尾减速；官方明确要求别把原始线性进度直接用于返回预览。 */
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

    const val Short = 200
    const val Medium = 300
    const val Long = 450

    fun <T> emphasized(durationMillis: Int = Medium) = tween<T>(durationMillis, easing = Emphasized)
    fun <T> decelerate(durationMillis: Int = Long) = tween<T>(durationMillis, easing = EmphasizedDecelerate)
    fun <T> standard(durationMillis: Int = Short) = tween<T>(durationMillis, easing = Standard)
    /** Predictive back 转场/收尾用：cubic-bezier(0,0,0,1) 减速（官方推荐的返回手势曲线）。 */
    fun <T> standardDecelerate(durationMillis: Int = Medium) = tween<T>(durationMillis, easing = StandardDecelerate)
    /** 带回弹的 spring（开关圆点、对勾弹入等）。 */
    fun <T> springy() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
}

/** 骨架屏微光（加载占位）。给元素定好尺寸+圆角后调用即可：`Box(Modifier.height(14.dp).clip(..).shimmer())`。 */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    drawBehind {
        val w = size.width
        val shift = progress * 2f * w - w
        drawRect(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(shift, 0f),
                end = Offset(shift + w, size.height),
            ),
        )
    }
}

/** 出现时淡入+轻微放大（图片加载完成等）。[visible] 为 true 时播放到位。 */
fun Modifier.fadeScaleIn(visible: Boolean): Modifier = composed {
    val p by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(MolaMotion.Long, easing = MolaMotion.EmphasizedDecelerate),
        label = "fadeScaleIn",
    )
    graphicsLayer {
        alpha = p
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
    }
}

/** 流式打字光标（闪烁细条）。 */
@Composable
fun TypingCaret(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 18.dp,
) {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        modifier = modifier
            .size(width = 2.dp, height = height)
            .graphicsLayer { this.alpha = alpha }
            .background(color),
    )
}

/** 等待首 token 的脉冲三点（替代「正在生成…」文字）。 */
@Composable
fun PulsingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dot: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(1100, easing = MolaMotion.Standard),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 180),
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(dot)
                    .graphicsLayer {
                        alpha = a
                        val s = 0.7f + 0.3f * a
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** 分段控件：选中滑块在档位间平滑滑动（推理强度/主题等）。 */
@Composable
fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val count = options.size
    val index = options.indexOfFirst { it.first.equals(selected, ignoreCase = true) }.coerceAtLeast(0)
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
    ) {
        val segW: Dp = maxWidth / count
        val offsetX by animateDpAsState(targetValue = segW * index, animationSpec = MolaMotion.emphasized(), label = "segPill")
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(segW)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val sel = value.equals(selected, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
