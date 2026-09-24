package com.molagpt.app.feature.webview

import org.json.JSONObject

/** 网页继承的配色，以 CSS 变量给出。 */
internal class CanvasTheme(val dark: Boolean, val variables: List<Pair<String, String>>) {
    fun style(): String = buildString {
        append("<style id=\"mola-theme\">:root{color-scheme:").append(if (dark) "dark" else "light").append(';')
        variables.forEach { (name, value) -> append(name).append(':').append(value).append(';') }
        append("}html,body{background:var(--mola-bg);color:var(--mola-text);")
        append("font-family:system-ui,-apple-system,\"PingFang SC\",\"Noto Sans CJK SC\",\"Microsoft YaHei\",sans-serif;}</style>")
    }

    /** 切换明暗时不重载页面，只改变量。 */
    fun script(): String {
        val vars = JSONObject().apply { variables.forEach { (k, v) -> put(k, v) } }
        val payload = JSONObject().put("scheme", if (dark) "dark" else "light").put("vars", vars)
        return "window.__molaTheme&&window.__molaTheme($payload);"
    }
}

/**
 * 网页运行页的文档与策略（同桌面端 CanvasSandbox）。
 *
 * 每个网页有自己的 origin（`https://c-{key}.mola-canvas.example`），文档由应用从内存返回。
 * 真实 origin 才能用 localStorage（loadData 出来的是不透明 origin，存储一调用就抛错）；
 * 按网页分开的 origin 让两个页面的存储互不相干。CSP 作为响应头下发，先于页面自己声明的任何东西。
 */
internal object HtmlSandbox {
    const val HOST_SUFFIX = ".mola-canvas.example"

    /** 页面报错和被 CSP 拦下的请求走这个同源地址回报；拦截器收下，不出网。 */
    const val REPORT_PATH = "/__mola/report"

    private val cdnSources = CanvasCdn.allowedHosts.joinToString(" ") { "https://$it" }

    // 页面可以从白名单 CDN 拉库、和它们通信（模块导入、数据文件）；别的什么也出不去。
    // unsafe-eval 给运行时编译模板的库（Vue、Alpine、math.js）。
    val csp: String =
        "default-src 'none'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: $cdnSources; " +
            "style-src 'self' 'unsafe-inline' $cdnSources; " +
            "font-src 'self' data: $cdnSources; " +
            "img-src 'self' data: blob: $cdnSources; " +
            "media-src 'self' data: blob:; " +
            "connect-src 'self' $cdnSources; " +
            "worker-src 'self' blob:; child-src blob:; frame-src 'none'; object-src 'none'; " +
            "base-uri 'self'; form-action 'none'; manifest-src 'none'"

    fun hostFor(originKey: String): String = "c-$originKey$HOST_SUFFIX"

    private val HTML_OPEN = Regex("""<html\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val HEAD_BLOCK = Regex("""<head\b[^>]*>([\s\S]*?)</head\s*>""", RegexOption.IGNORE_CASE)
    private val BODY_BLOCK = Regex("""<body\b([^>]*)>([\s\S]*?)(?:</body\s*>|$)""", RegexOption.IGNORE_CASE)
    private val DOCUMENT_SHELL =
        Regex("""<!doctype[^>]*>|</?html\b[^>]*>|<head\b[^>]*>[\s\S]*?</head\s*>|</?body\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * 把模型的页面重新拼一遍，我们的 head 在最前：字符集、视口、主题变量、报错桥，
     * 然后才是页面自己的 head。html / body 上的属性保留，其余照模型写的用。
     */
    fun buildHtml(source: String, theme: CanvasTheme): String {
        val htmlAttributes = HTML_OPEN.find(source)?.groupValues?.get(1).orEmpty()
        val userHead = HEAD_BLOCK.find(source)?.groupValues?.get(1).orEmpty()
        val bodyMatch = BODY_BLOCK.find(source)
        val bodyAttributes = bodyMatch?.groupValues?.get(1).orEmpty()
        val body = bodyMatch?.groupValues?.get(2) ?: DOCUMENT_SHELL.replace(source, "")
        return "<!doctype html><html$htmlAttributes><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">" +
            theme.style() + RUNTIME_SCRIPT + userHead +
            "</head><body$bodyAttributes>" + body + "</body></html>"
    }

    /**
     * 报错时通知宿主，同时在页面里显示出来——坏掉的页面会说出来，而不是一片空白；
     * 宿主切换主题时可以不重载就改配色。回报用同源图片请求：不需要给页面开任何原生接口。
     */
    private val RUNTIME_SCRIPT = """
<script>(function(){
var post=function(type,text){try{var i=new Image();i.src='$REPORT_PATH?t='+type+'&m='+encodeURIComponent(String(text).slice(0,1500))+'&r='+Math.random();}catch(e){}};
var box=null;
var show=function(text){try{
if(!document.body){return;}
if(!box){box=document.createElement('div');box.setAttribute('data-mola-error','');
box.style.cssText='position:fixed;left:12px;right:12px;bottom:12px;z-index:2147483647;max-height:40vh;overflow:auto;padding:10px 12px;border-radius:8px;font:12px/1.5 monospace;white-space:pre-wrap;background:#fdecec;color:#8a1c1c;border:1px solid #e8b0b0';
box.onclick=function(){box.remove();box=null;};document.body.appendChild(box);}
box.textContent=(box.textContent?box.textContent+'\n':'')+text;}catch(e){}};
var report=function(text){text=String(text||'未知错误');post('error',text);show(text);};
window.addEventListener('error',function(e){
var t=e.target;
if(t&&t!==window&&(t.tagName==='SCRIPT'||t.tagName==='LINK')){report('资源加载失败：'+(t.src||t.href));return;}
if(t&&t!==window){return;}
report((e.error&&e.error.stack)||e.message);},true);
window.addEventListener('unhandledrejection',function(e){var r=e.reason;report((r&&r.stack)||r);});
document.addEventListener('securitypolicyviolation',function(e){post('blocked',e.blockedURI||'');});
window.__molaTheme=function(t){var r=document.documentElement;r.style.colorScheme=t.scheme;for(var k in t.vars){r.style.setProperty(k,t.vars[k]);}};
})();</script>
""".trimIndent()
}
