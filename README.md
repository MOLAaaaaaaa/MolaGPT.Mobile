# MolaGPT Mobile

MolaGPT Mobile 是 [MolaGPT](https://chatgpt.wljay.cn) 的原生 Android 客户端，基于 Kotlin 和 Jetpack Compose 构建。

它提供移动端对话体验，支持账号登录、模型选择、流式回复、本地会话管理和云同步。

## 功能特性

- 原生 Android 界面，支持浅色、深色和跟随系统主题。
- MolaGPT 账号登录、模型发现和账号配额展示。
- 流式 Markdown 渲染，支持代码块、数学公式、图片、Mermaid 图、思考过程和工具调用状态。
- 支持图片附件、联网搜索、网页访问和代码执行开关。
- 本地保存会话、消息、设置和登录凭据。
- 支持会话抽屉、历史分页、后台流式任务、完成通知和停止生成。
- 支持云同步、个性化记忆、用户画像管理和对话风格偏好。

## 构建

需要安装 Android Studio、Android SDK 和 JDK 17。

```powershell
.\gradlew.bat :app:assembleDebug
```

构建 release APK：

```powershell
.\gradlew.bat :app:assembleRelease
```

当前 release 构建启用 R8 和资源收缩。正式公开分发前，需要配置 release signingConfig 或使用单独签名流程。
