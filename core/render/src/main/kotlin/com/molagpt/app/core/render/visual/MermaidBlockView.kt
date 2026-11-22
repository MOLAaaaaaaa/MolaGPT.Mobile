package com.molagpt.app.core.render.visual

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molagpt.app.core.render.CodeBlockView
import com.molagpt.app.core.render.CopyCodeButton
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.rememberHighlightedCode
import kotlinx.coroutines.delay

/**
 * Mermaid 图表 / 源码双态块（参考 chat-demo 的双态切换与流式降级策略）。
 *
 * - 顶栏左侧提供「图表 | 代码」切换，右侧提供全屏放大与一键复制；
 * - 流式生成期间保留上次有效图形，不闪烁报错；仅在流式结束后仍无法解析时展示错误提示与「查看代码」入口；
 * - 无宿主（例如全屏选字阅读层或只读列表）时自动降级为带语法高亮的代码块。
 */
@Composable
fun MermaidBlockView(
    source: String,
    closed: Boolean,
    streaming: Boolean,
    stateKey: String?,
    modifier: Modifier = Modifier,
) {
    val host = LocalVisualHost.current
    if (host == null || !host.supportsMermaid) {
        CodeBlockView(language = "mermaid", code = source, modifier = modifier)
        return
    }

    val generating = !closed && streaming
    var localShowDiagram by remember(stateKey) { mutableStateOf<Boolean?>(null) }
    val showDiagram = (if (stateKey != null) host.mermaidShowDiagram(stateKey) else localShowDiagram) ?: true
    val setShowDiagram: (Boolean) -> Unit = { diagram ->
        if (stateKey != null) {
            host.setMermaidShowDiagram(stateKey, diagram)
        } else {
            localShowDiagram = diagram.takeIf { !it }
        }
    }

    if (!showDiagram) {
        CodeBlockView(
            language = "mermaid",
            code = source,
            modifier = modifier,
            headerStart = {
                MermaidModeSwitch(showDiagram = false, onSelect = setShowDiagram)
            },
        )
        return
    }

    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(10.dp)
    var copied by remember(source) { mutableStateOf(false) }
    var renderState by remember(stateKey) { mutableStateOf<MermaidRenderState>(MermaidRenderState.Loading) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val openFullscreen: () -> Unit = {
        host.openFullscreen { close ->
            MermaidFullscreenSheet(host = host, source = source, onClose = close)
        }
    }

    val cachedHeightDp = remember(stateKey) { stateKey?.let { host.mermaidHeightDp(it) } }
    val targetHeight = when (val s = renderState) {
        is MermaidRenderState.Ready -> s.heightDp.coerceIn(MIN_INLINE_HEIGHT_DP, MAX_INLINE_HEIGHT_DP).dp
        MermaidRenderState.Loading -> (cachedHeightDp ?: DEFAULT_LOADING_HEIGHT_DP)
            .coerceIn(MIN_INLINE_HEIGHT_DP, MAX_INLINE_HEIGHT_DP).dp
        is MermaidRenderState.Failed -> 112.dp
    }
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = MolaMotion.standard(),
        label = "mermaidHeight",
    )

    DisableSelection {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cs.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MermaidModeSwitch(showDiagram = true, onSelect = setShowDiagram)
                Spacer(Modifier.weight(1f))
                if (renderState is MermaidRenderState.Ready) {
                    IconButton(
                        onClick = openFullscreen,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInFull,
                            contentDescription = "全屏查看图表",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                CopyCodeButton(
                    copied = copied,
                    onClick = {
                        clipboard.setText(AnnotatedString(source))
                        copied = true
                    },
                )
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))

            if (!generating && source.isBlank()) {
                MermaidStatusBox(
                    text = "图表内容为空",
                    isError = false,
                    onShowCode = { setShowDiagram(false) },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedHeight)
                        .background(cs.surface),
                ) {
                    host.MermaidCanvas(
                        source = source,
                        streaming = generating,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                        onStateChange = { state ->
                            renderState = state
                            if (state is MermaidRenderState.Ready && stateKey != null) {
                                host.setMermaidHeightDp(stateKey, state.heightDp)
                            }
                        },
                    )
                    // 列表内嵌态由外层覆盖点击层，纵向滑动完全交给 LazyColumn，轻点进入全屏缩放。
                    if (renderState is MermaidRenderState.Ready) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(onClick = openFullscreen),
                        )
                    }
                    when (val state = renderState) {
                        MermaidRenderState.Loading -> {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(cs.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 1.8.dp,
                                        color = cs.onSurfaceVariant,
                                    )
                                    Text(
                                        text = if (generating) "正在生成图表…" else "正在渲染图表…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = cs.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is MermaidRenderState.Failed -> {
                            if (!generating) {
                                MermaidStatusBox(
                                    text = state.reason?.takeIf { it.isNotBlank() } ?: "图表渲染失败",
                                    isError = true,
                                    onShowCode = { setShowDiagram(false) },
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(cs.surface),
                                )
                            }
                        }
                        is MermaidRenderState.Ready -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun MermaidModeSwitch(
    showDiagram: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.55f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SwitchTab(label = "图表", selected = showDiagram, onClick = { onSelect(true) })
        SwitchTab(label = "代码", selected = !showDiagram, onClick = { onSelect(false) })
    }
}

@Composable
private fun SwitchTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) cs.onSurface else cs.onSurfaceVariant,
        modifier = Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(cs.surface) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun MermaidStatusBox(
    text: String,
    isError: Boolean,
    onShowCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isError) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onShowCode) {
            Text("查看源码", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MermaidFullscreenSheet(
    host: VisualHost,
    source: String,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val canvas = cs.background
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val textToolbar = LocalTextToolbar.current
    val codeVScroll = rememberScrollState()
    val codeHScroll = rememberScrollState()
    val overlapPx = with(density) { 24.dp.toPx() }
    var showCode by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var renderState by remember(source, showCode) {
        mutableStateOf<MermaidRenderState>(MermaidRenderState.Loading)
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val close: () -> Unit = {
        textToolbar.hide()
        onClose()
    }

    VisualFullscreenScaffold(
        title = "Mermaid 图表",
        hint = if (showCode) "源码" else "双指缩放 · 拖动查看",
        onClose = close,
        canvasColor = canvas,
        topBarOverlap = {
            if (showCode) (codeVScroll.value / overlapPx).coerceIn(0f, 1f) else 1f
        },
        showBottomEdge = true,
        // 图表态为原生 WebView，不包进 hazeSource 离屏层，避免双指缩放时硬件 DrawFunctor 多遍重绘闪烁；
        // 代码态为纯 Compose 文本，启用完整毛玻璃。
        glassBlur = showCode,
        actions = {
            MermaidModeSwitch(
                showDiagram = !showCode,
                onSelect = { diagram ->
                    if (diagram) textToolbar.hide()
                    showCode = !diagram
                },
            )
            Spacer(Modifier.width(4.dp))
            CopyCodeButton(
                copied = copied,
                onClick = {
                    clipboard.setText(AnnotatedString(source))
                    copied = true
                },
            )
            Spacer(Modifier.width(4.dp))
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (showCode) {
                val highlighted = rememberHighlightedCode(code = source, language = "mermaid")
                SelectionContainer(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(codeVScroll)
                            .horizontalScroll(codeHScroll)
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = innerPadding.calculateTopPadding() + 8.dp,
                                bottom = innerPadding.calculateBottomPadding() + 32.dp,
                            ),
                    ) {
                        Text(
                            text = highlighted,
                            color = cs.onBackground,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
                            ),
                            softWrap = false,
                        )
                    }
                }
            } else {
                host.MermaidCanvas(
                    source = source,
                    streaming = false,
                    interactive = true,
                    contentPadding = innerPadding,
                    backgroundColor = canvas,
                    modifier = Modifier.fillMaxSize(),
                    onStateChange = { renderState = it },
                )
                if (renderState is MermaidRenderState.Loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(canvas)
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                } else if (renderState is MermaidRenderState.Failed) {
                    MermaidStatusBox(
                        text = (renderState as MermaidRenderState.Failed).reason ?: "图表渲染失败",
                        isError = true,
                        onShowCode = { showCode = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(canvas)
                            .padding(innerPadding),
                    )
                }
            }
        }
    }
}

private const val MIN_INLINE_HEIGHT_DP = 140
private const val MAX_INLINE_HEIGHT_DP = 400
private const val DEFAULT_LOADING_HEIGHT_DP = 168
