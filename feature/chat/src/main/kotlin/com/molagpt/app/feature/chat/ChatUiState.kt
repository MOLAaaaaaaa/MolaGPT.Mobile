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
    val isModelRefreshing: Boolean = false,
    val isStreaming: Boolean = false,
    val inputEnabled: Boolean = true,
    val enabledTools: EnabledTools = EnabledTools(),
    val useThinking: Boolean = false,
    val reasoningEffort: String = "medium",
    /** 当前会话所属 BYOK 提供商 baseUrl（供推理弹层判断聚合网关/预算折算）；MolaGPT 会话为空串。 */
    val providerBaseUrl: String = "",
    /** 本次回复未检测到推理时的自校正提示。 */
    val reasoningMissHint: ReasoningMissHint? = null,
    val pendingAttachments: List<FileInfo> = emptyList(),
    /** 正在编辑的用户消息；非空时 Composer 进入编辑态，发送会截断该条及之后消息后重发。 */
    val editingMessage: EditingUserMessage? = null,
    val error: String? = null,
    val authExpired: Boolean = false,
    val isLoadingHistory: Boolean = false,
)

/** Composer 编辑用户消息的会话态；[revision] 变化时输入框重新预填 [text]。 */
@Immutable
data class EditingUserMessage(
    val messageId: String,
    val createdAt: Long,
    val text: String,
    val revision: Long,
)

/** 运行时自校正：开启了推理但回复中无思考内容。 */
@Immutable
data class ReasoningMissHint(
    /** 当前配置是否为低置信（仅按模型名推测）。 */
    val lowConfidence: Boolean,
    /** 是否允许「关闭推理」——常开推理模型（如 Kimi K3）无法关闭，隐藏该操作。 */
    val canTurnOff: Boolean = true,
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
