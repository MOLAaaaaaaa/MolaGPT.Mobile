package com.molagpt.app.core.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * 把 Markdown 源串解析成 [MdBlock] 列表。**应在后台线程调用**（:core:render 会用
 * Dispatchers.Default 包裹）。流式期间整段重解析成本可接受；render 层按块 hash 缓存渲染结果。
 *
 * 处理顺序：
 *  1. 先识别已闭合的块级 `$$...$$`、`\[...\]` 和数学 environment；
 *  2. 把已闭合的 `$...$` / `$`…``$` / `\(...\)` 替换成私有占位符，避免 CommonMark 吞反斜杠
 *     或解释公式里的 `_`；
 *  3. 其余内容交给 commonmark，代码区始终受保护；AST 映射时恢复公式并保留 GFM 表格对齐信息。
 * 未闭合的公式和代码都保持普通文本/代码，等待下一批流式内容补全后再升级渲染。
 */
object MarkdownParser {
    private val parser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create(), TablesExtension.create()))
        .build()

    fun parse(markdown: String): List<MdBlock> {
        val visibleMarkdown = stripHiddenContext(markdown)
        if (visibleMarkdown.isBlank()) return emptyList()
        val out = ArrayList<MdBlock>()
        for (seg in splitDisplayMath(visibleMarkdown)) {
            if (seg.isMath) {
                val expr = seg.text.trim()
                if (expr.isNotEmpty()) out.add(MdBlock.MathBlock(expr))
            } else if (seg.text.isNotBlank()) {
                val prepared = extractInlineMath(seg.text)
                parseCommonmark(prepared.markdown, prepared.inlineMath, out)
            }
        }
        return out
    }

    private fun stripHiddenContext(input: String): String =
        input
            .replace(Regex("""✝[^✝]*✝"""), "")
            .replace(Regex("""†[^†]*†"""), "")
            .replace(Regex("""⟦MEM[:：][\s\S]*?⟧"""), "")

    private fun parseCommonmark(
        md: String,
        inlineMath: InlineMathContext,
        out: MutableList<MdBlock>,
    ) {
        val doc = parser.parse(md) as Document
        var node = doc.firstChild
        while (node != null) {
            blockOf(node, inlineMath)?.let(out::add)
            node = node.next
        }
    }

    private fun blockOf(node: Node, inlineMath: InlineMathContext): MdBlock? = when (node) {
        is Heading -> MdBlock.Heading(node.level, inlinesOf(node, inlineMath))
        is Paragraph -> MdBlock.Paragraph(inlinesOf(node, inlineMath))
        is ThematicBreak -> MdBlock.Divider
        is FencedCodeBlock -> {
            val info = node.info?.trim()?.lowercase().orEmpty()
            val language = info.substringBefore(' ').removeSurrounding("{", "}")
            when {
                language == "mermaid" -> MdBlock.Mermaid(node.literal.trimEnd())
                language in MATH_FENCE_LANGUAGES && node.closingFenceLength != null -> {
                    MdBlock.MathBlock(node.literal.trim())
                }
                else -> MdBlock.Code(node.info?.trim()?.ifBlank { null }, node.literal.trimEnd())
            }
        }
        is IndentedCodeBlock -> MdBlock.Code(null, node.literal.trimEnd())
        is BlockQuote -> MdBlock.Quote(buildList {
            var c = node.firstChild
            while (c != null) {
                blockOf(c, inlineMath)?.let(::add)
                c = c.next
            }
        })
        is BulletList -> MdBlock.BulletList(listItems(node, inlineMath))
        is OrderedList -> MdBlock.OrderedList(node.markerStartNumber, listItems(node, inlineMath))
        is TableBlock -> tableOf(node, inlineMath)
        else -> null
    }

    private fun tableOf(table: TableBlock, inlineMath: InlineMathContext): MdBlock.Table {
        var header: List<List<MdInline>> = emptyList()
        var alignments: List<MdTableAlignment> = emptyList()
        val rows = mutableListOf<List<List<MdInline>>>()
        var child = table.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> {
                    val headerRow = child.firstChild as? TableRow
                    header = headerRow?.let { tableCells(it, inlineMath) }.orEmpty()
                    alignments = headerRow?.let(::tableAlignments).orEmpty()
                }
                is TableBody -> rows += tableRows(child, inlineMath)
            }
            child = child.next
        }
        return MdBlock.Table(header = header, rows = rows, alignments = alignments)
    }

    private fun tableRows(section: Node, inlineMath: InlineMathContext): List<List<List<MdInline>>> = buildList {
        var row = section.firstChild
        while (row != null) {
            if (row is TableRow) {
                add(tableCells(row, inlineMath))
            }
            row = row.next
        }
    }

    private fun tableCells(row: TableRow, inlineMath: InlineMathContext): List<List<MdInline>> = buildList {
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) {
                add(inlinesOf(cell, inlineMath))
            }
            cell = cell.next
        }
    }

    private fun tableAlignments(row: TableRow): List<MdTableAlignment> = buildList {
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) {
                add(
                    when (cell.alignment) {
                        TableCell.Alignment.LEFT -> MdTableAlignment.LEFT
                        TableCell.Alignment.CENTER -> MdTableAlignment.CENTER
                        TableCell.Alignment.RIGHT -> MdTableAlignment.RIGHT
                        null -> MdTableAlignment.DEFAULT
                    },
                )
            }
            cell = cell.next
        }
    }

    private fun listItems(list: Node, inlineMath: InlineMathContext): List<ListItemContent> = buildList {
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val blocks = mutableListOf<MdBlock>()
                var c = item.firstChild
                while (c != null) {
                    blockOf(c, inlineMath)?.let { blocks.add(it) }
                    c = c.next
                }
                add(ListItemContent(blocks))
            }
            item = item.next
        }
    }

    private fun inlinesOf(parent: Node, inlineMath: InlineMathContext): List<MdInline> {
        val out = ArrayList<MdInline>()
        collectInlines(
            start = parent.firstChild,
            out = out,
            inlineMath = inlineMath,
            bold = false,
            italic = false,
            strike = false,
        )
        return out
    }

    private fun collectInlines(
        start: Node?,
        out: MutableList<MdInline>,
        inlineMath: InlineMathContext,
        bold: Boolean,
        italic: Boolean,
        strike: Boolean,
    ) {
        var node = start
        while (node != null) {
            when (node) {
                is Text -> appendTextAndInlineMath(node.literal, inlineMath, bold, italic, strike, out)
                is Code -> out.add(MdInline.Code(node.literal))
                is Emphasis -> collectInlines(node.firstChild, out, inlineMath, bold, true, strike)
                is StrongEmphasis -> collectInlines(node.firstChild, out, inlineMath, true, italic, strike)
                is Strikethrough -> collectInlines(node.firstChild, out, inlineMath, bold, italic, true)
                is Link -> out.add(
                    MdInline.Link(
                        text = textOf(node, inlineMath),
                        url = expandInlineMathMarkers(node.destination.orEmpty(), inlineMath),
                    ),
                )
                is Image -> out.add(
                    MdInline.Image(
                        url = expandInlineMathMarkers(node.destination.orEmpty(), inlineMath),
                        alt = textOf(node, inlineMath),
                    ),
                )
                is SoftLineBreak -> out.add(MdInline.SoftBreak)
                is HardLineBreak -> out.add(MdInline.HardBreak)
                else -> collectInlines(node.firstChild, out, inlineMath, bold, italic, strike)
            }
            node = node.next
        }
    }

    /**
     * CommonMark 会把 `\(` 当成转义后的普通括号，也可能继续解释公式里的 `_`、`*`。
     * 因此先在源码层把已闭合的行内公式替换为私有占位符，AST 生成后再恢复成 [MdInline.Math]。
     * 未闭合公式、代码围栏和行内代码保持原文，适配逐 token 重解析。
     */
    private data class InlineMathToken(val expression: String, val source: String)

    private data class InlineMathContext(
        val markerStart: String,
        val tokens: List<InlineMathToken>,
    )

    private data class PreparedInlineMath(val markdown: String, val inlineMath: InlineMathContext)

    private fun extractInlineMath(src: String): PreparedInlineMath {
        val tokens = ArrayList<InlineMathToken>()
        val out = StringBuilder(src.length)
        val markerStart = inlineMathMarkerStartFor(src)
        val protectedRanges = markdownCodeRanges(src)
        var protectedIndex = 0
        var i = 0

        fun appendMath(expr: String, source: String) {
            val index = tokens.size
            tokens += InlineMathToken(expr.trim(), source)
            out.append(markerStart).append(index).append(INLINE_MATH_MARKER_END)
        }

        while (i < src.length) {
            while (protectedIndex < protectedRanges.size && i >= protectedRanges[protectedIndex].second) {
                protectedIndex++
            }
            val protected = protectedRanges.getOrNull(protectedIndex)
            if (protected != null && i >= protected.first && i < protected.second) {
                out.append(src, i, protected.second)
                i = protected.second
                continue
            }

            // 与流式 Markdown 项目保持一致：非行首的 `$$...$$` 是高置信行内公式；行首形式已在
            // splitDisplayMath 中作为块级公式消费。只接受完整的双 `$` run，避免误吃 `$$$`。
            if (isDoubleDollarDelimiter(src, i)) {
                val close = findDoubleDollarClose(src, i + 2)
                if (close >= 0) {
                    val expr = src.substring(i + 2, close)
                    if (expr.isNotBlank()) {
                        appendMath(expr, src.substring(i, close + 2))
                        i = close + 2
                        continue
                    }
                }
            }

            // GitHub 的高置信行内公式写法：$`...`$。它允许公式中安全出现 Markdown 元字符和 `$`。
            if (src.startsWith("$`", i) && !isEscaped(src, i)) {
                val close = findInlineSequenceClose(src, i + 2, "`$")
                if (close >= 0) {
                    val expr = src.substring(i + 2, close)
                    if (expr.isNotBlank()) {
                        appendMath(expr, src.substring(i, close + 2))
                        i = close + 2
                        continue
                    }
                }
            }

            if (src.startsWith("\\(", i) && !isEscaped(src, i)) {
                val close = findInlineSequenceClose(src, i + 2, "\\)")
                if (close >= 0) {
                    val expr = src.substring(i + 2, close)
                    if (expr.isNotBlank()) {
                        appendMath(expr, src.substring(i, close + 2))
                        i = close + 2
                        continue
                    }
                }
            }

            if (isSingleDollarDelimiter(src, i)) {
                val close = findSingleDollarClose(src, i + 1)
                if (close >= 0) {
                    val expr = src.substring(i + 1, close)
                    if (isLikelySingleDollarMath(expr, src.getOrNull(close + 1))) {
                        appendMath(expr, src.substring(i, close + 1))
                    } else {
                        // 把被判定为货币/普通文本的一整对作为字面量消费，避免与后续 `$` 交叉配对。
                        out.append(src, i, close + 1)
                    }
                    i = close + 1
                    continue
                }
            }

            out.append(src[i])
            i++
        }
        return PreparedInlineMath(
            markdown = out.toString(),
            inlineMath = InlineMathContext(markerStart, tokens),
        )
    }

    private fun appendTextAndInlineMath(
        text: String,
        inlineMath: InlineMathContext,
        bold: Boolean,
        italic: Boolean,
        strike: Boolean,
        out: MutableList<MdInline>,
    ) {
        var emittedCursor = 0
        var searchCursor = 0
        while (searchCursor < text.length) {
            val markerStart = text.indexOf(inlineMath.markerStart, searchCursor)
            if (markerStart < 0) break
            val indexStart = markerStart + inlineMath.markerStart.length
            val markerEnd = text.indexOf(INLINE_MATH_MARKER_END, indexStart)
            if (markerEnd < 0) break
            val index = text.substring(indexStart, markerEnd).toIntOrNull()
            val token = index?.let(inlineMath.tokens::getOrNull)
            if (token == null) {
                searchCursor = markerEnd + INLINE_MATH_MARKER_END.length
                continue
            }
            if (markerStart > emittedCursor) {
                out.add(MdInline.Text(text.substring(emittedCursor, markerStart), bold, italic, strike))
            }
            out.add(MdInline.Math(token.expression))
            emittedCursor = markerEnd + INLINE_MATH_MARKER_END.length
            searchCursor = emittedCursor
        }
        if (emittedCursor < text.length) {
            out.add(MdInline.Text(text.substring(emittedCursor), bold, italic, strike))
        }
    }

    private fun inlineMathMarkerStartFor(src: String): String {
        var marker = INLINE_MATH_MARKER_BASE
        while (src.contains(marker)) marker += 'M'
        return marker
    }

    private fun findInlineSequenceClose(src: String, from: Int, close: String): Int {
        var i = from
        while (i + close.length <= src.length) {
            if (src[i] == '\n') return -1
            if (src.startsWith(close, i) && !isEscaped(src, i)) return i
            i++
        }
        return -1
    }

    private fun isSingleDollarDelimiter(src: String, index: Int): Boolean =
        src.getOrNull(index) == '$' &&
            src.getOrNull(index - 1) != '$' &&
            src.getOrNull(index + 1) != '$' &&
            !isEscaped(src, index)

    private fun isDoubleDollarDelimiter(src: String, index: Int): Boolean =
        src.getOrNull(index) == '$' &&
            src.getOrNull(index + 1) == '$' &&
            src.getOrNull(index - 1) != '$' &&
            src.getOrNull(index + 2) != '$' &&
            !isEscaped(src, index)

    private fun findSingleDollarClose(src: String, from: Int): Int {
        var i = from
        while (i < src.length && src[i] != '\n') {
            if (isSingleDollarDelimiter(src, i)) return i
            i++
        }
        return -1
    }

    private fun findDoubleDollarClose(src: String, from: Int): Int {
        var i = from
        while (i < src.length && src[i] != '\n') {
            if (isDoubleDollarDelimiter(src, i)) return i
            i++
        }
        return -1
    }

    /**
     * 单 `$` 与货币、Shell 变量冲突最多。明确的 LaTeX 命令/运算符直接接受；纯数字金额、
     * 带自然语言的多单词片段以及 `$5 and $10` 这类跨金额配对拒绝。更明确的 `\(...\)` 不受此限制。
     */
    private fun isLikelySingleDollarMath(expr: String, charAfterClose: Char?): Boolean {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.all { it.isDigit() || it.isWhitespace() || it in ".,%，．" }) return false
        if (trimmed.first().isDigit() && charAfterClose?.isDigit() == true) return false
        if (trimmed.last() in "+-*/" && charAfterClose?.isLetterOrDigit() == true) return false

        val hasStrongMathSignal = trimmed.any {
            it == '\\' || it in "^_=+-*/<>±×÷≈≠≤≥∑∏∫√∞{}"
        }
        if (hasStrongMathSignal) return true
        if (expr != trimmed) return false

        val words = Regex("""[\p{L}]+""").findAll(trimmed).map { it.value }.toList()
        if (words.size > 1 && words.any { it.length > 1 }) return false

        return trimmed.none(Char::isWhitespace) || words.all { it.length == 1 }
    }

    private fun isEscaped(src: String, index: Int): Boolean {
        var slashes = 0
        var i = index - 1
        while (i >= 0 && src[i] == '\\') {
            slashes++
            i--
        }
        return slashes % 2 == 1
    }

    private fun delimiterRunLength(src: String, from: Int, delimiter: Char): Int {
        var end = from
        while (end < src.length && src[end] == delimiter) end++
        return end - from
    }

    private fun findMatchingRun(src: String, from: Int, delimiter: Char, length: Int): Int {
        var i = from
        while (i < src.length) {
            if (src[i] != delimiter) {
                i++
                continue
            }
            val run = delimiterRunLength(src, i, delimiter)
            if (run == length) return i
            i += run
        }
        return -1
    }

    private fun textOf(node: Node, inlineMath: InlineMathContext): String = buildString {
        var c = node.firstChild
        while (c != null) {
            if (c is Text) {
                append(expandInlineMathMarkers(c.literal, inlineMath))
            } else {
                append(textOf(c, inlineMath))
            }
            c = c.next
        }
    }

    private fun expandInlineMathMarkers(text: String, inlineMath: InlineMathContext): String {
        var expanded = text
        inlineMath.tokens.forEachIndexed { index, token ->
            expanded = expanded.replace(
                "${inlineMath.markerStart}$index$INLINE_MATH_MARKER_END",
                token.source,
            )
        }
        return expanded
    }

    // —— fence 感知的块级公式分段 ——
    private data class Seg(val isMath: Boolean, val text: String)

    private fun splitDisplayMath(src: String): List<Seg> {
        val protected = markdownCodeRanges(src)
        fun isProtected(idx: Int): Boolean = protected.any { idx >= it.first && idx < it.second }

        val segs = ArrayList<Seg>()
        val sb = StringBuilder()
        fun flushText() {
            if (sb.isNotEmpty()) {
                segs.add(Seg(false, sb.toString()))
                sb.setLength(0)
            }
        }

        var i = 0
        while (i < src.length) {
            if (
                isDoubleDollarDelimiter(src, i) &&
                !isProtected(i) &&
                hasOnlyIndentBefore(src, i)
            ) {
                val close = findDisplayClose(src, i + 2) { idx -> !isProtected(idx) }
                if (close >= 0) {
                    flushText()
                    segs.add(Seg(true, src.substring(i + 2, close)))
                    i = close + 2
                    continue
                }
            }
            if (
                i + 1 < src.length &&
                src[i] == '\\' &&
                src[i + 1] == '[' &&
                !isProtected(i) &&
                !isEscaped(src, i)
            ) {
                val close = findBracketDisplayClose(src, i + 2) { idx -> !isProtected(idx) }
                if (close >= 0) {
                    flushText()
                    segs.add(Seg(true, src.substring(i + 2, close)))
                    i = close + 2
                    continue
                }
            }

            val environment = displayEnvironmentAt(src, i)
            if (environment != null && !isProtected(i)) {
                val closeToken = "\\end{${environment.name}}"
                val close = findUnescapedToken(src, environment.contentStart, closeToken) { idx -> !isProtected(idx) }
                if (close >= 0) {
                    flushText()
                    val end = close + closeToken.length
                    segs.add(Seg(true, src.substring(i, end)))
                    i = end
                    continue
                }
            }

            sb.append(src[i])
            i++
        }
        flushText()
        return segs
    }

    private inline fun findDisplayClose(src: String, from: Int, allowed: (Int) -> Boolean): Int {
        var j = from
        while (j + 1 < src.length) {
            if (isDoubleDollarDelimiter(src, j) && allowed(j)) return j
            j++
        }
        return -1
    }

    /** 找块级 `\]` 闭合（返回反斜杠下标）；未闭合返回 -1（流式安全）。 */
    private inline fun findBracketDisplayClose(src: String, from: Int, allowed: (Int) -> Boolean): Int {
        var j = from
        while (j + 1 < src.length) {
            if (src[j] == '\\' && src[j + 1] == ']' && allowed(j) && !isEscaped(src, j)) return j
            j++
        }
        return -1
    }

    private data class DisplayEnvironment(val name: String, val contentStart: Int)

    private fun displayEnvironmentAt(src: String, index: Int): DisplayEnvironment? {
        if (!src.startsWith("\\begin{", index) || isEscaped(src, index) || !hasOnlyIndentBefore(src, index)) {
            return null
        }
        val nameStart = index + "\\begin{".length
        val nameEnd = src.indexOf('}', nameStart)
        if (nameEnd < 0 || nameEnd - nameStart > MAX_ENVIRONMENT_NAME_LENGTH) return null
        val name = src.substring(nameStart, nameEnd)
        if (name.lowercase() !in DISPLAY_MATH_ENVIRONMENTS) return null
        return DisplayEnvironment(name, nameEnd + 1)
    }

    private fun hasOnlyIndentBefore(src: String, index: Int): Boolean {
        val lineStart = src.lastIndexOf('\n', index - 1).let { if (it < 0) 0 else it + 1 }
        return src.substring(lineStart, index).all { it == ' ' || it == '\t' }
    }

    private inline fun findUnescapedToken(
        src: String,
        from: Int,
        token: String,
        allowed: (Int) -> Boolean,
    ): Int {
        var i = from
        while (i + token.length <= src.length) {
            if (src.startsWith(token, i) && allowed(i) && !isEscaped(src, i)) return i
            i++
        }
        return -1
    }

    /** 代码围栏的字符区间（含围栏行）。未闭合围栏保护到末尾——适配流式逐字到达。 */
    private fun fenceRanges(src: String): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        var pos = 0
        var openFence: OpenFence? = null
        for (line in src.split("\n")) {
            val lineStart = pos
            val lineEnd = pos + line.length
            val marker = fenceMarker(line)
            if (openFence == null && marker != null) {
                openFence = OpenFence(marker.character, marker.length, lineStart)
            } else if (
                openFence != null &&
                marker != null &&
                marker.character == openFence.character &&
                marker.length >= openFence.length &&
                marker.trailing.isBlank()
            ) {
                ranges.add(openFence.start to minOf(lineEnd + 1, src.length))
                openFence = null
            }
            pos = lineEnd + 1 // +1 for the '\n'
        }
        openFence?.let { ranges.add(it.start to src.length) }
        return ranges
    }

    /** fenced code 与行内反引号代码的统一保护区；未闭合代码保护到当前流式文本末尾。 */
    private fun markdownCodeRanges(src: String): List<Pair<Int, Int>> {
        val fences = fenceRanges(src)
        val ranges = ArrayList<Pair<Int, Int>>(fences.size + 4).apply { addAll(fences) }
        var fenceIndex = 0
        var i = 0
        while (i < src.length) {
            while (fenceIndex < fences.size && i >= fences[fenceIndex].second) fenceIndex++
            val fence = fences.getOrNull(fenceIndex)
            if (fence != null && i >= fence.first && i < fence.second) {
                i = fence.second
                continue
            }
            if (src[i] != '`') {
                i++
                continue
            }
            val runLength = delimiterRunLength(src, i, '`')
            val close = findMatchingRun(src, i + runLength, '`', runLength)
            val end = if (close < 0) src.length else close + runLength
            ranges.add(i to end)
            i = end
        }
        return ranges.sortedBy(Pair<Int, Int>::first)
    }

    private data class FenceMarker(val character: Char, val length: Int, val trailing: String)
    private data class OpenFence(val character: Char, val length: Int, val start: Int)

    private fun fenceMarker(line: String): FenceMarker? {
        val indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        if (indent > 3 || indent >= line.length) return null
        val character = line[indent]
        if (character != '`' && character != '~') return null
        val length = delimiterRunLength(line, indent, character)
        if (length < 3) return null
        return FenceMarker(character, length, line.substring(indent + length))
    }

    private val MATH_FENCE_LANGUAGES = setOf("math", "latex", "tex", "katex")
    private val DISPLAY_MATH_ENVIRONMENTS = setOf(
        "math",
        "displaymath",
        "equation",
        "equation*",
        "eqnarray",
        "eqnarray*",
        "align",
        "align*",
        "flalign",
        "flalign*",
        "aligned",
        "alignedat",
        "gather",
        "gather*",
        "gathered",
        "multline",
        "multline*",
        "split",
        "cases",
        "matrix",
        "smallmatrix",
        "pmatrix",
        "bmatrix",
        "vmatrix",
        "array",
    )

    private const val INLINE_MATH_MARKER_BASE = "\uE000M"
    private const val INLINE_MATH_MARKER_END = "\uE001"
    private const val MAX_ENVIRONMENT_NAME_LENGTH = 32
}
