package com.molagpt.app.feature.webview

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.molagpt.app.core.render.visual.MermaidRenderState
import com.molagpt.app.core.render.visual.VisualPalette
import com.molagpt.app.core.render.visual.rememberVisualPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * 在隔离沙盒 WebView 中渲染 Mermaid 图表。
 *
 * 复用 [CanvasCdn] 的镜像竞速（jsDelivr / npmmirror / unpkg）与磁盘缓存：
 * 首次加载后 mermaid.min.js 落盘，后续图表离线秒开；流式更新走 `evaluateJavascript` 增量刷新，
 * 避免整页重载闪烁。
 */
@Composable
fun MermaidDiagramView(
    source: String,
    streaming: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    backgroundColor: Color = Color.Unspecified,
    onStateChange: (MermaidRenderState) -> Unit = {},
) {
    val palette = rememberVisualPalette()
    val effectiveBg = backgroundColor.takeOrElse { palette.surface }
    val padTopPx = if (interactive) (contentPadding.calculateTopPadding().value + 16f).toInt() else 12
    val padBottomPx = if (interactive) (contentPadding.calculateBottomPadding().value + 24f).toInt() else 12
    val currentOnStateChange by rememberUpdatedState(onStateChange)
    val currentSource by rememberUpdatedState(source)
    val state = remember(interactive) {
        MermaidWebState(
            shellHtml = MermaidSandbox.buildShellHtml(
                interactive = interactive,
                backgroundHex = cssHex(effectiveBg.toArgb()),
                textHex = cssHex(palette.text.toArgb()),
                padTopPx = padTopPx,
                padBottomPx = padBottomPx,
            ),
            onStateChange = { currentOnStateChange(it) },
        )
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> state.webView?.onPause()
                Lifecycle.Event.ON_RESUME -> state.webView?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state, state.pageLoaded, streaming, palette, effectiveBg, padTopPx, padBottomPx) {
        val web = state.webView ?: return@LaunchedEffect
        if (!state.pageLoaded) return@LaunchedEffect
        web.setBackgroundColor(effectiveBg.toArgb())
        snapshotFlow { currentSource }.conflate().collect {
            val script = buildRenderScript(
                source = it,
                streaming = streaming,
                palette = palette,
                background = effectiveBg,
                padTopPx = if (interactive) padTopPx else null,
                padBottomPx = if (interactive) padBottomPx else null,
            )
            web.evaluateJavascript(script, null)
            // 等待期间合并新片段，下一次绘图取最新源码。结束生成时 effect 重启并立即绘制。
            if (streaming) delay(STREAM_RENDER_THROTTLE_MS)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createMermaidWebView(
                context = ctx,
                state = state,
                interactive = interactive,
                background = effectiveBg.toArgb(),
            ).also { web ->
                state.webView = web
                web.loadUrl("https://${MermaidSandbox.HOST}/")
            }
        },
        onRelease = { web ->
            state.webView = null
            web.stopLoading()
            web.destroy()
        },
    )
}

@Stable
private class MermaidWebState(
    val shellHtml: String,
    val onStateChange: (MermaidRenderState) -> Unit,
) {
    var webView: WebView? = null
    var pageLoaded by mutableStateOf(false)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMermaidWebView(
    context: Context,
    state: MermaidWebState,
    interactive: Boolean,
    background: Int,
): WebView {
    val main = Handler(Looper.getMainLooper())
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        clipToOutline = true
        setBackgroundColor(background)
        isVerticalScrollBarEnabled = interactive
        isHorizontalScrollBarEnabled = interactive
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(interactive)
            builtInZoomControls = interactive
            displayZoomControls = false
            useWideViewPort = interactive
            loadWithOverviewMode = interactive
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = MermaidSandboxClient(context.applicationContext, state, main)
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback,
            ) = callback.invoke(origin, false, false)
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
    }
}

private class MermaidSandboxClient(
    private val context: Context,
    private val state: MermaidWebState,
    private val main: Handler,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val uri = request.url
        val host = uri.host?.lowercase() ?: return null
        if (host == MermaidSandbox.HOST) return serveLocal(uri)
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http") return emptyResponse(403, "Blocked")
        if (scheme != "https") return null
        if (!CanvasCdn.isAllowedUri(uri) || request.method != "GET") {
            return emptyResponse(403, "Blocked")
        }
        val response = runBlocking { CanvasCdn.fetch(context, uri) }
            ?: return emptyResponse(502, "Unreachable")
        val (mime, charset) = splitMime(response.contentType)
        return WebResourceResponse(
            mime,
            charset,
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400"),
            ByteArrayInputStream(response.body),
        )
    }

    private fun serveLocal(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty()
        if (path == "/" || path == "/index.html") {
            return WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                mapOf("Content-Security-Policy" to MermaidSandbox.csp, "Cache-Control" to "no-store"),
                ByteArrayInputStream(state.shellHtml.toByteArray(Charsets.UTF_8)),
            )
        }
        if (path == MermaidSandbox.STATUS_PATH) {
            val status = uri.getQueryParameter("s")
            val height = uri.getQueryParameter("h")?.toIntOrNull() ?: 200
            val message = uri.getQueryParameter("m")
            main.post {
                when (status) {
                    "ready" -> state.onStateChange(MermaidRenderState.Ready(height))
                    "loading" -> state.onStateChange(MermaidRenderState.Loading)
                    "failed" -> state.onStateChange(MermaidRenderState.Failed(message))
                }
            }
            return emptyResponse(204, "No Content")
        }
        return emptyResponse(404, "Not Found")
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.host?.lowercase() != MermaidSandbox.HOST

    override fun onPageFinished(view: WebView, url: String?) {
        if (url?.contains(MermaidSandbox.HOST) == true) {
            state.pageLoaded = true
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            main.post { state.onStateChange(MermaidRenderState.Failed("图表容器加载失败")) }
        }
    }

    private fun emptyResponse(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun splitMime(value: String): Pair<String, String?> {
        val parts = value.split(';').map { it.trim() }
        val mime = parts.first().ifEmpty { "application/octet-stream" }
        val charset = parts.drop(1).firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"')
        return mime to charset
    }
}

private fun buildRenderScript(
    source: String,
    streaming: Boolean,
    palette: VisualPalette,
    background: Color,
    padTopPx: Int?,
    padBottomPx: Int?,
): String =
    MermaidSandbox.renderCallScript(
        code = source,
        streaming = streaming,
        dark = palette.dark,
        backgroundHex = cssHex(background.toArgb()),
        textHex = cssHex(palette.text.toArgb()),
        primaryHex = cssHex(palette.accent.toArgb()),
        borderHex = cssHex(palette.border.toArgb()),
        surfaceHex = cssHex(palette.elevated.toArgb()),
        padTopPx = padTopPx,
        padBottomPx = padBottomPx,
    )

private fun cssHex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

private const val STREAM_RENDER_THROTTLE_MS = 120L
