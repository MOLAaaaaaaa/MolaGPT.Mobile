package com.molagpt.app.core.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/*
 * 导航层玻璃。只用在确实有内容会滚到下面的地方：顶栏、聊天输入框、全屏阅读的工具栏。
 * 页面内容画进 [hazeSource]，浮层再从中取样模糊；玻璃不叠玻璃，内容层不用玻璃。
 * Android 12 以下没有模糊，退回接近不透明的同色底。
 */

/** 当前页面的玻璃取样源；由 [MolaScaffold] 或 [MolaGlassHost] 提供。 */
val LocalMolaHaze = staticCompositionLocalOf<HazeState?> { null }

/** 内容滚进顶栏下方的程度（0..1），顶栏据此淡入玻璃。由 [MolaScaffold] 按嵌套滚动提供。 */
val LocalMolaBarOverlap = staticCompositionLocalOf<() -> Float> { { 0f } }

/** 页面画布色；玻璃的染色和兜底底色都取它，与页面背景一致才看不出边界。 */
val LocalMolaCanvas = staticCompositionLocalOf { Color.Unspecified }

/** 内容滚过这段距离后顶栏玻璃完全显现。 */
private val OverlapDistance = 24.dp

/** 顶栏玻璃下沿的渐隐长度：模糊和底色在这段里逐渐消失，没有硬边。 */
private val TopEdgeFade = 32.dp

/** 与 M3 TopAppBar 的默认高度一致。 */
private val BarHeight = 64.dp

private const val EdgeTintAlpha = 0.66f
private const val GlassTintAlpha = 0.80f
private const val FallbackTintAlpha = 0.94f

/**
 * 页面骨架：内容铺满整屏（含状态栏和手势区下方），顶栏与底栏浮在内容之上并从中取样模糊。
 * 内容需要自行用 `inner` 让出顶栏高度，并把这段留白放在滚动容器**内部**，内容才能滚进顶栏下方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MolaScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val haze = rememberHazeState()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val distancePx = with(LocalDensity.current) { OverlapDistance.toPx() }
    val overlap: () -> Float = remember(scroll, distancePx) {
        { (-scroll.state.contentOffset / distancePx).coerceIn(0f, 1f) }
    }
    CompositionLocalProvider(
        LocalMolaHaze provides haze,
        LocalMolaBarOverlap provides overlap,
        LocalMolaCanvas provides containerColor,
    ) {
        Scaffold(
            modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets,
        ) { inner ->
            // 底色画进取样源：模糊的是「底色 + 内容」，透过玻璃看不到底下未模糊的原字。
            Box(Modifier.fillMaxSize().hazeSource(haze).background(containerColor)) {
                content(inner)
            }
        }
    }
}

/** 不走 [MolaScaffold] 的全屏层（如全屏阅读）自己提供取样源。 */
@Composable
fun MolaGlassHost(
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    canvasAlpha: () -> Float = { 1f },
    content: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val haze = rememberHazeState()
    CompositionLocalProvider(LocalMolaHaze provides haze, LocalMolaCanvas provides backgroundColor) {
        Box(modifier) {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(haze)
                    .drawBehind { drawRect(backgroundColor, alpha = canvasAlpha().coerceIn(0f, 1f)) },
            ) { content() }
            overlay()
        }
    }
}

/**
 * 通栏顶栏：铺到屏幕顶端，状态栏坐在玻璃上。静止时是平的；内容滚到下面后玻璃渐显，
 * 模糊自上而下减弱，下沿渐隐。
 *
 * [overlap] 不传时取 [MolaScaffold] 按嵌套滚动算出的值；列表由程序滚动定位的页面（如聊天）
 * 应自己按列表状态传入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MolaTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    overlap: (() -> Float)? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val fraction = overlap ?: LocalMolaBarOverlap.current
    Box(modifier) {
        MolaTopEdge(
            fraction = fraction,
            modifier = Modifier.matchParentSize(),
        )
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        )
    }
}

/**
 * 顶部玻璃边缘：覆盖父布局并向下多画 [TopEdgeFade]。父布局应与顶栏同高（含状态栏）。
 */
