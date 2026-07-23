package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/**
 * 桌面 relay 协议在手机侧的镜像。线格式由 MolaGPT.Desktop 的 `RelaySessionMeta` /
 * `RelayTranscriptEvent` 决定（System.Text.Json Web 默认：**camelCase 键 + 枚举为整数**），
 * 经 PHP 中继**原样透传**，服务器不重解释。
 *
 * 枚举一律建模为 [Int]（桌面按 ordinal 序列化），避免名称/序数漂移；展示层用扩展属性映射。
 * 可空字段（workspaceKey/model/...）兼容 Phase 2b 之前写入的老会话。
 */

/** 一个 agent 会话的元信息（`GET agent_sessions.php` 列表项；也由 meta 事件推送）。 */
@Serializable
data class RelaySessionMeta(
    val conversationId: String,
    val backendId: String = "",
    val title: String = "",
    val workingDirectory: String = "",
    val workspaceKey: String? = null,   // Phase 2b 起才有；老会话为 null
    val workspaceName: String? = null,  // 同上；为空时展示层回退到 cwd 末段
    val model: String? = null,
    val reasoningEffort: String? = null,
    val permissionMode: Int = 1,        // AgentPermissionMode ordinal（1 = AcceptEdits，P0 默认）
    val approvalPolicy: Int? = null,    // CodexApprovalPolicy ordinal
    val phase: Int = 0,                 // AgentSessionPhase ordinal（0 = Idle）
    val needsAttention: Boolean = false,
    val seq: Long = 0,
    val updatedAtMs: Long = 0,
    val activityAtMs: Long = 0,
    /** 该会话可切换到的模型，桌面从 CLI 实时发现（Claude initialize.models / Codex model/list）。
     *  为空表示尚未发现——展示层回退到"默认 + 已观察到的 model id"。老会话/老桌面为 null。 */
    val availableModels: List<AgentModelInfo>? = null,
    /** 拥有该会话的桌面桥接机器。多机账号下用于分组与命令路由；老会话为 null。 */
    val machineId: String? = null,
    val machineName: String? = null,
    /** 最近一次桌面端命令失败结果；由 relay 保留到下一次成功命令。 */
    val lastCommandId: String? = null,
    val lastCommandError: String? = null,
    val lastCommandAtMs: Long = 0,
)

/** 一台最近心跳过的桌面桥接机器（`GET agent_sessions.php` 的 `machines`）。 */
@Serializable
data class RelayMachine(
    val id: String,
    val name: String? = null,
    val lastSeenAtMs: Long = 0,
)

/** 会话列表快照：会话 + 桌面机器表（手机建会话时选目标机器用）。 */
data class AgentSessionsSnapshot(
    val sessions: List<RelaySessionMeta> = emptyList(),
    val machines: List<RelayMachine> = emptyList(),
)

/** 桌面 `AgentModelInfo` 的镜像：一个可切换到的模型。[id] 是回传给桌面用来切换的确切值
 *  （Claude 别名/全名，Codex 模型 slug）；其余为选择器展示用。 */
@Serializable
data class AgentModelInfo(
    val id: String,
    val displayName: String = id,
    val description: String? = null,
    val isDefault: Boolean = false,
    val reasoningEfforts: List<String>? = null,
)

/** 后端标识（线上是字符串 id，对齐桌面 `ClaudeCodeBackend.BackendId` / `CodexBackend.BackendId`）。 */
object AgentBackendIds {
    const val ClaudeCode = "claude-code"
    const val Codex = "codex"
}

/** 会话阶段（顺序对齐桌面 `AgentSessionPhase`）。 */
enum class AgentPhase {
    Idle, Spawning, Running, Waiting, Completed, Failed;

    companion object {
        fun fromOrdinal(i: Int): AgentPhase = entries.getOrElse(i) { Idle }
    }
}

/** 工具调用状态（顺序对齐桌面 `AgentToolStatus`）。 */
enum class AgentToolStatus {
    Started, Running, Completed, Failed;

    companion object {
        fun fromOrdinal(i: Int): AgentToolStatus = entries.getOrElse(i) { Started }
    }
}

/** 权限姿态（顺序对齐桌面 `AgentPermissionMode`）。 */
enum class AgentPermissionMode {
    Plan, AcceptEdits, Default, BypassPermissions;

    companion object {
        fun fromOrdinal(i: Int): AgentPermissionMode = entries.getOrElse(i) { AcceptEdits }
    }
}

/** Codex 审批策略（顺序对齐桌面 `CodexApprovalPolicy`）。 */
enum class CodexApprovalPolicy {
    Untrusted, OnRequest, Never;

    companion object {
        fun fromOrdinal(i: Int?): CodexApprovalPolicy? =
            i?.let { entries.getOrNull(it) }
    }
}

/** 手机可下发的命令操作（按**名称**序列化进命令 JSON 的 `op`，对齐桌面 `RelayCommandOp`）。 */
enum class RelayCommandOp { Send, Interrupt, Approve, New, SwitchOptions, Close }

