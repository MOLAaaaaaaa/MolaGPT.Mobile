package com.molagpt.app.core.storage

import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.RetryAttempt
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.SourceReference
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.storage.entity.ConversationEntity
import com.molagpt.app.core.storage.entity.MessageEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Android 本地模型 ↔ 服务端 sync.php 落盘 schema 的双向映射。
 *
 * 服务端每会话存 `<id>.json` = 消息数组，每条 `{role, content(string|array), timestamp(ms), model?,
 * reasoning_content?}`；元数据存 molaChatList.json
 * 每条 `{id:"chat_…", title, time(ISO), updated_at(ISO), model}`。
 *
 * 服务端 content 是 Markdown/HTML 混合串；拉到 Android 时需要把 `<think>`、
 * tool-status/DSanalysis、`<ref>` 与 meta.sources/meta.retry 尽量还原成原生 fragments。
 */
object SyncMapper {
    const val EPOCH_ISO = "1970-01-01T00:00:00.000Z"
    private const val THINK_CLOSE = "</think>"
    private const val DS_CLOSE = "</DSanalysis>"
    private const val BLOCKQUOTE_CLOSE = "</blockquote>"
    private const val META_DISPLAY_CONTENT = "displayContent"
    private const val META_SEND_CONTENT = "sendContent"
    private const val META_ATTACHMENTS = "attachments"

    private fun isoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun formatIso(ms: Long): String = isoFormat().format(Date(ms))

    fun parseIso(s: String?): Long =
        if (s.isNullOrBlank()) 0L else runCatching { isoFormat().parse(s)?.time ?: 0L }.getOrDefault(0L)

    // 会话本地 id(sess_) ↔ 服务端 conversation id(chat_)
    fun conversationIdOf(sessionId: String): String = Ids.conversationIdForSession(sessionId)

    fun sessionIdOf(conversationId: String): String =
        if (conversationId.startsWith("chat_")) "sess_" + conversationId.removePrefix("chat_") else conversationId

    /** 会话元数据 → 服务端 metadata 对象。 */
    fun conversationMetadata(c: ConversationEntity): JsonObject = buildJsonObject {
        put("id", conversationIdOf(c.sessionId))
        put("title", c.title)
        put("time", formatIso(c.createdAt))
        put("updated_at", formatIso(c.updatedAt))
        put("model", c.model ?: "auto")
    }

    /** 一条消息 → 服务端持久化对象。 */
    fun messageToJson(m: MessageEntity): JsonObject = buildJsonObject {
        val meta = MessageJson.decodeMeta(m.metadataJson)
        val isUser = m.role.equals(Role.USER.name, ignoreCase = true)
        val visible = visibleContent(m)
        val reasoning = reasoningOf(m)
        val sendContent = meta[META_SEND_CONTENT]?.takeIf { it.isNotBlank() }
        val attachments = MessageJson.decodeAttachments(meta[META_ATTACHMENTS])
        put("role", m.role.lowercase(Locale.US))
        put(
            "content",
            if (isUser && sendContent != null) sendContent else webCompatibleContent(visible, reasoning),
        )
        put("timestamp", m.createdAt)
        m.model?.let { put("model", it) }
        reasoning?.let { put("reasoning_content", it) }
        val metaJson = buildJsonObject {
            if (isUser && sendContent != null) {
                put(META_DISPLAY_CONTENT, meta[META_DISPLAY_CONTENT]?.takeIf { it.isNotBlank() } ?: visible)
            }
            if (isUser && attachments.isNotEmpty()) {
                put(META_ATTACHMENTS, attachmentsToJson(attachments))
            }
        }
        if (metaJson.isNotEmpty()) {
            put("meta", metaJson)
        }
    }

