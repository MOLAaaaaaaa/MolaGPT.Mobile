package com.molagpt.app.core.markdown

/**
 * 中性 Markdown 模型（不依赖 Compose）。:core:render 把它映射成 Compose 组件，
 * 从而 markdown 解析层可在后台线程跑、可单测、且与 UI 解耦。
 *
 * 解析时把**代码块 / Mermaid / mola-ui 组件 / 块级 LaTeX** 抽成独立块，便于 render 层分发到
 * 高亮组件 / 原生可视化组件 / JLaTeXMath。普通文本块携带行内片段列表。
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
        val alignments: List<MdTableAlignment> = emptyList(),
    ) : MdBlock
    /** [closed]：围栏已写出结束标记。流式中还没写完的代码块为 false。 */
    data class Code(val language: String?, val code: String, val closed: Boolean = true) : MdBlock
    data class Mermaid(val source: String) : MdBlock

    /**
     * `mola-ui` 围栏：内嵌组件（函数图像、图表、数据表、指标卡、条目卡）。
     *
     * [parsed] 在解析线程上就算好（JSON + 表达式编译），并按源码缓存：流式期间整段
     * 回答每来一批就重解析一次，已写完的组件拿回的是同一个实例，界面不会重建。
     */
    data class MolaUi(
        val source: String,
        val closed: Boolean,
        val parsed: com.molagpt.app.core.markdown.visual.MolaUiParsed,
    ) : MdBlock
    /** 块级公式（`$$...$$`、`\[...\]`、math fence 或常见数学 environment）。 */
    data class MathBlock(val expr: String) : MdBlock
    data object Divider : MdBlock
}

enum class MdTableAlignment { DEFAULT, LEFT, CENTER, RIGHT }

/** 列表项内容：可包含多个块（段落、子列表等）。 */
data class ListItemContent(val blocks: List<MdBlock>)

/** 行内片段。普通文本携带强调样式；行内公式（`$...$` / `$$...$$` / `$`…``$` / `\(...\)`）与行内代码单列。 */
sealed interface MdInline {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdInline

    data class Code(val text: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline

    /**
     * 联网回答里的引用角标（正文中的 `<ref source="1,3" />`）。
     *
     * 只带编号，不带标题与链接：编号对应的来源随消息单独下发（SSE 的 `molagpt_sources`），
     * 且往往比正文晚到。把解析结果留成编号，渲染层再按当时手上的来源表去解，
     * 来源补齐后同一份 block 不必重解析。
     */
    data class Citation(val ids: List<Int>) : MdInline
    /** 行内图片（Markdown `![alt](url)`；生成图片也走此路径，url 含 =imgtemp）。 */
    data class Image(val url: String, val alt: String = "") : MdInline
    data class Math(val expr: String) : MdInline
    data object SoftBreak : MdInline
    data object HardBreak : MdInline
}
