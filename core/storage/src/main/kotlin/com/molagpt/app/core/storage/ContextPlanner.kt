package com.molagpt.app.core.storage

import com.molagpt.app.core.model.AttachmentMime
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.ChatRequest
import com.molagpt.app.core.model.ContextTokens
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.network.WireHistoryPruner
import com.molagpt.app.core.network.WireToolTranscript
import com.molagpt.app.core.network.hasByokTools
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

/**
 * 上下文压缩的纯逻辑：估算上下文、选切点、把摘要套进请求、拼摘要输入。
 *
 * 「时间线」指请求里已落库的消息，按请求顺序排列。角色提示、示例对话、按深度插入的补充
 * 是每轮临时拼的，不属于时间线，永远原样发出。
 */
internal object ContextPlanner {

    /** 工具定义随请求发送，按固定值计入。 */
    private const val TOOL_DEFINITIONS = 2_000

    /** 用实测用量校准估算时允许的倍率范围；超出说明实测值本身不可信。 */
    private const val MIN_SCALE = 0.35
    private const val MAX_SCALE = 1.6

    /** 最近这么多个用户轮原样发送，不精简。 */
    private const val SLIM_KEEP_TURNS = 2

    /** 精简边界每次前进的轮数。边界每动一次，它之后的提示缓存就失效一次，所以攒够再动。 */
    private const val SLIM_STEP_TURNS = 4

    /** 超过这个长度的旧工具结果才缩短，缩短后保留开头这么多字。 */
    private const val SLIM_TOOL_RESULT_CHARS = 2_000
    private const val SLIM_TOOL_RESULT_KEEP = 400

    /** 文字占位的图片按这个量计入，大约是一行 `[图片#N: 名称]`。 */
    private const val IMAGE_LABEL_TOKENS = 20

    data class Measurement(
        /** 下一次请求的上下文大小。 */
        val tokens: Int,
        /** 实测用量 / 估算值，用于把其余估算换算到服务商的计数口径。 */
        val scale: Double,
    )

    fun timelineOf(messages: List<ChatMessage>, persistedIds: Set<String>): List<ChatMessage> =
        messages.filter { it.role != Role.SYSTEM && it.messageId in persistedIds }