    /** 服务端消息 → 本地实体（把 think/tool/ref/meta 尽量还原成 Android fragments）。 */
    fun jsonToMessage(sessionId: String, obj: JsonObject, index: Int = 0): MessageEntity {
        val roleStr = obj["role"]?.jsonPrimitive?.contentOrNull ?: "assistant"
        val role = when (roleStr.lowercase(Locale.US)) {
            "user" -> Role.USER
            "system" -> Role.SYSTEM
            "tool" -> Role.TOOL
            else -> Role.ASSISTANT
        }
        val content = contentString(obj["content"])
        val metaObj = obj["meta"] as? JsonObject
        val displayContent = metaObj?.get(META_DISPLAY_CONTENT)?.jsonPrimitive?.contentOrNull
        val attachments = parseAttachments(metaObj?.get(META_ATTACHMENTS), obj["content"])
        val contentHasHiddenContext = containsHiddenContext(content)
        val contentForDisplay =
            if (role == Role.USER && !displayContent.isNullOrBlank()) {
                displayContent
            } else if (role == Role.USER && contentHasHiddenContext) {
                cleanVisibleText(content)
            } else {
                content
            }
        val model = obj["model_label"]?.jsonPrimitive?.contentOrNull
            ?: obj["model"]?.jsonPrimitive?.contentOrNull
            ?: metaObj?.get("model")?.jsonPrimitive?.contentOrNull
        val sources = parseSources(metaObj?.get("sources") ?: obj["sources"])
        val normalized = normalizeCloudContent(
            content = contentForDisplay,
            explicitThinking = obj["reasoning_content"]?.jsonPrimitive?.contentOrNull
                ?: metaObj?.get("thinking")?.jsonPrimitive?.contentOrNull,
            sources = sources,
            role = role,
        )
        val ts = parseMessageTimestamp(obj["timestamp"])
        val createdAt = ts + index.coerceAtLeast(0)
        val meta = buildMap {
            parseRetry(metaObj?.get("retry"), sources)?.let { retry ->
                put(RetryAttempts.KEY_ATTEMPTS, RetryAttempts.encode(retry.attempts))
                put(RetryAttempts.KEY_CURRENT, retry.current.toString())
            }
            metaObj?.get("response_stats")?.let { put("response_stats", it.toString()) }
            obj["response_stats"]?.let { put("response_stats", it.toString()) }
            model?.let { put("modelDisplayName", it) }
            if (role == Role.USER && content != contentForDisplay && (!displayContent.isNullOrBlank() || contentHasHiddenContext)) {
                put(META_SEND_CONTENT, content)
                put(META_DISPLAY_CONTENT, contentForDisplay)
            }
            if (role == Role.USER && attachments.isNotEmpty()) {
                put(META_ATTACHMENTS, MessageJson.encodeAttachments(attachments))
            }
        }
        return MessageEntity(
            // 稳定可复现 id：服务端历史常出现相同 timestamp，必须带数组序号，避免同角色消息互相 REPLACE 覆盖。
            messageId = "${sessionId}_${ts}_${index}_${role.name}",
            sessionId = sessionId,
            role = role.name,
            status = MessageStatus.COMPLETE.name,
            createdAt = createdAt,
            updatedAt = createdAt,
            fragmentsJson = MessageJson.encodeFragments(normalized.fragments),
            rawText = normalized.rawText,
            model = model,
            metadataJson = MessageJson.encodeMeta(meta),
        )
    }

