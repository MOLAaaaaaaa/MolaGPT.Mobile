package com.molagpt.app.feature.chat

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.render.LocalCitationSources
import com.molagpt.app.core.render.LocalFaviconRenderer
import com.molagpt.app.core.render.LocalMarkdownImageRenderer
import com.molagpt.app.core.render.MarkdownBlockView
import com.molagpt.app.core.render.MolaBottomEdge
import com.molagpt.app.core.render.MolaGlassHost
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.MolaTopBar
import com.molagpt.app.feature.file.RemoteFavicon
import com.molagpt.app.feature.file.RemoteImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/*
 * 全屏阅读（选择文本）。
 *
 * 正文与列表里的行同列宽、同字号、同组件，打开时不重排：阅读层对齐到这条回答当前在屏幕上的
 * 位置，正文原地不动，只有周围换场——画布淡入盖住其它消息，工具栏落下。若阅读层无法滚到与
 * 列表一致的位置（回答开头还在屏幕中部），正文只做一段纵向位移。关闭时先把列表滚到阅读到的
 * 位置，正文再落回去。
 *
 * 动效：位移用 Expressive spatial 弹簧，关闭用 Standard spatial；透明度按进度换算、不回弹。
 * 返回手势跟手缩放到 90%，松手后正文落回列表。
 */

@Stable
internal class AssistantReaderState {
    // —— 列表登记的状态（MessageList 写入） ——
    /** 用快照状态：顶栏在绘制阶段读它，列表晚于顶栏登记时也能刷新。 */
    internal var listState by mutableStateOf<LazyListState?>(null)
    internal var rows: List<MessageListRow> = emptyList()
    /** 列表里正文行内容区（不含行间距）的顶边，屏幕坐标。 */
    internal val rowTops = HashMap<String, Float>()
    /** 列表视口里内容的起点（顶栏下沿），屏幕坐标。 */
    internal var listContentTop = 0f
    internal var overlapDistancePx = 1f
    internal var density = 1f
    /** 聊天页顶栏下沿、底栏上沿（屏幕坐标）：打开初期阅读层不在这两块里画，免得盖在还没淡出的顶栏和输入框上。 */
    internal var chromeTop = 0f
    internal var chromeBottom = Float.MAX_VALUE

    // —— 阅读层 ——
    var session by mutableStateOf<ReaderSession?>(null)
        private set
    internal val progress = Animatable(0f)
    internal var backProgress by mutableFloatStateOf(0f)
    internal var backEdgeSign by mutableIntStateOf(1)
    /** 由阅读层接管显示的列表行，期间在列表里隐藏，避免重影。 */
    internal var hiddenKeys by mutableStateOf<Set<String>>(emptySet())
    private var closing = false

    val visible: Boolean get() = session != null

    /** 聊天页顶栏与输入框的不透明度。 */
    fun chromeAlpha(): Float {
        if (session == null) return 1f
        val p = progress.value
        return maxOf(1f - (p / 0.3f).coerceIn(0f, 1f), backProgress)
    }

    /** 列表内容滚进顶栏下方的程度。 */
    fun listOverlap(): Float {
        val state = listState ?: return 0f
        if (state.firstVisibleItemIndex > 0) return 1f
        return (state.firstVisibleItemScrollOffset / overlapDistancePx).coerceIn(0f, 1f)
    }

    /** 打开一条回答。[preferredKey] 是用户双击的那一行，优先作为对齐锚点。 */
    fun open(message: ChatMessage, preferredKey: String?) {
        if (session != null) return
        val segments = rows.filter { it.isBodyOf(message.messageId) }
        if (segments.isEmpty()) return
        val visible = listState?.layoutInfo?.visibleItemsInfo.orEmpty()
        // 内容区底边 = 内容区顶边 + 行高 - 行间距。
        fun bottomOf(row: MessageListRow): Float? {
            val item = visible.firstOrNull { it.key == row.key } ?: return null
            val top = rowTops[row.key] ?: return null
            return top + item.size - row.topPaddingDp * density
        }
        val onScreen = segments.filter { seg -> (bottomOf(seg) ?: Float.NEGATIVE_INFINITY) > listContentTop + 1f }
        val anchor = onScreen.firstOrNull { it.key == preferredKey } ?: onScreen.firstOrNull()
        session = ReaderSession(
            message = message,
            segments = segments,
            anchorKey = anchor?.key,
            anchorTop = anchor?.let { rowTops[it.key] } ?: 0f,
            runKeys = anchor?.let { contiguousRun(segments, it.key) }.orEmpty(),
        )
    }

