package com.molagpt.app.core.render.visual

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 要在全屏里运行的一段 HTML。 */
@Immutable
data class HtmlRunRequest(
    val source: String,
    val title: String,
    /** 首行声明的文件名；同名页面共用一份 localStorage。 */
    val fileName: String?,
)

/** Mermaid 内嵌画布的渲染状态，供外壳决定高度、加载态与报错降级。 */
@Immutable
sealed interface MermaidRenderState {
    data object Loading : MermaidRenderState
    data class Ready(val heightDp: Int) : MermaidRenderState
    data class Failed(val reason: String? = null) : MermaidRenderState
}

/**
 * 内嵌组件、Mermaid 图表和网页卡片需要宿主页面帮忙的事：全屏展示、运行网页、
 * 渲染 Mermaid、记住每处卡片 / 代码（或图表 / 代码）的切换。
 *
 * 由对话页提供。:core:render 不依赖 WebView 所在的 :feature:webview，运行网页与 Mermaid 渲染
 * 交给宿主；没有宿主的地方（如全屏选字阅读层、其它只读页）自动退回纯代码展示。
 */
interface VisualHost {
    /** 设置「网页以卡片显示」。实现应由快照状态支撑，读它的地方会随设置重组。 */
    val htmlAsCard: Boolean

    /** 某处网页被单独切换过的形态；null 表示跟随设置。 */
    fun htmlForm(key: String): Boolean?

    /** 记下某处的形态。切回与设置一致时应当忘掉它，之后继续跟随设置。 */
    fun setHtmlForm(key: String, card: Boolean)

    fun runHtml(request: HtmlRunRequest)

    /** 按下「运行」时先把网页引擎准备好，抬手时它已经在了。 */
    fun prepareHtml() {}

    /** 全屏展示一个组件；[content] 收到关闭回调。 */
    fun openFullscreen(content: @Composable (close: () -> Unit) -> Unit)

    /** 宿主是否提供 Mermaid 图表渲染器。 */
    val supportsMermaid: Boolean get() = false

    /** 某处 Mermaid 被用户切过的形态（true=图表，false=代码）；null 表示默认图表。 */
    fun mermaidShowDiagram(key: String): Boolean? = null

    /** 记下某处 Mermaid 的形态。切回默认（图表）时忘掉它。 */
    fun setMermaidShowDiagram(key: String, showDiagram: Boolean) {}

    /** 记住某处 Mermaid 上次测得的实际高度（dp），避免列表滚出回收再滚回时高度回弹导致视口跳动。 */
    fun mermaidHeightDp(key: String): Int? = null

    /** 记录某处 Mermaid 渲染成功后的高度（dp）。 */
    fun setMermaidHeightDp(key: String, heightDp: Int) {}

    /** 在沙盒中渲染 Mermaid 图表。仅当 [supportsMermaid] 为 true 时由 [MermaidBlockView] 调用。 */
    @Composable
    fun MermaidCanvas(
        source: String,
        streaming: Boolean,
        interactive: Boolean,
        modifier: Modifier,
        contentPadding: PaddingValues = PaddingValues(0.dp),
        backgroundColor: Color = Color.Unspecified,
        onStateChange: (MermaidRenderState) -> Unit,
    ) {}
}

val LocalVisualHost = staticCompositionLocalOf<VisualHost?> { null }
