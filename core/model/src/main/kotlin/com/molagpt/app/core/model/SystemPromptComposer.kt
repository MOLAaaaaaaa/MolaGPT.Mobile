package com.molagpt.app.core.model

/**
 * 角色系统提示词的组装器：变量插值 + 角色/会话提示词合并。移植自桌面端 SystemPromptInterpolator。
 * 纯逻辑、不依赖 Android，便于单测。仅 BYOK 链路使用。
 */
object SystemPromptComposer {

    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}""")

    /**
     * 替换 `{{var}}` 占位符。支持：date/time/datetime/model/model_id/provider/username。
     * 未识别的占位符原样保留（用户可能写了 JSON 样式的花括号文本）。
     */
    fun interpolate(template: String?, vars: PromptVariables): String {
        if (template.isNullOrEmpty()) return ""
        if (!template.contains("{{")) return template
        return PLACEHOLDER.replace(template) { match ->
            when (match.groupValues[1].lowercase()) {
                "date" -> vars.date
                "time" -> vars.time
                "datetime" -> vars.datetime
                "model" -> vars.modelDisplayName ?: match.value
                "model_id" -> vars.modelId ?: match.value
                "provider" -> vars.providerName ?: match.value
                "username" -> vars.username?.takeIf { it.isNotBlank() } ?: "用户"
                else -> match.value
            }
        }
    }

    /**
     * 合并角色提示词与会话级提示词。
     * @param mode [Persona.MODE_OVERRIDE]（默认，会话级覆盖角色）或 [Persona.MODE_APPEND]（追加在角色之后）。
     * 两者都空返回 null。
     */
    fun combine(personaPrompt: String?, conversationPrompt: String?, mode: String?): String? {
        val hasPersona = !personaPrompt.isNullOrBlank()
        val hasConv = !conversationPrompt.isNullOrBlank()
        return when {
            !hasPersona && !hasConv -> null
            !hasConv -> personaPrompt
            !hasPersona -> conversationPrompt
            mode.equals(Persona.MODE_APPEND, ignoreCase = true) ->
                personaPrompt!!.trimEnd() + "\n\n" + conversationPrompt!!.trimStart()
            else -> conversationPrompt
        }
    }

    /** 一步到位：合并后插值。返回 null/空表示无需注入 system。 */
    fun compose(
        personaPrompt: String?,
        conversationPrompt: String?,
        mode: String?,
        vars: PromptVariables,
    ): String? {
        val merged = combine(personaPrompt, conversationPrompt, mode) ?: return null
        return interpolate(merged, vars).takeIf { it.isNotBlank() }
    }
}

/**
 * 插值上下文。date/time 由调用方按本地时区格式化后传入（保持本对象不依赖具体时间 API）。
 */
data class PromptVariables(
    val date: String,
    val time: String,
    val datetime: String,
    val modelDisplayName: String? = null,
    val modelId: String? = null,
    val providerName: String? = null,
    val username: String? = null,
)
