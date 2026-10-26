package com.molagpt.app.feature.agentcontrol.desktop

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.SearchStatus
import com.molagpt.app.core.model.SourceReference
import com.molagpt.app.core.model.ToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 电脑发来的消息 → 聊天页的 [ChatMessage]。
 *
 * 电脑把一条回答存成一段正文加若干「插在某个字符位置」的思考段与工具卡：
 * `content_offset` 是插入点，同一位置按 `timeline_index` 排先后。这里按插入点把正文切开，
 * 依次生成 Text / Thinking / ToolCall 片段。片段 id 只取决于位置序号与工具 id，
 * 回答还在增长时已有片段的 id 不变，列表只重组变化的部分。
 */
object DesktopMessageMapper {

    fun map(conversationId: String, message: JsonObject): ChatMessage? {
        val id = message.string("id") ?: return null
        val role = when (message.string("role")) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> return null
        }
        val content = message.string("content").orEmpty()
        val meta = message["meta"] as? JsonObject ?: JsonObject(emptyMap())
        val createdAt = message.long("createdAtMs") ?: 0
        val truncated = message.long("truncated")

        if (role == Role.USER) {
            val notice = message.string("kind") == "task_notification"
            val text = if (notice) taskNotice(content) else content
            return ChatMessage(
                messageId = id,
                sessionId = conversationId,
                // 任务通知以 user 角色交给模型，界面按系统事件显示。
                role = if (notice) Role.SYSTEM else Role.USER,
                status = MessageStatus.COMPLETE,
                createdAt = createdAt,
                updatedAt = createdAt,
                fragments = if (notice) listOf(MessageFragment.Tip("$id:t0", text))
                    else listOf(MessageFragment.Text("$id:t0", text)),
                rawText = text,
                attachments = attachments(id, meta),
                metadata = buildMap { if (truncated != null) put(TRUNCATED_KEY, truncated.toString()) },
            )
        }

