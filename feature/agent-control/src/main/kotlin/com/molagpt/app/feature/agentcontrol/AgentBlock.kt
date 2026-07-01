package com.molagpt.app.feature.agentcontrol

import com.molagpt.app.core.model.AgentToolStatus

/**
 * 手机侧 transcript 的一块。每块带稳定 [key] 供 LazyColumn 复用、避免重组抖动。
 * 由 [AgentTranscriptReducer] 从粗粒度 relay 事件折叠而来。
 */
sealed interface AgentBlock {
    val key: String

    data class User(override val key: String, val text: String) : AgentBlock
    data class AssistantText(override val key: String, val text: String) : AgentBlock
    data class Thinking(override val key: String, val text: String) : AgentBlock
    data class Pending(override val key: String, val text: String) : AgentBlock
    data class Tool(
        override val key: String,
        val toolId: String,
        val name: String,
        val status: AgentToolStatus,
        val title: String? = null,
        val argumentsJson: String? = null,
        val resultPreview: String? = null,
    ) : AgentBlock
    data class Permission(
        override val key: String,
        val permissionId: String,
        val toolName: String,
        val description: String? = null,
        val argumentsJson: String? = null,
        /** 本地乐观解析：用户点过的选择（"Once"/"Always"/"Deny"）。非 null 即收起按钮、显示已处理态。 */
        val resolved: String? = null,
    ) : AgentBlock
    data class Error(override val key: String, val message: String) : AgentBlock

    /** Token usage summary at turn end (read-only — managed internally by Claude/Code). */
    data class Usage(
        override val key: String,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
    ) : AgentBlock
}
