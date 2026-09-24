package com.molagpt.app.core.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.molagpt.app.core.markdown.MdBlock
import com.molagpt.app.core.markdown.MdInline
import com.molagpt.app.core.markdown.MdTableAlignment
import com.molagpt.app.core.markdown.visual.HtmlFence
import com.molagpt.app.core.render.visual.HtmlFenceView
import com.molagpt.app.core.render.visual.MolaUiBlockView
import com.molagpt.app.core.model.SourceReference

/**
 * Markdown 行内图片的渲染器（CompositionLocal）。:core:render 不依赖 Coil/:feature:file，
 * 由 feature 层（FragmentRenderer）提供真正的 Coil 实现；默认仅占位，保证本模块可独立编译。
 * 与运行网页交给 `LocalVisualHost` 同思路，维持 core 不反向依赖 feature。
 */
val LocalMarkdownImageRenderer: ProvidableCompositionLocal<@Composable (String, Modifier) -> Unit> =
    staticCompositionLocalOf {
        @Composable { url: String, m: Modifier ->
            Text(text = "[图片] $url", modifier = m, style = MaterialTheme.typography.labelSmall)
        }
    }

/**
 * 正文引用角标要解的来源表，由消息层提供（联网搜索的 `molagpt_sources`）。
 *
 * 走 CompositionLocal 而不是参数：来源是**整条消息**的，正文却被切成若干 block 分别渲染，
 * 一路透传要穿过表格单元格、列表项、引用块这些递归层。而且来源常常比正文晚到，
 * 用 [compositionLocalOf] 只让真正读它的那几段重组，不牵动整条消息。
 */
val LocalCitationSources: ProvidableCompositionLocal<List<SourceReference>> =
    compositionLocalOf { emptyList() }

/**
 * 引用胶囊上那枚 favicon 的渲染器。
 *
 * 跟 [LocalMarkdownImageRenderer] 分开注入：那个渲染的是正文配图，会撑满宽度并接管
 * 点击预览，用来画图标会先把一行撑开。默认什么都不画，胶囊退化成纯站点名。
 */
val LocalFaviconRenderer: ProvidableCompositionLocal<@Composable (String, Modifier) -> Unit> =
    staticCompositionLocalOf { @Composable { _: String, _: Modifier -> } }

