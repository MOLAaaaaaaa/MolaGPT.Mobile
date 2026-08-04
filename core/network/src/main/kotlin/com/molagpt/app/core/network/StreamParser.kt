package com.molagpt.app.core.network

import com.molagpt.app.core.model.SourceReference
import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.Usage
import com.molagpt.app.core.network.sse.SsePayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 把单个 [SsePayload] 解析成若干 [StreamEvent]。兼容 OpenAI 风格的 `choices[].delta`，
 * 同时容错各代理的字段差异；用 JsonElement 导航而非严格 DTO，以适应 provider 差异。
 *
 * 每个流 new 一个实例（持有 [InlineThinkSplitter] 状态）。
 *
 * 注意：reasoning 通道命名、tool_calls 结构、sources 形状存在代理差异；此处覆盖常见形态并做容错。
 */
class StreamParser(
    private val json: Json,
    /** Responses 最终回答流可只接收 final_answer；通用解析默认保留全部文本。 */
    private val responseFinalAnswerOnly: Boolean = false,
) {
    private val think = InlineThinkSplitter()
    private val webArtifacts = WebToolArtifactSplitter()
    private val responseMessagePhases = HashMap<String, String>()

    fun parse(payload: SsePayload): List<StreamEvent> {
        if (payload.isDone) return finishTail("stop")
        val raw = payload.data
        if (raw.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyList()

        val events = ArrayList<StreamEvent>(2)

        // —— 流内错误 ——
        root["error"]?.let { err ->
            val msg = (err as? JsonPrimitive)?.contentOrNull
                ?: (err as? JsonObject)?.get("message")?.prim()?.contentOrNull
            if (!msg.isNullOrBlank()) return listOf(StreamEvent.Failed(msg))
        }

        // —— 来源 / 引用 ——
        parseSources(root)?.let { if (it.isNotEmpty()) events.add(StreamEvent.Sources(it)) }

        val eventType = root["type"]?.prim()?.contentOrNull ?: payload.event
        rememberResponseMessagePhase(root, eventType)
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject

        // —— 增量正文 / 推理 ——
        val contentText = delta?.get("content")?.prim()?.contentOrNull
            ?: root["content"]?.prim()?.contentOrNull
            ?: responseTextDelta(root, eventType)
        var thinking = delta?.get("reasoning_content")?.prim()?.contentOrNull
            ?: delta?.get("reasoning")?.prim()?.contentOrNull
            ?: responseThinkingDelta(root, eventType)
        if (!thinking.isNullOrEmpty()) {
            events.add(StreamEvent.Delta(thinking = thinking))
        }
        if (!contentText.isNullOrEmpty()) {
            webArtifacts.feed(contentText).forEach { artifact ->
                appendArtifact(artifact, events)
            }
        }

        // —— 工具调用（简化：标记运行中；完整合成属后续增强）——
        (delta?.get("tool_calls") as? JsonArray)?.forEach { tc ->
            val o = tc as? JsonObject ?: return@forEach
            val fn = o["function"] as? JsonObject
            val name = fn?.get("name")?.prim()?.contentOrNull ?: "tool"
            val id = o["id"]?.prim()?.contentOrNull ?: name
            val args = fn?.get("arguments")?.prim()?.contentOrNull
            events.add(StreamEvent.Tool(id = id, name = name, status = ToolStatus.RUNNING, argsJson = args))
        }

        // —— 结束 ——
        val finish = choice?.get("finish_reason")?.prim()?.contentOrNull
            ?: responseFinishReason(eventType)
        if (!finish.isNullOrEmpty()) {
            finishTail(finish, parseUsage(root)).forEach(events::add)
        }
        return events
    }

    /** 流自然结束（EOF / [DONE]）时调用，冲刷 splitter 残余并补一个 Finish。 */
    fun finishTail(reason: String?, usage: Usage? = null): List<StreamEvent> {
        val out = ArrayList<StreamEvent>(2)
        webArtifacts.flush().forEach { artifact ->
            appendArtifact(artifact, out)
        }
        val tail = think.flush()
        if (tail.visible.isNotEmpty() || tail.thinking.isNotEmpty()) {
            out.add(StreamEvent.Delta(text = tail.visible.ifEmpty { null }, thinking = tail.thinking.ifEmpty { null }))
        }
        out.add(StreamEvent.Finish(reason = reason, usage = usage))
        return out
    }

    private fun appendArtifact(artifact: WebToolArtifact, events: MutableList<StreamEvent>) {
        when (artifact) {
            is WebToolArtifact.Text -> {
                val split = think.feed(artifact.text)
                if (split.visible.isNotEmpty() || split.thinking.isNotEmpty()) {
                    events.add(
                        StreamEvent.Delta(
                            text = split.visible.ifEmpty { null },
                            thinking = split.thinking.ifEmpty { null },
                        ),
                    )
                }
            }
            is WebToolArtifact.Tool -> {
                events.add(
                    StreamEvent.Tool(
                        id = artifact.id,
                        name = artifact.name,
                        status = artifact.status,
                        label = artifact.label,
                        resultPreview = artifact.preview,
                        provider = artifact.provider,
                    ),
                )
            }
        }
    }

    private fun parseUsage(root: JsonObject): Usage? {
        val u = root["usage"] as? JsonObject ?: return null
        val details = u["completion_tokens_details"] as? JsonObject
        return Usage(
            promptTokens = u["prompt_tokens"]?.prim()?.intOrNull,
            completionTokens = u["completion_tokens"]?.prim()?.intOrNull,
            totalTokens = u["total_tokens"]?.prim()?.intOrNull,
            reasoningTokens = details?.get("reasoning_tokens")?.prim()?.intOrNull
                ?: u["reasoning_tokens"]?.prim()?.intOrNull,
        )
    }

    private fun responseTextDelta(root: JsonObject, eventType: String?): String? {
        val deltaText = root["delta"]?.prim()?.contentOrNull
        return when (eventType) {
            "response.output_text.delta", "response.refusal.delta" -> {
                val itemId = root["item_id"]?.prim()?.contentOrNull
                val phase = root["phase"]?.prim()?.contentOrNull
                    ?: itemId?.let(responseMessagePhases::get)
                if (responseFinalAnswerOnly && phase.equals("commentary", ignoreCase = true)) {
                    null
                } else {
                    deltaText
                }
            }
            // output_text.done 的 text 是完整累积文本；增量 delta 已覆盖全部内容，
            // 再取全量会导致 AppendText 把全文再拼一次 → 重复渲染。
            "response.output_text.done" -> null
            else -> null
        }
    }

    private fun rememberResponseMessagePhase(root: JsonObject, eventType: String?) {
        if (eventType != "response.output_item.added" && eventType != "response.output_item.done") return
        val item = root["item"] as? JsonObject ?: return
        if (item["type"]?.prim()?.contentOrNull != "message") return
        val id = item["id"]?.prim()?.contentOrNull ?: return
        val phase = item["phase"]?.prim()?.contentOrNull ?: return
        responseMessagePhases[id] = phase
    }

    private fun responseThinkingDelta(root: JsonObject, eventType: String?): String? {
        val deltaText = root["delta"]?.prim()?.contentOrNull ?: return null
        return if (eventType?.contains("reasoning", ignoreCase = true) == true &&
            eventType.endsWith(".delta")
        ) {
            deltaText
        } else {
            null
        }
    }

    private fun responseFinishReason(eventType: String?): String? = when (eventType) {
        "response.completed" -> "stop"
        "response.incomplete" -> "incomplete"
        "response.failed" -> "error"
        else -> null
    }

    private fun parseSources(root: JsonObject): List<SourceReference>? {
        // 真实键名是 molagpt_sources，同时兼容 sources/citations。
        val arr = (root["molagpt_sources"] as? JsonArray)
            ?: (root["sources"] as? JsonArray)
            ?: (root["citations"] as? JsonArray)
            ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o["url"]?.prim()?.contentOrNull ?: return@mapNotNull null
            SourceReference(
                title = o["title"]?.prim()?.contentOrNull ?: url,
                url = url,
                snippet = o["snippet"]?.prim()?.contentOrNull ?: o["content"]?.prim()?.contentOrNull,
                // 来源序号兼容 index 与 id。
                index = o["index"]?.prim()?.intOrNull ?: o["id"]?.prim()?.intOrNull,
            )
        }
    }

    private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive
}
