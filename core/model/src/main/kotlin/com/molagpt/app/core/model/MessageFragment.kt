package com.molagpt.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 单条消息的内容片段。**不要用一个大 String 表示整条回答**——回答由若干 fragment 组成，
 * 流式过程中只对发生变化的 fragment 做局部更新，Compose 用稳定 [id] 做 key 仅重组变化项。
 *
 * 整个列表会被 kotlinx.serialization 序列化进 Room 的 `fragmentsJson` 字段，因此密封接口
 * 与各子类型都标注 [Serializable]，以 "type" 作为多态判别字段。
 */
@Serializable
sealed interface MessageFragment {
    /** 稳定且唯一的片段 id（LazyColumn / 重组 key）。流式期间同一片段的 id 必须保持不变。 */
    val id: String

    /** 普通正文（Markdown 源串，渲染期由 :core:markdown 后台解析）。 */
    @Serializable
    @SerialName("text")
    data class Text(override val id: String, val markdown: String) : MessageFragment

    /** 推理/思考过程（reasoning_content；默认折叠）。纯文本，不内嵌工具。
     * 工具调用作为独立顶层 [ToolCall] fragment 与思考块按到达顺序交错排列。 */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        override val id: String,
        val text: String,
        val collapsed: Boolean = true,
        val durationMs: Long? = null,
    ) : MessageFragment

    /** 代码块（带语言标识，独立渲染 + 复制 + 高亮）。 */
    @Serializable
    @SerialName("code")
    data class CodeBlock(
        override val id: String,
        val language: String? = null,
        val code: String,
    ) : MessageFragment

    /** LaTeX 公式（行内 display=false / 独立 display=true）；由 JLaTeXMath 原生 Canvas 渲染。 */
    @Serializable
    @SerialName("latex")
    data class Latex(
        override val id: String,
        val expr: String,
        val display: Boolean = true,
    ) : MessageFragment

    /** Mermaid 图（由 :feature:webview 离线 mermaid.js 渲染）。 */
    @Serializable
    @SerialName("mermaid")
    data class Mermaid(override val id: String, val source: String) : MessageFragment

    /** 联网搜索片段（搜索词 chip + 引用来源）。 */
    @Serializable
    @SerialName("search")
    data class SearchResult(
        override val id: String,
        val query: String,
        val refs: List<SourceReference> = emptyList(),
        val status: SearchStatus = SearchStatus.DONE,
    ) : MessageFragment

    /** 工具调用片段（联网/浏览器/代码执行/连接器）。 */
    @Serializable
    @SerialName("tool")
    data class ToolCall(
        override val id: String,
        val name: String,
        val status: ToolStatus,
        val label: String? = null,
        val argsJson: String? = null,
        val resultPreview: String? = null,
        val provider: String? = null,
    ) : MessageFragment

    /** 文件卡片（上传/生成的文件）。 */
    @Serializable
    @SerialName("file")
    data class FileCard(override val id: String, val file: FileInfo) : MessageFragment

    /** 生成/返回的图片。 */
    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String,
        val url: String,
        val prompt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : MessageFragment

    /** 行内提示（如自动路由选择了哪个模型）。 */
    @Serializable
    @SerialName("tip")
    data class Tip(override val id: String, val text: String) : MessageFragment

    /** 错误片段（流中错误、被拦截等）。 */
    @Serializable
    @SerialName("error")
    data class Error(override val id: String, val message: String) : MessageFragment
}

enum class ToolStatus { RUNNING, SUCCESS, FAILED }

enum class SearchStatus { SEARCHING, DONE, FAILED }
