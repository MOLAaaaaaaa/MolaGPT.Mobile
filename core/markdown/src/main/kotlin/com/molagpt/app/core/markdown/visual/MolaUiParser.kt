package com.molagpt.app.core.markdown.visual

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个 `mola-ui` 围栏的解析结果。 */
sealed interface MolaUiParsed {
    /** 还在流式写、暂时解析不了——保持占位。[component] 通常先到，用来预留该组件的高度。 */
    data class Incomplete(val component: String?) : MolaUiParsed

    /** 写完了但用不了：折叠显示源码和原因。 */
    data class Invalid(val reason: String) : MolaUiParsed

    data class Ready(val component: String, val id: String, val spec: VisualSpec) : MolaUiParsed
}

/**
 * 读 `mola-ui` 围栏：一个 JSON 对象，含 component、id、props。
 *
 * 模型偏离 JSON 有四种可预见的方式：尾逗号、注释、中文里没转义的英文双引号、LaTeX 里
 * 没转义的反斜杠。前两种解析器直接容忍；反斜杠先按 LaTeX 读一遍；引号按「后面紧跟
 * 结构字符才算字符串结束」修一遍再读。移植自桌面端。
 */
object MolaUiParser {
    const val FENCE_LANGUAGE = "mola-ui"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowTrailingComma = true
        allowComments = true
    }

    private val COMPONENT_PEEK = Regex("\"component\"\\s*:\\s*\"([A-Za-z0-9-]+)\"")

    private const val CACHE_LIMIT = 48
    private val cache = object : LinkedHashMap<String, MolaUiParsed>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MolaUiParsed>?) = size > CACHE_LIMIT
    }

    /**
     * 按源码缓存的 [parse]。流式期间整段回答每来一批就重解析一次，已写完的组件
     * 拿回同一个实例——界面按引用判断没变，滑块位置、排序状态都不会被重置。
     */
    fun parseCached(raw: String, closed: Boolean): MolaUiParsed {
        val key = (if (closed) "1" else "0") + raw
        synchronized(cache) { cache[key]?.let { return it } }
        val parsed = parse(raw, closed)
        // 流式中的半截结果不进缓存：每一批都不一样，只会把写完的挤出去。
        if (parsed !is MolaUiParsed.Incomplete) synchronized(cache) { cache[key] = parsed }
        return parsed
    }

    /** @param closed 围栏已写出结束标记，或整条回答已结束。在此之前括号没配平的解析失败只意味着「还没写完」。 */
    fun parse(raw: String, closed: Boolean): MolaUiParsed {
        val text = raw.trim()
        if (text.isEmpty()) return if (closed) MolaUiParsed.Invalid("组件内容为空") else MolaUiParsed.Incomplete(null)
        // 流式中括号还没配平时一定解析不了，省掉两次注定失败的解析。
        if (!closed && !bracesBalanced(text)) return MolaUiParsed.Incomplete(peekComponent(text))

        val prepared = keepLatexBackslashes(text)
        var error: String? = null
        val root: JsonElement? = try {
            json.parseToJsonElement(prepared)
        } catch (first: SerializationException) {
            try {
                json.parseToJsonElement(repairQuotes(prepared))
            } catch (_: SerializationException) {
                error = first.message
                null
            }
        }

        if (root == null) {
            if (!closed && !bracesBalanced(text)) return MolaUiParsed.Incomplete(peekComponent(text))
            return MolaUiParsed.Invalid("JSON 无法解析：" + shorten(error))
        }
        if (root !is JsonObject) return MolaUiParsed.Invalid("顶层必须是 JSON 对象")

        val component = VisualJson.string(root, "component")?.trim()?.lowercase()
            ?: return MolaUiParsed.Invalid("缺少 component 字段")
        val id = (root["id"] as? JsonPrimitive)?.content.orEmpty()
        val props = root["props"] as? JsonObject ?: return MolaUiParsed.Invalid("props 必须是对象")

        val result: SpecResult<VisualSpec> = when (component) {
            "function-plot", "plot", "function" -> FunctionPlotSpec.parse(props)
            "chart" -> ChartSpec.parse(props)
            "data-table", "table" -> DataTableSpec.parse(props)
            "stat-grid", "stats", "metrics" -> StatGridSpec.parse(props)
            "card-grid", "cards" -> CardGridSpec.parse(props)
            else -> return MolaUiParsed.Invalid(
                "不认识的组件 $component（可用 function-plot、chart、data-table、stat-grid、card-grid）",
            )
        }
        return when (result) {
            is SpecResult.Ok -> MolaUiParsed.Ready(component, id, result.spec)
            is SpecResult.Error -> MolaUiParsed.Invalid(result.message)
        }
    }

    /** 组件名通常最先写出，宿主据此在其余内容还在流式时就预留该组件的高度。 */
    fun peekComponent(raw: String): String? = COMPONENT_PEEK.find(raw)?.groupValues?.get(1)?.lowercase()

    /**
     * 模型在组件里写 LaTeX（\sin、\alpha、\frac）时不会把反斜杠写成 \\。\s 这类非法转义让
     * 整个组件解析失败；\f、\b、\r、\t 虽然合法，读出来却是控制字符加半截命令。
     *
     * 所以：非法转义保留反斜杠本身；\b \f \r \t 后面紧跟英文字母时也按字面保留——这几个
     * 控制字符模型不会有意写。\n 照常当换行：它在正文里太常见，猜错代价更大。
     */
    private fun keepLatexBackslashes(text: String): String {
        if ('\\' !in text) return text
        val output = StringBuilder(text.length + 8)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!inString || c != '\\' || i + 1 >= text.length) {
                if (c == '"') inString = !inString
                output.append(c)
                i++
                continue
            }
            val next = text[i + 1]
            val literal = when (next) {
                '"', '\\', '/', 'n' -> false
                'u' -> !(i + 5 < text.length && (i + 2..i + 5).all { text[it].isHexDigit() })
                'b', 'f', 'r', 't' -> i + 2 < text.length && text[i + 2].isAsciiLetter()
                else -> true
            }
            if (literal) {
                output.append("\\\\")
                i++
            } else {
                output.append(c).append(next)
                i += 2
            }
        }
        return output.toString()
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun repairQuotes(text: String): String {
        val output = StringBuilder(text.length + 16)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!inString) {
                if (c == '"') inString = true
                output.append(c)
                i++
                continue
            }
            if (c == '\\' && i + 1 < text.length) {
                output.append(c).append(text[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                var look = i + 1
                while (look < text.length && text[look].isWhitespace()) look++
                if (look >= text.length || text[look] in ",}]:") {
                    inString = false
                    output.append(c)
                } else {
                    output.append("\\\"")
                }
                i++
                continue
            }
            // 字符串里的裸换行是模型常犯的另一种错。
            when (c) {
                '\n' -> output.append("\\n")
                '\r' -> Unit
                else -> output.append(c)
            }
            i++
        }
        return output.toString()
    }

    private fun bracesBalanced(text: String): Boolean {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
            i++
        }
        return depth <= 0 && !inString
    }

    private fun shorten(message: String?): String {
        if (message.isNullOrBlank()) return "格式错误"
        var text = message.substringBefore("\nJSON input").substringBefore(" at path").trim()
        text = text.removePrefix("Unexpected JSON token at offset ").let { rest ->
            if (rest === text) text else "第 ${rest.substringBefore(':')} 个字符处：${rest.substringAfter(':').trim()}"
        }
        return if (text.length > 120) text.take(120) + "…" else text
    }
}

