package com.molagpt.app.core.network

import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.ServerToolInfo

sealed interface WebToolArtifact {
    data class Text(val text: String) : WebToolArtifact
    data class Tool(
        val id: String,
        val name: String,
        val status: ToolStatus,
        val label: String,
        val preview: String? = null,
        val provider: String? = null,
        val agentId: String? = null,
        val serverTool: ServerToolInfo? = null,
    ) : WebToolArtifact
}

class WebToolArtifactSplitter {
    private val carry = StringBuilder()
    private val activeToolIds = mutableMapOf<String, String>()
    private var activeDs: DsState? = null
    /**
     * 最近一个仍在进行的工具卡片（tool-status / steel-step）。
     * 裸分析标签可能没有 data-tool-type，因此完成态按位置关联到最近卡片。
     */
    private var lastRunningTool: ToolRef? = null
    private var sequence = 0
    private val managedTools = mutableMapOf<String, WebToolArtifact.Tool>()
    private var closedMcp = false

    fun feed(text: String): List<WebToolArtifact> {
        carry.append(text)
        return drain(flush = false)
    }

    fun flush(): List<WebToolArtifact> = drain(flush = true)

    private fun drain(flush: Boolean): List<WebToolArtifact> {
        val out = mutableListOf<WebToolArtifact>()
        while (carry.isNotEmpty()) {
            activeDs?.let { state ->
                consumeActiveDs(state, out)
                if (carry.isEmpty()) return@let
            }
            if (activeDs != null) break

            val s = carry.toString()
            val marker = nextMarker(s)
            if (marker == null) {
                val keep = if (flush) 0 else longestMarkerPrefixSuffix(s)
                val flushLen = s.length - keep
                if (flushLen > 0) {
                    val text = s.substring(0, flushLen)
                    if (text.any { !it.isWhitespace() && it != '\u200B' && it != '\uFEFF' }) {
                        lastRunningTool = null
                        closedMcp = false
                    }
                    out += WebToolArtifact.Text(text)
                    carry.delete(0, flushLen)
                }
                break
            }

            if (marker.index > 0) {
                out += WebToolArtifact.Text(s.substring(0, marker.index))
                carry.delete(0, marker.index)
                continue
            }

            if (marker.type == MarkerType.DS_ANALYSIS) {
                val before = carry.length
                consumeDsStart(flush, out)
                if (carry.length == before && activeDs == null) break
                continue
            }

            val end = s.indexOf(marker.endToken, startIndex = marker.startToken.length)
            if (end < 0) {
                if (flush) {
                    out += WebToolArtifact.Text(stripUiMarkers(s).ifBlank { s })
                    carry.clear()
                }
                break
            }

            val raw = s.substring(0, end + marker.endToken.length)
            val artifact = when (marker.type) {
                MarkerType.TOOL_STATUS -> parseToolStatus(raw)
                MarkerType.STEEL_STEP -> parseSteelStep(raw)
                MarkerType.DS_ANALYSIS -> error("DSanalysis is handled by consumeDsStart")
            }
            val redundantConnectorStatus = closedMcp && artifact.serverTool == null &&
                artifact.status == ToolStatus.SUCCESS && artifact.label in setOf("处理中...", "处理中…")
            if (!redundantConnectorStatus) out += artifact
            closedMcp = false
            carry.delete(0, raw.length)
        }
        return out
    }

    private fun consumeDsStart(flush: Boolean, out: MutableList<WebToolArtifact>) {
        val text = carry.toString()
        val tagEnd = text.indexOf('>')
        if (tagEnd < 0) {
            if (flush) {
                out += WebToolArtifact.Text(stripUiMarkers(text).ifBlank { text })
                carry.clear()
            }
            return
        }

        val tag = text.substring(0, tagEnd + 1)
        val toolType = attr(tag, "data-tool-type")?.ifBlank { null } ?: "analysis"
        val phase = attr(tag, "data-analysis-phase")?.lowercase()
        val showContent = toolType.lowercase() in CONTENT_VISIBLE_TOOL_TYPES
        // 分析标签完成时，优先更新最近一个仍在进行的工具卡片。
        val wireId = attr(tag, "data-tool-call")
        val managed = wireId?.let(managedTools::get) ?: lastRunningTool?.id?.let(managedTools::get)
        val prev = managed?.let { ToolRef(it.id, toolKey(it.name), it.name, it.label, it.provider.orEmpty()) } ?: lastRunningTool
        val state = when {
            managed != null -> DsState(
                id = managed.id, key = toolKey(managed.name), name = managed.name,
                label = managed.label, provider = managed.provider.orEmpty(), phase = phase,
                showContent = showContent || toolType == "web_search", emitCard = true,
                serverTool = managed.serverTool, managedStatus = managed.status,
                initialPreview = managed.preview,
            )
            // 需要显示内容的工具：有前置卡片则复用，否则独立新建。
            showContent -> DsState(
                id = prev?.id ?: toolIdFor(toolKey(toolType), ToolStatus.RUNNING),
                key = prev?.key ?: toolKey(toolType),
                name = toolType,
                label = analysisLabel(toolType),
                provider = readableProvider(toolType),
                phase = phase,
                showContent = true,
                emitCard = true,
            )
            // 不显示内容但有前置卡片：只更新前置卡片状态。
            prev != null -> DsState(
                id = prev.id,
                key = prev.key,
                name = prev.name,
                label = prev.label,
                provider = prev.provider,
                phase = phase,
                showContent = false,
                emitCard = true,
            )
            // 不显示内容且没有前置卡片：消费文本但不渲染卡片。
            else -> DsState(
                id = "",
                key = "",
                name = toolType,
                label = "",
                provider = "",
                phase = phase,
                showContent = false,
                emitCard = false,
            )
        }
        activeDs = state
        carry.delete(0, tagEnd + 1)
        consumeActiveDs(state, out)
    }