    private fun contentString(el: JsonElement?): String = when (el) {
        null -> ""
        is JsonPrimitive -> el.contentOrNull ?: ""
        is JsonArray -> el.mapNotNull { part ->
            (part as? JsonObject)?.let { o ->
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> o["text"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }
        }.joinToString("\n")
        else -> ""
    }

    private data class NormalizedCloudContent(
        val fragments: List<MessageFragment>,
        val rawText: String,
    )

    private data class CloudRetry(
        val attempts: List<RetryAttempt>,
        val current: Int,
    )

    private fun normalizeCloudContent(
        content: String,
        explicitThinking: String?,
        sources: List<SourceReference>,
        role: Role,
    ): NormalizedCloudContent {
        if (role != Role.ASSISTANT) {
            val text = cleanVisibleText(content)
            return NormalizedCloudContent(
                fragments = listOf(MessageFragment.Text(Ids.newFragmentId(), text)),
                rawText = text,
            )
        }

        val fragments = mutableListOf<MessageFragment>()
        var remaining = content

        val leadingTools = peelLeadingToolMarkup(remaining)
        leadingTools.tools.forEach(fragments::add)
        remaining = leadingTools.visible

        val split = splitThinkTags(remaining)
        var thinking = mergeText(explicitThinking, split.thinking)
        if (leadingTools.thinkingMarkup.isNotBlank()) {
            thinking = mergeText(thinking, leadingTools.thinkingMarkup)
        }
        if (!thinking.isNullOrBlank()) {
            fragments.add(MessageFragment.Thinking(Ids.newFragmentId(), thinking.trim(), collapsed = true))
        }

        val visible = cleanVisibleText(split.visible)
        if (visible.isNotBlank()) {
            fragments.add(MessageFragment.Text(Ids.newFragmentId(), visible))
        }

        if (sources.isNotEmpty()) {
            fragments.add(
                MessageFragment.SearchResult(
                    id = Ids.newFragmentId(),
                    query = leadingTools.searchQueries.joinToString(" / "),
                    refs = sources,
                ),
            )
        }

        if (fragments.isEmpty()) {
            fragments.add(MessageFragment.Text(Ids.newFragmentId(), visible.ifBlank { content }))
        }

        return NormalizedCloudContent(
            fragments = fragments,
            rawText = visible.ifBlank { cleanVisibleText(content) },
        )
    }

    private data class ToolPeel(
        val visible: String,
        val tools: List<MessageFragment.ToolCall>,
        val thinkingMarkup: String,
        val searchQueries: List<String>,
    )

    private fun peelLeadingToolMarkup(content: String): ToolPeel {
        val tools = mutableListOf<MessageFragment.ToolCall>()
        val thinking = StringBuilder()
        val queries = mutableListOf<String>()
        var pos = 0
        var movedAny = false
        while (pos < content.length) {
            pos = skipWhitespace(content, pos)
            val end = findToolMarkupEnd(content, pos)
            if (end < 0) break
            val unit = content.substring(pos, end)
            parseToolUnit(unit, tools, queries)
            appendThinking(thinking, unit)
            pos = end
            movedAny = true
        }
        return if (!movedAny) {
            ToolPeel(content, emptyList(), "", emptyList())
        } else {
            ToolPeel(content.substring(pos).trimStart(), tools, thinking.toString().trim(), queries)
        }
    }

    private fun parseToolUnit(
        unit: String,
        tools: MutableList<MessageFragment.ToolCall>,
        queries: MutableList<String>,
    ) {
        if (containsIgnoreCase(unit, "tool-search-blockquote")) {
            val query = extractSearchChips(unit).joinToString(" / ").also {
                if (it.isNotBlank()) queries.add(it)
            }
            tools.add(
                MessageFragment.ToolCall(
                    id = Ids.newFragmentId(),
                    name = "web_search",
                    status = ToolStatus.SUCCESS,
                    label = "网络搜索",
                    resultPreview = query.ifBlank { null },
                    provider = "MolaGPT",
                ),
            )
            return
        }
        if (startsWithToolStatusBlockquote(unit)) {
            tools.add(
                MessageFragment.ToolCall(
                    id = Ids.newFragmentId(),
                    name = "tool",
                    status = ToolStatus.SUCCESS,
                    label = htmlToText(unit).ifBlank { "工具调用" },
                    provider = "MolaGPT",
                ),
            )
            return
        }
        if (startsWithIgnoreCase(unit, 0, "<DSanalysis")) {
            val body = stripTagPair(unit, "DSanalysis").trim()
            if (body.isNotBlank()) {
                tools.add(
                    MessageFragment.ToolCall(
                        id = Ids.newFragmentId(),
                        name = dsToolName(unit),
                        status = ToolStatus.SUCCESS,
                        label = readableDsLabel(unit),
                        resultPreview = body,
                        provider = "MolaGPT",
                    ),
                )
            }
        }
    }

    private fun splitThinkTags(content: String): PairText {
        val visible = StringBuilder()
        val thinking = StringBuilder()
        var pos = 0
        while (pos < content.length) {
            val start = indexOfIgnoreCase(content, "<think", pos)
            if (start < 0) {
                visible.append(content, pos, content.length)
                break
            }
            visible.append(content, pos, start)
            val openEnd = content.indexOf('>', start)
            if (openEnd < 0) {
                appendThinking(thinking, content.substring(start))
                break
            }
            val close = indexOfIgnoreCase(content, THINK_CLOSE, openEnd + 1)
            if (close < 0) {
                appendThinking(thinking, content.substring(openEnd + 1))
                break
            }
            appendThinking(thinking, content.substring(openEnd + 1, close))
            pos = close + THINK_CLOSE.length
        }
        return PairText(visible.toString(), thinking.toString())
    }

    private data class PairText(val visible: String, val thinking: String)

    private fun cleanVisibleText(input: String): String {
        var text = input
        text = stripHiddenContext(text)
        text = removeToolMarkup(text)
        text = text.replace(Regex("""<ref\s+source=["']([^"']+)["']\s*/?>""", RegexOption.IGNORE_CASE)) {
            "[${it.groupValues[1]}]"
        }
        text = text.replace(Regex("""</?fz[^>]*>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<!--[\s\S]*?-->"""), "")
        return text.trim()
    }

    private fun stripHiddenContext(input: String): String =
        input
            .replace(Regex("""✝[^✝]*✝"""), "")
            .replace(Regex("""†[^†]*†"""), "")
            .replace(Regex("""⟦MEM[:：][\s\S]*?⟧"""), "")
            .trim()

    private fun containsHiddenContext(input: String): Boolean =
        Regex("""✝[^✝]*✝|†[^†]*†|⟦MEM[:：][\s\S]*?⟧""").containsMatchIn(input)

    private fun removeToolMarkup(input: String): String {
        var text = input
        text = Regex("""<blockquote\b(?=[^>]*\btool-status\b)[\s\S]*?</blockquote>""", RegexOption.IGNORE_CASE)
            .replace(text, "")
        text = Regex("""<DSanalysis\b[^>]*>[\s\S]*?</DSanalysis>""", RegexOption.IGNORE_CASE)
            .replace(text, "")
        text = Regex("""<steel-step\b[^>]*>[\s\S]*?</steel-step>""", RegexOption.IGNORE_CASE)
            .replace(text, "")
        return text
    }

    private fun parseSources(el: JsonElement?): List<SourceReference> {
        val arr = el as? JsonArray ?: return emptyList()
        var fallback = 1
        return arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val index = o["id"]?.jsonPrimitive?.intOrNull
                ?: o["index"]?.jsonPrimitive?.intOrNull
                ?: fallback
            fallback++
            SourceReference(
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: url,
                url = url,
                snippet = o["snippet"]?.jsonPrimitive?.contentOrNull ?: o["content"]?.jsonPrimitive?.contentOrNull,
                index = index,
            )
        }
    }

