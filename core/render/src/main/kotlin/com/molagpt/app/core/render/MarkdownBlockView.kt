package com.molagpt.app.core.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.molagpt.app.core.markdown.MdBlock
import com.molagpt.app.core.markdown.MdInline

/**
 * Markdown 行内图片的渲染器（CompositionLocal）。:core:render 不依赖 Coil/:feature:file，
 * 由 feature 层（FragmentRenderer）提供真正的 Coil 实现；默认仅占位，保证本模块可独立编译。
 * 与 Mermaid 的 lambda 注入同思路，维持 core 不反向依赖 feature。
 */
val LocalMarkdownImageRenderer: ProvidableCompositionLocal<@Composable (String, Modifier) -> Unit> =
    staticCompositionLocalOf {
        @Composable { url: String, m: Modifier ->
            Text(text = "[图片] $url", modifier = m, style = MaterialTheme.typography.labelSmall)
        }
    }

@Composable
fun MarkdownBlockView(
    block: MdBlock,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
) {
    val bodyLarge = scaledTextStyle(MaterialTheme.typography.bodyLarge, textScale)
    val bodySmall = scaledTextStyle(MaterialTheme.typography.bodySmall, textScale)
    when (block) {
        is MdBlock.Heading -> MathText(
            inlines = block.inlines,
            style = scaledTextStyle(headingStyle(block.level), textScale),
            modifier = modifier.padding(vertical = 4.dp),
        )
        is MdBlock.Paragraph -> InlineContentText(
            inlines = block.inlines,
            style = bodyLarge,
            modifier = modifier.padding(vertical = 2.dp),
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
        is MdBlock.Code -> CodeBlockView(language = block.language, code = block.code, modifier = modifier)
        is MdBlock.MathBlock -> LatexView(expr = block.expr, display = true, modifier = modifier.padding(vertical = 4.dp))
        is MdBlock.Mermaid -> CodeBlockView(language = "mermaid", code = block.source, modifier = modifier)
        MdBlock.Divider -> HorizontalDivider(modifier = modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun TableView(table: MdBlock.Table, modifier: Modifier, cellStyle: TextStyle) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    Column(modifier = modifier.horizontalScroll(rememberScrollState())) {
        if (table.header.isNotEmpty()) {
            Row {
                table.header.forEach { cell ->
                    TableCell(cell, Modifier.background(headerBg), bold = true, style = cellStyle)
                }
            }
        }
        table.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    TableCell(cell, Modifier.border(0.5.dp, borderColor), style = cellStyle)
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    inlines: List<MdInline>,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    style: TextStyle = TextStyle.Default,
) {
    MathText(
        inlines = inlines,
        style = style,
        modifier = modifier
            .defaultMinSize(minWidth = 112.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        forceBold = bold,
    )
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
) {
    if (inlines.none { it is MdInline.Image }) {
        MathText(inlines, style, modifier, forceBold)
        return
    }
    val imageRenderer = LocalMarkdownImageRenderer.current
    Column(modifier = modifier) {
        splitByImage(inlines).forEach { seg ->
            when (seg) {
                is InlineSeg.Text -> MathText(seg.inlines, style, Modifier.fillMaxWidth(), forceBold)
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
) {
    val density = LocalDensity.current
    val colorArgb = LocalContentColor.current.toArgb()
    val accent = MaterialTheme.colorScheme.primary
    val fontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 16.sp
    val fontPx = with(density) { fontSize.toPx() }
    val (text, content) = remember(inlines, style, forceBold, colorArgb, accent, fontPx) {
        buildMathAnnotated(inlines, forceBold, fontPx, colorArgb, accent)
    }
    Text(text = text, modifier = modifier, style = style, inlineContent = content)
}

private fun buildMathAnnotated(
    inlines: List<MdInline>,
    forceBold: Boolean,
    fontPx: Float,
    colorArgb: Int,
    accent: Color,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val content = LinkedHashMap<String, InlineTextContent>()
    var mathIdx = 0
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
                MdInline.SoftBreak -> append(" ")
                MdInline.HardBreak -> append("\n")
            }
        }
    }
    return text to content
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