    private fun consumeActiveDs(state: DsState, out: MutableList<WebToolArtifact>) {
        val text = carry.toString()
        val end = text.indexOf("</DSanalysis>")
        if (end >= 0) {
            state.buffer.append(text.substring(0, end))
            val status = state.managedStatus ?: when (state.phase) {
                "error", "failed", "failure" -> ToolStatus.FAILED
                "cancelled" -> ToolStatus.CANCELLED
                else -> ToolStatus.SUCCESS
            }
            if (state.emitCard) {
                val tool = state.toTool(status)
                if (state.serverTool != null) managedTools[state.id] = tool
                out += tool
            }
            // 关联卡片已结束：清位置锚点与其 key（后续同类工具会新建卡片）。
            if (state.serverTool == null) {
                if (state.key.isNotEmpty()) activeToolIds.remove(state.key)
                lastRunningTool = null
            }
            closedMcp = state.name == "mcp"
            activeDs = null
            carry.delete(0, end + "</DSanalysis>".length)
        } else {
            val close = "</DSanalysis>"
            val keep = (minOf(text.length, close.length - 1) downTo 1)
                .firstOrNull { text.endsWith(close.take(it), ignoreCase = true) } ?: 0
            state.buffer.append(text.dropLast(keep))
            carry.delete(0, text.length - keep)
            // 仅白名单(显内容)在流式中刷新；非白名单复用前置卡片不必刷（完成时一次性置完成）。
            if (state.emitCard && state.showContent) out += state.toTool(state.managedStatus ?: ToolStatus.RUNNING)
        }
    }

    private fun nextMarker(text: String): Marker? =
        MARKERS.mapNotNull { marker ->
            val index = text.indexOf(marker.startToken)
            if (index >= 0) marker.copy(index = index) else null
        }.minByOrNull { it.index }

