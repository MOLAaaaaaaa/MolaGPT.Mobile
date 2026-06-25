package com.molagpt.app.core.model

/**
 * 一个预置 / 自定义角色（Persona）：捆绑一段 system prompt + 图标 + 名称 + 少量默认开关。
 *
 * **仅 BYOK 模型生效**——官方 MolaGPT 账号模型的系统提示与个性化记忆由服务端统一注入，
 * 不暴露角色配置，避免双重 system。镜像桌面端 `personas` 表设计，便于未来云同步对齐。
 *
 * [icon] 是图标 key（映射到 `core:render` 的 PersonaIcons 矢量图标），不存 emoji。
 * [systemPrompt] 支持 `{{date}}/{{time}}/{{model}}/{{provider}}/{{username}}` 变量，发送时插值。
 */
data class Persona(
    val id: String,
    val name: String,
    /** 图标 key（PersonaIcons.resolve）；空则用默认图标。 */
    val icon: String? = null,
    val systemPrompt: String = "",
    /** 默认工具开关（null = 不强制，沿用会话当前设置）。预留，首版 UI 暂不暴露。 */
    val defaultEnableNetwork: Boolean? = null,
    val defaultEnableWebFetch: Boolean? = null,
    val defaultThinking: Boolean? = null,
    val defaultReasoningEffort: String? = null,
    val sortOrder: Int = 0,
    val pinned: Boolean = false,
    /** 内置角色：不可删除（可复制成副本再改）。 */
    val isBuiltin: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 提示词首行预览，用于列表副标题。 */
    val preview: String
        get() = systemPrompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: "自定义角色，尚未设置提示词"

    companion object {
        /** 内置「通用助手」的固定 id：会话未显式选角色时，BYOK 链路以它兜底。 */
        const val BUILTIN_DEFAULT_ID = "builtin-default"

        /** 角色提示词与会话级提示词的合并模式。 */
        const val MODE_OVERRIDE = "override"
        const val MODE_APPEND = "append"
    }
}
