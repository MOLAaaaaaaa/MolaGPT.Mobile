package com.molagpt.app.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 缩短协议快照里过长的工具结果，只留开头。
 *
 * 较早轮次的网页正文、搜索结果、MCP 返回在回答里已经用过，之后每轮原样重发只是占上下文。
 * 工具调用和结果的配对、id、顺序一概不动——拆开配对会被服务商直接拒收。
 * 开头保留一段，网页的标题与链接、搜索结果的前几条都在里面，模型仍知道这次调用拿到了什么。
 */
object WireHistoryPruner {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @return 缩短后的快照；没有需要缩短的结果或无法解析时返回 null。 */
    fun prune(raw: String?, maxChars: Int, keepChars: Int): String? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val items = (root["items"] as? JsonArray)?.map { it as? JsonObject ?: return null } ?: return null
        var changed = false

        fun shorten(element: JsonElement?): JsonElement? {
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            if (text.length <= maxChars) return null
            changed = true
            return JsonPrimitive(
                text.take(keepChars) +
                    "\n\n[较早的工具结果，已省略后续 ${text.length - keepChars} 字。需要时请重新调用工具。]",
            )
        }

        fun JsonObject.replacing(key: String): JsonObject =
            shorten(this[key])?.let { JsonObject(this + (key to it)) } ?: this

        val pruned = items.map { item ->
            when (root.str("wire_api")) {
                OpenAiWireHistory.CHAT_COMPLETIONS ->
                    if (item.str("role") == "tool") item.replacing("content") else item

                OpenAiWireHistory.RESPONSES ->
                    if (item.str("type") == "function_call_output") item.replacing("output") else item

                NativeWireHistory.ANTHROPIC_MESSAGES -> {
                    val blocks = item["content"] as? JsonArray
                    if (blocks == null) {
                        item
                    } else {
                        val next = blocks.map { block ->
                            val obj = block as? JsonObject
                            if (obj?.str("type") == "tool_result") obj.replacing("content") else block
                        }
                        JsonObject(item + ("content" to JsonArray(next)))
                    }
                }

                NativeWireHistory.GEMINI_GENERATE_CONTENT -> {
                    val parts = item["parts"] as? JsonArray
                    if (parts == null) {
                        item
                    } else {
                        val next = parts.map { part ->
                            val obj = part as? JsonObject ?: return@map part
                            val call = obj["functionResponse"] as? JsonObject ?: return@map part
                            val response = call["response"] as? JsonObject ?: return@map part
                            val shortened = response.replacing("result").replacing("error")
                            if (shortened === response) part
                            else JsonObject(obj + ("functionResponse" to JsonObject(call + ("response" to shortened))))
                        }
                        JsonObject(item + ("parts" to JsonArray(next)))
                    }
                }

                else -> return null
            }
        }
        if (!changed) return null
        return JsonObject(root + ("items" to JsonArray(pruned))).toString()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