/**
 * 回答里的 HTML 围栏什么时候显示成「网页」卡片。规则同桌面端 FenceArtifactCapture：
 * 按语言分类；没声明语言的只认完整文档。尺寸门槛只在写完后才看——教程里三行的片段留作代码。
 */
object HtmlFence {
    // 没声明是整页的 HTML 要够「一页」大才离开正文：教程里满是 10–20 行的示例，属于它旁边的讲解。
    private const val MIN_BYTES = 1500
    private const val MIN_LINES = 30

    private val DOCUMENT_START = Regex("""^\s*(?:<!doctype\s+html|<html\b)""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<title[^>]*>([^<]{1,80})</title>""", RegexOption.IGNORE_CASE)

    fun normalizeLanguage(info: String?): String {
        var value = info.orEmpty().trim()
        val cut = value.indexOfAny(charArrayOf(' ', '\t', ',', ';', '{'))
        if (cut >= 0) value = value.substring(0, cut)
        return value.trim('.', '{', '}').lowercase()
    }

    /** 这段代码是不是 HTML（能拿去运行）。 */
    fun isHtml(language: String?, code: String): Boolean = when (normalizeLanguage(language)) {
        "html", "htm", "xhtml" -> true
        // 只认完整文档：没标语言、装着一个 <div> 的围栏，多半是示例而不是要运行的东西。
        "", "text", "txt", "plain", "plaintext", "xml" -> DOCUMENT_START.containsMatchIn(code)
        else -> false
    }

