package com.molagpt.app.feature.webview

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 受控 WebView 封装（feature.webview 是 WebView 的**唯一**落点，不污染聊天 UI）。
 * 仅用于：Mermaid 渲染、HTML 预览、搜索结果网页、登录/验证码、外链。
 * 默认禁用文件访问与通用 JS 注入风险面，按需通过 [configure] 收紧/放开。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MolaWebView(
    modifier: Modifier = Modifier,
    javaScriptEnabled: Boolean = true,
    onCreated: (WebView) -> Unit = {},
    configure: (WebView) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    this.javaScriptEnabled = javaScriptEnabled
                    domStorageEnabled = true
                    // 安全默认：禁止访问本地文件与内容 URI。
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    builtInZoomControls = false
                    setSupportZoom(false)
                }
                onCreated(this)
            }
        },
        update = { webView -> configure(webView) },
    )
}
