package com.molagpt.app.feature.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Mermaid 图渲染：用 WebView 加载 assets 内的离线 mermaid.js（无网络依赖），
 * 渲染后通过 JS bridge 仅回传内容高度，反向调整 Compose 容器高度避免内嵌滚动。
 *
 * 提示：需把 mermaid.min.js 放到 `feature/webview/src/main/assets/mermaid/mermaid.min.js`
 * （体积较大的第三方资源，未随脚手架附带；README 注明从官方发行版获取并校验）。
 */
@Composable
fun MermaidWebView(source: String, modifier: Modifier = Modifier) {
    var heightDp by remember { mutableIntStateOf(160) }
    val density = LocalConfiguration.current.densityDpi / 160f

    MolaWebView(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        onCreated = { web ->
            web.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onRendered(pxHeight: Int) {
                        web.post { heightDp = (pxHeight / density).toInt().coerceIn(80, 1600) }
                    }
                },
                "MermaidBridge",
            )
        },
        configure = { web -> renderMermaid(web, source) },
    )
}

private fun renderMermaid(web: WebView, source: String) {
    val escaped = source.replace("\\", "\\\\").replace("`", "\\`")
    val html = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <script src="mermaid/mermaid.min.js"></script>
        <style>body{margin:0;padding:8px;background:transparent;} .mermaid{font-size:14px;}</style>
        </head><body>
        <div class="mermaid" id="g">${'$'}{SRC}</div>
        <script>
          try {
            mermaid.initialize({ startOnLoad: false, securityLevel: 'strict' });
            mermaid.run({ nodes: [document.getElementById('g')] }).then(function(){
              if (window.MermaidBridge) MermaidBridge.onRendered(document.body.scrollHeight);
            });
          } catch (e) {
            document.getElementById('g').textContent = `${'$'}{SRC}`;
          }
        </script>
        </body></html>
    """.trimIndent().replace("\${SRC}", escaped)

    web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
}
