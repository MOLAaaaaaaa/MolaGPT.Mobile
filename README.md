# MolaGPT Mobile

MolaGPT Mobile 是 [MolaGPT](https://chatgpt.wljay.cn) 的原生 Android 客户端，使用 Kotlin 与 Jetpack Compose 构建。除了连接 MolaGPT 账户，也支持接入 OpenAI 兼容服务等自定义模型，并在移动端管理对话、工具、角色和图像工作台。

## 功能特性

### 对话体验

- 原生 Compose 界面，支持浅色、深色和跟随系统主题。
- 流式 Markdown 渲染，支持代码块、数学公式、图片、Mermaid 图、思考过程和工具调用状态。
- 支持图片附件、联网搜索、网页访问、代码执行及生成中止。
- 会话抽屉、历史分页、后台流式任务和完成通知。
- 图片全屏预览、缩放与保存，兼容远程链接及 Base64/Data URL 图片。

### 模型与工具

- MolaGPT 账号登录、模型发现、额度展示和云端会话同步。
- BYOK 自定义模型服务，可管理 Provider、模型、API 地址和密钥。
- 支持视觉模型代理：文本模型不具备视觉能力时，可交由指定视觉模型理解图片。
- 支持图像生成工具，让兼容工具调用的 BYOK 模型使用独立图像服务。
- 支持配置联网搜索服务和远程 MCP 服务器，并按工具启用或停用。

### 画图工作台（由 [@DisaWdcba](https://github.com/DisaWdcba) 提供）

- 原生图像生成与编辑工作台，可从对话工具栏或 BYOK 工具设置进入。
- 兼容 OpenAI Images、OpenAI Chat Completions 图像回退及 Gemini 图像响应。
- 支持参考图、多轮修改、批量生成、蒙版局部重绘和生成参数配置。
- 支持画图历史、本地结果保存、再次生成、基于结果继续修改及 Base64 图片解析。
- 支持尺寸、质量、输出格式、图片数量、压缩率等模型相关参数。

### 个性化与跨端协作

- 个性化记忆、用户画像和回答风格管理。
- BYOK 角色管理，可新建、复制、编辑角色及配置系统提示词。
- Agent Control，可在手机端查看并接管桌面端 Agent 会话。
- 会话、消息和设置本地持久化；MolaGPT 会话支持增量云同步。

## 项目结构

```text
app/                Android 应用入口与导航
core/               网络、存储、模型与通用渲染能力
feature/chat/       对话界面与流式交互
feature/file/       文件及图片预览
feature/session/    会话列表与历史管理
feature/settings/   设置、BYOK、Agent Control、角色与图像工作台
baselineprofile/    Baseline Profile 配置
```

## 构建

需要安装 Android Studio、Android SDK 和 JDK 17。

构建 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

构建 Release APK：

```powershell
.\gradlew.bat :app:assembleRelease
```

Release 构建启用 R8 和资源收缩。正式分发前，需要在本地配置 release signingConfig；请勿提交密钥库、签名密码或其他私密配置。

## 致谢

- 感谢 [DisaWdcba](https://github.com/DisaWdcba) 通过 [PR #2](https://github.com/MOLAaaaaaaa/MolaGPT.Mobile/pull/2) 贡献原生抹茶画图工作台。
- 图像工作台参考并延续了 [SimpleAIPainting](https://github.com/DisaWdcba/SimpleAIPainting) 的产品思路。

欢迎通过 Issue 或 Pull Request 反馈问题和参与改进。
