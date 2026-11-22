package com.molagpt.app.feature.webview

import org.json.JSONObject

/**
 * Mermaid 隔离渲染壳：虚拟源 `https://mermaid.mola-canvas.example/`，无 JS bridge，
 * 仅允许从白名单 CDN（经 [CanvasCdn] 镜像竞速与落盘缓存）加载 `mermaid.min.js`。
 *
 * 流式渲染策略对齐 chat-demo：
 * - 串行队列调用 `mermaid.render`，防止连续增量相互踩配置；
 * - 流式期间若整段语法报错，自动裁掉未写完的末行重试前缀；仍失败则保留上一张有效图不闪错；
 * - 流式结束后若仍失败，才通过同源信标回报失败态。
 */
internal object MermaidSandbox {
    const val HOST = "mermaid.mola-canvas.example"
    const val STATUS_PATH = "/__mola/mermaid-status"
    const val MERMAID_SCRIPT_URL = "https://cdn.jsdelivr.net/npm/mermaid@11.10.1/dist/mermaid.min.js"

    val csp: String =
        "default-src 'none'; " +
            "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; " +
            "font-src 'self' data:; " +
            "connect-src 'none'; " +
            "frame-src 'none'; " +
            "object-src 'none'; " +
            "base-uri 'self'; " +
            "form-action 'none'"

    fun renderCallScript(
        code: String,
        streaming: Boolean,
        dark: Boolean,
        backgroundHex: String,
        textHex: String,
        primaryHex: String,
        borderHex: String,
        surfaceHex: String,
        padTopPx: Int? = null,
        padBottomPx: Int? = null,
    ): String {
        val payload = JSONObject()
            .put("code", code)
            .put("streaming", streaming)
            .put("dark", dark)
            .put("bg", backgroundHex)
            .put("text", textHex)
            .put("primary", primaryHex)
            .put("border", borderHex)
            .put("surface", surfaceHex)
            .apply {
                if (padTopPx != null) put("padTop", padTopPx)
                if (padBottomPx != null) put("padBottom", padBottomPx)
            }
        return "window.__molaMermaidRender&&window.__molaMermaidRender($payload);"
    }

