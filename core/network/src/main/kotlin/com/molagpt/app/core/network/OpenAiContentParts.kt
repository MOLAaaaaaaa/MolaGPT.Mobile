package com.molagpt.app.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Chat Completions 的 content 可以是字符串，也可以按顺序包含 text / thinking 块。 */
internal fun forEachOpenAiContentPart(
    content: JsonElement?,
    onText: (String) -> Unit,
    onThinking: (String) -> Unit,
) {
    if (content is JsonPrimitive) {
        content.contentOrNull?.let(onText)
        return
    }
    (content as? JsonArray)?.forEach { part ->
        if (part is JsonPrimitive) part.contentOrNull?.let(onText)
        val block = part as? JsonObject ?: return@forEach
        when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> (block["text"] as? JsonPrimitive)?.contentOrNull?.let(onText)
            "thinking" -> (block["thinking"] as? JsonArray)?.forEach { thought ->
                val text = thought as? JsonObject
                if ((text?.get("type") as? JsonPrimitive)?.contentOrNull == "text") {
                    (text["text"] as? JsonPrimitive)?.contentOrNull?.let(onThinking)
                }
            }
        }
    }
}

internal fun openAiReplayContent(text: String, thinking: String): JsonElement =
    if (thinking.isEmpty()) JsonPrimitive(text) else buildJsonArray {
        addJsonObject {
            put("type", "thinking")
            putJsonArray("thinking") { addJsonObject { put("type", "text"); put("text", thinking) } }
            put("closed", true)
        }
        if (text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", text) }
    }
