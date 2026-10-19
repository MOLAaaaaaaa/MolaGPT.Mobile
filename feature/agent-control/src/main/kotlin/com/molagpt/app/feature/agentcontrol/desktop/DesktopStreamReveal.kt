package com.molagpt.app.feature.agentcontrol.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 电脑上一条回答按时间线逐字放出时的「截到第几个字」视图。
 *
 * 电脑大约每秒才推一次整条消息，原样显示就是一秒一大段。这里把一条回答看成按时间线排开的一串：
 * 正文切片、思考段（按字计）、工具卡（不占字数，走到就出现）；[truncate] 只保留前 [budget] 个字，
 * 交给 [DesktopMessageMapper] 照常映射。时间线与映射器的排法一致（插入点、再按 timeline_index），
 * 保留的总是前缀，所以片段 id 在放出过程中保持稳定。
 */
internal object DesktopStreamReveal {

    /** 整条回答在时间线上的总字数：正文加全部思考。 */
    fun length(message: JsonObject): Int {
        val content = message.str("content").orEmpty()
        return content.length + timeline(message, content).sumOf { (it as? Item.Thinking)?.text?.length ?: 0 }
    }

    /** 只保留时间线上前 [budget] 个字；并标成仍在生成，放完之前不出现操作栏与统计。 */
    fun truncate(message: JsonObject, budget: Int): JsonObject {
        val content = message.str("content").orEmpty()
        val meta = message["meta"] as? JsonObject ?: JsonObject(emptyMap())
        var remaining = budget.coerceAtLeast(0)
        var contentEnd = 0
        val keptSegments = mutableListOf<JsonObject>()
        val keptTools = mutableListOf<JsonObject>()
        var legacyThinking: String? = null
        var stopped = false
        for (item in timeline(message, content)) {
            val gap = item.offset - contentEnd
            if (gap > 0) {
                val take = minOf(gap, remaining)
                contentEnd += take
                remaining -= take
                if (take < gap) {
                    stopped = true
                    break
                }
            }
            when (item) {
                is Item.Tool -> keptTools += item.json
                is Item.Thinking -> {
                    val take = minOf(item.text.length, remaining)
                    remaining -= take
                    val text = item.text.substring(0, take)
                    if (item.json == null) legacyThinking = text
                    else keptSegments += JsonObject(item.json + mapOf("source" to JsonPrimitive(text), "timeline_index" to JsonPrimitive(item.order)))
                    if (take < item.text.length) {
                        stopped = true
                        break
                    }
                }
            }
        }
        if (!stopped) contentEnd += minOf(content.length - contentEnd, remaining)

        val newMeta = buildMap {
            putAll(meta)
            put("streaming", JsonPrimitive(true))
            remove("stopped")
            if (meta["thinking_segments"] != null) put("thinking_segments", JsonArray(keptSegments))
            if (meta["tool_calls"] != null) put("tool_calls", JsonArray(keptTools))
            if (meta["thinking"] != null) put("thinking", JsonPrimitive(legacyThinking.orEmpty()))
        }
        return JsonObject(message + mapOf("content" to JsonPrimitive(content.substring(0, contentEnd)), "meta" to JsonObject(newMeta)))
    }

    private sealed interface Item {
        val offset: Int
        val order: Int

        /** [json] 为 null 时是没有分段信息的老消息，整段思考放在最前。 */
        class Thinking(override val offset: Int, override val order: Int, val text: String, val json: JsonObject?) : Item

        /** 工具卡带上排序用的 timeline_index，截断后映射器仍能排出同样的顺序。 */
        class Tool(override val offset: Int, override val order: Int, val json: JsonObject) : Item
    }

    /** 与 [DesktopMessageMapper] 相同的插入点与排序规则。 */
    private fun timeline(message: JsonObject, content: String): List<Item> {
        val meta = message["meta"] as? JsonObject ?: return emptyList()
        val items = mutableListOf<Item>()
        (meta["thinking_segments"] as? JsonArray)?.forEachIndexed { index, element ->
            val segment = element as? JsonObject ?: return@forEachIndexed
            val text = segment.str("source")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            items += Item.Thinking(
                offset = (segment.integer("content_offset") ?: 0).coerceIn(0, content.length),
                order = segment.integer("timeline_index") ?: index,
                text = text,
                json = segment,
            )
        }
        if (items.isEmpty()) {
            meta.str("thinking")?.takeIf { it.isNotBlank() }?.let { items += Item.Thinking(0, -1, it, null) }
        }
        (meta["tool_calls"] as? JsonArray)?.forEachIndexed { index, element ->
            val tool = element as? JsonObject ?: return@forEachIndexed
            val order = tool.integer("timeline_index") ?: (1000 + index)
            items += Item.Tool(
                offset = (tool.integer("content_offset") ?: content.length).coerceIn(0, content.length),
                order = order,
                json = JsonObject(tool + ("timeline_index" to JsonPrimitive(order))),
            )
        }
        return items.sortedWith(compareBy<Item> { it.offset }.thenBy { it.order })
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
}
