package com.molagpt.app.feature.chat

import androidx.compose.runtime.Immutable
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.EnabledTools
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel

/**
 * 聊天页 UI 状态。`@Immutable` 让 Compose 把它当稳定输入，配合 LazyColumn 稳定 key，
 * 一个 token 到来时只重组发生变化的 message/fragment，而非整屏。
 *
 * [messages] 已是「历史 + 内存中 in-flight 助手消息」合并后的完整列表（由 VM 维护）。
 * [selectedModel] 由 VM 据 [selectedModelId] 派生，Composer 据其 supportsThinking/supportsReasoningEffort
 * 决定是否显示「推理」开关与「推理强度」。
 */
@Immutable
data class ChatUiState(
    val sessionId: String,
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val models: List<ProviderModel> = emptyList(),
    /** 全量模型按阵营分组（MolaGPT 一组；每个 BYOK provider 一组），供选择器分组展示与跨阵营切换。 */
    val modelGroups: List<ModelGroup> = emptyList(),
    val selectedModelId: String? = null,
    val selectedModel: ProviderModel? = null,
    val providerKind: ProviderKind = ProviderKind.MOLAGPT,
    val hasMolaGptModels: Boolean = false,
    val hasByokModels: Boolean = false,
    /** 是否配置了已启用的 MCP 服务器（用于门控对话内 MCP 工具开关）。 */
    val hasMcpServers: Boolean = false,
    val isModelRefreshing: Boolean = false,
    val isStreaming: Boolean = false,
    val inputEnabled: Boolean = true,
    val enabledTools: EnabledTools = EnabledTools(),
    val useThinking: Boolean = false,
    val reasoningEffort: String = "medium",
    val pendingAttachments: List<FileInfo> = emptyList(),
    val error: String? = null,
    val authExpired: Boolean = false,
    val isLoadingHistory: Boolean = false,
)

/**
 * 模型选择器分组：MolaGPT 账户为一组，每个 BYOK 提供商各自一组。
 * [providerId] 在 MolaGPT 组为 null。
 */
@Immutable
data class ModelGroup(
    val kind: ProviderKind,
    val providerId: String?,
    val title: String,
    val models: List<ProviderModel>,
)
