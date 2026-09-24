package com.molagpt.app.core.render.visual

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** 要在全屏里运行的一段 HTML。 */
@Immutable
data class HtmlRunRequest(
    val source: String,
    val title: String,
    /** 首行声明的文件名；同名页面共用一份 localStorage。 */
    val fileName: String?,
)

/**
 * 内嵌组件和网页卡片需要宿主页面帮忙的事：全屏展示、运行网页、记住每处卡片 / 代码的切换。
 *
 * 由对话页提供。:core:render 不依赖 WebView 所在的 :feature:webview，运行网页交给宿主；
 * 没有宿主的地方（其它页面复用 Markdown 渲染时）全屏和「运行」按钮不出现。
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
}

val LocalVisualHost = staticCompositionLocalOf<VisualHost?> { null }
