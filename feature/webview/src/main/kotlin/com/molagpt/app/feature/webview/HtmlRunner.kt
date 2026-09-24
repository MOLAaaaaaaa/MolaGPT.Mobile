package com.molagpt.app.feature.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.molagpt.app.core.render.visual.VisualExport
import com.molagpt.app.core.render.visual.VisualPalette
import com.molagpt.app.core.render.visual.rememberVisualPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * 全屏运行一段模型写的 HTML。
 *
 * 页面跑在 [HtmlSandbox] 的独立 origin 里：文档从内存返回并带 CSP 头，子资源只放行白名单 CDN
 * （经 [CanvasCdn] 竞速镜像、落盘缓存），其余请求一律拦下并计数；页面不能跳转、不能弹窗，
 * 用户点的外链交给浏览器。这个 WebView 不开放任何原生接口，报错经同源请求回报。
 *
 * 状态行始终说清页面的网络行为：没出网、从 CDN 加载了几个、拦了几个。
 */
@Composable
fun HtmlRunner(
    source: String,
    title: String,
    fileName: String?,
    originKey: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = rememberVisualPalette()
    val theme = remember(palette) { themeOf(palette) }
    val state = remember(source, originKey) { RunnerState(HtmlSandbox.hostFor(originKey)) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showSource by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var errorsOpen by remember { mutableStateOf(false) }

    // 明暗切换时只改变量，不重载页面（页面里的状态不丢）。
    LaunchedEffect(theme) {
        if (state.document.isNotEmpty()) state.webView?.evaluateJavascript(theme.script(), null)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        // 应用退到后台时暂停页面的定时器和动画，回来再继续。
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

    Column(modifier = modifier.fillMaxSize().background(palette.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "关闭", tint = palette.text) }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.trafficText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { showSource = !showSource }) {
                Icon(
                    if (showSource) Icons.Outlined.Language else Icons.Outlined.Code,
                    contentDescription = if (showSource) "查看网页" else "查看代码",
                    tint = palette.text,
                )
            }
            IconButton(onClick = {
                showSource = false
                state.load(source, theme)
            }) { Icon(Icons.Filled.Refresh, contentDescription = "重新运行", tint = palette.text) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = palette.text)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("分享网页文件") },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                val name = VisualExport.safeName(fileName?.substringBeforeLast('.') ?: title, "page")
                                runCatching { VisualExport.shareText(context, "$name.html", "text/html", source) }
                                    .onFailure { Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show() }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制代码") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(source))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = palette.border)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createWebView(ctx, state, theme.background).also { web ->
                        state.webView = web
                        state.load(source, theme)
                    }
                },
                onRelease = { web ->
                    state.webView = null
                    web.stopLoading()
                    web.destroy()
                },
            )
            if (state.loading && !showSource) {
                Box(
                    modifier = Modifier.fillMaxSize().background(palette.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.muted)
                        Spacer(Modifier.width(10.dp))
                        Text("正在准备预览…", style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    }
                }
            }
            if (showSource) SourceView(source, palette)
        }

        if (state.errors.isNotEmpty()) {
            ErrorPanel(state.errors, errorsOpen, palette, onToggle = { errorsOpen = !errorsOpen }) {
                clipboard.setText(AnnotatedString(state.errors.joinToString("\n\n")))
                Toast.makeText(context, "已复制报错", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** 按下「运行」时先把 WebView 的内核加载起来：进程里第一次创建 WebView 是最慢的那一下。 */
object HtmlRunnerWarmup {
    private var warmed = false

    fun prepare(context: Context) {
        if (warmed) return
        warmed = true
        runCatching { WebView(context.applicationContext).destroy() }
    }
}

@Composable
private fun SourceView(source: String, palette: VisualPalette) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = source,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.text,
            softWrap = false,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ErrorPanel(
    errors: List<String>,
    open: Boolean,
    palette: VisualPalette,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(palette.bad.copy(alpha = 0.08f))) {
        HorizontalDivider(color = palette.border)
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = palette.bad, modifier = Modifier.size(16.dp))
            Text(
                text = "页面报错 ${errors.size} 条",
                style = MaterialTheme.typography.labelLarge,
                color = palette.bad,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onCopy) { Text("复制") }
            TextButton(onClick = onToggle) { Text(if (open) "收起" else "查看") }
        }
        if (open) {
            Text(
                text = errors.joinToString("\n\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = palette.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Stable
private class RunnerState(val host: String) {
    var webView: WebView? = null
    var document: String = ""
    var loading by mutableStateOf(true)
    val errors = mutableStateListOf<String>()
    var loaded by mutableIntStateOf(0)
    var failed by mutableIntStateOf(0)
    var blocked by mutableIntStateOf(0)

    fun load(source: String, theme: CanvasTheme) {
        val web = webView ?: return
        document = HtmlSandbox.buildHtml(source, theme)
        errors.clear()
        loaded = 0
        failed = 0
        blocked = 0
        loading = true
        // 查询串只为绕过页面缓存；文档由拦截器从内存返回。
        web.loadUrl("https://$host/?v=${System.currentTimeMillis()}")
    }

    fun report(type: String?, message: String) {
        when (type) {
            "error" -> if (errors.size < 50 && message !in errors) errors += message
            // CSP 在页面内部就拦下了这些请求，根本不会到拦截器，由页面自己回报。
            "blocked" -> blocked++
        }
    }

    fun trafficText(): String {
        if (loaded == 0 && failed == 0 && blocked == 0) return if (loading) "正在加载" else "未访问外部网络"
        return buildList {
            if (loaded > 0) add("从 CDN 加载 $loaded 个资源")
            if (failed > 0) add("$failed 个加载失败")
            if (blocked > 0) add("已拦截 $blocked 个请求")
        }.joinToString(" · ")
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(context: Context, state: RunnerState, background: Int): WebView {
    val main = Handler(Looper.getMainLooper())
    return WebView(context).apply {
        // 没有 layoutParams 时 AndroidView 给的是 WRAP_CONTENT，WebView 据此进入「高度随内容」
        // 模式，把 CSS 视口高度强制成 0：html,body{height:100%} 于是为 0，垂直居中的页面
        // 有一半落到顶部之外、滚不回来。尺寸由 Compose 定死，这里声明占满。
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // 首帧出来之前 WebView 会越过自身边界把整屏刷白，盖住顶栏（Google issue 174233728）；
        // 裁到自身轮廓为止。页面头里有要等 CDN 的脚本时，这段空白会持续好几秒。
        clipToOutline = true
        setBackgroundColor(background)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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
            mediaPlaybackRequiresUserGesture = true
            // 为桌面宽度写的页面可以双指放大看。
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = SandboxClient(context.applicationContext, state, main)
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
    }
}

private class SandboxClient(
    private val context: Context,
    private val state: RunnerState,
    private val main: Handler,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val host = uri.host?.lowercase() ?: return null
        if (host.endsWith(HtmlSandbox.HOST_SUFFIX)) return serveLocal(uri, host)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        if (!CanvasCdn.isAllowed(host) || request.method != "GET") {
            main.post { state.blocked++ }
            return plain(403, "Blocked")
        }
        // 这个回调本来就在浏览器的 IO 线程上，可以阻塞着等镜像竞速的结果。
        val response = runBlocking { CanvasCdn.fetch(context, uri) }
        if (response == null) {
            main.post { state.failed++ }
            return plain(502, "Unreachable")
        }
        main.post { state.loaded++ }
        val (mime, charset) = splitContentType(response.contentType)
        return WebResourceResponse(
            mime,
            charset,
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400"),
            ByteArrayInputStream(response.body),
        )
    }

    private fun serveLocal(uri: Uri, host: String): WebResourceResponse {
        val path = uri.path.orEmpty()
        if ((path == "/" || path == "/index.html") && host == state.host) {
            return WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                mapOf("Content-Security-Policy" to HtmlSandbox.csp, "Cache-Control" to "no-store"),
                ByteArrayInputStream(state.document.toByteArray(Charsets.UTF_8)),
            )
        }
        if (path == HtmlSandbox.REPORT_PATH && host == state.host) {
            val type = uri.getQueryParameter("t")
            val message = uri.getQueryParameter("m").orEmpty()
            main.post { state.report(type, message) }
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        return plain(404, "Not Found")
    }

    /** 页面不能把自己导航到别处。用户真的点了的外链交给浏览器。 */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (request.isForMainFrame && uri.host?.lowercase()?.endsWith(HtmlSandbox.HOST_SUFFIX) == true) return false
        val scheme = uri.scheme?.lowercase()
        if (request.hasGesture() && (scheme == "http" || scheme == "https")) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (url?.contains(HtmlSandbox.HOST_SUFFIX) == true) state.loading = true
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        state.loading = false
    }

    override fun onPageFinished(view: WebView, url: String?) {
        state.loading = false
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            state.loading = false
            state.report("error", "页面加载失败：${error.description}")
        }
    }

    private fun plain(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun splitContentType(value: String): Pair<String, String?> {
        val parts = value.split(';').map { it.trim() }
        val mime = parts.first().ifEmpty { "application/octet-stream" }
        val charset = parts.drop(1).firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"')
        return mime to charset
    }
}

private fun themeOf(palette: VisualPalette): ThemeHolder = ThemeHolder(palette)

/** 页面配色：与原生组件同一套颜色，网页里的图表和对话里的配色一致。 */
private class ThemeHolder(palette: VisualPalette) {
    val background: Int = palette.surface.toArgb()
    private val sheet = CanvasTheme(
        dark = palette.dark,
        variables = buildList {
            add("--mola-bg" to css(palette.surface.toArgb()))
            add("--mola-surface" to css(palette.grid.toArgb()))
            add("--mola-text" to css(palette.text.toArgb()))
            add("--mola-muted" to css(palette.muted.toArgb()))
            add("--mola-border" to css(palette.border.toArgb()))
            add("--mola-accent" to css(palette.accent.toArgb()))
            palette.chartColors.forEachIndexed { i, color -> add("--mola-chart-${i + 1}" to css(color.toArgb())) }
        },
    )

    fun script(): String = sheet.script()

    fun canvas(): CanvasTheme = sheet

    private fun css(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
}

private fun RunnerState.load(source: String, theme: ThemeHolder) = load(source, theme.canvas())