val RelaySessionMeta.phaseEnum: AgentPhase get() = AgentPhase.fromOrdinal(phase)
val RelaySessionMeta.permissionModeEnum: AgentPermissionMode get() = AgentPermissionMode.fromOrdinal(permissionMode)
val RelaySessionMeta.approvalPolicyEnum: CodexApprovalPolicy? get() = CodexApprovalPolicy.fromOrdinal(approvalPolicy)

/** 真实会话活动时间；老 relay meta 没有该字段时回退到 snapshot 更新时间。 */
val RelaySessionMeta.sortAtMs: Long get() = activityAtMs.takeIf { it > 0 } ?: updatedAtMs

/** 是否正在跑（决定 composer 显 Stop、列表显忙碌点）。 */
val RelaySessionMeta.isBusy: Boolean
    get() = phaseEnum == AgentPhase.Spawning || phaseEnum == AgentPhase.Running

/** 是否 Quick Chat（无工作区 / rootless 会话）。 */
val RelaySessionMeta.isQuickChat: Boolean get() = workspaceKey.isNullOrBlank()

/** 展示用工作区名：优先 workspaceName，回退到 cwd 末段，再回退「Quick Chat」。 */
val RelaySessionMeta.displayWorkspace: String
    get() = workspaceName?.takeIf { it.isNotBlank() }
        ?: workingDirectory.trimEnd('/', '\\')
            .substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.isNotBlank() }
        ?: "Quick Chat"

/** 展示用机器名：优先 machineName，回退 machineId 短截，再回退「未知电脑」。 */
val RelaySessionMeta.displayMachine: String
    get() = machineName?.takeIf { it.isNotBlank() }
        ?: machineId?.takeIf { it.isNotBlank() }?.let { id ->
            if (id.length <= 8) id else id.take(8) + "…"
        }
        ?: "未知电脑"

/** 机器是否在线：桌面每 ~10–15s 心跳；超过此窗口视为离线。 */
fun RelayMachine.isOnline(nowMs: Long = System.currentTimeMillis(), timeoutMs: Long = 45_000L): Boolean =
    lastSeenAtMs > 0 && nowMs - lastSeenAtMs < timeoutMs

/** 展示用机器名。 */
val RelayMachine.displayName: String
    get() = name?.takeIf { it.isNotBlank() }
        ?: id.takeIf { it.isNotBlank() }?.let { if (it.length <= 8) it else it.take(8) + "…" }
        ?: "未知电脑"

/**
 * 折叠后的粗粒度 relay 事件（手机侧由 `AgentControlService` 从 JSON 手解码——
 * 线格式枚举为整数、键演进中，手解码比 kotlinx 多态更耐脏）。
 */
sealed interface RelayEvent {
    /** 本回合用户输入（乐观本地回显）。 */
    data class UserPrompt(val text: String) : RelayEvent

    /** 工具调用进展（运行中/成功/失败）。 */
    data class ToolProgress(
        val id: String,
        val name: String,
        val status: AgentToolStatus,
        val title: String? = null,
        val argumentsJson: String? = null,
        val resultPreview: String? = null,
    ) : RelayEvent

    /** 待审批权限提示（Phase 4 接 approve/deny）。 */
    data class PermissionPrompt(
        val id: String,
        val toolName: String,
        val description: String? = null,
        val argumentsJson: String? = null,
    ) : RelayEvent

    /** 助手答复快照（**替换**当前助手块全文）。
     *  [segmentId] 标识回合内一段连续答复（工具调用分段）；桌面在流式期间会用**同一**
     *  segmentId 反复发送"到目前为止的全文"，手机按 segmentId 原位替换即得伪流式。
     *  老桌面/历史投影为 null——退回按事件 seq 分块（回合末单段语义，行为同旧版）。 */
    data class AnswerSnapshot(val text: String, val segmentId: String? = null) : RelayEvent

    /** 思考快照（**替换**当前思考块全文），segment 语义同 [AnswerSnapshot]。 */
    data class ThinkingSnapshot(val text: String, val segmentId: String? = null) : RelayEvent

    /** 回合正常结束（带可选 usage）。 */
    data class TurnDone(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
    ) : RelayEvent

    /** 回合失败。 */
    data class TurnFailed(val message: String) : RelayEvent

    /** 未识别 kind——容忍协议演进，reducer 忽略。 */
    data object Unknown : RelayEvent
}

/** 一个 seq 标记的 relay 信封（手机按 `seq > 已处理` 在重连后追赶）。 */
data class RelayEnvelope(val sessionId: String, val seq: Long, val event: RelayEvent)

/** relay 连接状态（由 [AgentControlViewModel] 驱动 UI 展示）。 */
enum class RelayConnectionState {
    /** 未连接 / 未启动 */
    Disconnected,
    /** 首次连接中 */
    Connecting,
    /** 已连接且心跳正常 */
    Connected,
    /** 连接断开，正在重试 */
    Reconnecting,
}
