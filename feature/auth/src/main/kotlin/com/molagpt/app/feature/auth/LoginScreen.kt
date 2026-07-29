package com.molagpt.app.feature.auth

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.render.ImeDismissBackHandler

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoggedIn: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // 非空时弹出 OAuth WebView 覆盖层加载该授权 URL。
    var oauthUrl by remember { mutableStateOf<String?>(null) }

    if (state.loggedIn) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onLoggedIn() }
    }

    // 不在此无条件拦截系统返回：交给 NavHost 走可 seek 的预测式返回转场（普通 BackHandler 会拦掉它）。
    // 「以游客身份继续」按钮仍调 onBack；OAuth 覆盖层（OAuthOverlay）另有自己的 BackHandler 负责关闭。
    // 唯一的例外是下面这个：它只在键盘可见时 enabled，键盘收起时不存在，因此不影响上述转场。
    ImeDismissBackHandler()

    Column(
        modifier = modifier
            .fillMaxSize()
            // 不透明背景：右侧推入转场期间登录页与下层设置页同时在场，缺背景会透出下层（见反馈截图）。
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 本页用 Column+.background，无 Surface 提供 LocalContentColor（默认 Black），
        // 夜间模式下必须显式指定 onSurface，否则标题几乎看不见。
        Text(
            "登录 MolaGPT",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "登录后即可使用云端模型对话",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        Button(
            onClick = { viewModel.login(username, password) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("登录")
            }
        }

        // —— 第三方登录 ——
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                "  或使用第三方登录  ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        OAuthButton("使用 Linux.do 登录", enabled = !state.loading) { oauthUrl = viewModel.oauthUrl(OAuthProvider.LINUXDO) }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OAuthButton("Google", enabled = !state.loading, modifier = Modifier.weight(1f)) { oauthUrl = viewModel.oauthUrl(OAuthProvider.GOOGLE) }
            OAuthButton("Microsoft", enabled = !state.loading, modifier = Modifier.weight(1f)) { oauthUrl = viewModel.oauthUrl(OAuthProvider.MICROSOFT) }
        }

        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
                Text("以游客身份继续")
            }
        }
    }

    oauthUrl?.let { url ->
        OAuthOverlay(
            url = url,
            onCode = { code ->
                oauthUrl = null
                viewModel.loginWithOAuthCode(code)
            },
            onClose = { oauthUrl = null },
        )
    }
}

@Composable
private fun OAuthButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Text(text)
    }
}

/** OAuth 授权覆盖层：内嵌 WebView 加载授权页，拦截 molagpt://oauth_callback?code= 取回一次性 code。 */
@Composable
private fun OAuthOverlay(url: String, onCode: (String) -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    // 0..100。授权页常带 302 跳转，跳转时进度会归零重来，故直接以 <100 判定加载中。
    var progress by remember { mutableIntStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("取消") }
                Spacer(Modifier.width(4.dp))
                Text("第三方登录", style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            // 网页首帧出来前 WebView 区域仍是空白，进度条让加载状态对用户可见。
            if (progress < 100) {
                if (progress > 0) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            OAuthWebView(
                url = url,
                onCode = onCode,
                onProgress = { progress = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * OAuth 授权页共用的 WebView 单例。
 * WebView.destroy() 会卡死整个 App 的渲染管线（实测，含延迟到 idle 执行），故全进程复用
 * 同一实例、永不销毁；用 applicationContext 创建避免连带 Activity 泄漏。
 */
private object OAuthWebViewHolder {
    private var instance: WebView? = null
    var onCode: (String) -> Unit = {}
    var onProgress: (Int) -> Unit = {}

    @SuppressLint("SetJavaScriptEnabled")
    fun get(context: Context): WebView = instance ?: WebView(context.applicationContext).apply {
        // 首帧渲染完成前 WebView 会越过自身边界把全屏刷白、盖住同屏 Compose 内容
        //（Google issue 174233728），官方建议即裁剪到自身 outline。
        clipToOutline = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == OAuthProvider.CALLBACK_SCHEME && u.host == OAuthProvider.CALLBACK_HOST) {
                    u.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { onCode(it) }
                    return true
                }
                return false
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }
        }
        instance = this
    }
}

@Composable
private fun OAuthWebView(
    url: String,
    onCode: (String) -> Unit,
    onProgress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember { OAuthWebViewHolder.get(context) }
    SideEffect {
        OAuthWebViewHolder.onCode = onCode
        OAuthWebViewHolder.onProgress = onProgress
    }
    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            OAuthWebViewHolder.onCode = {}
            OAuthWebViewHolder.onProgress = {}
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { webView },
        // 不 destroy（见 OAuthWebViewHolder 注释），实例由 holder 持有复用。
        onRelease = { },
    )
    LaunchedEffect(url) { webView.loadUrl(url) }
}