    /** 与锚点在列表里紧挨着的正文行：它们之间没有工具卡等其它行，打开后相对位置不变。 */
    private fun contiguousRun(segments: List<MessageListRow>, anchorKey: String): Set<String> {
        val indexOf = rows.withIndex().associate { (i, row) -> row.key to i }
        val bodyIndices = segments.mapNotNull { indexOf[it.key] }.toSet()
        val anchorIndex = indexOf[anchorKey] ?: return setOf(anchorKey)
        var lo = anchorIndex
        while (lo - 1 in bodyIndices) lo--
        var hi = anchorIndex
        while (hi + 1 in bodyIndices) hi++
        return (lo..hi).map { rows[it].key }.toSet()
    }

    internal fun reveal(s: ReaderSession) {
        hiddenKeys = s.runKeys
        s.ready = true
    }

    /** 关闭：先把列表滚到阅读位置，再让正文落回去。 */
    suspend fun close(readerScroll: Int, readerContentTop: Float, screenHeight: Float) {
        val s = session ?: return
        if (closing) return
        closing = true
        try {
            val pb0 = backProgress
            // 阅读层视口里第一段仍露在工具栏下方的正文作为落点。
            val target = s.segments.firstOrNull { seg ->
                val top = s.segmentTops0[seg.key] ?: return@firstOrNull false
                val height = s.segmentHeights[seg.key] ?: 0
                top - readerScroll + height > readerContentTop + 1f
            }
            s.fadeOnly = true
            if (target != null) {
                val readerTop = (s.segmentTops0[target.key] ?: 0f) - readerScroll
                val residual = alignList(target.key, readerTop)
                if (residual != null && abs(residual) < screenHeight * 0.6f) {
                    s.closeRunKeys = contiguousRun(s.segments, target.key)
                    s.dy = residual
                    s.fadeOnly = false
                    hiddenKeys = s.closeRunKeys
                }
            }
            if (s.fadeOnly) s.dy = 0f
            s.closingNow = true
            progress.animateTo(0f, MolaMotion.spatialSettle()) {
                backProgress = pb0 * value.coerceIn(0f, 1f)
            }
        } finally {
            session = null
            hiddenKeys = emptySet()
            backProgress = 0f
            progress.snapTo(0f)
            closing = false
        }
    }

    /** 把列表里 [key] 那一行滚到屏幕纵坐标 [y]，返回对不齐的剩余量（列表到头了）。 */
    private suspend fun alignList(key: String, y: Float): Float? {
        val state = listState ?: return null
        val index = rows.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return null
        if (state.layoutInfo.visibleItemsInfo.none { it.key == key }) {
            state.scrollToItem(index)
            awaitLayout()
        }
        val before = rowTops[key] ?: return null
        state.scrollBy(before - y)
        awaitLayout()
        val after = rowTops[key] ?: return null
        return after - y
    }

    private fun MessageListRow.isBodyOf(messageId: String): Boolean = when (this) {
        is MessageListRow.AssistantText -> this.messageId == messageId
        is MessageListRow.Fragment -> this.messageId == messageId && fragment.isAssistantBodyText()
        else -> false
    }
}

@Stable
internal class ReaderSession(
    val message: ChatMessage,
    val segments: List<MessageListRow>,
    val anchorKey: String?,
    /** 打开时锚点行内容区顶边（屏幕坐标）。 */
    val anchorTop: Float,
    /** 打开时由阅读层接管、全程不透明的行。 */
    val runKeys: Set<String>,
) {
    /** 正文相对最终位置的纵向偏移，随进度归零。 */
    var dy by mutableFloatStateOf(0f)
    var ready by mutableStateOf(false)
    /** 对不齐时退化为整体淡入淡出。 */
    var fadeOnly by mutableStateOf(false)
    var closingNow by mutableStateOf(false)
    var closeRunKeys: Set<String> = emptySet()
    /** 回答末尾已贴近屏幕底部时，阅读层滚不到与列表一致的位置；末尾补这段留白让正文原地不动。 */
    var extraBottomPx by mutableFloatStateOf(0f)
    /** 阅读层滚动为 0、无位移时各段顶边（屏幕坐标），测一次即可。 */
    val segmentTops0 = HashMap<String, Float>()
    val segmentHeights = HashMap<String, Int>()

    fun opaqueKeys(): Set<String> = if (closingNow) closeRunKeys else runKeys
}

