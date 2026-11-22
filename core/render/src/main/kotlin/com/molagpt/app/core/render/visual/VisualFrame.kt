package com.molagpt.app.core.render.visual

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.LocalMolaCanvas
import com.molagpt.app.core.render.LocalMolaHaze
import com.molagpt.app.core.render.MolaBottomEdge
import com.molagpt.app.core.render.MolaGlassHost
import com.molagpt.app.core.render.MolaTopBar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

class VisualMenuItem(val label: String, val onClick: () -> Unit)

/**
 * 比外框宽、在框里横向滑动的内容（宽表格）导出图片时的完整版本：按 [width] 在屏幕外
 * 重排一遍再录制。屏幕上那一层只画了看得见的一截，直接录会把右边裁掉。
 */
class WideCapture(val width: Dp, val content: @Composable ColumnScope.() -> Unit)

/**
 * 内嵌组件的外框：标题、右上角的全屏与更多菜单，内容放在下面。
 *
 * 右上角的按钮浮在标题行之上、不在录制层里，「保存图片」「分享图片」拿到的是标题加内容，
 * 不带这几个按钮。录制层自带底色：透明底的 PNG 发到聊天软件里常被显示成黑底。
 */
@Composable
fun VisualFrame(
    title: String?,
    modifier: Modifier = Modifier,
    meta: String? = null,
    imageName: String = "图表",
    onFullscreen: (() -> Unit)? = null,
    menu: List<VisualMenuItem> = emptyList(),
    capture: Boolean = true,
    wideCapture: WideCapture? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = rememberVisualPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val shape = RoundedCornerShape(12.dp)
    var menuOpen by remember { mutableStateOf(false) }
    val wideLayer = rememberGraphicsLayer()
    // 非空时在屏幕外排一份完整宽度的内容，录好就完成它。只在导出的那一两帧存在。
    var wideRequest by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }

    suspend fun snapshot(): Bitmap {
        if (wideCapture != null) {
            val ready = CompletableDeferred<Unit>()
            wideRequest = ready
            try {
                if (withTimeoutOrNull(1_000) { ready.await() } != null) {
                    // 特别宽的表可能超出位图上限，那就退回只存看得见的部分。
                    runCatching { wideLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()?.let { return it }
                }
            } finally {
                wideRequest = null
            }
        }
        return layer.toImageBitmap().asAndroidBitmap()
    }

    val items = buildList {
        addAll(menu)
        if (capture) {
            add(VisualMenuItem("保存图片") {
                scope.launch {
                    val bitmap = snapshot()
                    val ok = VisualExport.savePng(context, bitmap)
                    Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                }
            })
            add(VisualMenuItem("分享图片") {
                scope.launch {
                    val bitmap = snapshot()
                    runCatching { VisualExport.sharePng(context, bitmap, VisualExport.safeName(title, imageName)) }
                        .onFailure { Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show() }
                }
            })
        }
    }
    val tools = (if (onFullscreen != null) 1 else 0) + (if (items.isNotEmpty()) 1 else 0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.border, shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .background(palette.surface),
        ) {
            if (!title.isNullOrBlank() || !meta.isNullOrBlank() || tools > 0) {
                FrameHeader(title, meta, palette, end = 8.dp + 40.dp * tools)
            }
            content()
        }

        val request = wideRequest
        if (request != null && wideCapture != null) {
            Column(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val width = maxOf(constraints.maxWidth, ceil(wideCapture.width.toPx()).toInt())
                        val placeable = measurable.measure(Constraints.fixedWidth(width))
                        // 不占位：外框尺寸不变；这一份也从不画上屏幕，只录进 wideLayer。
                        layout(0, 0) { placeable.place(0, 0) }
                    }
                    .drawWithContent {
                        wideLayer.record { this@drawWithContent.drawContent() }
                        request.complete(Unit)
                    }
                    .background(palette.surface),
            ) {
                if (!title.isNullOrBlank() || !meta.isNullOrBlank()) FrameHeader(title, meta, palette, end = 14.dp)
                wideCapture.content(this)
            }
        }

        if (tools > 0) {
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp)) {
                if (onFullscreen != null) {
                    IconButton(onClick = onFullscreen, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.OpenInFull,
                            contentDescription = "全屏查看",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = palette.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            items.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        menuOpen = false
                                        item.onClick()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameHeader(title: String?, meta: String?, palette: VisualPalette, end: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 11.dp, end = end, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title?.takeIf { it.isNotBlank() }.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!meta.isNullOrBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 内嵌可视化组件的全屏查看骨架：复用 [MolaGlassHost] + [MolaTopBar] + [MolaBottomEdge]，
 * 与自由选择页（AssistantReaderOverlay）保持同一套全屏沉浸视觉。
 *
 * 内容层铺满整屏（覆盖状态栏与底部全面屏手势白条区），由 [content] 收到顶栏（含状态栏）与
 * 底部手势栏的内边距 [PaddingValues]，在可滚动或可缩放内容内部留白，使内容自然穿过上下半透明软边缘。
 *
 * [glassBlur] 为 false 时（如承载可缩放 WebView 的 Mermaid 图表态），不将内容包进 `hazeSource`
 * 离屏层，避免 Chromium 硬件 `DrawFunctor` 在双指缩放分块重绘时被多遍采样引发闪烁，改用同几何、
 * 同渐隐曲线的半透明渐变边缘覆盖顶栏与手势条区域。
 */
@Composable
internal fun VisualFullscreenScaffold(
    title: String,
    hint: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    canvasColor: Color = MaterialTheme.colorScheme.background,
    topBarOverlap: () -> Float = { 1f },
    showBottomEdge: Boolean = true,
    glassBlur: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val innerPadding = PaddingValues(
        top = statusTop + FullscreenBarHeight,
        bottom = navBottom,
    )
    val topBar: @Composable () -> Unit = {
        MolaTopBar(
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!hint.isNullOrBlank()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            },
            actions = actions,
            overlap = topBarOverlap,
        )
    }

    CompositionLocalProvider(LocalContentColor provides contentColorFor(canvasColor)) {
        if (glassBlur) {
            MolaGlassHost(
                backgroundColor = canvasColor,
                modifier = modifier.fillMaxSize(),
                content = { content(innerPadding) },
                overlay = {
                    topBar()
                    if (showBottomEdge) {
                        MolaBottomEdge(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(navBottom + FullscreenBottomEdgeExtra),
                        )
                    }
                },
            )
        } else {
            CompositionLocalProvider(
                LocalMolaHaze provides null,
                LocalMolaCanvas provides canvasColor,
            ) {
                Box(modifier.fillMaxSize().background(canvasColor)) {
                    Box(Modifier.fillMaxSize()) { content(innerPadding) }
                    Box(Modifier.fillMaxWidth()) {
                        ScrimTopEdge(
                            color = canvasColor,
                            fraction = topBarOverlap,
                            modifier = Modifier.matchParentSize(),
                        )
                        topBar()
                    }
                    if (showBottomEdge) {
                        ScrimBottomEdge(
                            color = canvasColor,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(navBottom + FullscreenBottomEdgeExtra),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrimTopEdge(
    color: Color,
    fraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val solidPx = with(density) { WindowInsets.statusBars.getTop(this) + FullscreenBarHeight.toPx() }
    val fadePx = with(density) { FullscreenTopEdgeFade.toPx() }
    val brush = remember(color, solidPx, fadePx) {
        val total = (solidPx + fadePx).coerceAtLeast(1f)
        val solid = (solidPx / total).coerceIn(0f, 1f)
        Brush.verticalGradient(
            0f to color.copy(alpha = 0.86f),
            solid * 0.78f to color.copy(alpha = 0.76f),
            solid to color.copy(alpha = 0.48f),
            1f to Color.Transparent,
        )
    }
    Box(
        modifier
            .extendBottomEdge(FullscreenTopEdgeFade)
            .graphicsLayer { alpha = fraction().coerceIn(0f, 1f) }
            .background(brush),
    )
}

@Composable
private fun ScrimBottomEdge(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val brush = remember(color) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to color.copy(alpha = 0.48f),
            0.8f to color.copy(alpha = 0.78f),
            1f to color.copy(alpha = 0.86f),
        )
    }
    Box(modifier.background(brush))
}

private fun Modifier.extendBottomEdge(extra: Dp): Modifier = layout { measurable, constraints ->
    val extraPx = extra.roundToPx()
    val height = constraints.maxHeight
    val placeable = measurable.measure(
        constraints.copy(minHeight = height + extraPx, maxHeight = height + extraPx),
    )
    layout(placeable.width, height) { placeable.place(0, 0) }
}

private val FullscreenBarHeight = 64.dp
private val FullscreenTopEdgeFade = 32.dp
private val FullscreenBottomEdgeExtra = 40.dp