        val status = when {
            meta.bool("streaming") == true -> MessageStatus.STREAMING
            meta.bool("stopped") == true -> MessageStatus.STOPPED
            else -> MessageStatus.COMPLETE
        }
        val fragments = assistantFragments(id, content, meta, status == MessageStatus.STREAMING)
        return ChatMessage(
            messageId = id,
            sessionId = conversationId,
            role = Role.ASSISTANT,
            // 还没有任何内容时也算生成中：聊天列表只给生成中的空回答画占位，标成等待就只剩一个模型名。
            status = status,
            createdAt = createdAt,
            updatedAt = createdAt,
            fragments = fragments,
            rawText = content,
            model = meta.string("model"),
            metadata = stats(meta) + buildMap { if (truncated != null) put(TRUNCATED_KEY, truncated.toString()) },
        )
    }

    /** 正文只发了开头和结尾时，标在 metadata 里的全文长度。 */
    const val TRUNCATED_KEY = "desktopTruncated"

    private sealed interface Insert {
        val offset: Int
        val order: Int
    }

    private data class ThinkingInsert(override val offset: Int, override val order: Int, val text: String, val seconds: Double?) : Insert
    private data class ToolInsert(override val offset: Int, override val order: Int, val tool: JsonObject) : Insert

    private fun assistantFragments(id: String, content: String, meta: JsonObject, streaming: Boolean): List<MessageFragment> {
        val inserts = mutableListOf<Insert>()
        (meta["thinking_segments"] as? JsonArray)?.forEachIndexed { index, item ->
            val segment = item as? JsonObject ?: return@forEachIndexed
            val text = segment.string("source")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            inserts += ThinkingInsert(
                offset = (segment.int("content_offset") ?: 0).coerceIn(0, content.length),
                order = segment.int("timeline_index") ?: index,
                text = text,
                seconds = (segment["elapsed_seconds"] as? JsonPrimitive)?.doubleOrNull,
            )
        }
        // 没有分段信息的老消息：整段思考放在最前面。
        if (inserts.isEmpty()) {
            meta.string("thinking")?.takeIf { it.isNotBlank() }?.let { inserts += ThinkingInsert(0, -1, it, null) }
        }
        (meta["tool_calls"] as? JsonArray)?.forEachIndexed { index, item ->
            val tool = item as? JsonObject ?: return@forEachIndexed
            inserts += ToolInsert(
                offset = (tool.int("content_offset") ?: content.length).coerceIn(0, content.length),
                order = tool.int("timeline_index") ?: (1000 + index),
                tool = tool,
            )
        }
        inserts.sortWith(compareBy<Insert> { it.offset }.thenBy { it.order })

        val fragments = mutableListOf<MessageFragment>()
        var position = 0
        var textIndex = 0
        var thinkingIndex = 0
        fun text(until: Int) {
            if (until <= position) return
            val piece = content.substring(position, until)
            if (piece.isNotBlank()) fragments += MessageFragment.Text("$id:t${textIndex++}", piece)
            position = until
        }
        for (insert in inserts) {
            text(insert.offset)
            when (insert) {
                is ThinkingInsert -> fragments += MessageFragment.Thinking(
                    id = "$id:th${thinkingIndex++}",
                    text = insert.text,
                    durationMs = insert.seconds?.let { (it * 1000).toLong() },
                )
                is ToolInsert -> fragments += tool(id, insert.tool, fragments.size)
            }
        }
        text(content.length)

        // 与聊天页一致：思考进行中展开，后面出现正文才收起；写完的回答一律收起。
        fragments.forEachIndexed { index, fragment ->
            if (fragment is MessageFragment.Thinking) {
                val answered = fragments.subList(index + 1, fragments.size).any { it is MessageFragment.Text }
                fragments[index] = fragment.copy(collapsed = !streaming || answered)
            }
        }

        sources(meta)?.let { refs -> fragments += MessageFragment.SearchResult("$id:sources", query = "", refs = refs, status = SearchStatus.DONE) }
        if (!streaming && fragments.isEmpty() && meta.bool("stopped") == true) {
            fragments += MessageFragment.Tip("$id:stopped", "已停止")
        }
        return fragments
    }

    internal fun tool(messageId: String, tool: JsonObject, position: Int): MessageFragment.ToolCall {
        val toolId = tool.string("id")?.takeIf { it.isNotBlank() } ?: "p$position"
        val name = tool.string("name").orEmpty()
        val result = tool.string("result_preview_json")
        val resultJson = parseObject(result)
        val taskId = tool.string("background_task_id")
            ?: resultJson?.takeIf { it.bool("background") == true }?.string("task_id")
        val taskState = taskId?.let { tool.string("task_state") ?: "running" }
        val status = when (taskState) {
            null -> when (tool.string("status")?.lowercase()) {
                "completed", "succeeded", "success" -> ToolStatus.SUCCESS
                "failed", "cancelled", "canceled", "error", "denied", "interrupted" -> ToolStatus.FAILED
                else -> ToolStatus.RUNNING
            }
            "running" -> ToolStatus.RUNNING
            "completed" -> ToolStatus.SUCCESS
            else -> ToolStatus.FAILED
        }
        val statusLabel = when (taskState) {
            "running" -> "后台运行中"
            "cancelled" -> "已停止"
            "interrupted" -> "已中断"
            else -> null
        }
        val subagent = name == "spawn_agent" || name == "followup_agent"
        val agentId = tool.string("agent_id") ?: resultJson?.string("agent_id")?.takeIf { subagent }
        val agentLabel = resultJson?.string("label")?.takeIf { it.isNotBlank() && name == "spawn_agent" }
        val task = if (taskState == "running" && subagent) parseObject(tool.string("arguments_json"))?.string("task") else null
        return MessageFragment.ToolCall(
            id = "$messageId:tool:$toolId",
            name = name,
            status = status,
            label = agentLabel?.let { "子 Agent · $it" } ?: tool.string("label")?.takeIf { it.isNotBlank() }
                ?: tool.string("summary")?.takeIf { it.isNotBlank() },
            argsJson = tool.string("arguments_json"),
            resultPreview = task?.takeIf { it.isNotBlank() }?.let { "**任务**\n\n$it" } ?: result ?: tool.string("detail"),
            provider = tool.string("provider"),
            statusLabel = statusLabel,
            agentId = agentId,
        )
    }

    // 电脑发来的预览可能被截断，无法解析时仍显示原文。
    private fun parseObject(text: String?): JsonObject? =
        text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private val noticeBlock = Regex("<task-notification>(.*?)</task-notification>", RegexOption.DOT_MATCHES_ALL)

    internal fun taskNotice(content: String): String {
        val lines = noticeBlock.findAll(content).map { block ->
            fun tag(name: String): String? = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(block.groupValues[1])?.groupValues?.get(1)?.trim()
            val label = tag("label")?.takeIf { it.isNotBlank() } ?: "后台任务"
            val status = when (tag("status")) {
                "completed" -> "已完成"
                "failed" -> "失败"
                "cancelled" -> "已停止"
                "interrupted" -> "已中断"
                else -> "已结束"
            }
            listOfNotNull("后台任务「$label」$status", tag("elapsed")).joinToString(" · ")
        }.toList()
        return lines.joinToString("\n").ifEmpty { "（后台任务完成，已通知 Agent）" }
    }

    private fun sources(meta: JsonObject): List<SourceReference>? {
        val items = (meta["sources"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val refs = items.mapIndexedNotNull { index, source ->
            val url = source.string("url")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            SourceReference(title = source.string("title")?.takeIf { it.isNotBlank() } ?: url, url = url, index = index + 1)
        }
        return refs.ifEmpty { null }
    }

    private fun attachments(messageId: String, meta: JsonObject): List<Attachment> =
        (meta["attachments"] as? JsonArray).orEmpty().mapIndexedNotNull { index, item ->
            val attachment = item as? JsonObject ?: return@mapIndexedNotNull null
            val name = attachment.string("filename") ?: attachment.string("label") ?: return@mapIndexedNotNull null
            Attachment(
                id = "$messageId:a$index",
                name = name,
                mimeType = attachment.string("mime") ?: if (attachment.string("kind") == "image") "image/*" else "application/octet-stream",
                label = attachment.string("label"),
            )
        }

    private fun stats(meta: JsonObject): Map<String, String> {
        val stats = meta["response_stats"] as? JsonObject ?: return emptyMap()
        return buildMap {
            stats.long("promptTokens")?.let { put(ChatMessageMetadataKeys.PROMPT_TOKENS, it.toString()) }
            (stats.long("completionTokens") ?: stats.long("answerTokens"))?.let { put(ChatMessageMetadataKeys.COMPLETION_TOKENS, it.toString()) }
            stats.long("totalTokens")?.let { put(ChatMessageMetadataKeys.TOTAL_TOKENS, it.toString()) }
            stats.long("cacheReadTokens")?.let { put(ChatMessageMetadataKeys.CACHED_TOKENS, it.toString()) }
            stats.long("reasoningTokens")?.let { put(ChatMessageMetadataKeys.REASONING_TOKENS, it.toString()) }
            stats.long("totalTime")?.let { put(ChatMessageMetadataKeys.DURATION_MS, it.toString()) }
            stats.long("ttft")?.let { put(ChatMessageMetadataKeys.TTFT_MS, it.toString()) }
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
