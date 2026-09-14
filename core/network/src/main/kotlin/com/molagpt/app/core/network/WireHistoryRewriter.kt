package com.molagpt.app.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一段可见正文的改写前后。[before] 用来核对快照与显示正文的分段是否真的对得上。 */
data class TextReplacement(val before: String, val after: String)

/**
 * 把**显示正文那一次改写的结果**搬进线格式快照。
 *
 * 关键是「搬」而不是「再跑一遍规则」。显示正文是合并过的：Gemini 可能分两个文本块回
 * `"甲,"` 和 `"乙"`，屏幕上是一条 `"甲,乙"`。规则在合并后的文本上命中（逗号两侧都是汉字），
 * 逐块再跑一遍却命中不了——同一条规则、不同分段，结果必然不一致。所以规则只跑一次，
 * 这里只负责把结果放回去。
 *
 * 分段口径：**连续的可见正文块合并成一段**，工具调用、工具结果、思考块、函数调用天然把段
 * 断开——这和显示层按 fragment 断开的位置是同一处。每段与一个显示 Text 片段一一对应，
 * 且该段合并后的原文必须与显示片段逐字相等；对不上就返回 null，调用方把整份快照丢掉。
 *
 * 不动的东西：工具调用及其 id、工具结果、Anthropic 的带签名思考块、Gemini 的 functionCall、
 * 消息身份与顺序。
 */
object WireHistoryRewriter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val KNOWN_WIRE_APIS = setOf(
        OpenAiWireHistory.CHAT_COMPLETIONS,
        OpenAiWireHistory.RESPONSES,
        NativeWireHistory.ANTHROPIC_MESSAGES,
        NativeWireHistory.GEMINI_GENERATE_CONTENT,
    )

    /**
     * @param replacements 按出现顺序排列的可见正文段，来自消息的 Text 片段。
     * @return 改写后的快照；分段对不上或形状不认识时返回 null。
     */
    fun rewrite(raw: String?, replacements: List<TextReplacement>): String? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val wireApi = root["wire_api"]?.jsonPrimitive?.contentOrNull ?: return null
        // 先认协议再看内容：不然一份 items 为空的陌生快照会一路走到底，被当成「改写成功」留下来。
        if (wireApi !in KNOWN_WIRE_APIS) return null
        val items = root["items"] as? JsonArray ?: return null

        val cursor = Cursor(replacements)
        val rewritten = ArrayList<JsonObject>(items.size)
        for (element in items) {
            val item = element as? JsonObject ?: return null
            val next = when (wireApi) {
                OpenAiWireHistory.CHAT_COMPLETIONS -> rewriteChatCompletion(item, cursor)
                OpenAiWireHistory.RESPONSES -> rewriteResponsesItem(item, cursor)
                NativeWireHistory.ANTHROPIC_MESSAGES -> rewriteAnthropicItem(item, cursor)
                NativeWireHistory.GEMINI_GENERATE_CONTENT -> rewriteGeminiItem(item, cursor)
                else -> return null
            }
            rewritten += next ?: return null
        }
        // 显示片段比快照多，说明两边对不上，同样不能留。
        if (!cursor.consumedAll) return null
        return JsonObject(root + ("items" to JsonArray(rewritten))).toString()
    }

    /** 按顺序发放替换结果，顺带核对分段。 */
    private class Cursor(private val replacements: List<TextReplacement>) {
        private var index = 0
        val consumedAll: Boolean get() = index == replacements.size

        fun next(before: String): String? {
            val replacement = replacements.getOrNull(index) ?: return null
            if (replacement.before != before) return null
            index++
            return replacement.after
        }
    }

    private fun rewriteChatCompletion(item: JsonObject, cursor: Cursor): JsonObject? {
        if (item.role() != "assistant") return item
        val content = item["content"] as? JsonPrimitive ?: return item
        if (!content.isString || content.content.isEmpty()) return item
        val after = cursor.next(content.content) ?: return null
        return JsonObject(item + ("content" to JsonPrimitive(after)))
    }

    private fun rewriteResponsesItem(item: JsonObject, cursor: Cursor): JsonObject? {
        if (item["type"]?.jsonPrimitive?.contentOrNull != "message") return item
        if (item.role() != "assistant") return item
        val content = item["content"] as? JsonArray ?: return item
        val blocks = mergeRuns(
            blocks = content,
            cursor = cursor,
            textOf = { block ->
                if (block["type"]?.jsonPrimitive?.contentOrNull == "output_text") block.text() else null
            },
        ) ?: return null
        return JsonObject(item + ("content" to JsonArray(blocks)))
    }

    private fun rewriteAnthropicItem(item: JsonObject, cursor: Cursor): JsonObject? {
        if (item.role() != "assistant") return item
        val content = item["content"] as? JsonArray ?: return item
        val blocks = mergeRuns(
            blocks = content,
            cursor = cursor,
            // thinking 块带签名、tool_use 带 id，都不是可见正文，也都把段断开。
            textOf = { block -> if (block["type"]?.jsonPrimitive?.contentOrNull == "text") block.text() else null },
        ) ?: return null
        return JsonObject(item + ("content" to JsonArray(blocks)))
    }

    private fun rewriteGeminiItem(item: JsonObject, cursor: Cursor): JsonObject? {
        if (item.role() != "model") return item
        val parts = item["parts"] as? JsonArray ?: return item
        val rewritten = mergeRuns(
            blocks = parts,
            cursor = cursor,
            textOf = { part ->
                val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                if (isThought || part.containsKey("functionCall")) null else part.text()
            },
        ) ?: return null
        return JsonObject(item + ("parts" to JsonArray(rewritten)))
    }

    /**
     * 把连续的可见正文块并成一段，用 [cursor] 发下来的结果写回该段的第一个块，其余块删掉。
     *
     * 合并而不是逐块替换，是因为改写后的文本没法按原来的块边界切开——`"甲," + "乙"` 变成
     * `"甲，乙"`，两块之间的那一刀已经不存在了。并成一块对模型完全等价，协议上也合法。
     */
    private fun mergeRuns(
        blocks: JsonArray,
        cursor: Cursor,
        textOf: (JsonObject) -> String?,
    ): List<JsonElement>? {
        val out = ArrayList<JsonElement>(blocks.size)
        var head: JsonObject? = null
        val joined = StringBuilder()

        // 收尾一段：把改写结果写进该段的第一个块，其余块已经被并掉。
        fun flush(): Boolean {
            val start = head ?: return true
            val before = joined.toString()
            head = null
            joined.setLength(0)
            // 整段都是空文本，屏幕上没有对应片段，直接丢掉，也不消费替换。
            if (before.isEmpty()) return true
            val after = cursor.next(before) ?: return false
            out += JsonObject(start + ("text" to JsonPrimitive(after)))
            return true
        }

        for (element in blocks) {
            val block = element as? JsonObject
            val text = block?.let(textOf)
            if (block != null && text != null) {
                if (head == null) head = block
                joined.append(text)
            } else {
                if (!flush()) return null
                out += element
            }
        }
        if (!flush()) return null
        return out
    }

    private fun JsonObject.role(): String? = this["role"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.text(): String = (this["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
}