    private fun parseToolStatus(raw: String): WebToolArtifact.Tool {
        if (attr(raw, "data-tool-call") != null) return parseManagedTool(raw)
        val batch = Regex("""data-subagent-batch="([^"]+)"""").find(raw)?.groupValues?.get(1)
        if (batch != null) return WebToolArtifact.Tool(
            id = "subagents_$batch", name = "server_subagents", status = ToolStatus.RUNNING,
            label = "子代理任务", agentId = "server:$batch",
        )
        val classes = raw.substringBefore('>').lowercase()
        val label = stripHtml(raw).ifBlank { "工具调用中" }
        val status = when {
            "cancelled" in classes -> ToolStatus.CANCELLED
            "error" in classes -> ToolStatus.FAILED
            "completed" in classes || "success" in classes -> ToolStatus.SUCCESS
            else -> ToolStatus.RUNNING
        }
        val name = when {
            "tool-search" in classes || label.contains("搜索") || label.contains("检索") -> "web_search"
            label.contains("查看图片") || label.contains("分析图片") || label.contains("图片分析") -> "image-analyze"
            "tool-image" in classes || label.contains("绘制") || label.contains("图片") -> "image-gen"
            label.contains("Python", ignoreCase = true) -> "execute_python_code"
            label.contains("浏览器") || label.contains("网页") -> "steel_browser"
            else -> "tool_status"
        }
        val key = toolKey(name)
        val id = toolIdFor(key, status)
        if (status != ToolStatus.RUNNING) activeToolIds.remove(key)
        val provider = readableProvider(name)
        if (status == ToolStatus.RUNNING) {
            lastRunningTool = ToolRef(id, key, name, label, provider)
        } else if (lastRunningTool?.id == id) {
            lastRunningTool = null
        }
        return WebToolArtifact.Tool(
            id = id,
            name = name,
            status = status,
            label = label,
            provider = provider,
        )
    }

    private fun parseSteelStep(raw: String): WebToolArtifact.Tool {
        if (attr(raw, "data-tool-call") != null) return parseManagedTool(raw)
        val label = stripHtml(raw).ifBlank { "网页访问" }
        val status = if (label.contains("失败") || label.contains("错误")) ToolStatus.FAILED else ToolStatus.RUNNING
        val key = toolKey("steel_browser")
        val id = toolIdFor(key, status)
        val provider = readableProvider("steel_browser")
        if (status == ToolStatus.RUNNING) {
            lastRunningTool = ToolRef(id, key, "steel_browser", label, provider)
        } else if (lastRunningTool?.id == id) {
            lastRunningTool = null
        }
        return WebToolArtifact.Tool(
            id = id,
            name = "steel_browser",
            status = status,
            label = label,
            provider = provider,
        )
    }

    private fun parseManagedTool(raw: String): WebToolArtifact.Tool {
        val id = requireNotNull(attr(raw, "data-tool-call"))
        val previous = managedTools[id]
        val classes = Regex("""class=["']([^"']*)["']""").find(raw)?.groupValues?.get(1).orEmpty()
        val status = when {
            "cancelled" in classes -> ToolStatus.CANCELLED
            "error" in classes -> ToolStatus.FAILED
            "completed" in classes || "success" in classes -> ToolStatus.SUCCESS
            else -> ToolStatus.RUNNING
        }
        val label = Regex("""<p\b[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
        fun field(css: String) = Regex("""<(?:div|span)\b[^>]*class=["'][^"']*\b$css\b[^"']*["'][^>]*>(.*?)</(?:div|span)>""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.let(::stripHtml)
        fun ticket(css: String) = Regex("""<button\b[^>]*class=["'][^"']*\b$css\b[^"']*["'][^>]*>""")
            .find(raw)?.value?.let { attr(it, "data-tool-ticket") }
        val info = ServerToolInfo(
            callId = id, icon = attr(raw, "data-tool-icon") ?: previous?.serverTool?.icon,
            runningLabel = previous?.serverTool?.runningLabel ?: label.takeIf { status == ToolStatus.RUNNING },
            metadata = field("tool-status-meta") ?: field("tool-steel-meta-item") ?: previous?.serverTool?.metadata,
            error = field("tool-status-error"), retryTicket = ticket("tool-download-retry"),
            fileTicket = ticket("tool-retry-file"), filename = attr(raw, "data-filename"),
        )
        val name = attr(raw, "data-tool-name") ?: previous?.name ?: "tool"
        val tool = WebToolArtifact.Tool(
            id = id, name = name, status = status, label = label,
            preview = previous?.preview ?: Regex("""<span\b[^>]*class=["'][^"']*\btool-search-chip-text\b[^"']*["'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(raw).map { stripHtml(it.groupValues[1]) }.joinToString("\n").ifBlank { null },
            provider = readableProvider(name), serverTool = info,
        )
        managedTools[id] = tool
        if (status == ToolStatus.RUNNING) lastRunningTool = ToolRef(id, toolKey(name), name, label, tool.provider.orEmpty())
        else if (lastRunningTool?.id == id) lastRunningTool = null
        return tool
    }

    private fun toolIdFor(key: String, status: ToolStatus): String {
        val existing = activeToolIds[key]
        if (existing != null) return existing
        sequence += 1
        val id = "webtool_${sequence}_${key.replace(Regex("[^a-z0-9_]+"), "_")}"
        if (status == ToolStatus.RUNNING) activeToolIds[key] = id
        return id
    }

    private fun toolKey(name: String): String = when (name) {
        "execute_python_code", "python" -> "python"
        "web_search", "search_web" -> "web_search"
        "steel_browser", "browser" -> "steel_browser"
        "image-gen" -> "image_gen"
        "image-analyze" -> "image_analyze"
        "image-action" -> "image_action"
        "mcp" -> "mcp"
        else -> name.ifBlank { "tool" }.lowercase()
    }

    private fun longestMarkerPrefixSuffix(text: String): Int {
        var best = 0
        for (marker in MARKERS) {
            val max = minOf(text.length, marker.startToken.length - 1)
            for (len in max downTo 1) {
                if (text.regionMatches(text.length - len, marker.startToken, 0, len)) {
                    best = maxOf(best, len)
                    break
                }
            }
        }
        return best
    }

    private fun stripHtml(raw: String): String = raw
        .replace(Regex("<[^>]+>"), " ")
        .decodeBasicHtml()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripUiMarkers(raw: String): String = raw
        .replace("<!--PY_OUTPUT_BEGIN-->", "")
        .replace("<!--PY_OUTPUT_END-->", "")
        .replace("<!--MCP_OUTPUT_BEGIN-->", "")
        .replace("<!--MCP_OUTPUT_END-->", "")
        .replace(Regex("</?DSanalysis\\b[^>]*>", RegexOption.IGNORE_CASE), "")
        .decodeBasicHtml()
        .trim()

    private fun String.decodeBasicHtml(): String = this
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    private fun attr(tag: String, name: String): String? {
        val pattern = Regex("""$name\s*=\s*["']([^"']*)["']""")
        return pattern.find(tag)?.groupValues?.getOrNull(1)?.decodeBasicHtml()
    }

    private fun analysisLabel(toolType: String): String = when (toolType) {
        "python" -> "分析过程"
        "mcp" -> "连接器调用"
        "image-gen" -> "图片生成"
        "image-analyze" -> "图片分析"
        "image-action" -> "图片处理"
        "tool-call" -> "工具调用"
        else -> "工具调用"
    }

    private fun readableProvider(name: String): String = when (name) {
        "read_document", "search_document" -> "会话文档"
        "molagpt_fast_codebase_search", "code-search" -> "项目代码"
        "activate_skill" -> "技能"
        "recall_memory", "forget_memories_by_description" -> "记忆"
        "molagpt_import_sandbox_to_project" -> "项目文件"
        "analyze_image", "visual_search" -> "图片查看"
        "image_file_process" -> "图片处理"
        "image_generation_and_editing" -> "图片生成"
        "web_search", "search_web" -> "联网搜索"
        "steel_browser", "browser" -> "网页阅读"
        "execute_python_code", "python" -> "Python"
        "mcp" -> "MCP 服务器"
        "image-gen" -> "图片生成"
        "image-analyze" -> "图片分析"
        "image-action" -> "图片处理"
        else -> name
    }

    private data class DsState(
        val id: String,
        val name: String,
        val key: String,
        val label: String,
        val provider: String,
        val phase: String?,
        /**
         * 是否把分析标签内容展开显示在卡片里。
         * 图片生成等工具已有独立结果视图时不展开内容；完成态仍通过共享 id 更新工具卡片。
         */
        val showContent: Boolean,
        /** 是否产生/更新可见工具卡片。false=非白名单且无前置卡片，完全隐藏（仅消费文本不渲染）。 */
        val emitCard: Boolean,
        val serverTool: ServerToolInfo? = null,
        val managedStatus: ToolStatus? = null,
        val initialPreview: String? = null,
        val buffer: StringBuilder = StringBuilder(),
    ) {
        fun toTool(status: ToolStatus): WebToolArtifact.Tool =
            WebToolArtifact.Tool(
                id = id,
                name = name,
                status = status,
                label = label,
                preview = if (showContent) {
                    buffer.toString().let { stripPreview(it) }.takeIf { it.isNotBlank() } ?: initialPreview
                } else {
                    initialPreview
                },
                provider = provider,
                serverTool = serverTool,
            )

        private fun stripPreview(raw: String): String = raw
            .replace("<!--PY_OUTPUT_BEGIN-->", "")
            .replace("<!--PY_OUTPUT_END-->", "")
            .replace("<!--MCP_OUTPUT_BEGIN-->", "")
            .replace("<!--MCP_OUTPUT_END-->", "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    private data class Marker(
        val startToken: String,
        val endToken: String,
        val type: MarkerType,
        val index: Int = -1,
    )

    /** 最近进行中工具卡片的身份（供分析标签位置关联复用）。 */
    private data class ToolRef(
        val id: String,
        val key: String,
        val name: String,
        val label: String,
        val provider: String,
    )

    private enum class MarkerType { TOOL_STATUS, DS_ANALYSIS, STEEL_STEP }

    private companion object {
        /**
         * 分析标签内容显示白名单（data-tool-type 取值）。
         * 其余类型只更新工具卡片状态，不展开原始分析内容。
         */
        val CONTENT_VISIBLE_TOOL_TYPES = setOf("python", "mcp", "image-action", "code-search")

        val MARKERS = listOf(
            Marker("<blockquote class=\"tool-status", "</blockquote>", MarkerType.TOOL_STATUS),
            Marker("<DSanalysis", "</DSanalysis>", MarkerType.DS_ANALYSIS),
            Marker("<steel-step>", "</steel-step>", MarkerType.STEEL_STEP),
        )
    }
}