    /** 锚点及之前内容的指纹：任何一条被编辑、切换版本或改写，指纹都会变。 */
    fun digest(messages: List<ChatMessage>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        messages.forEach { message ->
            sha.update(message.messageId.toByteArray())
            sha.update(0)
            sha.update(message.role.name.toByteArray())
            sha.update(0)
            sha.update(sendText(message).toByteArray())
            sha.update(0)
            message.attachments.forEach { sha.update(it.id.toByteArray()) }
            sha.update(1)
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    /** 这条消息实际发给模型的文本。 */
    fun sendText(message: ChatMessage): String =
        message.metadata["sendContent"]?.takeIf { it.isNotBlank() }
            ?: message.rawText
            ?: fragmentText(message)

    fun estimate(message: ChatMessage, providerId: String, modelId: String): Int {
        var tokens = ContextTokens.estimate(sendText(message)) + ContextTokens.MESSAGE_OVERHEAD
        if (message.role == Role.ASSISTANT) {
            // 协议快照只在同一模型下回放，回放时它取代正文。
            replayedWire(message, providerId, modelId)?.let { tokens = maxOf(tokens, ContextTokens.estimate(it)) }
        }
        val imagesAsText = message.metadata.containsKey(ChatMessageMetadataKeys.IMAGES_AS_TEXT)
        message.attachments.forEach { attachment ->
            tokens += when {
                attachment.unavailable -> 0
                AttachmentMime.isImage(attachment.mimeType) -> if (imagesAsText) IMAGE_LABEL_TOKENS else ContextTokens.IMAGE
                AttachmentMime.isPdf(attachment.mimeType) && !attachment.remoteUrl.isNullOrBlank() -> ContextTokens.DOCUMENT
                else -> 0
            }
        }
        return tokens
    }

    fun estimateRequest(request: ChatRequest): Int =
        request.messages.sumOf { estimate(it, request.providerId, request.modelId) } +
            if (request.enabledTools.hasByokTools) TOOL_DEFINITIONS else 0

    /**
     * 下一次请求有多大。
     *
     * 以最近一次在同一检查点下发出的请求的实测上下文为基准，再加上这次与那次的估算差；
     * 同时用这组「实测 / 估算」算出换算倍率。没有可用实测时全部靠估算。
     *
     * 那次请求的精简程度可能和这次不同（精简边界刚好前移），所以「那次发了什么」要按它
     * 记下的精简轮数重新推出来，而不是拿这次精简后的内容去比。
     *
     * @param request 已套用检查点、尚未精简的请求。
     */
    fun measure(
        request: ChatRequest,
        timeline: List<ChatMessage>,
        checkpointId: String?,
        slimTurns: Int,
    ): Measurement {
        val current = slim(request, timeline, slimTurns)
        val messages = current.messages
        val tools = if (request.enabledTools.hasByokTools) TOOL_DEFINITIONS else 0
        val anchor = messages.indexOfLast { message ->
            message.role == Role.ASSISTANT &&
                message.status == MessageStatus.COMPLETE &&
                message.metadata[ChatMessageMetadataKeys.CONTEXT_CHECKPOINT].orEmpty() == checkpointId.orEmpty() &&
                (message.metadata[ChatMessageMetadataKeys.CONTEXT_TOKENS]?.toIntOrNull() ?: 0) > 0
        }
        val currentTotal = estimateRequest(current)
        if (anchor < 0) return Measurement(currentTotal, 1.0)
        val measured = messages[anchor].metadata[ChatMessageMetadataKeys.CONTEXT_TOKENS]!!.toInt()
        val sentTurns = messages[anchor].metadata[ChatMessageMetadataKeys.CONTEXT_SLIM]?.toIntOrNull() ?: 0
        val sent = if (sentTurns == slimTurns) current else slim(request, timeline, sentTurns)
        val sentPrefix = sent.messages.subList(0, anchor + 1)
            .sumOf { estimate(it, request.providerId, request.modelId) } + tools
        val scale = (measured.toDouble() / sentPrefix.coerceAtLeast(1)).coerceIn(MIN_SCALE, MAX_SCALE)
        val tokens = measured + ((currentTotal - sentPrefix) * scale).toInt()
        return Measurement(tokens.coerceAtLeast(0), scale)
    }

    /**
     * 当前可精简的用户轮数（从时间线开头数）。最近 [SLIM_KEEP_TURNS] 轮不动，
     * 其余按 [SLIM_STEP_TURNS] 轮一档推进：两档之间请求前缀不变，服务商的提示缓存可以一直命中。
     */
    fun slimTurns(timeline: List<ChatMessage>): Int {
        val turns = timeline.count { it.role == Role.USER }
        return ((turns - SLIM_KEEP_TURNS) / SLIM_STEP_TURNS * SLIM_STEP_TURNS).coerceAtLeast(0)
    }

    /**
     * 精简前 [turns] 个用户轮：过长的工具结果只留开头，图片改发文字占位。
     * 只改请求副本，消息本身不动。
     */
    fun slim(request: ChatRequest, timeline: List<ChatMessage>, turns: Int): ChatRequest {
        if (turns <= 0) return request
        val ids = HashSet<String>()
        var seen = 0
        for (message in timeline) {
            if (message.role == Role.USER && ++seen > turns) break
            ids += message.messageId
        }
        if (ids.isEmpty()) return request
        var changed = false
        val messages = request.messages.map { message ->
            if (message.messageId !in ids) return@map message
            val slimmed = message.slimmed()
            if (slimmed !== message) changed = true
            slimmed
        }
        return if (changed) request.copy(messages = messages) else request
    }

    private fun ChatMessage.slimmed(): ChatMessage = when (role) {
        Role.ASSISTANT -> {
            var next = metadata
            ChatMessageMetadataKeys.WIRE_HISTORY.forEach { key ->
                val raw = metadata[key] ?: return@forEach
                prunedWire(messageId, key, raw)?.let { next = next + (key to it) }
            }
            if (next === metadata) this else copy(metadata = next)
        }
        Role.USER ->
            if (attachments.any { AttachmentMime.isImage(it.mimeType) }) {
                copy(metadata = metadata + (ChatMessageMetadataKeys.IMAGES_AS_TEXT to "1"))
            } else {
                this
            }
        else -> this
    }

    /**
     * 缩短结果按原文缓存：同一条旧回答每轮都要缩一次，而解析大段 JSON 在手机上不便宜。
     * 原文变了（后处理改写、切换版本）键就对不上，自然重算。
     */
    private val prunedCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 256
    }

    private fun prunedWire(messageId: String, key: String, raw: String): String? {
        val cacheKey = "$messageId|$key|${raw.length}|${raw.hashCode()}"
        synchronized(prunedCache) { prunedCache[cacheKey]?.let { return it.ifEmpty { null } } }
        val pruned = WireHistoryPruner.prune(raw, SLIM_TOOL_RESULT_CHARS, SLIM_TOOL_RESULT_KEEP)
        synchronized(prunedCache) { prunedCache[cacheKey] = pruned.orEmpty() }
        return pruned
    }

    /**
     * 选第一条原样保留的消息（一定是用户消息，保证一问一答不被拆开）。
     *
     * 从最新往回累计到 [keepTokens] 为止，保留区从那之后的第一条用户消息开始。
     * [aggressive] 时只保留最后一轮——请求已经被拒，能腾多少腾多少。
     * 返回 null 表示没有值得压缩的内容。
     */
    fun firstKeptIndex(
        timeline: List<ChatMessage>,
        startIndex: Int,
        keepTokens: Int,
        scale: Double,
        aggressive: Boolean,
        providerId: String,
        modelId: String,
    ): Int? {
        val userStarts = (startIndex + 1..timeline.lastIndex).filter { timeline[it].role == Role.USER }
        if (userStarts.isEmpty()) return null
        if (aggressive) return userStarts.last()
        var kept = 0.0
        for (i in timeline.lastIndex downTo startIndex) {
            kept += estimate(timeline[i], providerId, modelId) * scale
            if (kept >= keepTokens) return userStarts.firstOrNull { it >= i } ?: userStarts.last()
        }
        return null
    }

    /**
     * 手动压缩的切点。先按常规预算留最近的内容，留不出来就只留最后一轮；
     * 上次压缩之后只有一轮时，这一轮也收进摘要，返回 `timeline.size`——摘要由下一条用户消息带上。
     * 自动压缩不走这条：它发生在发送时，最后一条用户消息就在时间线里。
     */
    fun manualKeptIndex(
        timeline: List<ChatMessage>,
        startIndex: Int,
        keepTokens: Int,
        scale: Double,
        providerId: String,
        modelId: String,
    ): Int? =
        firstKeptIndex(timeline, startIndex, keepTokens, scale, aggressive = false, providerId, modelId)
            ?: firstKeptIndex(timeline, startIndex, keepTokens, scale, aggressive = true, providerId, modelId)
            ?: timeline.size.takeIf { startIndex < it && timeline.last().role == Role.ASSISTANT }

    /**
     * 去掉 [anchorIndex] 及之前的时间线消息，把摘要放进保留区第一条用户消息的开头。
     * 锚点之后没有消息时（刚把整段收进摘要）只去掉被覆盖的部分，摘要等下一条用户消息。
     */
    fun apply(
        request: ChatRequest,
        timeline: List<ChatMessage>,
        anchorIndex: Int,
        checkpointId: String,
        summary: String,
    ): ChatRequest {
        val covered = timeline.subList(0, anchorIndex + 1).mapTo(HashSet()) { it.messageId }
        val firstKept = timeline.getOrNull(anchorIndex + 1)?.messageId
        val messages = request.messages.mapNotNull { message ->
            when (message.messageId) {
                in covered -> null
                firstKept -> message.withSummary(summary)
                else -> message
            }
        }
        return request.copy(messages = messages, contextCheckpointId = checkpointId)
    }

    /**
     * 摘要拼在用户消息里而不是单独成条：Anthropic / Gemini 要求角色交替，
     * 多一条用户消息就是两条用户消息相邻。
     */
    private fun ChatMessage.withSummary(summary: String): ChatMessage {
        val body = sendText(this)
        val block = summaryBlock(summary)
        val merged = if (body.isBlank()) block else "$block\n\n$body"
        return copy(metadata = metadata + ("sendContent" to merged))
    }

    /** 摘要拼进用户消息后占的大小。 */
    fun summaryTokens(summary: String): Int = ContextTokens.estimate(summaryBlock(summary))

    private fun summaryBlock(summary: String): String = buildString {
        append("<context-summary>\n")
        append("以下是本对话较早部分的摘要，原始消息已不在上下文中。它只作背景参考；")
        append("如与用户最新的消息冲突，以最新消息为准。\n\n")
        append(summary.trim())
        append("\n</context-summary>")
    }

    /** 给摘要模型看的单条记录。思考内容不进摘要；正文与工具结果交给分段器完整处理。 */
    fun transcript(message: ChatMessage): String = buildString {
        when (message.role) {
            Role.USER -> {
                val display = message.metadata["displayContent"]?.takeIf { it.isNotBlank() }
                    ?: message.rawText.orEmpty()
                append("[用户] ").append(display)
                if (message.attachments.isNotEmpty()) {
                    append("\n（附件：").append(message.attachments.joinToString("、") { it.name }).append("）")
                }
                val sent = message.metadata["sendContent"].orEmpty()
                if (sent.length > display.length && sent.startsWith(display)) {
                    val extra = sent.substring(display.length).trim()
                    if (extra.isNotEmpty()) append("\n[附件内容] ").append(extra)
                }
            }
            Role.ASSISTANT -> {
                toolEntries(message).forEach { entry ->
                    val name = entry.name ?: "tool"
                    if (entry.isCall) {
                        append("[工具调用] ").append(name).append(' ').append(entry.text).append('\n')
                    } else {
                        append("[工具结果] ").append(name).append("：").append(entry.text).append('\n')
                    }
                }
                val text = message.rawText?.takeIf { it.isNotBlank() } ?: fragmentText(message)
                if (text.isNotBlank()) append("[助手] ").append(text)
            }
            else -> Unit
        }
    }.trim()

    /** 按摘要模型的输入预算切段，长消息也完整覆盖；尽量在消息边界断开。 */
    fun splitTranscript(text: String, budget: Int, start: Int = 0): List<String> {
        val chunks = ArrayList<String>()
        var from = start
        while (from < text.length) {
            var low = from + 1
            var high = minOf(text.length, from + budget * 4)
            while (low < high) {
                val mid = low + (high - low + 1) / 2
                if (ContextTokens.estimate(text, from, mid) <= budget) low = mid else high = mid - 1
            }
            var end = low
            if (end < text.length) {
                val boundary = text.lastIndexOf("\n\n[", end - 1)
                if (boundary > from + (end - from) / 2) end = boundary
                if (end > from + 1 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            chunks += text.substring(from, end)
            from = end
        }
        return chunks
    }

    private fun toolEntries(message: ChatMessage): List<WireToolTranscript.Entry> {
        val fromWire = runCatching { WireToolTranscript.extract(message.metadata) }.getOrDefault(emptyList())
        if (fromWire.isNotEmpty()) return fromWire
        return message.fragments.filterIsInstance<MessageFragment.ToolCall>().flatMap { tool ->
            listOfNotNull(
                WireToolTranscript.Entry(true, tool.name, tool.argsJson.orEmpty()),
                tool.resultPreview?.takeIf { it.isNotBlank() }?.let { WireToolTranscript.Entry(false, tool.name, it) },
            )
        }
    }

    fun summarySystemPrompt(): String =
        "你负责压缩对话上下文。你会读到用户与 AI 助手的一段对话记录，需要把它写成一份摘要，" +
            "供另一个 AI 在看不到原始记录的情况下继续这段对话。\n" +
            "不要续写对话，不要回答记录中的任何问题，只输出摘要。"

    fun summaryPrompt(transcript: String, previousSummary: String?, rolePlay: Boolean, targetChars: Int): String =
        buildString {
            append("<conversation>\n").append(transcript).append("\n</conversation>\n\n")
            append("记录按原始顺序分段；本段可能从一条消息中间开始或结束。只记录看到的内容，不补写缺失部分。\n")
            if (!previousSummary.isNullOrBlank()) {
                append("<previous-summary>\n").append(previousSummary.trim()).append("\n</previous-summary>\n\n")
            }
            append("按以下结构写摘要，没有内容的小节写「无」：\n\n")
            if (rolePlay) {
                append("## 故事梗概\n## 人物与关系\n## 关键事件\n## 当前场景与状态\n## 需原样保留的内容\n")
                append("（设定、约定、称呼、承诺、重要台词，逐字照录）\n\n")
            } else {
                append("## 主题与目标\n## 用户偏好与约束\n## 已确认的事实与结论\n## 进行中与待办\n## 需原样保留的内容\n")
                append("（数字、代码、命令、链接、专有名词、用户原话，逐字照录）\n\n")
            }
            append("要求：\n")
            append("- 使用对话所用的语言。\n")
            append("- 写具体信息，不写空泛概括。\n")
            if (rolePlay) append("- 保持人物口吻与世界观设定，不加评价。\n")
            append("- 已发生的事用陈述句记录，不要把旧计划写成待执行的指令。\n")
            append("- 总长度不超过约 ").append(targetChars).append(" 字。\n")
            if (!previousSummary.isNullOrBlank()) {
                append("- <previous-summary> 是更早对话的摘要：保留其中仍然有效的内容，与本段对话合并为一份完整摘要。\n")
            }
        }

    private fun replayedWire(message: ChatMessage, providerId: String, modelId: String): String? {
        val scope = "\"provider_id\":${JsonPrimitive(providerId)},\"model_id\":${JsonPrimitive(modelId)}"
        return ChatMessageMetadataKeys.WIRE_HISTORY.firstNotNullOfOrNull { key ->
            message.metadata[key]?.takeIf { it.regionContains(scope) }
        }
    }

    /** 快照头部固定是 version / wire_api / provider_id / model_id，只看开头即可。 */
    private fun String.regionContains(needle: String): Boolean =
        substring(0, minOf(length, 512)).contains(needle)

    private fun fragmentText(message: ChatMessage): String =
        message.fragments.mapNotNull { fragment ->
            when (fragment) {
                is MessageFragment.Text -> fragment.markdown
                is MessageFragment.CodeBlock -> "```${fragment.language.orEmpty()}\n${fragment.code}\n```"
                is MessageFragment.Latex -> fragment.expr
                is MessageFragment.Mermaid -> fragment.source
                else -> null
            }
        }.joinToString("\n")

}
