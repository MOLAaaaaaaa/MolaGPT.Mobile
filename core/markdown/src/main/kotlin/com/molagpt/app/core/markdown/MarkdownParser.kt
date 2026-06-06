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
 *  1. fence 感知地把块级 `$$ ... $$` 抽成 [MdBlock.MathBlock]（未闭合代码围栏保护到末尾，
 *     避免流式中代码里的 `$` 被误判为公式）；
 *  2. 其余段交给 commonmark；代码围栏 → Code/Mermaid，其余 → 段落/标题/列表/引用；
 *  3. 段落/标题/列表项的行内文本再拆出行内 `$...$` 公式与强调样式。
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
                parseCommonmark(seg.text, out)
            }
        }
        return out
    }

    private fun stripHiddenContext(input: String): String =
        input
            .replace(Regex("""✝[^✝]*✝"""), "")
            .replace(Regex("""†[^†]*†"""), "")
            .replace(Regex("""⟦MEM[:：][\s\S]*?⟧"""), "")

    private fun parseCommonmark(md: String, out: MutableList<MdBlock>) {
        val doc = parser.parse(md) as Document
        var node = doc.firstChild
        while (node != null) {
            blockOf(node)?.let(out::add)
            node = node.next
        }
    }

    private fun blockOf(node: Node): MdBlock? = when (node) {
        is Heading -> MdBlock.Heading(node.level, inlinesOf(node))
        is Paragraph -> MdBlock.Paragraph(inlinesOf(node))
        is ThematicBreak -> MdBlock.Divider
        is FencedCodeBlock -> {
            val info = node.info?.trim()?.lowercase().orEmpty()
            if (info == "mermaid") MdBlock.Mermaid(node.literal.trimEnd())
            else MdBlock.Code(node.info?.trim()?.ifBlank { null }, node.literal.trimEnd())
        }
        is IndentedCodeBlock -> MdBlock.Code(null, node.literal.trimEnd())
        is BlockQuote -> MdBlock.Quote(buildList { var c = node.firstChild; while (c != null) { blockOf(c)?.let(::add); c = c.next } })
        is BulletList -> MdBlock.BulletList(listItems(node))
        is OrderedList -> MdBlock.OrderedList(1, listItems(node))
        is TableBlock -> tableOf(node)
        else -> null
    }

    private fun tableOf(table: TableBlock): MdBlock.Table {
        var header: List<List<MdInline>> = emptyList()
        val rows = mutableListOf<List<List<MdInline>>>()
        var child = table.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> header = tableRows(child).firstOrNull().orEmpty()
                is TableBody -> rows += tableRows(child)
            }
            child = child.next
        }
        return MdBlock.Table(header = header, rows = rows)
    }

    private fun tableRows(section: Node): List<List<List<MdInline>>> = buildList {
        var row = section.firstChild
        while (row != null) {
            if (row is TableRow) {
                add(tableCells(row))
            }
            row = row.next
        }
    }

    private fun tableCells(row: TableRow): List<List<MdInline>> = buildList {
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) {
                add(inlinesOf(cell))
            }
            cell = cell.next
        }
    }

    private fun listItems(list: Node): List<List<MdInline>> = buildList {
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val inlines = ArrayList<MdInline>()
                var c = item.firstChild
                while (c != null) {
                    if (c is Paragraph) inlines.addAll(inlinesOf(c))
                    c = c.next
                }
                add(inlines)
            }
            item = item.next
        }
    }

    private fun inlinesOf(parent: Node): List<MdInline> {
        val out = ArrayList<MdInline>()
        collectInlines(parent.firstChild, out, bold = false, italic = false, strike = false)
        return out
    }

    private fun collectInlines(start: Node?, out: MutableList<MdInline>, bold: Boolean, italic: Boolean, strike: Boolean) {
        var node = start
        while (node != null) {
            when (node) {
                is Text -> splitInlineMath(node.literal, bold, italic, strike, out)
                is Code -> out.add(MdInline.Code(node.literal))
                is Emphasis -> collectInlines(node.firstChild, out, bold, true, strike)
                is StrongEmphasis -> collectInlines(node.firstChild, out, true, italic, strike)
                is Strikethrough -> collectInlines(node.firstChild, out, bold, italic, true)
                is Link -> out.add(MdInline.Link(textOf(node), node.destination ?: ""))
                is Image -> out.add(MdInline.Image(node.destination ?: "", textOf(node)))
                is SoftLineBreak -> out.add(MdInline.SoftBreak)
                is HardLineBreak -> out.add(MdInline.HardBreak)
                else -> collectInlines(node.firstChild, out, bold, italic, strike)
            }
            node = node.next
        }
    }

    /** 把一段纯文本里的行内 `$...$`（非 `$$`、不跨行）拆成 [MdInline.Math]。 */
    private fun splitInlineMath(text: String, bold: Boolean, italic: Boolean, strike: Boolean, out: MutableList<MdInline>) {
        var i = 0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(MdInline.Text(buf.toString(), bold, italic, strike))
                buf.setLength(0)
            }
        }
        while (i < text.length) {
            val c = text[i]
            // 行内 $...$（非 $$，不跨行）
            if (c == '$' && (i + 1 >= text.length || text[i + 1] != '$')) {
                val end = findInlineMathEnd(text, i + 1)
                if (end > i + 1) {
                    flush()
                    out.add(MdInline.Math(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }
            // 行内 \( ... \)（KaTeX 风格，不跨行）。
            if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
                val end = findParenMathEnd(text, i + 2)
                if (end >= i + 2) {
                    flush()
                    out.add(MdInline.Math(text.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }
            buf.append(c)
            i++
        }
        flush()
    }

    private fun findInlineMathEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            val c = text[j]
            if (c == '\n') return -1
            if (c == '$') return j
            j++
        }
        return -1
    }

    /** 找行内 `\)` 闭合（返回反斜杠下标）；不跨行，未闭合返回 -1（流式安全，不提前渲染）。 */
    private fun findParenMathEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            val c = text[j]
            if (c == '\n') return -1
            if (c == '\\' && j + 1 < text.length && text[j + 1] == ')') return j
            j++
        }
        return -1
    }

    private fun textOf(node: Node): String = buildString {
        var c = node.firstChild
        while (c != null) {
            if (c is Text) append(c.literal) else append(textOf(c))
            c = c.next
        }
    }

    // —— fence 感知的块级 $$ 分段 ——
    private data class Seg(val isMath: Boolean, val text: String)

    private fun splitDisplayMath(src: String): List<Seg> {
        val protected = fenceRanges(src)
        fun isProtected(idx: Int): Boolean = protected.any { idx >= it.first && idx < it.second }

        val segs = ArrayList<Seg>()
        val sb = StringBuilder()
        var i = 0
        while (i < src.length) {
            if (i + 1 < src.length && src[i] == '$' && src[i + 1] == '$' && !isProtected(i)) {
                val close = findDisplayClose(src, i + 2) { idx -> !isProtected(idx) }
                if (close >= 0) {
                    if (sb.isNotEmpty()) { segs.add(Seg(false, sb.toString())); sb.setLength(0) }
                    segs.add(Seg(true, src.substring(i + 2, close)))
                    i = close + 2
                    continue
                }
            }
            // 块级 \[ ... \]（KaTeX display）。未闭合时不抽取，留给后续流式补全。
            if (i + 1 < src.length && src[i] == '\\' && src[i + 1] == '[' && !isProtected(i)) {
                val close = findBracketDisplayClose(src, i + 2) { idx -> !isProtected(idx) }
                if (close >= 0) {
                    if (sb.isNotEmpty()) { segs.add(Seg(false, sb.toString())); sb.setLength(0) }
                    segs.add(Seg(true, src.substring(i + 2, close)))
                    i = close + 2
                    continue
                }
            }
            sb.append(src[i]); i++
        }
        if (sb.isNotEmpty()) segs.add(Seg(false, sb.toString()))
        return segs
    }

    private inline fun findDisplayClose(src: String, from: Int, allowed: (Int) -> Boolean): Int {
        var j = from
        while (j + 1 < src.length) {
            if (src[j] == '$' && src[j + 1] == '$' && allowed(j)) return j
            j++
        }
        return -1
    }

    /** 找块级 `\]` 闭合（返回反斜杠下标）；未闭合返回 -1（流式安全）。 */
    private inline fun findBracketDisplayClose(src: String, from: Int, allowed: (Int) -> Boolean): Int {
        var j = from
        while (j + 1 < src.length) {
            if (src[j] == '\\' && src[j + 1] == ']' && allowed(j)) return j
            j++
        }
        return -1
    }

    /** 代码围栏的字符区间（含围栏行）。未闭合围栏保护到末尾——适配流式逐字到达。 */
    private fun fenceRanges(src: String): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        var pos = 0
        var inFence = false
        var startPos = 0
        for (line in src.split("\n")) {
            val lineStart = pos
            val lineEnd = pos + line.length
            val t = line.trimStart()
            if (t.startsWith("```") || t.startsWith("~~~")) {
                if (!inFence) { inFence = true; startPos = lineStart }
                else { inFence = false; ranges.add(startPos to lineEnd) }
            }
            pos = lineEnd + 1 // +1 for the '\n'
        }
        if (inFence) ranges.add(startPos to src.length)
        return ranges
    }
}
