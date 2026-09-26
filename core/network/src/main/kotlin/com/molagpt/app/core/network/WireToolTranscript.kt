package com.molagpt.app.core.network

import com.molagpt.app.core.model.ChatMessageMetadataKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从助手消息的协议快照里取出工具调用与工具结果，按发生顺序排列。
 *
 * 界面上的工具卡只存预览，完整结果只在快照里。上下文摘要需要结果本身，
 * 否则搜索、网页、MCP 返回的事实会随压缩一起丢掉。
 */
object WireToolTranscript {
    data class Entry(
        /** true = 调用（[text] 为参数），false = 结果。 */
        val isCall: Boolean,
        val name: String?,
        val text: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun extract(metadata: Map<String, String>): List<Entry> {
        val raw = ChatMessageMetadataKeys.WIRE_HISTORY.firstNotNullOfOrNull { key ->
            metadata[key]?.takeIf { it.isNotBlank() }
        } ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val items = (root["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        return when (root.str("wire_api")) {
            OpenAiWireHistory.CHAT_COMPLETIONS -> chatCompletions(items)
            OpenAiWireHistory.RESPONSES -> responses(items)
            NativeWireHistory.ANTHROPIC_MESSAGES -> anthropic(items)
            NativeWireHistory.GEMINI_GENERATE_CONTENT -> gemini(items)
            else -> emptyList()
        }
    }

    private fun chatCompletions(items: List<JsonObject>): List<Entry> {
        val names = mutableMapOf<String, String>()
        return buildList {
            items.forEach { item ->
                when (item.str("role")) {
                    "assistant" -> (item["tool_calls"] as? JsonArray)?.forEach { call ->
                        val obj = call as? JsonObject ?: return@forEach
                        val fn = obj["function"] as? JsonObject
                        val name = fn?.str("name")
                        obj.str("id")?.let { id -> name?.let { names[id] = it } }
                        add(Entry(true, name, fn?.str("arguments").orEmpty()))
                    }
                    "tool" -> add(Entry(false, item.str("tool_call_id")?.let(names::get), textOf(item["content"])))
                }
            }
        }
    }

    private fun responses(items: List<JsonObject>): List<Entry> {
        val names = mutableMapOf<String, String>()
        return buildList {
            items.forEach { item ->
                when (item.str("type")) {
                    "function_call" -> {
                        val name = item.str("name")
                        item.str("call_id")?.let { id -> name?.let { names[id] = it } }
                        add(Entry(true, name, item.str("arguments").orEmpty()))
                    }
                    "function_call_output" ->
                        add(Entry(false, item.str("call_id")?.let(names::get), textOf(item["output"])))
                }
            }
        }
    }

    private fun anthropic(items: List<JsonObject>): List<Entry> {
        val names = mutableMapOf<String, String>()
        return buildList {
            items.forEach { item ->
                val blocks = (item["content"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@forEach
                blocks.forEach { block ->
                    when (block.str("type")) {
                        "tool_use" -> {
                            val name = block.str("name")
                            block.str("id")?.let { id -> name?.let { names[id] = it } }
                            add(Entry(true, name, block["input"]?.toString().orEmpty()))
                        }
                        "tool_result" ->
                            add(Entry(false, block.str("tool_use_id")?.let(names::get), textOf(block["content"])))
                    }
                }
            }
        }
    }

    private fun gemini(items: List<JsonObject>): List<Entry> = buildList {
        items.forEach { item ->
            val parts = (item["parts"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@forEach
            parts.forEach { part ->
                (part["functionCall"] as? JsonObject)?.let { call ->
                    add(Entry(true, call.str("name"), call["args"]?.toString().orEmpty()))
                }
                (part["functionResponse"] as? JsonObject)?.let { response ->
                    add(Entry(false, response.str("name"), textOf(response["response"])))
                }
            }
        }
    }

    /** 结果可能是字符串、文本块数组或任意 JSON；统一成一段文本。 */
    private fun textOf(element: JsonElement?): String = when (element) {
        null -> ""
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString("\n") { part ->
            val obj = part as? JsonObject
            obj?.str("text") ?: textOf(part)
        }
        is JsonObject -> element.str("text") ?: element.str("content") ?: element.toString()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