@Composable
fun MolaTopEdge(
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = canvasColor(),
) {
    val haze = LocalMolaHaze.current ?: return
    val density = LocalDensity.current
    val solidPx = with(density) { WindowInsets.statusBars.getTop(this) + BarHeight.toPx() }
    val fadePx = with(density) { TopEdgeFade.toPx() }
    val style = remember(color, solidPx, fadePx) {
        val total = solidPx + fadePx
        val solid = (solidPx / total).coerceIn(0f, 1f)
        HazeBlurStyle {
            blurRadius(GlassBlur.Edge)
            noiseFactor(0f)
            backgroundColor(color)
            colorEffects(listOf(HazeColorEffect.tint(color.copy(alpha = EdgeTintAlpha))))
            fallbackColorEffect(HazeColorEffect.tint(color.copy(alpha = FallbackTintAlpha)))
            // 模糊强度在下沿逐渐减弱，整体再按遮罩淡出，看不到边界。
            progressive(HazeProgressive.verticalGradient(startY = solidPx * 0.5f, startIntensity = 1f, endY = total, endIntensity = 0f))
            mask(Brush.verticalGradient(0f to Color.Black, solid * 0.85f to Color.Black, 1f to Color.Transparent))
        }
    }
    Box(
        modifier
            .extendBottom(TopEdgeFade)
            .graphicsLayer { alpha = fraction() }
            .hazeBlur(input = HazeInput.Sources(haze), style = style),
    )
}

/**
 * 底部软边缘：从上往下逐渐显现，放在输入框背后、铺到屏幕底边，
 * 让滚过手势条的正文渐隐而不是被一条色带切断。
 */
@Composable
fun MolaBottomEdge(
    modifier: Modifier = Modifier,
    color: Color = canvasColor(),
) {
    val haze = LocalMolaHaze.current ?: return
    val style = remember(color) {
        HazeBlurStyle {
            blurRadius(GlassBlur.Edge)
            noiseFactor(0f)
            backgroundColor(color)
            colorEffects(listOf(HazeColorEffect.tint(color.copy(alpha = EdgeTintAlpha))))
            fallbackColorEffect(HazeColorEffect.tint(color.copy(alpha = FallbackTintAlpha)))
            progressive(HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f))
            mask(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Black, 1f to Color.Black))
        }
    }
    Box(modifier.hazeBlur(input = HazeInput.Sources(haze), style = style))
}

/**
 * 浮在内容上的玻璃块（如聊天输入框）：模糊背后内容 + 同色底 + 细描边 + 轻阴影。
 * 没有取样源时就是普通的不透明底。
 */
@Composable
fun Modifier.molaGlass(shape: Shape, elevation: Dp = 6.dp): Modifier {
    val haze = LocalMolaHaze.current
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface
    val style = remember(surface) {
        HazeBlurStyle {
            blurRadius(GlassBlur.Island)
            noiseFactor(0f)
            backgroundColor(surface)
            colorEffects(listOf(HazeColorEffect.tint(surface.copy(alpha = GlassTintAlpha))))
            fallbackColorEffect(HazeColorEffect.tint(surface.copy(alpha = FallbackTintAlpha)))
        }
    }
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.05f),
            spotColor = Color.Black.copy(alpha = 0.10f),
        )
        .clip(shape)
        .then(
            if (haze != null) Modifier.hazeBlur(input = HazeInput.Sources(haze), style = style)
            else Modifier.background(surface),
        )
        .border(1.dp, colors.outline.copy(alpha = 0.34f), shape)
}

@Composable
private fun canvasColor(): Color =
    LocalMolaCanvas.current.takeOrElse { MaterialTheme.colorScheme.background }

private object GlassBlur {
    val Edge = 14.dp
    val Island = 20.dp
}

/** 报告原尺寸，但让子节点向下多画 [extra]（用于渐隐越过父布局下沿）。 */
private fun Modifier.extendBottom(extra: Dp): Modifier = layout { measurable, constraints ->
    val extraPx = extra.roundToPx()
    val height = constraints.maxHeight
    val placeable = measurable.measure(
        constraints.copy(minHeight = height + extraPx, maxHeight = height + extraPx),
    )
    layout(placeable.width, height) { placeable.place(0, 0) }
}
