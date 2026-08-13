package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.TitleMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 标题提示词：跟随用户语言、禁标点、直接回标题、限长。
 * `{locale}` / `{content}` 为占位符，见 [buildTitlePrompt]。
 */
internal const val DEFAULT_TITLE_PROMPT = """我会在 <content> 块里给你一段对话内容。
请把这段用户与助手的对话总结成一个简短标题。
1. 标题语言与用户的主要语言保持一致
2. 不要使用标点符号或其他特殊符号
3. 直接回复标题本身，不要任何前缀、引号或解释
4. 使用 {locale} 语言总结
5. 标题不超过 12 个字

<content>
{content}
</content>"""

/** 把消息窗口拼成喂给标题模型的 `content`。 */
internal fun formatTitleContent(messages: List<TitleMessage>): String =
    messages.joinToString("\n\n") { message ->
        val speaker = if (message.role == Role.ASSISTANT) "Assistant" else "User"
        "$speaker: ${message.text}"
    }

internal fun buildTitlePrompt(messages: List<TitleMessage>, locale: String): String =
    DEFAULT_TITLE_PROMPT
        .replace("{locale}", locale)
        .replace("{content}", formatTitleContent(messages))

/**
 * 清洗模型返回的标题：剥思考标签 → 取首个非空行 → 去引号/「标题：」前缀 → 去首尾标点 → 限长。
 *
 * 返回 null 表示不可用（空、或明显是错误报文/JSON），调用方据此回退到占位标题——
 * 绝不能把 `HTTP 401 {...}` 这种串写成会话标题。
 */
internal fun cleanGeneratedTitle(raw: String?): String? {
    var title = raw?.let(::stripThinkTags)?.trim() ?: return null
    title = title.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    if (looksLikeErrorPayload(title)) return null
    title = title.replace(Regex("^[\"'“”‘’「」『』《》*#\\s]+|[\"'“”‘’「」『』《》*\\s]+$"), "")
    title = title.replace(Regex("^(标题|title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "").trim()
    title = title.trim('。', '，', ',', '.', ':', '：', ';', '；', '!', '！', '?', '？')
    if (title.length > 30) title = title.take(30).trim()
    return title.ifBlank { null }
}

/** 推理模型可能把 `<think>` 块混进正文；标题只要结论。 */
private fun stripThinkTags(raw: String): String =
    raw.replace(Regex("(?s)<think(ing)?>.*?</think(ing)?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("(?s)<think(ing)?>.*", RegexOption.IGNORE_CASE), " ")

private fun looksLikeErrorPayload(text: String): Boolean =
    text.startsWith("{") || text.startsWith("[") ||
        text.startsWith("<") || Regex("HTTP\\s*[45]\\d\\d").containsMatchIn(text)

/**
 * 从 BYOK 非流式响应里抽标题正文。**只认成功形状**，其余一律 null（由调用方回退）——
 * 与外挂视觉共用的 `parseXxxTextResult` 不同，那些出错时会返回人类可读的错误串，
 * 那对喂回模型有用，但作为标题就是脏数据。
 */
internal fun parseTitleResponse(type: ByokProviderType, json: Json, body: String): String? {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    // 不少 OpenAI 兼容网关在**成功**响应里也带 `"error": null`，kotlinx 会解析成 JsonNull
    // （一个非 Kotlin-null 的对象）。用 `!= null` 判定会把正常响应误判成错误、静默丢掉标题。
    root["error"]?.takeIf { it !is JsonNull }?.let { return null }
    return when (type) {
        ByokProviderType.OPENAI_COMPAT -> (root["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.textOrJoined()

        ByokProviderType.OPENAI_RESPONSE -> (root["output"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            ?.mapNotNull { message ->
                (message["content"] as? JsonArray)
                    ?.mapNotNull { part ->
                        val obj = part as? JsonObject ?: return@mapNotNull null
                        if (obj["type"]?.jsonPrimitive?.contentOrNull != "output_text") return@mapNotNull null
                        obj["text"]?.jsonPrimitive?.contentOrNull
                    }
                    ?.joinToString("")
            }
            ?.joinToString("")

        // Anthropic / Gemini 的思考内容与正文同在一个数组里，必须按类型挑出正文块。
        ByokProviderType.ANTHROPIC -> (root["content"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")

        ByokProviderType.GEMINI -> (root["candidates"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.let { it as? JsonArray }
            ?.mapNotNull { it as? JsonObject }
            ?.filterNot { it["thought"]?.jsonPrimitive?.booleanOrNull == true }
            ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
    }?.takeIf { it.isNotBlank() }
}

/** OpenAI 兼容层的 content 可能是字符串，也可能是 `[{type:text,text:...}]`。 */
private fun kotlinx.serialization.json.JsonElement.textOrJoined(): String? = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
    else -> jsonPrimitive.contentOrNull
}