    /**
     * 是否显示成卡片。还在写的围栏凭首行（文件名或 doctype）就能认出是整页；
     * 写完的还要过尺寸门槛。
     */
    fun isPage(language: String?, code: String, closed: Boolean): Boolean {
        if (!isHtml(language, code)) return false
        val lines = lineCount(code)
        if (!closed) return isDeclaredPage(code) || lines >= MIN_LINES
        if (code.isBlank()) return false
        return isDeclaredPage(code) || code.toByteArray(Charsets.UTF_8).size >= MIN_BYTES || lines >= MIN_LINES
    }

    fun lineCount(code: String): Int = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1

    private fun isDeclaredPage(code: String): Boolean =
        DOCUMENT_START.containsMatchIn(code) ||
            fileName(code)?.contains(".htm", ignoreCase = true) == true ||
            DOCUMENT_START.containsMatchIn(skipFirstLine(code))

    private fun skipFirstLine(code: String): String {
        val newline = code.indexOf('\n')
        return if (newline < 0) "" else code.substring(newline + 1, minOf(code.length, newline + 200))
    }

    /** 给用户看的名字：首行声明的文件名，否则页面 `<title>`，否则「网页」。 */
    fun title(code: String): String {
        fileName(code)?.let { return it }
        val head = if (code.length > 4096) code.substring(0, 4096) else code
        TITLE.find(head)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return "网页"
    }

    /**
     * 模型在首行声明的文件名（`<!-- solar.html -->`），它是交付物跨版本的身份，
     * 所以只接受看起来像文件名的写法。
     */
    fun fileName(code: String): String? {
        var start = 0
        while (start < code.length) {
            var end = code.indexOf('\n', start)
            if (end < 0) end = code.length
            val line = code.substring(start, end).trim()
            if (line.isNotEmpty()) return if (line.length > 160) null else parseFileComment(line)
            start = end + 1
        }
        return null
    }

    private fun parseFileComment(line: String): String? {
        var span = when {
            line.startsWith("<!--") -> line.indexOf("-->").let { end -> if (end > 4) line.substring(4, end) else line.substring(4) }
            line.startsWith("/*") -> line.indexOf("*/").let { end -> if (end > 2) line.substring(2, end) else line.substring(2) }
            line.startsWith("//") -> line.substring(2)
            else -> return null
        }.trim()
        for (prefix in listOf("filename:", "file:", "name:", "文件名：", "文件名:", "文件：")) {
            if (span.startsWith(prefix, ignoreCase = true)) {
                span = span.substring(prefix.length).trim()
                break
            }
        }
        if (span.isEmpty() || span.length > 64 || span.any { it in "/\\\u0000 <>\"" }) return null
        val dot = span.lastIndexOf('.')
        if (dot <= 0 || dot >= span.length - 1) return null
        val extension = span.substring(dot + 1)
        return span.takeIf { extension.length <= 8 && extension.all { it.isLetterOrDigit() } }
    }
}