    fun buildShellHtml(
        interactive: Boolean,
        backgroundHex: String,
        textHex: String,
        padTopPx: Int = 16,
        padBottomPx: Int = 16,
    ): String {
        val viewport = if (interactive) {
            "width=device-width, initial-scale=1, minimum-scale=0.5, maximum-scale=4, user-scalable=yes"
        } else {
            "width=device-width, initial-scale=1, user-scalable=no"
        }
        val rootStyle = if (interactive) {
            "html{margin:0;min-width:100%;min-height:100%;background:$backgroundHex;}" +
                "body{margin:0;width:max-content;min-width:100%;min-height:100vh;background:$backgroundHex;color:$textHex;display:flex;font-family:system-ui,-apple-system,\"PingFang SC\",\"Noto Sans CJK SC\",sans-serif;}"
        } else {
            "html,body{margin:0;width:100%;background:$backgroundHex;color:$textHex;font-family:system-ui,-apple-system,\"PingFang SC\",\"Noto Sans CJK SC\",sans-serif;}" +
                "body{padding:12px;display:flex;align-items:center;justify-content:center;overflow:hidden;}"
        }
        val diagramStyle = if (interactive) {
            "margin:auto;flex:0 0 auto;padding:${padTopPx}px 16px ${padBottomPx}px 16px;display:flex;align-items:center;justify-content:center;"
        } else {
            "width:100%;display:flex;align-items:center;justify-content:center;"
        }
        val svgRule = if (interactive) {
            "#diagram svg{display:block;max-width:none;height:auto;background:transparent!important;}"
        } else {
            "#diagram svg{display:block;max-width:100%;height:auto;margin:0 auto;background:transparent!important;}"
        }
        return """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="$viewport">
<style>
*{box-sizing:border-box;}
$rootStyle
#diagram{$diagramStyle}
$svgRule
</style>
</head>
<body>
<div id="diagram"></div>
<script>
(function(){
  var seq=0, queue=Promise.resolve(), pending=null, engineReady=false, engineFailed=false;
  var displayedCode=null, displayedDark=null, displayedBg=null, lastReqId=0;
  var report=function(s,h,m){
    try{
      var q='?s='+encodeURIComponent(s)+'&r='+Math.random();
      if(h!=null){q+='&h='+Math.round(h);}
      if(m){q+='&m='+encodeURIComponent(String(m).slice(0,240));}
      var i=new Image();
      i.src='$STATUS_PATH'+q;
    }catch(e){}
  };
  var sanitize=function(code){return String(code||'').replace(/<((?:img|image)\s+)/gi,'&lt;$1');};
  var stripLastLine=function(code){
    var text=String(code||'').replace(/\s+$/,'');
    var idx=Math.max(text.lastIndexOf('\n'),text.lastIndexOf('\r'));
    return idx>0?text.slice(0,idx):'';
  };
  var applyTheme=function(req){
    document.documentElement.style.background=req.bg;
    document.body.style.background=req.bg;
    document.body.style.color=req.text;
    if(${if (interactive) "true" else "false"}){
      var host=document.getElementById('diagram');
      if(host){
        if(req.padTop!=null) host.style.paddingTop=req.padTop+'px';
        if(req.padBottom!=null) host.style.paddingBottom=req.padBottom+'px';
      }
    }
  };
  var tryDraw=async function(input,req){
    window.mermaid.initialize({
      startOnLoad:false,
      securityLevel:'strict',
      suppressErrorRendering:true,
      theme:req.dark?'dark':'default',
      themeVariables:{
        background:req.bg,
        mainBkg:req.surface,
        nodeBkg:req.surface,
        primaryColor:req.surface,
        primaryBorderColor:req.primary,
        primaryTextColor:req.text,
        lineColor:req.text,
        textColor:req.text,
        clusterBkg:req.surface,
        clusterBorder:req.border,
        edgeLabelBackground:req.bg
      }
    });
    var res=await window.mermaid.render('mola-mermaid-'+(++seq), sanitize(input));
    var svg=res&&res.svg;
    if(!svg) throw new Error('empty svg');
    var host=document.getElementById('diagram');
    host.innerHTML=svg;
    var el=host.querySelector('svg');
    if(el){
      el.removeAttribute('height');
      if(!${if (interactive) "true" else "false"}){
        el.style.maxWidth='100%';
        el.style.height='auto';
      }
    }
    displayedCode=input;
    displayedDark=req.dark;
    displayedBg=req.bg;
    var rect=el?el.getBoundingClientRect():host.getBoundingClientRect();
    var h=Math.max(120, Math.ceil((rect&&rect.height?rect.height:180)+28));
    report('ready',h,null);
  };
  var run=function(req){
    var reqId=++lastReqId;
    applyTheme(req);
    if(engineFailed){
      report('failed',null,'图表引擎加载失败');
      return;
    }
    if(!engineReady||!window.mermaid){
      pending=req;
      return;
    }
    var code=String(req.code||'').trim();
    if(!code){
      if(!req.streaming){report('failed',null,'图表内容为空');}
      return;
    }
    if(code===displayedCode && req.dark===displayedDark && req.bg===displayedBg){
      var el=document.querySelector('#diagram svg');
      var rect=el?el.getBoundingClientRect():null;
      report('ready',rect?Math.max(120,Math.ceil(rect.height+28)):180,null);
      return;
    }
    queue=queue.then(async function(){
      if(reqId!==lastReqId) return;
      try{
        await tryDraw(code,req);
      }catch(err){
        if(reqId!==lastReqId) return;
        if(req.streaming){
          var prefix=stripLastLine(code);
          if(prefix && (prefix!==displayedCode || req.dark!==displayedDark || req.bg!==displayedBg)){
            try{
              await tryDraw(prefix,req);
              return;
            }catch(e2){}
          }
          if(!displayedCode){report('loading',null,null);}
        }else{
          displayedCode=null;
          var host=document.getElementById('diagram');
          if(host) host.innerHTML='';
          report('failed',null,'图表语法无法解析');
        }
      }
    }).catch(function(){});
  };
  window.__molaMermaidRender=run;
  window.__molaMermaidLoaded=function(){
    engineReady=true;
    if(pending){var p=pending;pending=null;run(p);}
  };
  window.__molaMermaidLoadError=function(){
    engineFailed=true;
    report('failed',null,'图表引擎加载失败，请检查网络或查看源码');
  };
})();
</script>
<script src="$MERMAID_SCRIPT_URL" onload="window.__molaMermaidLoaded()" onerror="window.__molaMermaidLoadError()"></script>
</body>
</html>
""".trimIndent()
    }
}
