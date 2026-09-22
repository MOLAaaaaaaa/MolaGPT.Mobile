package com.molagpt.app.core.model

/**
 * 角色系统提示词的组装器：变量插值 + 角色/会话提示词合并。移植自桌面端 SystemPromptInterpolator。
 * 纯逻辑、不依赖 Android，便于单测。仅 BYOK 链路使用。
 */
object SystemPromptComposer {

    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}""")

    const val PREFIX_CACHE_WARNING =
        "使用 {{time}} 或 {{datetime}} 会使前缀缓存每分钟失效，可能增加输入成本；仅需日期时请使用 {{date}}。"

    fun breaksPrefixCache(template: String?): Boolean {
        if (template.isNullOrEmpty() || !template.contains("{{")) return false
        return PLACEHOLDER.findAll(template).any { match ->
            val name = match.groupValues[1]
            name.equals("time", ignoreCase = true) || name.equals("datetime", ignoreCase = true)
        }
    }

    /**
     * 替换 `{{var}}` 占位符。支持：date/time/datetime/model/model_id/provider/username。
     * 未识别的占位符原样保留（用户可能写了 JSON 样式的花括号文本）。
     */
    fun interpolate(template: String?, vars: PromptVariables): String {
        if (template.isNullOrEmpty()) return ""
        if (!template.contains("{{")) return template
        return PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            when (key.lowercase()) {
                "date" -> vars.date
                "time" -> vars.time
                "datetime" -> vars.datetime
                "model" -> vars.modelDisplayName ?: match.value
                "model_id" -> vars.modelId ?: match.value
                "provider" -> vars.providerName ?: match.value
                "username", "user" -> vars.username?.takeIf { it.isNotBlank() } ?: "用户"
                // 角色卡里通行的两个写法，指同一件事。
                "char", "character" -> vars.characterName?.takeIf { it.isNotBlank() } ?: match.value
                "original" -> vars.original
                // 角色字段与世界书的命名出口；都没有就原样留着（用户可能写的是 JSON 样式的花括号）。
                else -> vars.roleFields[key] ?: vars.outlets[key] ?: match.value
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
 *
 * 后四项只有角色扮演链路会用到：[characterName] 对应 `{{char}}`，[roleFields] 是角色卡的
 * description / personality / scenario / persona 等字段，[outlets] 是世界书的命名出口，
 * [original] 供会话级提示词用 `{{original}}` 引用角色本身的提示词。
 */
data class PromptVariables(
    val date: String,
    val time: String,
    val datetime: String,
    val modelDisplayName: String? = null,
    val modelId: String? = null,
    val providerName: String? = null,
    val username: String? = null,
    val characterName: String? = null,
    val roleFields: Map<String, String> = emptyMap(),
    val outlets: Map<String, String> = emptyMap(),
    val original: String = "",
)