@Composable
fun MarkdownBlockView(
    block: MdBlock,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    /**
     * 流式尾部渐隐：仅对**正在流式输出的最后一个 block** 传 true。
     * 渐变锚在真实行尾（由文本布局给出），不切分字符，开销与文本量无关。
     * 同时表示「这一块所在的回答还在生成」：没写完的网页 / 组件围栏据此显示为生成中。
     */
    tailFade: Boolean = false,
    /** 这一块在对话里的稳定身份，网页代码块记「卡片 / 代码」切换用；没有时切换只在控件里记。 */
    blockKey: String? = null,
) {
    val bodyLarge = scaledTextStyle(MaterialTheme.typography.bodyLarge, textScale)
    val bodySmall = scaledTextStyle(MaterialTheme.typography.bodySmall, textScale)
    when (block) {
        is MdBlock.Heading -> MathText(
            inlines = block.inlines,
            style = scaledTextStyle(headingStyle(block.level), textScale),
            modifier = modifier.padding(vertical = 4.dp),
            tailFade = tailFade,
        )
        is MdBlock.Paragraph -> InlineContentText(
            inlines = block.inlines,
            style = bodyLarge,
            modifier = modifier.padding(vertical = 2.dp),
            tailFade = tailFade,
        )
        is MdBlock.Quote -> Row(modifier = modifier.padding(vertical = 2.dp)) {
            Spacer(Modifier.width(3.dp).height(1.dp))
            HorizontalDivider(
                modifier = Modifier.width(3.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(8.dp))
            Column { block.blocks.forEach { MarkdownBlockView(it, textScale = textScale) } }
        }
        is MdBlock.BulletList -> Column(modifier = modifier.padding(vertical = 2.dp)) {
            block.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("•  ", style = bodyLarge)
                    Column(modifier = Modifier.weight(1f)) {
                        item.blocks.forEach { MarkdownBlockView(it, textScale = textScale) }
                    }
                }
            }
        }
        is MdBlock.OrderedList -> Column(modifier = modifier.padding(vertical = 2.dp)) {
            block.items.forEachIndexed { i, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("${block.start + i}.  ", style = bodyLarge)
                    Column(modifier = Modifier.weight(1f)) {
                        item.blocks.forEach { MarkdownBlockView(it, textScale = textScale) }
                    }
                }
            }
        }
        is MdBlock.Table -> TableView(block, modifier.padding(vertical = 6.dp), bodySmall)
        is MdBlock.Code -> if (HtmlFence.isHtml(block.language, block.code)) {
            HtmlFenceView(block, streaming = tailFade, stateKey = blockKey, modifier = modifier.padding(vertical = 4.dp))
        } else {
            CodeBlockView(language = block.language, code = block.code, modifier = modifier)
        }
        is MdBlock.MathBlock -> LatexView(expr = block.expr, display = true, modifier = modifier.padding(vertical = 4.dp))
        is MdBlock.Mermaid -> CodeBlockView(language = "mermaid", code = block.source, modifier = modifier)
        is MdBlock.MolaUi -> MolaUiBlockView(block, streaming = tailFade, modifier = modifier.padding(vertical = 6.dp))
        MdBlock.Divider -> HorizontalDivider(modifier = modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun TableView(table: MdBlock.Table, modifier: Modifier, cellStyle: TextStyle) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val columnCount = maxOf(
        table.header.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columnCount == 0) return
    // 只按最先稳定下来的表头估算列宽；后续流式追加行不会让整张表来回跳宽。
    val columnWidths = remember(table.header, columnCount) {
        List(columnCount) { index -> estimatedTableColumnWidth(table.header.getOrNull(index)) }
    }
    Column(modifier = modifier.horizontalScroll(rememberScrollState())) {
        if (table.header.isNotEmpty()) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                repeat(columnCount) { index ->
                    TableCell(
                        inlines = table.header.getOrNull(index).orEmpty(),
                        width = columnWidths[index],
                        alignment = table.alignments.getOrNull(index) ?: MdTableAlignment.DEFAULT,
                        modifier = Modifier.background(headerBg).border(0.5.dp, borderColor),
                        bold = true,
                        style = cellStyle,
                    )
                }
            }
        }
        table.rows.forEach { row ->
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                repeat(columnCount) { index ->
                    TableCell(
                        inlines = row.getOrNull(index).orEmpty(),
                        width = columnWidths[index],
                        alignment = table.alignments.getOrNull(index) ?: MdTableAlignment.DEFAULT,
                        modifier = Modifier.border(0.5.dp, borderColor),
                        style = cellStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    inlines: List<MdInline>,
    width: Dp,
    alignment: MdTableAlignment,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    style: TextStyle = TextStyle.Default,
) {
    MathText(
        inlines = inlines,
        style = style.copy(textAlign = alignment.toTextAlign()),
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        forceBold = bold,
    )
}

private fun MdTableAlignment.toTextAlign(): TextAlign = when (this) {
    MdTableAlignment.CENTER -> TextAlign.Center
    MdTableAlignment.RIGHT -> TextAlign.End
    MdTableAlignment.DEFAULT, MdTableAlignment.LEFT -> TextAlign.Start
}

private fun estimatedTableColumnWidth(header: List<MdInline>?): Dp {
    val label = buildString {
        header.orEmpty().forEach { inline ->
            when (inline) {
                is MdInline.Text -> append(inline.text)
                is MdInline.Code -> append(inline.text)
                is MdInline.Link -> append(inline.text)
                is MdInline.Image -> append(inline.alt)
                is MdInline.Math -> append(inline.expr)
                is MdInline.Citation -> inline.ids.forEach { append(" $it ") }
                MdInline.SoftBreak, MdInline.HardBreak -> append(' ')
            }
        }
    }
    val widthUnits = label.sumOf { char -> if (char.code >= 0x2E80) 1.0 else 0.56 }
    return (widthUnits * 14.0 + 28.0).coerceIn(112.0, 220.0).toFloat().dp
}

/**
 * 行内文本：先按图片切段（纯数据），文本段走 [MathText]（含行内公式真渲染），
 * 图片段走 [LocalMarkdownImageRenderer]。无图片时退化为单个 MathText（绝大多数情况）。
 */
@Composable
private fun InlineContentText(
    inlines: List<MdInline>,
    style: TextStyle,
    modifier: Modifier,
    forceBold: Boolean = false,
    tailFade: Boolean = false,
) {
    if (inlines.none { it is MdInline.Image }) {
        MathText(inlines, style, modifier, forceBold, tailFade)
        return
    }
    val imageRenderer = LocalMarkdownImageRenderer.current
    Column(modifier = modifier) {
        val segs = splitByImage(inlines)
        segs.forEachIndexed { index, seg ->
            when (seg) {
                is InlineSeg.Text -> MathText(
                    seg.inlines,
                    style,
                    Modifier.fillMaxWidth(),
                    forceBold,
                    // 只有最后一段文本才是「正在写」的那一段。
                    tailFade && index == segs.lastIndex,
                )
                is InlineSeg.Img -> imageRenderer(seg.url, Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
    }
}

/** 文本（含行内公式）。公式经 JLaTeXMath 光栅化为 InlineTextContent，与正文同字号随文排版。 */
@Composable
private fun MathText(
    inlines: List<MdInline>,
    style: TextStyle,
    modifier: Modifier,
    forceBold: Boolean = false,
    tailFade: Boolean = false,
) {
    val density = LocalDensity.current
    val colorArgb = LocalContentColor.current.toArgb()
    val accent = MaterialTheme.colorScheme.primary
    val fontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 16.sp
    val fontPx = with(density) { fontSize.toPx() }
    val sources = LocalCitationSources.current
    val badge = CitationStyle(
        // 中性底色而不是主色染色：胶囊里已经有 favicon 在提供颜色，再加一层品牌色
        // 会把一句话里的三枚胶囊变成三块高饱和补丁。
        fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        muted = MaterialTheme.colorScheme.outline,
        fontSize = fontSize * CITATION_FONT_SCALE,
        fontScale = CITATION_FONT_SCALE,
        // 图标比标签字大一点：不少 favicon 是方画布上一条窄字标，按标签字号缩会糊成一横。
        iconSize = with(density) { (fontSize * 0.84f).toDp() },
    )
    val (text, content) = remember(inlines, style, forceBold, colorArgb, accent, fontPx, sources, badge) {
        buildMathAnnotated(inlines, forceBold, fontPx, colorArgb, accent, sources, badge)
    }
    // 渐变必须锚在真实行尾：流式时最后一行常只写到一半，锚在容器右边缘会让渐变落在空白处。
    var tail by remember { mutableStateOf(TailGeometry.None) }
    Text(
        text = text,
        modifier = if (tailFade) {
            modifier.streamingTailFade(
                active = true,
                lastLineEndX = tail.endX,
                lastLineTop = tail.top,
                lastLineBottom = tail.bottom,
            )
        } else {
            modifier
        },
        style = style,
        inlineContent = content,
        onTextLayout = if (tailFade) {
            { layout ->
                val line = layout.lineCount - 1
                if (line >= 0) {
                    val g = TailGeometry(
                        endX = layout.getLineRight(line),
                        top = layout.getLineTop(line),
                        bottom = layout.getLineBottom(line),
                    )
                    if (g != tail) tail = g
                }
            }
        } else {
            {}
        },
    )
}

/** 最后一行的几何信息（行尾 x 与上下边界），用于把渐变精确贴在文字末端。 */
private data class TailGeometry(val endX: Float, val top: Float, val bottom: Float) {
    companion object { val None = TailGeometry(0f, 0f, 0f) }
}

private fun buildMathAnnotated(
    inlines: List<MdInline>,
    forceBold: Boolean,
    fontPx: Float,
    colorArgb: Int,
    accent: Color,
    sources: List<SourceReference>,
    badge: CitationStyle,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val content = LinkedHashMap<String, InlineTextContent>()
    var mathIdx = 0
    var citationIdx = 0
    val text = buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is MdInline.Text -> withStyle(
                    SpanStyle(
                        fontWeight = if (forceBold || inline.bold) FontWeight.Bold else null,
                        fontStyle = if (inline.italic) FontStyle.Italic else null,
                        textDecoration = if (inline.strike) TextDecoration.LineThrough else null,
                    ),
                ) { append(inline.text) }
                is MdInline.Code -> withStyle(SpanStyle(color = accent)) { append(inline.text) }
                is MdInline.Link -> appendMarkdownLink(inline, accent)
                is MdInline.Math -> {
                    val bmp = if (inline.expr.isNotBlank()) {
                        latexInlineBitmap(inline.expr, fontPx, colorArgb)
                    } else {
                        null
                    }
                    if (bmp != null && fontPx > 0f) {
                        val id = "m${mathIdx++}"
                        appendInlineContent(id, inline.expr)
                        content[id] = InlineTextContent(
                            Placeholder(
                                width = (bmp.width.toFloat() / fontPx).em,
                                height = (bmp.height.toFloat() / fontPx).em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = inline.expr,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        append("$${inline.expr}$")
                    }
                }
                is MdInline.Image -> append(inline.alt) // 兜底：MathText 不应收到图片（已被 splitByImage 切走）
                is MdInline.Citation -> {
                    // 一个 `<ref source="1,3" />` 是**一条**引用，折成一枚胶囊，多出来的
                    // 来源记在 "+N" 上。解不到来源的编号丢掉，剩下的照常画。
                    val refs = inline.ids.mapNotNull { id -> sources.firstOrNull { it.index == id } }
                    if (refs.isNotEmpty()) {
                        val key = "c${citationIdx++}"
                        val label = refs[0].site.take(CITATION_SITE_MAX)
                        val plus = if (refs.size > 1) "+${refs.size - 1}" else null
                        // 可选文本是 [N]：无障碍朗读和复制选区拿到的是它。
                        appendInlineContent(key, "[" + inline.ids.joinToString(",") + "]")
                        content[key] = InlineTextContent(
                            Placeholder(
                                width = citationPillWidth(label, plus, badge.fontScale),
                                height = 1.5.em,
                                // TextCenter 而不是 Center：Center 对齐的是**行盒**的中线，
                                // 行高比字大多少，胶囊就跟着往下掉多少。
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) { CitationPill(refs, label, plus, badge) }
                    }
                }
                MdInline.SoftBreak -> append(" ")
                MdInline.HardBreak -> append("\n")
            }
        }
    }
    return text to content
}

/**
 * 胶囊的配色与尺寸，随正文字号缩放；提出来是为了进 [MathText] 的 remember key。
 *
 * [fontScale] 是标签字号相对正文的倍数，占位宽度要按它换算成 em。
 */
private data class CitationStyle(
    val fill: Color,
    val content: Color,
    val muted: Color,
    val fontSize: TextUnit,
    val fontScale: Float,
    val iconSize: Dp,
) {
    val textStyle: TextStyle = TextStyle(
        fontSize = fontSize,
        lineHeight = fontSize,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
}

private const val CITATION_FONT_SCALE = 0.62f
private const val CITATION_SITE_MAX = 22

/**
 * 胶囊的占位宽度，单位 em（相对正文字号）。
 *
 * [InlineTextContent] 的尺寸必须**先于**内容算出来，量不到真实文本，只能按字符估。
 * 宁可估宽：多出来的变成内边距，估窄了会把站点名裁掉。
 */
private fun citationPillWidth(site: String, plus: String?, fontScale: Float): TextUnit {
    // 域名是 ASCII，平均字宽约 0.55 个字号。
    val text = site.length * fontScale * 0.55f
    val extra = plus?.let { it.length * fontScale * 0.55f + 0.18f } ?: 0f
    // 左让位 + 图标 + 间距 + 文字 + 右内边距。
    return (0.18f + 0.84f + 0.2f + text + extra + 0.42f).em
}

/**
 * 正文里的引用胶囊：favicon + 站点名（+N），点开一张可翻页的来源卡。
 *
 * 解不到任何来源的 `<ref>` 在上游就被丢掉了，所以走到这里的胶囊一定点得开。
 */
@Composable
private fun CitationPill(
    refs: List<SourceReference>,
    label: String,
    plus: String?,
    style: CitationStyle,
) {
    var showCard by remember(refs) { mutableStateOf(false) }
    val favicon = LocalFaviconRenderer.current

    Row(
        // padding 在 clip 之前：让出的间距留在药丸外面，连着的两枚才不会贴在一起。
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 2.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(style.fill)
            .clickable { showCard = true }
            .padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        refs[0].faviconEndpoint?.let { endpoint ->
            favicon(endpoint, Modifier.size(style.iconSize).clip(RoundedCornerShape(2.dp)))
        }
        Text(
            text = label,
            color = style.content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
            // 文字在药丸里偏下，是 Android 那份字体 padding 撑的：它在字形上下补的空
            // 不对称，居中的是「带 padding 的盒子」而不是字。关掉再压行高才是字形本身。
            style = style.textStyle,
        )
        if (plus != null) {
            Text(
                text = plus,
                color = style.muted,
                maxLines = 1,
                softWrap = false,
                style = style.textStyle,
            )
        }
    }

    if (showCard) {
        CitationCard(refs) { showCard = false }
    }
}

/**
 * 来源卡：一条一页，多条用 ← → 翻。
 *
 * 贴底的 sheet 而不是贴着胶囊弹的浮层：行内元素在屏幕边缘时浮层很容易顶出可视区
 * （网页端正是这么出的问题），贴底的卡永远在屏内，单手也够得着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitationCard(refs: List<SourceReference>, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val favicon = LocalFaviconRenderer.current
    val cs = MaterialTheme.colorScheme
    var index by remember(refs) { mutableIntStateOf(0) }
    val current = refs[index.coerceIn(refs.indices)]

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                current.faviconEndpoint?.let { endpoint ->
                    favicon(endpoint, Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = current.site,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (refs.size > 1) {
                    IconButton(onClick = { index = (index - 1 + refs.size) % refs.size }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上一条来源",
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { index = (index + 1) % refs.size }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "下一条来源",
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${index + 1}/${refs.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = current.title.ifBlank { current.url },
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri(current.url) } }
                    .padding(end = 8.dp),
            )

            current.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownLink(inline: MdInline.Link, accent: Color) {
    val url = inline.url.trim()
    val label = inline.text.ifBlank { url }
    val linkStyle = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
    if (label.isEmpty()) return
    if (isOpenableMarkdownUrl(url)) {
        withLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle))) {
            append(label)
        }
    } else {
        withStyle(linkStyle) { append(label) }
    }
}

/** 仅允许系统浏览器能安全打开的常见协议。 */
private fun isOpenableMarkdownUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}

private sealed interface InlineSeg {
    data class Text(val inlines: List<MdInline>) : InlineSeg
    data class Img(val url: String, val alt: String) : InlineSeg
}

/** 把行内片段按图片切成「文本段 / 图片段」序列（纯数据，便于在稳定循环里渲染）。 */
private fun splitByImage(inlines: List<MdInline>): List<InlineSeg> {
    val out = ArrayList<InlineSeg>()
    val run = ArrayList<MdInline>()
    fun flush() {
        if (run.isNotEmpty()) {
            out.add(InlineSeg.Text(run.toList()))
            run.clear()
        }
    }
    inlines.forEach { inline ->
        if (inline is MdInline.Image) {
            flush()
            out.add(InlineSeg.Img(inline.url, inline.alt))
        } else {
            run.add(inline)
        }
    }
    flush()
    return out
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

private fun scaledTextStyle(style: TextStyle, scale: Float): TextStyle {
    if (scale == 1f) return style
    return style.copy(
        fontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize * scale else style.fontSize,
        lineHeight = if (style.lineHeight != TextUnit.Unspecified) style.lineHeight * scale else style.lineHeight,
    )
}