/** 阅读层。盖住整个聊天页（含顶栏、输入框），由 [AssistantReaderState.open] 触发。 */
@Composable
internal fun AssistantReaderOverlay(state: AssistantReaderState) {
    val session = state.session ?: return
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val textToolbar = LocalTextToolbar.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val canvas = MaterialTheme.colorScheme.background
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barBottom = statusTop + ReaderBarHeight
    val contentTopPad = barBottom + 8.dp
    val barBottomPx = with(density) { barBottom.toPx() }
    val overlapPx = with(density) { 24.dp.toPx() }
    val shiftPx = with(density) { 12.dp.toPx() }
    val progress = state.progress
    val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val currentSession by rememberUpdatedState(session)

    val close: () -> Unit = {
        textToolbar.hide()
        scope.launch { state.close(scroll.value, barBottomPx, screenHeightPx) }
    }

    // 首帧只测量（不可见）：拿到各段位置后滚到与列表一致的位置，再开始动画。
    LaunchedEffect(session) {
        withFrameNanos { }
        val anchorKey = session.anchorKey
        val anchorReaderTop = anchorKey?.let { session.segmentTops0[it] }
        if (anchorKey == null || anchorReaderTop == null) {
            session.fadeOnly = true
            scroll.scrollTo(scroll.maxValue)
        } else {
            val desired = (anchorReaderTop - session.anchorTop).toInt()
            if (desired > scroll.maxValue) {
                session.extraBottomPx = (desired - scroll.maxValue).toFloat()
                awaitLayout()
            }
            scroll.scrollTo(desired.coerceIn(0, scroll.maxValue))
            // 回答开头还在屏幕中部时阅读层无法往回滚，正文从原处升到顶部。
            session.dy = session.anchorTop - (anchorReaderTop - scroll.value)
        }
        state.reveal(session)
        progress.snapTo(0f)
        progress.animateTo(1f, MolaMotion.expressiveSpatial())
    }

    PredictiveBackHandler(enabled = session.ready && !session.closingNow) { events ->
        try {
            events.collect { event ->
                state.backEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
                state.backProgress = MolaMotion.StandardDecelerate.transform(event.progress.coerceIn(0f, 1f))
            }
            textToolbar.hide()
            state.close(scroll.value, barBottomPx, screenHeightPx)
        } catch (e: CancellationException) {
            Animatable(state.backProgress).animateTo(0f, MolaMotion.effects()) { state.backProgress = value }
            throw e
        }
    }

    val p: () -> Float = { progress.value }
    val backInset = with(density) { 12.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (currentSession.ready) 1f else 0f
                val b = state.backProgress
                if (b > 0f) {
                    val scale = 1f - 0.1f * b
                    scaleX = scale
                    scaleY = scale
                    translationX = state.backEdgeSign * (size.width / 20f - backInset) * b
                    shape = RoundedCornerShape(28.dp * b)
                    clip = true
                }
                if (currentSession.fadeOnly) alpha *= p().coerceIn(0f, 1f)
            },
    ) {
        CompositionLocalProvider(
            // 阅读层在 Scaffold 之外，正文颜色要与列表一致地取自画布的前景色。
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalMarkdownImageRenderer provides { url, modifier -> RemoteImage(url, modifier) },
            LocalFaviconRenderer provides { url, modifier -> RemoteFavicon(url, modifier) },
        ) {
            MolaGlassHost(
                backgroundColor = canvas,
                canvasAlpha = { (p() / 0.55f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                content = {
                    // 不用 LazyColumn：未组合的段落无法加入 SelectionContainer 的连续选区。
                    SelectionContainer(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    compositingStrategy = if (p() < ChromeReveal) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                                }
                                .drawWithContent {
                                    drawContent()
                                    val hide = 1f - (p() / ChromeReveal).coerceIn(0f, 1f)
                                    if (hide > 0f) {
                                        val top = state.chromeTop.coerceIn(0f, size.height)
                                        val bottom = state.chromeBottom.coerceIn(0f, size.height)
                                        drawRect(Color.Black, Offset.Zero, Size(size.width, top), alpha = hide, blendMode = BlendMode.DstOut)
                                        drawRect(
                                            Color.Black,
                                            Offset(0f, bottom),
                                            Size(size.width, size.height - bottom),
                                            alpha = hide,
                                            blendMode = BlendMode.DstOut,
                                        )
                                    }
                                }
                                .graphicsLayer { translationY = currentSession.dy * (1f - p()) }
                                .verticalScroll(scroll)
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = contentTopPad,
                                    bottom = navBottom + 32.dp + with(density) { session.extraBottomPx.toDp() },
                                ),
                        ) {
                            session.segments.forEachIndexed { index, segment ->
                                MessageListRowView(
                                    segment = segment,
                                    first = index == 0,
                                    modifier = Modifier
                                        .onGloballyPositioned { coords ->
                                            if (!currentSession.ready) {
                                                session.segmentTops0[segment.key] = coords.positionInRoot().y
                                                session.segmentHeights[segment.key] = coords.size.height
                                            }
                                        }
                                        .graphicsLayer {
                                            alpha = if (segment.key in currentSession.opaqueKeys()) 1f
                                            else ((p() - 0.15f) / 0.5f).coerceIn(0f, 1f)
                                        },
                                )
                            }
                        }
                    }
                },
                overlay = {
                    MolaTopBar(
                        title = { Text("选择文本") },
                        navigationIcon = {
                            IconButton(onClick = close) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭")
                            }
                        },
                        actions = {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(session.message.copyMarkdown())) }) {
                                Text("复制全文")
                            }
                        },
                        overlap = { (scroll.value / overlapPx).coerceIn(0f, 1f) * ((p() - 0.3f) / 0.5f).coerceIn(0f, 1f) },
                        modifier = Modifier.graphicsLayer {
                            alpha = ((p() - 0.2f) / 0.5f).coerceIn(0f, 1f)
                            translationY = -shiftPx * (1f - p())
                        },
                    )
                    MolaBottomEdge(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(navBottom + 40.dp)
                            .graphicsLayer { alpha = ((p() - 0.3f) / 0.5f).coerceIn(0f, 1f) },
                    )
                },
            )
        }
    }
}

