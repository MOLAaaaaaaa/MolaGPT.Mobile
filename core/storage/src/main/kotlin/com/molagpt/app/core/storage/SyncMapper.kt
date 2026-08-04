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
import kotlinx.serialization.json.Json
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
 * tool-status、分析标签、`<ref>` 与 meta.sources/meta.retry 尽量还原成原生 fragments。
 */
object SyncMapper {
    const val EPOCH_ISO = "1970-01-01T00:00:00.000Z"
    private const val THINK_CLOSE = "</think>"
    private const val DS_CLOSE = "</DSanalysis>"
    private const val BLOCKQUOTE_CLOSE = "</blockquote>"
    private const val META_DISPLAY_CONTENT = "displayContent"
    private const val META_SEND_CONTENT = "sendContent"
    private const val META_ATTACHMENTS = "attachments"

    /** Web 的编辑分支快照；本地按云端原始 JSON 串原样存取，同步纯透传，不做结构化转换。 */
    const val META_EDIT_SNAPSHOTS = "editSnapshots"

    /** 云端 meta 中本端不认识的字段，原样暂存，上传时回写——防止 Web 新增字段被静默丢弃。 */
    private const val META_CLOUD_EXTRA = "cloudMetaExtra"

    /** 本端会自行生成、不参与 [META_CLOUD_EXTRA] 透传的 meta 键。 */
    private val KNOWN_CLOUD_META_KEYS = setOf(
        META_DISPLAY_CONTENT,
        META_ATTACHMENTS,
        META_EDIT_SNAPSHOTS,
        "sources",
        "retry",
        "response_stats",
        "thinking",
        "model",
    )

    /** 仅用于 meta 透传字段的解析/再序列化，容忍服务端未知结构。 */
    private val syncJson = Json { ignoreUnknownKeys = true; isLenient = true }

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

    /**
     * 一条消息 → 服务端持久化对象。
     *
     * [includeSnapshots] = false 用于写入编辑快照内部的消息：快照里再嵌快照会指数膨胀，
     * 与 Web 的 `makePersistableMessage(msg, {includeSnapshots:false})` 对齐。
     */
    fun messageToJson(m: MessageEntity, includeSnapshots: Boolean = true): JsonObject = buildJsonObject {
        val meta = MessageJson.decodeMeta(m.metadataJson)
        val isUser = m.role.equals(Role.USER.name, ignoreCase = true)
        val visible = if (isUser) visibleContent(m) else webTimelineContent(m)
        val sources = sourcesOf(m)
        val sendContent = meta[META_SEND_CONTENT]?.takeIf { it.isNotBlank() }
        val attachments = MessageJson.decodeAttachments(meta[META_ATTACHMENTS])
        put("role", m.role.lowercase(Locale.US))
        put(
            "content",
            if (isUser && sendContent != null) sendContent else visible,
        )
        put("timestamp", m.createdAt)
        m.model?.let { put("model", it) }
        // 友好模型名（Web 的 model_label）：不写的话快照/同步往返后只剩原始 id，
        // 模型一旦从列表里消失，抬头就再也显示不出名字。
        meta["modelDisplayName"]?.takeIf { it.isNotBlank() }?.let { put("model_label", it) }
        // 先铺未识别的云端字段，已知字段随后覆盖——本地权威，同时保证 Web 新增字段不被丢弃。
        val metaFields = LinkedHashMap<String, JsonElement>()
        decodeCloudExtra(meta[META_CLOUD_EXTRA]).forEach { (k, v) -> metaFields[k] = v }
        if (isUser && sendContent != null) {
            metaFields[META_DISPLAY_CONTENT] =
                JsonPrimitive(meta[META_DISPLAY_CONTENT]?.takeIf { it.isNotBlank() } ?: visible)
        }
        if (isUser && attachments.isNotEmpty()) {
            metaFields[META_ATTACHMENTS] = attachmentsToJson(attachments)
        }
        // 编辑分支快照：本地存的就是云端原始 JSON，原样回写。
        if (isUser && includeSnapshots) {
            parseJsonObject(meta[META_EDIT_SNAPSHOTS])?.let { metaFields[META_EDIT_SNAPSHOTS] = it }
        }
        if (!isUser && sources.isNotEmpty()) {
            metaFields["sources"] = sourcesToJson(sources)
        }
        // 重试版本：本地 RetryAttempt 列表 → Web 的 meta.retry（此前只读不写，换端即丢）。
        if (!isUser) {
            retryToJson(meta)?.let { metaFields["retry"] = it }
        }
        if (metaFields.isNotEmpty()) {
            put("meta", JsonObject(metaFields))
        }
    }

