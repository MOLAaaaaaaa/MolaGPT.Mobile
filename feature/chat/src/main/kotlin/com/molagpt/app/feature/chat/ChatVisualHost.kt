package com.molagpt.app.feature.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molagpt.app.core.render.visual.HtmlRunRequest
import com.molagpt.app.core.render.visual.VisualHost
import com.molagpt.app.feature.webview.HtmlRunnerWarmup
import java.security.MessageDigest

/** 对话页上方盖着的全屏层：运行中的网页，或放大查看的组件。 */
internal sealed interface VisualOverlay {
    class Html(val request: HtmlRunRequest, val originKey: String) : VisualOverlay
    class Fullscreen(val content: @Composable (close: () -> Unit) -> Unit) : VisualOverlay
}

/**
 * 对话页给内嵌组件和网页卡片的宿主：全屏层、运行网页、每处网页「卡片 / 代码」的单独切换。
 *
 * 切换记在这里而不是列表项里：一条回答整段是一个列表项，滚出屏幕就被销毁，记在项里的
 * 状态回来就没了。
 */
@Stable
internal class ChatVisualHost(
    private val context: Context,
    private val htmlAsCardSetting: State<Boolean>,
) : VisualHost {
    private val forms = mutableStateMapOf<String, Boolean>()

    var overlay by mutableStateOf<VisualOverlay?>(null)
        private set

    /** 当前会话 id：同一会话里同名的网页共用一份 localStorage，改版后数据还在。 */
    var sessionId: String = ""

    override val htmlAsCard: Boolean get() = htmlAsCardSetting.value

    override fun htmlForm(key: String): Boolean? = forms[key]

    override fun setHtmlForm(key: String, card: Boolean) {
        // 切回与设置一致的形态就忘掉：之后这一处继续跟随设置。
        if (card == htmlAsCard) forms.remove(key) else forms[key] = card
    }

    override fun runHtml(request: HtmlRunRequest) {
        overlay = VisualOverlay.Html(request, originKey(request))
    }

    override fun prepareHtml() = HtmlRunnerWarmup.prepare(context)

    override fun openFullscreen(content: @Composable (close: () -> Unit) -> Unit) {
        overlay = VisualOverlay.Fullscreen(content)
    }

    fun close() {
        overlay = null
    }

    /** 没声明文件名的网页按内容区分：两段不同的无名页面不该共用存储。 */
    private fun originKey(request: HtmlRunRequest): String {
        val identity = request.fileName?.lowercase() ?: ("#" + digest(request.source))
        return digest("$sessionId|$identity").take(16)
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
