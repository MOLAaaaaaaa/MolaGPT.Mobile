package com.molagpt.app.feature.webview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 受控 HTML 预览。调用方应在传入前用服务端 DOMPurify 或本地清洗确保安全
 * （MolaGPT 后端已对 HTML 走 DOMPurify）。此处默认禁用文件/内容访问。
 */
@Composable
fun HtmlPreview(html: String, modifier: Modifier = Modifier, heightDp: Int = 320) {
    MolaWebView(
        modifier = modifier.fillMaxWidth().height(heightDp.dp),
        configure = { web ->
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )
}
