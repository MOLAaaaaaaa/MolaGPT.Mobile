package com.molagpt.app.core.model

/**
 * 手机远控电脑上的 MolaGPT 对话（Work / BYOK）。协议见 `docs/desktop-remote-design.md`。
 * 对话内容只在电脑上，这里的类型都是电脑发来的投影。
 */
data class DesktopInfo(
    val machineId: String,
    val machineName: String,
    val appVersion: String,
    val online: Boolean,
    val lastSeenAtMs: Long,
    val conversations: List<DesktopConversation>,
)

data class DesktopConversation(
    val id: String,
    val title: String,
    val modelLabel: String?,
    val providerLabel: String?,
    val updatedAtMs: Long,
    val busy: Boolean,
    val pendingApprovals: Int,
)

/** 电脑发来的一条待审批。 */
data class RemoteApproval(
    val requestId: String,
    val conversationId: String,
    val kind: String,
    val toolName: String,
    val displayName: String,
    val summary: String?,
    val argumentsPreview: String?,
    val codePreview: String?,
    val resolvedPath: String?,
    val capabilities: List<String>,
    val options: List<String>,
    val createdAtMs: Long,
) {
    val isPython: Boolean get() = kind == "python"
}

object RemoteApprovalDecision {
    const val ONCE = "once"
    const val SESSION = "session"
    const val DENY = "deny"
}

enum class DesktopLinkState { Disconnected, Connecting, Connected }

/**
 * 电脑上一个对话在手机上可调的设置：模型、网络访问、推理。能力与档位由电脑按它自己的规则给出，
 * 手机不再按本机方言表推算，两边才不会各说各话。
 */
data class RemoteSettings(
    val providerId: String,
    val modelId: String,
    val model: String,
    val provider: String,
    /** `work` 或 `byok`。 */
    val family: String,
    val networkAvailable: Boolean,
    val networkOn: Boolean,
    /** 推理能关；常开模型为 false。 */
    val thinkingToggle: Boolean,
    val thinkingOn: Boolean,
    /** 空表示只有开关。 */
    val effortLevels: List<String>,
    val effort: String?,
    /** 电脑能收的附件；旧版电脑不收附件，为 null。 */
    val attachments: RemoteAttachmentRules? = null,
) {
    val familyLabel: String get() = if (family == "work") "Work" else "BYOK"

    /** 交给输入区的模型：能力按电脑给的来。 */
    fun composerModel(): ProviderModel {
        val adjustable = thinkingToggle || effortLevels.isNotEmpty()
        return ProviderModel(
            id = modelId,
            displayName = model,
            apiUrl = "",
            supportsThinking = thinkingToggle,
            supportsReasoningEffort = effortLevels.isNotEmpty(),
            supportsToolCalling = networkAvailable,
            providerId = providerId,
            providerName = provider,
            providerKind = ProviderKind.BYOK,
            thinkingConfig = if (!adjustable) null else ThinkingConfig(
                // 没有档位时只给开关：KIMI 在档位表里就是「仅开关」。
                kind = if (effortLevels.isEmpty()) ThinkingParamKind.KIMI else ThinkingParamKind.OPENAI_REASONING_EFFORT,
                effortLevels = effortLevels,
                defaultEffort = effort ?: effortLevels.firstOrNull() ?: ThinkingKinds.MEDIUM,
                alwaysOn = !thinkingToggle,
            ),
        )
    }
}

/** 电脑上可切换到的模型，按服务商分组。 */
data class RemoteModelGroup(
    val providerId: String,
    val provider: String,
    val family: String,
    val models: List<RemoteModelChoice>,
)

data class RemoteModelChoice(val id: String, val label: String)

/**
 * 电脑按自己输入区的规则给出的附件准入。文本、PDF、Office 文档总能发送。
 *
 * @property images 模型能看图（自身支持视觉，或电脑配置了视觉代理）。
 * @property files 能收抽不出文字的二进制文件（需电脑上的 Python 工具）。
 */
data class RemoteAttachmentRules(val images: Boolean, val files: Boolean)

/**
 * 电脑上可用来新建对话的角色。头像是电脑端的图标字形，手机不显示。
 *
 * @property preview 简介或提示词首行，空表示没有提示词。
 */
data class RemotePersona(val id: String, val name: String, val preview: String = "")

/** 在电脑上新建对话时的初始设置，见协议文档 §5.8。 */
data class RemoteDraftDefaults(
    val settings: RemoteSettings,
    val personaId: String?,
    val personas: List<RemotePersona>,
)