    /** 本地重试版本 → Web `meta.retry`（`attempts[].content/model/model_label` + `current`）。 */
    private fun retryToJson(meta: Map<String, String>): JsonObject? {
        val attempts = RetryAttempts.decode(meta[RetryAttempts.KEY_ATTEMPTS])
        if (attempts.isEmpty()) return null
        val current = meta[RetryAttempts.KEY_CURRENT]?.toIntOrNull()
            ?.coerceIn(0, attempts.lastIndex) ?: attempts.lastIndex
        return buildJsonObject {
            put(
                "attempts",
                buildJsonArray {
                    attempts.forEach { a ->
                        add(
                            buildJsonObject {
                                put("content", webTimelineContent(a.fragments, a.rawText))
                                a.model?.let { put("model", it) }
                                a.modelDisplayName?.let { put("model_label", it) }
                            },
                        )
                    }
                },
            )
            put("current", current)
        }
    }

    private fun parseJsonObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { syncJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    private fun decodeCloudExtra(raw: String?): Map<String, JsonElement> =
        parseJsonObject(raw)?.filterKeys { it !in KNOWN_CLOUD_META_KEYS } ?: emptyMap()

    /** 云端 meta 中本端不认识的字段 → 原样暂存的 JSON 串。 */
    private fun encodeCloudExtra(metaObj: JsonObject?): String? {
        if (metaObj == null) return null
        val extra = metaObj.filterKeys { it !in KNOWN_CLOUD_META_KEYS }
        if (extra.isEmpty()) return null
        return JsonObject(extra).toString()
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
            // 编辑分支快照原样留存（本端不解析结构），上传时回写，保证与 Web 双向不丢。
            (metaObj?.get(META_EDIT_SNAPSHOTS) as? JsonObject)?.let {
                put(META_EDIT_SNAPSHOTS, it.toString())
            }
            encodeCloudExtra(metaObj)?.let { put(META_CLOUD_EXTRA, it) }
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

        val timeline = parseCloudTimeline(content)
        val fragments = timeline.fragments.toMutableList()
        if (!explicitThinking.isNullOrBlank() && !timeline.hasInlineThinking) {
            fragments.add(
                0,
                MessageFragment.Thinking(Ids.newFragmentId(), explicitThinking.trim(), collapsed = true),
            )
        }

        if (sources.isNotEmpty()) {
            fragments.add(
                MessageFragment.SearchResult(
                    id = Ids.newFragmentId(),
                    query = timeline.searchQueries.joinToString(" / "),
                    refs = sources,
                ),
            )
        }

        if (fragments.isEmpty()) {
            val fallback = cleanVisibleText(content)
            fragments.add(MessageFragment.Text(Ids.newFragmentId(), fallback.ifBlank { content }))
        }

        return NormalizedCloudContent(
            fragments = fragments,
            rawText = timeline.visibleText.ifBlank { cleanVisibleText(content) },
        )
    }

    private data class CloudTimelineParse(
        val fragments: List<MessageFragment>,
        val visibleText: String,
        val searchQueries: List<String>,
        val hasInlineThinking: Boolean,
    )

    private fun parseCloudTimeline(content: String): CloudTimelineParse {
        val fragments = mutableListOf<MessageFragment>()
        val visible = StringBuilder()
        val searchQueries = mutableListOf<String>()
        var pos = 0
        var hasThinking = false

        fun appendVisible(segment: String) {
            val text = cleanVisibleText(segment)
            if (text.isBlank()) return
            fragments.add(MessageFragment.Text(Ids.newFragmentId(), text))
            if (visible.isNotEmpty()) visible.append("\n\n")
            visible.append(text)
        }

        while (pos < content.length) {
            val next = findNextCloudTimelineMarker(content, pos)
            if (next < 0) {
                appendVisible(content.substring(pos))
                break
            }

            if (next > pos) {
                appendVisible(content.substring(pos, next))
            }

            if (startsWithIgnoreCase(content, next, "<think")) {
                val openEnd = content.indexOf('>', next)
                if (openEnd < 0) {
                    appendThinkingFragment(fragments, content.substring(next))
                    hasThinking = true
                    break
                }
                val close = indexOfIgnoreCase(content, THINK_CLOSE, openEnd + 1)
                val body = if (close < 0) {
                    content.substring(openEnd + 1)
                } else {
                    content.substring(openEnd + 1, close)
                }
                appendThinkingFragment(fragments, body)
                hasThinking = true
                pos = if (close < 0) content.length else close + THINK_CLOSE.length
                continue
            }

            val end = findToolMarkupEnd(content, next)
            if (end < 0) {
                appendVisible(content.substring(next, (next + 1).coerceAtMost(content.length)))
                pos = next + 1
                continue
            }

            val unit = content.substring(next, end)
            if (startsWithIgnoreCase(unit, 0, "<DSanalysis") && mergeDsAnalysisWithPreviousTool(fragments, unit)) {
                pos = end
                continue
            }

            val tools = mutableListOf<MessageFragment.ToolCall>()
            parseToolUnit(unit, tools, searchQueries)
            fragments.addAll(tools)
            pos = end
        }

        return CloudTimelineParse(
            fragments = fragments,
            visibleText = visible.toString(),
            searchQueries = searchQueries,
            hasInlineThinking = hasThinking,
        )
    }

    private fun appendThinkingFragment(fragments: MutableList<MessageFragment>, text: String) {
        val thinking = text.trim()
        if (thinking.isBlank()) return
        fragments.add(MessageFragment.Thinking(Ids.newFragmentId(), thinking, collapsed = true))
    }

    private fun mergeDsAnalysisWithPreviousTool(fragments: MutableList<MessageFragment>, unit: String): Boolean {
        val body = stripTagPair(unit, "DSanalysis").trim()
        if (body.isBlank()) return true
        val lastIndex = fragments.lastIndex
        val previous = fragments.getOrNull(lastIndex) as? MessageFragment.ToolCall ?: return false
        fragments[lastIndex] = previous.copy(
            name = dsToolName(unit),
            status = webToolStatus(unit),
            label = readableDsLabel(unit),
            resultPreview = body,
            provider = previous.provider ?: "MolaGPT",
        )
        return true
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
        if (startsWithIgnoreCase(unit, 0, "<steel-step")) {
            val name = steelStepToolName(unit)
            val status = webToolStatus(unit)
            tools.add(
                MessageFragment.ToolCall(
                    id = Ids.newFragmentId(),
                    name = name,
                    status = status,
                    label = extractSteelStepTitle(unit)
                        .ifBlank { readableToolStatusLabel(name, status) },
                    resultPreview = extractSteelStepPreview(unit),
                    provider = "MolaGPT",
                ),
            )
            return
        }
        if (startsWithToolStatusBlockquote(unit)) {
            val name = toolNameFromStatusMarkup(unit)
            tools.add(
                MessageFragment.ToolCall(
                    id = Ids.newFragmentId(),
                    name = name,
                    status = webToolStatus(unit),
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
                        status = webToolStatus(unit),
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

    private fun findNextCloudTimelineMarker(source: String, start: Int): Int =
        minPositive(
            indexOfIgnoreCase(source, "<think", start),
            indexOfIgnoreCase(source, "<steel-step", start),
            indexOfIgnoreCase(source, "<DSanalysis", start),
            indexOfNextToolStatusBlockquote(source, start),
        )

    private fun indexOfNextToolStatusBlockquote(source: String, start: Int): Int {
        var pos = indexOfIgnoreCase(source, "<blockquote", start)
        while (pos >= 0) {
            if (startsWithToolStatusBlockquote(source, pos)) return pos
            pos = indexOfIgnoreCase(source, "<blockquote", pos + "<blockquote".length)
        }
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

    private fun extractSteelStepTitle(html: String): String =
        Regex(
            """<p\b[^>]*class=["'][^"']*\btool-steel-step-title\b[^"']*["'][^>]*>([\s\S]*?)</p>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let(::htmlToText).orEmpty()

    private fun extractSteelStepPreview(html: String): String? {
        val items = Regex(
            """<span\b[^>]*class=["'][^"']*\btool-steel-meta-item\b[^"']*["'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).map { htmlToText(it.groupValues[1]) }.filter { it.isNotBlank() }.distinct().toList()
        return items.joinToString("\n").ifBlank { null }
    }

    private fun toolNameFromStatusMarkup(unit: String): String {
        if (containsIgnoreCase(unit, "tool-search-blockquote")) return "web_search"
        val text = htmlToText(unit)
        return when {
            containsIgnoreCase(text, "搜索") -> "web_search"
            containsIgnoreCase(text, "查看图片") || containsIgnoreCase(text, "图片分析") -> "image-analyze"
            containsIgnoreCase(text, "绘制") || containsIgnoreCase(text, "图片生成") -> "image-gen"
            containsIgnoreCase(text, "Python") -> "python"
            containsIgnoreCase(text, "阅读网页") || containsIgnoreCase(text, "读取网页") -> "web_fetch"
            else -> "tool"
        }
    }

    private fun steelStepToolName(unit: String): String {
        val title = extractSteelStepTitle(unit)
        return when {
            containsIgnoreCase(unit, "data-steel-action=\"scrape\"") ||
                containsIgnoreCase(unit, "data-steel-action='scrape'") ||
                containsIgnoreCase(title, "阅读网页") ||
                containsIgnoreCase(title, "读取网页") -> "web_fetch"
            else -> "steel_browser"
        }
    }

    private fun webToolStatus(unit: String): ToolStatus {
        val lower = unit.lowercase(Locale.US)
        return when {
            lower.contains("data-analysis-phase=\"error\"") ||
                lower.contains(" tool-status error") ||
                lower.contains(" failed") ||
                lower.contains("失败") -> ToolStatus.FAILED
            else -> ToolStatus.SUCCESS
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
        containsIgnoreCase(unit, "web_search") -> "web_search"
        containsIgnoreCase(unit, "image-analyze") -> "image-analyze"
        containsIgnoreCase(unit, "image-gen") -> "image-gen"
        containsIgnoreCase(unit, "image-action") -> "image-action"
        containsIgnoreCase(unit, "操作类型: scrape") ||
            containsIgnoreCase(unit, "**操作类型:** scrape") ||
            containsIgnoreCase(unit, "operation type: scrape") ||
            (containsIgnoreCase(unit, "页面标题") && containsIgnoreCase(unit, "内容长度")) -> "web_fetch"
        containsIgnoreCase(unit, "python") -> "python"
        containsIgnoreCase(unit, "mcp") -> "mcp"
        containsIgnoreCase(unit, "image") -> "image-action"
        else -> "tool"
    }

    private fun readableDsLabel(unit: String): String = when (dsToolName(unit)) {
        "web_search" -> "网络搜索"
        "web_fetch" -> "阅读网页"
        "image-analyze" -> "图片分析"
        "image-gen" -> "图片生成"
        "image-action" -> "图片处理"
        "python" -> "Python 执行"
        "mcp" -> "连接器调用"
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

    private fun minPositive(vararg values: Int): Int =
        values.filter { it >= 0 }.minOrNull() ?: -1

    private fun visibleContent(m: MessageEntity): String {
        val frags = MessageJson.decodeFragments(m.fragmentsJson)
        val hasWebOnlyFragments = frags.any { it is MessageFragment.ToolCall || it is MessageFragment.Image || it is MessageFragment.SearchResult }
        if (!hasWebOnlyFragments && !m.rawText.isNullOrBlank()) return m.rawText!!
        val hasSearchTool = frags.any { it is MessageFragment.ToolCall && webToolType(it.name) == "web_search" }
        return buildString {
            frags.forEach { f ->
                when (f) {
                    is MessageFragment.Text -> append(f.markdown)
                    is MessageFragment.CodeBlock ->
                        append("\n```").append(f.language ?: "").append('\n').append(f.code).append("\n```\n")
                    is MessageFragment.Latex -> append(if (f.display) "$$${f.expr}$$" else "$${f.expr}$")
                    is MessageFragment.Mermaid -> append("\n```mermaid\n").append(f.source).append("\n```\n")
                    is MessageFragment.ToolCall -> append(webToolMarkup(f))
                    is MessageFragment.SearchResult -> if (!hasSearchTool) append(webSearchMarkup(f))
                    is MessageFragment.Image -> append("\n\n![${escapeMarkdownAlt(f.prompt ?: "生成的图片")}](${f.url})\n\n")
                    else -> Unit
                }
            }
        }.trim()
    }

    private fun webTimelineContent(m: MessageEntity): String =
        webTimelineContent(MessageJson.decodeFragments(m.fragmentsJson), m.rawText)

    /** fragments（+ 无时间线片段时的 rawText 快路径）→ Web 时间线文本。重试版本上传时复用。 */
    private fun webTimelineContent(frags: List<MessageFragment>, rawText: String?): String {
        val hasTimelineFragments = frags.any {
            it is MessageFragment.Thinking ||
                it is MessageFragment.ToolCall ||
                it is MessageFragment.Image ||
                it is MessageFragment.SearchResult
        }
        val plainFallback = rawText?.takeIf { it.isNotBlank() }
        if (!hasTimelineFragments && plainFallback != null) return plainFallback

        val hasSearchTool = frags.any { it is MessageFragment.ToolCall && webToolType(it.name) == "web_search" }
        return buildString {
            frags.forEach { f ->
                when (f) {
                    is MessageFragment.Text -> append(f.markdown)
                    is MessageFragment.Thinking -> appendWebThink(f.text)
                    is MessageFragment.CodeBlock ->
                        append("\n```").append(f.language ?: "").append('\n').append(f.code).append("\n```\n")
                    is MessageFragment.Latex -> append(if (f.display) "$$${f.expr}$$" else "$${f.expr}$")
                    is MessageFragment.Mermaid -> append("\n```mermaid\n").append(f.source).append("\n```\n")
                    is MessageFragment.ToolCall -> append(webToolMarkup(f))
                    is MessageFragment.SearchResult -> if (!hasSearchTool) append(webSearchMarkup(f))
                    is MessageFragment.Image -> append("\n\n![${escapeMarkdownAlt(f.prompt ?: "生成的图片")}](${f.url})\n\n")
                    is MessageFragment.Tip -> append("\n\n").append(f.text).append("\n\n")
                    is MessageFragment.Error -> append("\n\n").append(f.message).append("\n\n")
                    else -> Unit
                }
            }
        }.trim()
    }

    private fun StringBuilder.appendWebThink(text: String) {
        val thinking = text.trim()
        if (thinking.isBlank()) return
        append("\n\n<think>\n")
        append(thinking)
        append("\n</think>\n\n")
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
        if (toolType == "web_search") {
            return webSearchMarkup(chips = searchChipsFromTool(tool, label), phase = phase)
        }
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
        val chips = splitSearchChipText(search.query.takeIf { it.isNotBlank() } ?: "联网搜索")
        return webSearchMarkup(chips = chips, phase = "completed")
    }

    private data class SearchChip(
        val text: String,
        val badges: List<String> = emptyList(),
    )

    private fun webSearchMarkup(chips: List<SearchChip>, phase: String): String {
        val safePhase = when (phase) {
            "completed", "error", "analyzing" -> phase
            else -> "completed"
        }
        val chipHtml = chips.ifEmpty { listOf(SearchChip("联网搜索")) }.joinToString("") { chip ->
            val badges = chip.badges.joinToString("") { badge ->
                val icon = searchBadgeIcon(badge)
                "<span class=\"tool-search-chip-badge\"><span class=\"tool-search-chip-badge-icon\"><i class=\"$icon\"></i></span>${escapeHtml(badge)}</span>"
            }
            "<span class=\"tool-search-chip\"><span class=\"tool-search-chip-icon\" aria-hidden=\"true\"><i class=\"fas fa-search\"></i></span>" +
                "<span class=\"tool-search-chip-text\">${escapeHtml(chip.text)}</span>$badges</span>"
        }
        return "\n\n<blockquote class=\"tool-status $safePhase tool-search-blockquote\" data-search-phase=\"$safePhase\">" +
            "<p class=\"tool-search-title\">网络搜索</p><div class=\"tool-search-chip-wrap\">$chipHtml</div></blockquote>\n\n" +
            "<DSanalysis data-tool-type=\"web_search\" data-analysis-phase=\"$safePhase\"></DSanalysis>\n\n"
    }

    private fun searchChipsFromTool(tool: MessageFragment.ToolCall, label: String): List<SearchChip> =
        searchChipsFromArgs(tool.argsJson).ifEmpty { splitSearchChipText(label) }

    private fun searchChipsFromArgs(argsJson: String?): List<SearchChip> {
        if (argsJson.isNullOrBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject ?: return emptyList()
        val queries = root["queries"] as? JsonArray
        if (queries != null) {
            return queries.mapNotNull { item ->
                if (item is JsonPrimitive) {
                    return@mapNotNull item.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { SearchChip(it) }
                }
                val obj = item as? JsonObject ?: return@mapNotNull null
                val query = obj["query"]?.jsonPrimitive?.contentOrNull
                    ?: obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["q"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                SearchChip(query, searchBadgesFrom(obj))
            }.filter { it.text.isNotBlank() }
        }
        val query = root["query"]?.jsonPrimitive?.contentOrNull
            ?: root["search_query"]?.jsonPrimitive?.contentOrNull
            ?: root["q"]?.jsonPrimitive?.contentOrNull
            ?: return emptyList()
        return listOf(SearchChip(query, searchBadgesFrom(root))).filter { it.text.isNotBlank() }
    }

    private fun searchBadgesFrom(obj: JsonObject): List<String> =
        listOfNotNull(
            obj["topic"]?.jsonPrimitive?.contentOrNull,
            obj["time_range"]?.jsonPrimitive?.contentOrNull,
            obj["country"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
        ).filter { it.isNotBlank() }

    private fun splitSearchChipText(rawText: String): List<SearchChip> {
        val normalized = rawText
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^\s*(?:✓\s*)?(?:网络搜索|联网搜索|搜索完成|网络搜索完成|联网搜索完成)\s*[:：-]?\s*"""), "")
            .trim()
        if (normalized.isBlank()) return emptyList()
        val slashParts = normalized.split(Regex("""\s*/\s*""")).map { it.trim() }.filter { it.isNotBlank() }
        if (slashParts.size > 1) {
            return slashParts.map { parseSearchChipWithTrailingBadge(it) }
        }

        val knownBadge = """(?:news|finance|paper|technology|day|week|month|year)"""
        val matches = Regex("""(.+?)\s+($knownBadge)(?=\s+|$)""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map {
                SearchChip(
                    text = it.groupValues[1].trim(),
                    badges = listOf(it.groupValues[2].lowercase(Locale.US)),
                )
            }
            .filter { it.text.isNotBlank() }
            .toList()
        return matches.ifEmpty { listOf(parseSearchChipWithTrailingBadge(normalized)) }
    }

    private fun parseSearchChipWithTrailingBadge(value: String): SearchChip {
        val match = Regex("""^(.+?)\s+(news|finance|paper|technology|day|week|month|year)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim())
        return if (match != null) {
            SearchChip(match.groupValues[1].trim(), listOf(match.groupValues[2].lowercase(Locale.US)))
        } else {
            SearchChip(value.trim())
        }
    }

    private fun searchBadgeIcon(badge: String): String = when (badge.lowercase(Locale.US)) {
        "news" -> "far fa-newspaper"
        "finance" -> "fas fa-chart-line"
        "paper" -> "fas fa-graduation-cap"
        "technology" -> "fas fa-microchip"
        "day", "week", "month", "year" -> "far fa-clock"
        else -> if (badge.length == 2 && badge.all { it.isLetter() }) "fas fa-globe-asia" else "fas fa-tag"
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
        if (name == "web_fetch" || name == "steel_browser") {
            return if (completed) "阅读网页" else if (status == ToolStatus.FAILED) "网页阅读失败" else "正在阅读网页"
        }
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

    private fun sourcesOf(m: MessageEntity): List<SourceReference> {
        val seen = mutableSetOf<String>()
        return MessageJson.decodeFragments(m.fragmentsJson)
            .filterIsInstance<MessageFragment.SearchResult>()
            .flatMap { it.refs }
            .filter { source ->
                val key = source.url.takeIf { it.isNotBlank() }
                    ?: "${source.index}:${source.title}"
                seen.add(key)
            }
    }

    private fun sourcesToJson(sources: List<SourceReference>): JsonArray = buildJsonArray {
        sources.forEachIndexed { index, source ->
            add(
                buildJsonObject {
                    put("id", source.index ?: index + 1)
                    put("title", source.title)
                    put("url", source.url)
                    source.snippet?.takeIf { it.isNotBlank() }?.let { put("snippet", it) }
                    source.faviconUrl?.takeIf { it.isNotBlank() }?.let { put("favicon", it) }
                },
            )
        }
    }

}
