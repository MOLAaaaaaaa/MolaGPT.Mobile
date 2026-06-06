package com.molagpt.app.feature.chat

import androidx.compose.runtime.Immutable
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.EnabledTools
import com.molagpt.app.core.model.FileInfo
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
    val selectedModelId: String? = null,
    val selectedModel: ProviderModel? = null,
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
