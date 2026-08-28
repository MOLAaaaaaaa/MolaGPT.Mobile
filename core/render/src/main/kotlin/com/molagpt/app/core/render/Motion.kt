package com.molagpt.app.core.render

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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

    // ---- 统一二级页转场（NavHost 的 push/pop 与页内 Hub⇄会话共用同一套，手感一致）----
    /** 二级页推入：从右侧滑入。 */
    val PushEnter: EnterTransition = slideInHorizontally(standardDecelerate()) { it }
    /** 推入时下层页轻微左移视差。 */
    val PushExit: ExitTransition = slideOutHorizontally(standardDecelerate()) { -it / 4 }
    /** 返回：下层页从左侧轻微视差归位。 */
    val PopEnter: EnterTransition = slideInHorizontally(standardDecelerate()) { -it / 4 }
    /** 返回：当前页向右滑出。 */
    val PopExit: ExitTransition = slideOutHorizontally(standardDecelerate()) { it }
}

/**
 * 流式正文的尾部渐隐：给**最后一行的末端**加一段透明度渐变，让新字看起来是「淡出来的」。
 *
 * 不按字符切分——长回答下逐字动画会让元素数与重组量随文本线性膨胀。这里只在绘制阶段用一层
 * 渐变蒙版（`DstIn`）作用于文本末端，开销与文本量无关，观感却接近逐字淡入。
 *
 * [active] 为 false（流式结束）时完全不介入绘制，避免给静态文本留下渐变痕迹。
 * [lastLineEndX] / [lastLineTop] / [lastLineBottom] 由调用方从 `onTextLayout` 传入：渐变必须锚在
 * **真实行尾**而不是容器右边缘——流式时最后一行往往只写到一半，锚在容器边缘会让渐变落在空白处，
 * 完全看不到效果。
 */
fun Modifier.streamingTailFade(
    active: Boolean,
    lastLineEndX: Float,
    lastLineTop: Float,
    lastLineBottom: Float,
    fadeWidth: Dp = 96.dp,
): Modifier = if (!active || lastLineEndX <= 0f) this else composed {
    val fadePx = with(LocalDensity.current) { fadeWidth.toPx() }
    // 需要离屏合成：DstIn 要与已绘制的文本像素做相交，直接画会与背景混合。
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val start = (lastLineEndX - fadePx).coerceAtLeast(0f)
            val end = lastLineEndX.coerceAtMost(size.width)
            if (end <= start) return@drawWithContent
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Black.copy(alpha = 0.22f)),
                    startX = start,
                    endX = end,
                ),
                topLeft = Offset(start, lastLineTop),
                size = Size(end - start, (lastLineBottom - lastLineTop).coerceAtLeast(0f)),
                blendMode = BlendMode.DstIn,
            )
        }
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

/**
 * 段落骨架：几条宽度递减的裸微光条，没有卡片/边框/底色，直接浮在背景上。
 *
 * 形状是照着「一段自然收尾的文字」做的——[widthFractions] 逐行收窄，最后一行明显短，
 * 所以它只适合用在正文本身也是无容器纯文本排版的地方（助手消息就是）。
 * 需要带容器的加载态请直接用 [shimmer] 自己搭。
 */
@Composable
fun SkeletonLines(
    modifier: Modifier = Modifier,
    widthFractions: List<Float> = listOf(0.88f, 0.64f, 0.40f),
    lineHeight: Dp = 12.dp,
    spacing: Dp = 9.dp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        widthFractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(lineHeight)
                    // clip 必须在 shimmer 之前：shimmer 是 drawBehind 平铺渐变，靠外层裁剪出圆角。
                    .clip(RoundedCornerShape(lineHeight / 2))
                    .shimmer(),
            )
        }
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