private val ReaderBarHeight = 64.dp

/** 阅读层在顶栏、输入框区域里显现所用的进度区间。 */
private const val ChromeReveal = 0.45f

@Composable
private fun MessageListRowView(segment: MessageListRow, first: Boolean, modifier: Modifier) {
    val top = if (first) 0 else segment.topPaddingDp
    when (segment) {
        is MessageListRow.AssistantText -> CompositionLocalProvider(
            LocalCitationSources provides segment.citationSources,
        ) {
            Column(Modifier.fillMaxWidth().padding(top = top.dp).then(modifier)) {
                segment.blocks.forEachIndexed { index, block ->
                    MarkdownBlockView(
                        block = block,
                        modifier = Modifier.fillMaxWidth(),
                        blockKey = "${segment.messageId}:${segment.fragmentId}:$index",
                    )
                }
            }
        }
        is MessageListRow.Fragment -> CompositionLocalProvider(
            LocalCitationSources provides segment.citationSources,
        ) {
            FragmentRenderer(
                fragment = segment.fragment,
                modifier = Modifier.fillMaxWidth().padding(top = top.dp).then(modifier),
            )
        }
        else -> Unit
    }
}

/** 复制全文用的 Markdown：正文片段按原顺序拼起来，代码与公式还原成源码。 */
private fun ChatMessage.copyMarkdown(): String {
    val body = fragments.filter { it.isAssistantBodyText() }
    if (body.isEmpty()) return rawText.orEmpty()
    return body.joinToString("\n\n") { fragment ->
        when (fragment) {
            is MessageFragment.Text -> fragment.markdown
            is MessageFragment.CodeBlock -> "```${fragment.language.orEmpty()}\n${fragment.code}\n```"
            is MessageFragment.Latex -> if (fragment.display) "\$\$${fragment.expr}\$\$" else "\$${fragment.expr}\$"
            is MessageFragment.Mermaid -> "```mermaid\n${fragment.source}\n```"
            else -> ""
        }
    }.trim()
}

internal fun MessageFragment.isAssistantBodyText(): Boolean =
    this is MessageFragment.Text || this is MessageFragment.CodeBlock ||
        this is MessageFragment.Latex || this is MessageFragment.Mermaid

/** 等状态变化落到布局上：下一帧开头才重组，再下一帧位置回调才更新。 */
private suspend fun awaitLayout() {
    withFrameNanos { }
    withFrameNanos { }
}

/** 键盘收起后再开阅读层：键盘收起会让列表重新布局，提前测量的位置会作废。 */
internal suspend fun awaitImeHidden(imeBottom: () -> Int) {
    if (imeBottom() == 0) return
    withTimeoutOrNull(450) { snapshotFlow { imeBottom() }.first { it == 0 } }
    awaitLayout()
}

/** 只观察轻点，不消费事件，保留原来的长按选字、链接点击和滚动手势。 */
@Composable
internal fun Modifier.onAssistantTextDoubleTap(
    messageId: String,
    enabled: Boolean,
    onDoubleTap: () -> Unit,
): Modifier {
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    if (!enabled) return this
    return pointerInput(messageId) {
        var previousTap: PointerInputChange? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var isTap = true
            var up: PointerInputChange? = null
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.size != 1 ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                ) {
                    isTap = false
                }
                if (change.changedToUpIgnoreConsumed()) {
                    up = change
                    break
                }
            } while (event.changes.any { it.pressed })

            val lastTap = previousTap
            if (!isTap || up == null) {
                previousTap = null
            } else if (lastTap != null &&
                down.uptimeMillis - lastTap.uptimeMillis in
                viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastTap.position).getDistance() <= viewConfiguration.touchSlop * 2
            ) {
                previousTap = null
                currentOnDoubleTap()
            } else {
                previousTap = up
            }
        }
    }
}
