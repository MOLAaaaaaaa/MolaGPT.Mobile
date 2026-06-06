package com.molagpt.app.core.model

/**
 * 对话风格偏好 + 自定义指令。服务端 style_preferences schema：
 * `{styles:[wire...], custom_instruction}`。服务端会把它拼进 system prompt 影响后续回复语气。
 * [styles] 存预设风格的 wire 值（多选）；未知值原样保留，UI 经 [ConversationStyle.fromWire] 映射显示。
 */
data class StylePreferences(
    val styles: List<String> = emptyList(),
    val customInstruction: String = "",
) {
    fun hasStyle(style: ConversationStyle): Boolean = styles.contains(style.wire)

    /** 切换某预设风格的选中态，返回新对象（保留未知值原顺序，已知值去重）。 */
    fun toggled(style: ConversationStyle): StylePreferences =
        copy(styles = if (styles.contains(style.wire)) styles - style.wire else styles + style.wire)

    companion object {
        /** 自定义指令字数上限（与 UI 计数一致；后端不强制，前端约束体验）。 */
        const val CUSTOM_INSTRUCTION_MAX = 500
    }
}

/** 对话风格预设（多选）。 */
enum class ConversationStyle(val wire: String, val label: String) {
    MORE_DIRECT("more_direct", "更直接"),
    POLITE("polite", "更克制"),
    CONCISE("concise", "更精炼"),
    DETAILED("detailed", "更详细"),
    FORMAL("formal", "更专业"),
    CASUAL("casual", "更轻松"),
}