    private fun parseAttachments(metaEl: JsonElement?, contentEl: JsonElement?): List<Attachment> {
        val fromMeta = (metaEl as? JsonArray)?.mapIndexedNotNull { index, item ->
            val o = item as? JsonObject ?: return@mapIndexedNotNull null
            val filename = o["filename"]?.jsonPrimitive?.contentOrNull
                ?: o["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapIndexedNotNull null
            val thumbnailUrl = o["thumbnailUrl"]?.jsonPrimitive?.contentOrNull
                ?: o["url"]?.jsonPrimitive?.contentOrNull
            val mime = o["mime"]?.jsonPrimitive?.contentOrNull
                ?: o["mimeType"]?.jsonPrimitive?.contentOrNull
                ?: if (!thumbnailUrl.isNullOrBlank()) "image/*" else "application/octet-stream"
            Attachment(
                id = "att_${index}_${filename.hashCode()}",
                name = filename,
                mimeType = mime,
                remoteUrl = thumbnailUrl,
                label = o["label"]?.jsonPrimitive?.contentOrNull,
                thumbnailUrl = thumbnailUrl,
            )
        }.orEmpty()
        if (fromMeta.isNotEmpty()) return fromMeta

        val contentArr = contentEl as? JsonArray ?: return emptyList()
        return contentArr.mapIndexedNotNull { index, item ->
            val o = item as? JsonObject ?: return@mapIndexedNotNull null
            if (o["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@mapIndexedNotNull null
            val image = o["image_url"]
            val url = when (image) {
                is JsonObject -> image["url"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> image.contentOrNull
                else -> null
            } ?: return@mapIndexedNotNull null
            Attachment(
                id = "att_image_${index}_${url.hashCode()}",
                name = filenameFromUrl(url) ?: "图片${index + 1}",
                mimeType = "image/*",
                remoteUrl = url,
                label = "图片",
                thumbnailUrl = url,
            )
        }
    }

    private fun attachmentsToJson(attachments: List<Attachment>): JsonArray = buildJsonArray {
        attachments.forEach { attachment ->
            add(
                buildJsonObject {
                    put("filename", attachment.name)
                    put("label", attachment.label?.takeIf { it.isNotBlank() } ?: attachment.defaultLabel())
                    attachment.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { put("thumbnailUrl", it) }
                        ?: attachment.remoteUrl?.takeIf { it.isNotBlank() }?.let { put("thumbnailUrl", it) }
                    put("mime", attachment.mimeType)
                },
            )
        }
    }

    private fun Attachment.defaultLabel(): String =
        if (mimeType.startsWith("image/")) "图片" else name.substringAfterLast('.', "文件").uppercase(Locale.US)

    private fun filenameFromUrl(url: String): String? =
        url.substringBefore('?')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }

    private fun parseRetry(el: JsonElement?, fallbackSources: List<SourceReference>): CloudRetry? {
        val retry = el as? JsonObject ?: return null
        val attemptsNode = retry["attempts"] as? JsonArray ?: return null
        val attempts = attemptsNode.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> {
                    val text = item.contentOrNull.orEmpty()
                    RetryAttempt(
                        fragments = normalizeCloudContent(text, null, fallbackSources, Role.ASSISTANT).fragments,
                        rawText = cleanVisibleText(text),
                    )
                }
                is JsonObject -> {
                    val text = contentString(item["content"])
                    val sources = parseSources(item["sources"]).ifEmpty { fallbackSources }
                    val normalized = normalizeCloudContent(
                        content = text,
                        explicitThinking = item["reasoning_content"]?.jsonPrimitive?.contentOrNull,
                        sources = sources,
                        role = Role.ASSISTANT,
                    )
                    RetryAttempt(
                        fragments = normalized.fragments,
                        rawText = normalized.rawText,
                        model = item["model"]?.jsonPrimitive?.contentOrNull,
                        modelDisplayName = item["model_label"]?.jsonPrimitive?.contentOrNull,
                        status = MessageStatus.COMPLETE.name,
                    )
                }
                else -> null
            }
        }
        if (attempts.isEmpty()) return null
        val current = (retry["current"]?.jsonPrimitive?.intOrNull ?: attempts.lastIndex)
            .coerceIn(0, attempts.lastIndex)
        return CloudRetry(attempts, current)
    }

    private fun parseMessageTimestamp(el: JsonElement?): Long {
        val primitive = el as? JsonPrimitive ?: return System.currentTimeMillis()
        primitive.longOrNull?.let { return if (it > 9_999_999_999L) it else it * 1000L }
        val text = primitive.contentOrNull ?: return System.currentTimeMillis()
        return parseIso(text).takeIf { it > 0L } ?: System.currentTimeMillis()
    }

    private fun findToolMarkupEnd(source: String, start: Int): Int {
        if (startsWithIgnoreCase(source, start, "<steel-step")) return findTagEnd(source, start, "</steel-step>")
        if (startsWithIgnoreCase(source, start, "<DSanalysis")) return findTagEnd(source, start, DS_CLOSE)
        if (startsWithToolStatusBlockquote(source, start)) return findTagEnd(source, start, BLOCKQUOTE_CLOSE)
        return -1
    }

    private fun findTagEnd(source: String, start: Int, closeTag: String): Int {
        val close = indexOfIgnoreCase(source, closeTag, start)
        return if (close < 0) source.length else close + closeTag.length
    }

    private fun startsWithToolStatusBlockquote(source: String): Boolean =
        startsWithToolStatusBlockquote(source, 0)

    private fun startsWithToolStatusBlockquote(source: String, start: Int): Boolean {
        if (!startsWithIgnoreCase(source, start, "<blockquote")) return false
        val openEnd = source.indexOf('>', start)
        if (openEnd < 0) return false
        return containsIgnoreCase(source.substring(start, openEnd + 1), "tool-status")
    }

    private fun stripTagPair(source: String, tag: String): String {
        val openEnd = source.indexOf('>')
        if (openEnd < 0) return source
        val close = indexOfIgnoreCase(source, "</$tag>", openEnd + 1)
        return if (close < 0) source.substring(openEnd + 1) else source.substring(openEnd + 1, close)
    }

    private fun extractSearchChips(html: String): List<String> {
        val matches = Regex(
            """<span\b[^>]*class=["'][^"']*\btool-search-chip-text\b[^"']*["'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).map { htmlToText(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        return matches.ifEmpty {
            val fallback = htmlToText(html)
                .replace("网络搜索", "")
                .trim()
            listOf(fallback).filter { it.isNotBlank() }
        }
    }

    private fun htmlToText(html: String): String =
        html.replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

    private fun dsToolName(unit: String): String = when {
        containsIgnoreCase(unit, "python") -> "python"
        containsIgnoreCase(unit, "image") -> "image-action"
        else -> "tool"
    }

    private fun readableDsLabel(unit: String): String = when {
        containsIgnoreCase(unit, "python") -> "Python 执行"
        containsIgnoreCase(unit, "image") -> "图片处理"
        else -> "工具调用"
    }

    private fun mergeText(first: String?, second: String?): String? {
        if (second.isNullOrBlank()) return first
        if (first.isNullOrBlank()) return second
        if (first.contains(second, ignoreCase = false)) return first
        if (second.contains(first, ignoreCase = false)) return second
        return first.trimEnd() + "\n\n" + second.trimStart()
    }

    private fun appendThinking(builder: StringBuilder, segment: String?) {
        if (segment.isNullOrBlank()) return
        if (builder.isNotEmpty() && !builder.endsWithBlankLine()) builder.append("\n\n")
        builder.append(segment.trim())
    }

    private fun StringBuilder.endsWithBlankLine(): Boolean =
        length >= 2 && this[length - 1] == '\n' && (this[length - 2] == '\n' || this[length - 2] == '\r')

    private fun skipWhitespace(source: String, start: Int): Int {
        var i = start
        while (i < source.length && source[i].isWhitespace()) i++
        return i
    }

    private fun startsWithIgnoreCase(source: String, start: Int, value: String): Boolean =
        start >= 0 &&
            start + value.length <= source.length &&
            source.regionMatches(start, value, 0, value.length, ignoreCase = true)

    private fun indexOfIgnoreCase(source: String, value: String, start: Int = 0): Int =
        source.indexOf(value, start, ignoreCase = true)

    private fun containsIgnoreCase(source: String, value: String): Boolean =
        source.contains(value, ignoreCase = true)

    private fun visibleContent(m: MessageEntity): String {
        val frags = MessageJson.decodeFragments(m.fragmentsJson)
        val hasWebOnlyFragments = frags.any { it is MessageFragment.ToolCall || it is MessageFragment.Image || it is MessageFragment.SearchResult }
        if (!hasWebOnlyFragments && !m.rawText.isNullOrBlank()) return m.rawText!!
        return buildString {
            frags.forEach { f ->
                when (f) {
                    is MessageFragment.Text -> append(f.markdown)
                    is MessageFragment.CodeBlock ->
                        append("\n```").append(f.language ?: "").append('\n').append(f.code).append("\n```\n")
                    is MessageFragment.Latex -> append(if (f.display) "$$${f.expr}$$" else "$${f.expr}$")
                    is MessageFragment.Mermaid -> append("\n```mermaid\n").append(f.source).append("\n```\n")
                    is MessageFragment.ToolCall -> append(webToolMarkup(f))
                    is MessageFragment.SearchResult -> append(webSearchMarkup(f))
                    is MessageFragment.Image -> append("\n\n![${escapeMarkdownAlt(f.prompt ?: "生成的图片")}](${f.url})\n\n")
                    else -> Unit
                }
            }
        }.trim()
    }

    private fun webToolMarkup(tool: MessageFragment.ToolCall): String {
        val phase = when (tool.status) {
            ToolStatus.SUCCESS -> "completed"
            ToolStatus.FAILED -> "error"
            ToolStatus.RUNNING -> "analyzing"
        }
        val label = tool.label?.takeIf { it.isNotBlank() } ?: readableToolStatusLabel(tool.name, tool.status)
        val safeLabel = escapeHtml(label)
        val toolType = webToolType(tool.name)
        val extraClass = if (toolType == "image-gen") " tool-image-blockquote" else ""
        val dsBody = buildString {
            tool.argsJson?.takeIf { it.isNotBlank() }?.let {
                append("**参数：**\n\n```json\n").append(it).append("\n```\n")
            }
            tool.resultPreview?.takeIf { it.isNotBlank() }?.let {
                if (isNotBlank()) append('\n')
                append(it)
            }
        }
        return "\n\n<blockquote class=\"tool-status $phase$extraClass\"><p>$safeLabel</p></blockquote>\n\n" +
            "<DSanalysis data-tool-type=\"$toolType\" data-analysis-phase=\"$phase\">" +
            dsBody +
            "</DSanalysis>\n\n"
    }

    private fun webSearchMarkup(search: MessageFragment.SearchResult): String {
        val chips = search.query.takeIf { it.isNotBlank() } ?: "联网搜索"
        return "\n\n<blockquote class=\"tool-status completed tool-search-blockquote\"><p class=\"tool-search-title\">联网搜索</p>" +
            "<div class=\"tool-search-chip-wrap\"><span class=\"tool-search-chip\"><span class=\"tool-search-chip-text\">" +
            escapeHtml(chips) +
            "</span></span></div></blockquote>\n\n" +
            "<DSanalysis data-tool-type=\"web_search\" data-analysis-phase=\"completed\"></DSanalysis>\n\n"
    }

    private fun webToolType(name: String): String = when (name) {
        "image-gen", "image_generation", "image_generation_and_editing", "draw_with_canvas" -> "image-gen"
        "image-analyze", "analyze_sandbox_image" -> "image-analyze"
        "image-action", "image_file_process" -> "image-action"
        "search_web", "web_search" -> "web_search"
        "steel_browser", "browser", "web_fetch" -> "tool-call"
        "execute_python_code", "python" -> "python"
        "mcp" -> "mcp"
        else -> "tool-call"
    }

    private fun readableToolStatusLabel(name: String, status: ToolStatus): String {
        val completed = status == ToolStatus.SUCCESS
        return when (webToolType(name)) {
            "image-gen" -> if (completed) "绘制完成" else if (status == ToolStatus.FAILED) "绘制失败" else "正在绘制"
            "image-analyze" -> if (completed) "图片分析完成" else if (status == ToolStatus.FAILED) "图片分析失败" else "正在查看图片"
            "image-action" -> if (completed) "图片处理完成" else if (status == ToolStatus.FAILED) "图片处理失败" else "正在处理图片"
            "python" -> if (completed) "Python 执行完成" else if (status == ToolStatus.FAILED) "Python 执行失败" else "正在执行 Python"
            "mcp" -> if (completed) "连接器调用完成" else if (status == ToolStatus.FAILED) "连接器调用失败" else "正在调用连接器"
            "web_search" -> if (completed) "联网搜索完成" else if (status == ToolStatus.FAILED) "联网搜索失败" else "正在访问互联网"
            else -> if (completed) "工具调用完成" else if (status == ToolStatus.FAILED) "工具调用失败" else "正在处理..."
        }
    }

    private fun escapeMarkdownAlt(value: String): String =
        value.replace(Regex("""\s+"""), " ").replace("[", "").replace("]", "").trim().ifBlank { "生成的图片" }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun reasoningOf(m: MessageEntity): String? {
        val frags = MessageJson.decodeFragments(m.fragmentsJson)
        val r = frags.filterIsInstance<MessageFragment.Thinking>().joinToString("\n") { it.text }.trim()
        return r.ifBlank { null }
    }

    private fun webCompatibleContent(visible: String, reasoning: String?): String {
        val thinking = reasoning?.trim()
        if (thinking.isNullOrBlank()) return visible
        val body = visible.trimStart()
        return buildString {
            append("<think>\n")
            append(thinking)
            append("\n</think>")
            if (body.isNotBlank()) {
                append("\n\n")
                append(body)
            }
        }
    }
}
