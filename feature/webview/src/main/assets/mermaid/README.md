# mermaid.min.js 放置位置

把 Mermaid 官方发行版的 `mermaid.min.js` 放到本目录：

```
feature/webview/src/main/assets/mermaid/mermaid.min.js
```

获取方式（任选其一）：
- 官方 GitHub Release：https://github.com/mermaid-js/mermaid/releases
- npm 包 `mermaid` 解包后的 `dist/mermaid.min.js`

注意：
- 该文件体积较大（数百 KB），**未随本脚手架附带**，需手动放入。
- 放入后请核对完整性（与官方校验值比对）。
- 离线加载，无运行时网络依赖；`MermaidWebView` 以 `file:///android_asset/` 为 baseURL 引用它。
- 若未放置该文件，Mermaid 块会降级为代码块展示（见 :core:render 的 MarkdownBlockView）。
