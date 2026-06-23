package com.molagpt.app.core.markdown

/**
 * 中性 Markdown 模型（不依赖 Compose）。:core:render 把它映射成 Compose 组件，
 * 从而 markdown 解析层可在后台线程跑、可单测、且与 UI 解耦。
 *
 * 解析时把**代码块 / Mermaid / 块级 LaTeX** 抽成独立块，便于 render 层分发到
 * 高亮组件 / WebView / JLaTeXMath。普通文本块携带行内片段列表。
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    /** 列表项现在支持嵌套块（段落 + 子列表）。 */
    data class BulletList(val items: List<ListItemContent>) : MdBlock
    data class OrderedList(val start: Int, val items: List<ListItemContent>) : MdBlock
    data class Table(
        val header: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Mermaid(val source: String) : MdBlock
    /** 块级公式（$$ ... $$）。 */
    data class MathBlock(val expr: String) : MdBlock
    data object Divider : MdBlock
}

/** 列表项内容：可包含多个块（段落、子列表等）。 */
data class ListItemContent(val blocks: List<MdBlock>)

/** 行内片段。普通文本携带强调样式；行内公式（$...$）与行内代码单列。 */
sealed interface MdInline {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdInline

    data class Code(val text: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
    /** 行内图片（Markdown `![alt](url)`；生成图片也走此路径，url 含 =imgtemp）。 */
    data class Image(val url: String, val alt: String = "") : MdInline
    data class Math(val expr: String) : MdInline
    data object SoftBreak : MdInline
    data object HardBreak : MdInline
}
