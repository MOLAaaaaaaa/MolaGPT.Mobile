package com.molagpt.app.core.render

import androidx.collection.LruCache
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 代码高亮配色（参考 Atom One Dark / Light，兼顾 MolaGPT 深浅主题的对比度）。
 */
@Immutable
data class CodeHighlightPalette(
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val type: Color,
    val property: Color,
    val annotation: Color,
    val operator: Color,
    val punctuation: Color,
    val tag: Color,
    val attrName: Color,
    val diffAdd: Color,
    val diffRemove: Color,
    val diffHeader: Color,
)

private val DarkCodePalette = CodeHighlightPalette(
    plain = Color(0xFFABB2BF),
    keyword = Color(0xFFC678DD),
    string = Color(0xFF98C379),
    number = Color(0xFFD19A66),
    comment = Color(0xFF7F848E),
    function = Color(0xFF61AFEF),
    type = Color(0xFFE5C07B),
    property = Color(0xFFE06C75),
    annotation = Color(0xFFE5C07B),
    operator = Color(0xFF56B6C2),
    punctuation = Color(0xFF9DA5B4),
    tag = Color(0xFFE06C75),
    attrName = Color(0xFFD19A66),
    diffAdd = Color(0xFF98C379),
    diffRemove = Color(0xFFE06C75),
    diffHeader = Color(0xFF61AFEF),
)

private val LightCodePalette = CodeHighlightPalette(
    plain = Color(0xFF383A42),
    keyword = Color(0xFFA626A4),
    string = Color(0xFF50A14F),
    number = Color(0xFF986801),
    comment = Color(0xFFA0A1A7),
    function = Color(0xFF4078F2),
    type = Color(0xFFC18401),
    property = Color(0xFFE45649),
    annotation = Color(0xFFC18401),
    operator = Color(0xFF0184BC),
    punctuation = Color(0xFF696C77),
    tag = Color(0xFFE45649),
    attrName = Color(0xFF986801),
    diffAdd = Color(0xFF2E7D32),
    diffRemove = Color(0xFFC62828),
    diffHeader = Color(0xFF1565C0),
)

/**
 * 记住并缓存代码高亮结果。
 *
 * 常规代码块（<= 12,000 字符）单遍扫描耗时在亚毫秒级且命中 LRU 缓存，首帧即呈现高亮；
 * 超大代码块切到后台线程异步着色，首帧先显示纯文本，避免主线程掉帧。
 */
@Composable
fun rememberHighlightedCode(
    code: String,
    language: String?,
): AnnotatedString {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val normalized = remember(language) { CodeSyntaxHighlighter.normalizeLanguage(language) }
    val cached = remember(code, normalized, dark) {
        if (normalized == null || code.isEmpty()) {
            AnnotatedString(code)
        } else {
            CodeSyntaxHighlighter.getCached(code, normalized, dark)
        }
    }
    if (cached != null) return cached
    if (code.length <= SYNC_HIGHLIGHT_THRESHOLD) {
        return remember(code, normalized, dark) {
            CodeSyntaxHighlighter.highlight(code, normalized!!, dark)
        }
    }
    val asyncResult by produceState(initialValue = AnnotatedString(code), code, normalized, dark) {
        value = withContext(Dispatchers.Default) {
            CodeSyntaxHighlighter.highlight(code, normalized!!, dark)
        }
    }
    return asyncResult
}

private const val SYNC_HIGHLIGHT_THRESHOLD = 12_000

/**
 * 零外部依赖的纯 Kotlin 单遍词法高亮器。
 *
 * 按语言族（C 系/脚本/Web/配置/SQL/Diff/Mermaid）进行状态机扫描，
 * 不走回溯正则，流式期间未闭合的字符串或注释也能安全高亮到末尾且无 ReDoS 风险。
 */
internal object CodeSyntaxHighlighter {
    private data class CacheKey(val language: String, val dark: Boolean, val code: String)

    private val cache = LruCache<CacheKey, AnnotatedString>(128)

    fun normalizeLanguage(raw: String?): String? {
        val token = raw?.trim()?.lowercase()
            ?.substringBefore(' ')
            ?.substringBefore('{')
            ?.trim('.')
            .orEmpty()
        if (token.isEmpty()) return null
        return LANGUAGE_ALIASES[token]
    }

    fun getCached(code: String, normalizedLanguage: String, dark: Boolean): AnnotatedString? =
        cache.get(CacheKey(normalizedLanguage, dark, code))

    fun highlight(code: String, normalizedLanguage: String, dark: Boolean): AnnotatedString {
        val key = CacheKey(normalizedLanguage, dark, code)
        cache.get(key)?.let { return it }
        val palette = if (dark) DarkCodePalette else LightCodePalette
        val result = when (normalizedLanguage) {
            "json" -> highlightJson(code, palette)
            "html", "xml" -> highlightMarkup(code, palette)
            "css" -> highlightCss(code, palette)
            "yaml" -> highlightYaml(code, palette)
            "toml", "ini" -> highlightIniToml(code, palette)
            "diff" -> highlightDiff(code, palette)
            "markdown" -> highlightMarkdown(code, palette)
            "mermaid" -> highlightMermaid(code, palette)
            else -> highlightGeneric(code, normalizedLanguage, palette)
        }
        cache.put(key, result)
        return result
    }

    // ---- 通用编程语言（Kotlin/Java/JS/TS/Python/Go/Rust/C/C++/C#/Swift/Dart/SQL/Shell/Ruby/PHP/Lua） ----

    private data class LangSpec(
        val keywords: Set<String>,
        val constants: Set<String> = COMMON_CONSTANTS,
        val builtInTypes: Set<String> = emptySet(),
        val slashComment: Boolean = true,
        val hashComment: Boolean = false,
        val dashComment: Boolean = false,
        val blockComment: Boolean = true,
        val nestedBlockComment: Boolean = false,
        val backtickString: Boolean = false,
        val tripleQuote: Boolean = false,
        val singleQuoteString: Boolean = true,
        val atAnnotation: Boolean = false,
        val hashDirective: Boolean = false,
        val caseInsensitiveKeywords: Boolean = false,
    )

    private fun highlightGeneric(code: String, lang: String, p: CodeHighlightPalette): AnnotatedString {
        val spec = LANG_SPECS[lang] ?: DEFAULT_SPEC
        val commentStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val keywordStyle = SpanStyle(color = p.keyword, fontWeight = FontWeight.Medium)
        val stringStyle = SpanStyle(color = p.string)
        val numberStyle = SpanStyle(color = p.number)
        val functionStyle = SpanStyle(color = p.function)
        val typeStyle = SpanStyle(color = p.type)
        val propertyStyle = SpanStyle(color = p.property)
        val annotationStyle = SpanStyle(color = p.annotation)
        val operatorStyle = SpanStyle(color = p.operator)
        val punctuationStyle = SpanStyle(color = p.punctuation)

        return buildAnnotatedString {
            append(code)
            val n = code.length
            var i = 0
            var prevNonSpace = '\u0000'
            var prevWordWasTypeDecl = false
            var prevWordWasFuncDecl = false

            while (i < n) {
                val c = code[i]

                // 行注释 //
                if (spec.slashComment && c == '/' && i + 1 < n && code[i + 1] == '/') {
                    val end = findLineEnd(code, i + 2)
                    addStyle(commentStyle, i, end)
                    i = end
                    continue
                }

                // 块注释 /* ... */
                if (spec.blockComment && c == '/' && i + 1 < n && code[i + 1] == '*') {
                    val end = findBlockCommentEnd(code, i + 2, spec.nestedBlockComment)
                    addStyle(commentStyle, i, end)
                    i = end
                    continue
                }

                // -- 行注释（SQL / Lua）
                if (spec.dashComment && c == '-' && i + 1 < n && code[i + 1] == '-') {
                    val end = findLineEnd(code, i + 2)
                    addStyle(commentStyle, i, end)
                    i = end
                    continue
                }

                // # 预处理指令（C/C++/C#）或 Rust 属性 #[...]
                if (spec.hashDirective && c == '#') {
                    if (lang == "rust" && i + 1 < n && (code[i + 1] == '[' || code[i + 1] == '!')) {
                        val end = findBalancedBracket(code, i + 1)
                        addStyle(annotationStyle, i, end)
                        i = end
                        continue
                    }
                    if (isLineStartOrIndent(code, i)) {
                        var j = i + 1
                        while (j < n && (code[j] == ' ' || code[j] == '\t')) j++
                        while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        addStyle(annotationStyle, i, j)
                        i = j
                        prevNonSpace = '#'
                        continue
                    }
                }

                // # 行注释（Python / Bash / Ruby / Dockerfile 等）
                if (spec.hashComment && c == '#') {
                    val end = findLineEnd(code, i + 1)
                    addStyle(commentStyle, i, end)
                    i = end
                    continue
                }

                // 三引号字符串（Kotlin / Python / Dart）
                if (spec.tripleQuote && (c == '"' || (c == '\'' && lang == "python")) &&
                    i + 2 < n && code[i + 1] == c && code[i + 2] == c
                ) {
                    val end = findTripleQuoteEnd(code, i + 3, c)
                    addStyle(stringStyle, i, end)
                    i = end
                    prevNonSpace = c
                    continue
                }

                // 字符串 "..." / '...' / `...`
                if (c == '"' || (spec.singleQuoteString && c == '\'') || (spec.backtickString && c == '`')) {
                    val end = findStringEnd(code, i + 1, c, allowMultiline = c == '`')
                    addStyle(stringStyle, i, end)
                    i = end
                    prevNonSpace = c
                    continue
                }

                // @ 注解 / 装饰器
                if (spec.atAnnotation && c == '@' && i + 1 < n && isIdentStart(code[i + 1])) {
                    var j = i + 1
                    while (j < n && (isIdentPart(code[j]) || code[j] == '.')) j++
                    addStyle(annotationStyle, i, j)
                    i = j
                    prevNonSpace = '@'
                    continue
                }

                // $ 变量（Shell / PHP）
                if ((lang == "bash" || lang == "php") && c == '$' && i + 1 < n &&
                    (isIdentStart(code[i + 1]) || code[i + 1] == '{' || code[i + 1].isDigit())
                ) {
                    val end = if (code[i + 1] == '{') {
                        val close = code.indexOf('}', i + 2)
                        if (close >= 0) close + 1 else n
                    } else {
                        var j = i + 1
                        while (j < n && isIdentPart(code[j])) j++
                        j
                    }
                    addStyle(propertyStyle, i, end)
                    i = end
                    prevNonSpace = '$'
                    continue
                }

                // 数字字面量
                if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit() && !isIdentPart(prevNonSpace))) {
                    val end = findNumberEnd(code, i)
                    addStyle(numberStyle, i, end)
                    i = end
                    prevNonSpace = '0'
                    continue
                }

                // 标识符 / 关键字 / 类型 / 函数 / 属性
                if (isIdentStart(c)) {
                    var j = i + 1
                    while (j < n && isIdentPart(code[j])) j++
                    val word = code.substring(i, j)
                    val lookup = if (spec.caseInsensitiveKeywords) word.lowercase() else word
                    val nextNonSpace = peekNextNonSpace(code, j)
                    val isPropertyAccess = prevNonSpace == '.' && !prevWordWasTypeDecl

                    when {
                        lookup in spec.keywords -> {
                            addStyle(keywordStyle, i, j)
                            prevWordWasTypeDecl = lookup in TYPE_DECL_KEYWORDS
                            prevWordWasFuncDecl = lookup in FUNC_DECL_KEYWORDS
                        }
                        lookup in spec.constants -> {
                            addStyle(numberStyle, i, j)
                            prevWordWasTypeDecl = false
                            prevWordWasFuncDecl = false
                        }
                        lookup in spec.builtInTypes -> {
                            addStyle(typeStyle, i, j)
                            prevWordWasTypeDecl = false
                            prevWordWasFuncDecl = false
                        }
                        prevWordWasFuncDecl -> {
                            addStyle(functionStyle, i, j)
                            prevWordWasFuncDecl = false
                        }
                        prevWordWasTypeDecl -> {
                            addStyle(typeStyle, i, j)
                            prevWordWasTypeDecl = false
                        }
                        nextNonSpace == '(' -> {
                            addStyle(if (word.first().isUpperCase() && !isPropertyAccess) typeStyle else functionStyle, i, j)
                        }
                        isPropertyAccess -> {
                            addStyle(propertyStyle, i, j)
                        }
                        word.length >= 2 && word.first().isUpperCase() && word.any { it.isLowerCase() } -> {
                            addStyle(typeStyle, i, j)
                        }
                        else -> {
                            prevWordWasTypeDecl = false
                            prevWordWasFuncDecl = false
                        }
                    }
                    prevNonSpace = code[j - 1]
                    i = j
                    continue
                }

                // 运算符与标点
                when {
                    c in OPERATOR_CHARS -> {
                        var j = i + 1
                        while (j < n && code[j] in OPERATOR_CHARS && j - i < 3) j++
                        addStyle(operatorStyle, i, j)
                        prevNonSpace = code[j - 1]
                        i = j
                    }
                    c in PUNCTUATION_CHARS -> {
                        addStyle(punctuationStyle, i, i + 1)
                        prevNonSpace = c
                        if (c == ';' || c == '{' || c == '}') {
                            prevWordWasTypeDecl = false
                            prevWordWasFuncDecl = false
                        }
                        i++
                    }
                    else -> {
                        if (!c.isWhitespace()) prevNonSpace = c
                        i++
                    }
                }
            }
        }
    }

    // ---- JSON / JSONC ----

    private fun highlightJson(code: String, p: CodeHighlightPalette): AnnotatedString {
        val keyStyle = SpanStyle(color = p.property)
        val stringStyle = SpanStyle(color = p.string)
        val numberStyle = SpanStyle(color = p.number)
        val commentStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val punctStyle = SpanStyle(color = p.punctuation)

        return buildAnnotatedString {
            append(code)
            val n = code.length
            var i = 0
            while (i < n) {
                val c = code[i]
                when {
                    c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                        val end = findLineEnd(code, i + 2)
                        addStyle(commentStyle, i, end)
                        i = end
                    }
                    c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                        val end = findBlockCommentEnd(code, i + 2, nested = false)
                        addStyle(commentStyle, i, end)
                        i = end
                    }
                    c == '"' -> {
                        val end = findStringEnd(code, i + 1, '"', allowMultiline = false)
                        val isKey = peekNextNonSpace(code, end) == ':'
                        addStyle(if (isKey) keyStyle else stringStyle, i, end)
                        i = end
                    }
                    c == '-' && i + 1 < n && code[i + 1].isDigit() -> {
                        val end = findNumberEnd(code, i + 1)
                        addStyle(numberStyle, i, end)
                        i = end
                    }
                    c.isDigit() -> {
                        val end = findNumberEnd(code, i)
                        addStyle(numberStyle, i, end)
                        i = end
                    }
                    c.isLetter() -> {
                        var j = i + 1
                        while (j < n && code[j].isLetter()) j++
                        val word = code.substring(i, j)
                        if (word == "true" || word == "false" || word == "null") {
                            addStyle(numberStyle, i, j)
                        }
                        i = j
                    }
                    c in "{}[],:" -> {
                        addStyle(punctStyle, i, i + 1)
                        i++
                    }
                    else -> i++
                }
            }
        }
    }

    // ---- HTML / XML / SVG ----

    private fun highlightMarkup(code: String, p: CodeHighlightPalette): AnnotatedString {
        val tagStyle = SpanStyle(color = p.tag)
        val attrStyle = SpanStyle(color = p.attrName)
        val valueStyle = SpanStyle(color = p.string)
        val commentStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val punctStyle = SpanStyle(color = p.punctuation)
        val doctypeStyle = SpanStyle(color = p.keyword)

        return buildAnnotatedString {
            append(code)
            val n = code.length
            var i = 0
            while (i < n) {
                if (code[i] != '<') {
                    i++
                    continue
                }
                // <!-- comment -->
                if (code.startsWith("<!--", i)) {
                    val close = code.indexOf("-->", i + 4)
                    val end = if (close >= 0) close + 3 else n
                    addStyle(commentStyle, i, end)
                    i = end
                    continue
                }
                // <!DOCTYPE ...> / <![CDATA[...]]>
                if (i + 1 < n && (code[i + 1] == '!' || code[i + 1] == '?')) {
                    val close = code.indexOf('>', i + 2)
                    val end = if (close >= 0) close + 1 else n
                    addStyle(doctypeStyle, i, end)
                    i = end
                    continue
                }
                // <tag ...> 或 </tag>
                var j = i + 1
                if (j < n && code[j] == '/') j++
                val nameStart = j
                while (j < n && (code[j].isLetterOrDigit() || code[j] in "-_:.")) j++
                if (j == nameStart) {
                    i++
                    continue
                }
                addStyle(punctStyle, i, nameStart)
                addStyle(tagStyle, nameStart, j)

                // 解析标签内部属性直到 >
                while (j < n && code[j] != '>') {
                    when {
                        code[j].isWhitespace() -> j++
                        code[j] == '/' && j + 1 < n && code[j + 1] == '>' -> {
                            addStyle(punctStyle, j, j + 2)
                            j += 2
                            break
                        }
                        code[j] == '"' || code[j] == '\'' -> {
                            val end = findStringEnd(code, j + 1, code[j], allowMultiline = true)
                            addStyle(valueStyle, j, end)
                            j = end
                        }
                        code[j] == '=' -> {
                            addStyle(punctStyle, j, j + 1)
                            j++
                        }
                        code[j].isLetter() || code[j] in "_:@" -> {
                            val aStart = j
                            while (j < n && (code[j].isLetterOrDigit() || code[j] in "-_:@.")) j++
                            addStyle(attrStyle, aStart, j)
                        }
                        else -> j++
                    }
                }
                if (j < n && code[j] == '>') {
                    addStyle(punctStyle, j, j + 1)
                    j++
                }
                i = j
            }
        }
    }

    // ---- CSS / SCSS / LESS ----

    private fun highlightCss(code: String, p: CodeHighlightPalette): AnnotatedString {
        val commentStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val atRuleStyle = SpanStyle(color = p.keyword, fontWeight = FontWeight.Medium)
        val selectorStyle = SpanStyle(color = p.type)
        val propStyle = SpanStyle(color = p.property)
        val stringStyle = SpanStyle(color = p.string)
        val numberStyle = SpanStyle(color = p.number)
        val funcStyle = SpanStyle(color = p.function)
        val punctStyle = SpanStyle(color = p.punctuation)

        return buildAnnotatedString {
            append(code)
            val n = code.length
            var i = 0
            var braceDepth = 0
            var inValue = false

            while (i < n) {
                val c = code[i]
                when {
                    c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                        val end = findBlockCommentEnd(code, i + 2, nested = false)
                        addStyle(commentStyle, i, end)
                        i = end
                    }
                    c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                        val end = findLineEnd(code, i + 2)
                        addStyle(commentStyle, i, end)
                        i = end
                    }
                    c == '"' || c == '\'' -> {
                        val end = findStringEnd(code, i + 1, c, allowMultiline = false)
                        addStyle(stringStyle, i, end)
                        i = end
                    }
                    c == '@' -> {
                        var j = i + 1
                        while (j < n && (code[j].isLetterOrDigit() || code[j] == '-')) j++
                        addStyle(atRuleStyle, i, j)
                        i = j
                    }
                    c == '{' -> {
                        addStyle(punctStyle, i, i + 1)
                        braceDepth++
                        inValue = false
                        i++
                    }
                    c == '}' -> {
                        addStyle(punctStyle, i, i + 1)
                        braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        inValue = false
                        i++
                    }
                    c == ':' && braceDepth > 0 -> {
                        addStyle(punctStyle, i, i + 1)
                        inValue = true
                        i++
                    }
                    c == ';' -> {
                        addStyle(punctStyle, i, i + 1)
                        inValue = false
                        i++
                    }
                    c == '#' && inValue -> {
                        var j = i + 1
                        while (j < n && code[j].isHexDigit()) j++
                        addStyle(numberStyle, i, j)
                        i = j
                    }
                    c.isDigit() -> {
                        var j = findNumberEnd(code, i)
                        while (j < n && (code[j].isLetter() || code[j] == '%')) j++
                        addStyle(numberStyle, i, j)
                        i = j
                    }
                    c.isLetter() || c == '-' || c == '.' || c == '#' -> {
                        var j = i + 1
                        while (j < n && (code[j].isLetterOrDigit() || code[j] in "-_")) j++
                        val style = when {
                            braceDepth == 0 -> selectorStyle
                            !inValue -> propStyle
                            peekNextNonSpace(code, j) == '(' -> funcStyle
                            else -> null
                        }
                        if (style != null) addStyle(style, i, j)
                        i = j
                    }
                    else -> i++
                }
            }
        }
    }

    // ---- YAML ----

    private fun highlightYaml(code: String, p: CodeHighlightPalette): AnnotatedString {
        val commentStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val keyStyle = SpanStyle(color = p.property)
        val stringStyle = SpanStyle(color = p.string)
        val numberStyle = SpanStyle(color = p.number)
        val punctStyle = SpanStyle(color = p.punctuation)

        return buildAnnotatedString {
            append(code)
            var lineStart = 0
            val n = code.length
            while (lineStart < n) {
                val lineEnd = findLineEnd(code, lineStart)
                var i = lineStart
                while (i < lineEnd && (code[i] == ' ' || code[i] == '\t')) i++
                if (i < lineEnd) {
                    if (code[i] == '#') {
                        addStyle(commentStyle, i, lineEnd)
                    } else {
                        if (code[i] == '-' && (i + 1 == lineEnd || code[i + 1] == ' ')) {
                            addStyle(punctStyle, i, i + 1)
                            i++
                            while (i < lineEnd && code[i] == ' ') i++
                        }
                        val colon = findYamlColon(code, i, lineEnd)
                        if (colon > i) {
                            addStyle(keyStyle, i, colon)
                            addStyle(punctStyle, colon, colon + 1)
                            highlightYamlValue(this, code, colon + 1, lineEnd, stringStyle, numberStyle, commentStyle)
                        } else {
                            highlightYamlValue(this, code, i, lineEnd, stringStyle, numberStyle, commentStyle)
                        }
                    }
                }
                lineStart = if (lineEnd < n) lineEnd + 1 else n
            }
        }
    }

    private fun highlightYamlValue(
        builder: AnnotatedString.Builder,
        code: String,
        start: Int,
        end: Int,
        stringStyle: SpanStyle,
        numberStyle: SpanStyle,
        commentStyle: SpanStyle,
    ) {
        var i = start
        while (i < end && code[i] == ' ') i++
        if (i >= end) return
        if (code[i] == '#') {
            builder.addStyle(commentStyle, i, end)
            return
        }
        if (code[i] == '"' || code[i] == '\'') {
            val strEnd = findStringEnd(code, i + 1, code[i], allowMultiline = false).coerceAtMost(end)
            builder.addStyle(stringStyle, i, strEnd)
            val hash = code.indexOf('#', strEnd)
            if (hash in strEnd until end) builder.addStyle(commentStyle, hash, end)
            return
        }
        val hash = findInlineHashComment(code, i, end)
        val valueEnd = if (hash >= 0) hash else end
        val raw = code.substring(i, valueEnd).trim()
        val lower = raw.lowercase()
        when {
            lower == "true" || lower == "false" || lower == "null" || lower == "~" ->
                builder.addStyle(numberStyle, i, valueEnd)
            raw.isNotEmpty() && raw.matches(YAML_NUMBER_REGEX) ->
                builder.addStyle(numberStyle, i, valueEnd)
        }
        if (hash >= 0) builder.addStyle(commentStyle, hash, end)
    }

    // ---- TOML / INI / Properties ----

    private fun highlightIniToml(code: String, p: CodeHighlightPalette): AnnotatedString {
        val commentStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val sectionStyle = SpanStyle(color = p.type, fontWeight = FontWeight.SemiBold)
        val keyStyle = SpanStyle(color = p.property)
        val stringStyle = SpanStyle(color = p.string)
        val numberStyle = SpanStyle(color = p.number)
        val punctStyle = SpanStyle(color = p.punctuation)

        return buildAnnotatedString {
            append(code)
            var lineStart = 0
            val n = code.length
            while (lineStart < n) {
                val lineEnd = findLineEnd(code, lineStart)
                var i = lineStart
                while (i < lineEnd && (code[i] == ' ' || code[i] == '\t')) i++
                if (i < lineEnd) {
                    when (code[i]) {
                        '#', ';' -> addStyle(commentStyle, i, lineEnd)
                        '[' -> addStyle(sectionStyle, i, lineEnd)
                        else -> {
                            val eq = code.indexOf('=', i).takeIf { it in i until lineEnd }
                            if (eq != null) {
                                addStyle(keyStyle, i, eq)
                                addStyle(punctStyle, eq, eq + 1)
                                highlightYamlValue(this, code, eq + 1, lineEnd, stringStyle, numberStyle, commentStyle)
                            }
                        }
                    }
                }
                lineStart = if (lineEnd < n) lineEnd + 1 else n
            }
        }
    }

    // ---- Diff / Patch ----

    private fun highlightDiff(code: String, p: CodeHighlightPalette): AnnotatedString {
        val addStyle = SpanStyle(color = p.diffAdd)
        val removeStyle = SpanStyle(color = p.diffRemove)
        val headerStyle = SpanStyle(color = p.diffHeader, fontWeight = FontWeight.Medium)
        val metaStyle = SpanStyle(color = p.comment)

        return buildAnnotatedString {
            append(code)
            var lineStart = 0
            val n = code.length
            while (lineStart < n) {
                val lineEnd = findLineEnd(code, lineStart)
                if (lineEnd > lineStart) {
                    val line = code.substring(lineStart, lineEnd)
                    val style = when {
                        line.startsWith("@@") -> headerStyle
                        line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> metaStyle
                        line.startsWith("+") -> addStyle
                        line.startsWith("-") -> removeStyle
                        else -> null
                    }
                    if (style != null) addStyle(style, lineStart, lineEnd)
                }
                lineStart = if (lineEnd < n) lineEnd + 1 else n
            }
        }
    }

    // ---- Markdown ----

    private fun highlightMarkdown(code: String, p: CodeHighlightPalette): AnnotatedString {
        val headingStyle = SpanStyle(color = p.function, fontWeight = FontWeight.SemiBold)
        val codeStyle = SpanStyle(color = p.string)
        val quoteStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val keywordStyle = SpanStyle(color = p.keyword)

        return buildAnnotatedString {
            append(code)
            var lineStart = 0
            val n = code.length
            while (lineStart < n) {
                val lineEnd = findLineEnd(code, lineStart)
                val line = code.substring(lineStart, lineEnd)
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("#") -> addStyle(headingStyle, lineStart, lineEnd)
                    trimmed.startsWith(">") -> addStyle(quoteStyle, lineStart, lineEnd)
                    trimmed.startsWith("```") || trimmed.startsWith("~~~") -> addStyle(keywordStyle, lineStart, lineEnd)
                    else -> {
                        var j = lineStart
                        while (j < lineEnd) {
                            if (code[j] == '`') {
                                val close = code.indexOf('`', j + 1)
                                if (close in (j + 1) until lineEnd) {
                                    addStyle(codeStyle, j, close + 1)
                                    j = close + 1
                                    continue
                                }
                            }
                            j++
                        }
                    }
                }
                lineStart = if (lineEnd < n) lineEnd + 1 else n
            }
        }
    }

    // ---- Mermaid ----

    private fun highlightMermaid(code: String, p: CodeHighlightPalette): AnnotatedString {
        val commentStyle = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
        val keywordStyle = SpanStyle(color = p.keyword, fontWeight = FontWeight.Medium)
        val typeStyle = SpanStyle(color = p.type, fontWeight = FontWeight.SemiBold)
        val stringStyle = SpanStyle(color = p.string)
        val arrowStyle = SpanStyle(color = p.operator)
        val numberStyle = SpanStyle(color = p.number)

        return buildAnnotatedString {
            append(code)
            val n = code.length
            var i = 0
            while (i < n) {
                val c = code[i]
                when {
                    c == '%' && i + 1 < n && code[i + 1] == '%' -> {
                        val end = findLineEnd(code, i + 2)
                        addStyle(commentStyle, i, end)
                        i = end
                    }
                    c == '"' || c == '\'' -> {
                        val end = findStringEnd(code, i + 1, c, allowMultiline = false)
                        addStyle(stringStyle, i, end)
                        i = end
                    }
                    c in "-=." && i + 1 < n && code[i + 1] in "-=>.ox)" -> {
                        var j = i + 1
                        while (j < n && code[j] in "-=>.<ox|") j++
                        if (j - i >= 2) {
                            addStyle(arrowStyle, i, j)
                            i = j
                        } else {
                            i++
                        }
                    }
                    c.isDigit() -> {
                        val end = findNumberEnd(code, i)
                        addStyle(numberStyle, i, end)
                        i = end
                    }
                    isIdentStart(c) -> {
                        var j = i + 1
                        while (j < n && (isIdentPart(code[j]) || code[j] == '-')) j++
                        val word = code.substring(i, j)
                        when {
                            word in MERMAID_DIAGRAM_TYPES -> addStyle(typeStyle, i, j)
                            word.lowercase() in MERMAID_KEYWORDS -> addStyle(keywordStyle, i, j)
                        }
                        i = j
                    }
                    else -> i++
                }
            }
        }
    }

    // ---- 扫描辅助函数 ----

    private fun findLineEnd(s: String, from: Int): Int {
        val idx = s.indexOf('\n', from)
        return if (idx < 0) s.length else idx
    }

    private fun findBlockCommentEnd(s: String, from: Int, nested: Boolean): Int {
        var i = from
        var depth = 1
        val n = s.length
        while (i + 1 < n) {
            if (nested && s[i] == '/' && s[i + 1] == '*') {
                depth++
                i += 2
            } else if (s[i] == '*' && s[i + 1] == '/') {
                depth--
                i += 2
                if (depth == 0) return i
            } else {
                i++
            }
        }
        return n
    }

    private fun findTripleQuoteEnd(s: String, from: Int, quote: Char): Int {
        var i = from
        val n = s.length
        while (i + 2 < n) {
            if (s[i] == '\\') {
                i += 2
                continue
            }
            if (s[i] == quote && s[i + 1] == quote && s[i + 2] == quote) {
                return i + 3
            }
            i++
        }
        return n
    }

    private fun findStringEnd(s: String, from: Int, quote: Char, allowMultiline: Boolean): Int {
        var i = from
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            if (!allowMultiline && c == '\n') return i
            i++
        }
        return n
    }

    private fun findNumberEnd(s: String, from: Int): Int {
        var i = from
        val n = s.length
        if (s[i] == '0' && i + 1 < n && s[i + 1] in "xXbBoO") {
            i += 2
            while (i < n && (s[i].isHexDigit() || s[i] == '_')) i++
        } else {
            while (i < n && (s[i].isDigit() || s[i] == '_')) i++
            if (i < n && s[i] == '.' && i + 1 < n && s[i + 1].isDigit()) {
                i++
                while (i < n && (s[i].isDigit() || s[i] == '_')) i++
            }
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                while (i < n && (s[i].isDigit() || s[i] == '_')) i++
            }
        }
        while (i < n && s[i] in "uUlLfFdDiI0123456789") i++
        return i
    }

    private fun findBalancedBracket(s: String, from: Int): Int {
        var i = from
        var depth = 0
        val n = s.length
        while (i < n) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth <= 0) return i + 1
                }
                '\n' -> if (depth == 0) return i
            }
            i++
        }
        return n
    }

    private fun findYamlColon(s: String, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val c = s[i]
            if (c == '"' || c == '\'' || c == '#') return -1
            if (c == ':' && (i + 1 == end || s[i + 1] == ' ' || s[i + 1] == '\t')) return i
            i++
        }
        return -1
    }

    private fun findInlineHashComment(s: String, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            if (s[i] == '#' && (i == start || s[i - 1].isWhitespace())) return i
            i++
        }
        return -1
    }

    private fun isLineStartOrIndent(s: String, idx: Int): Boolean {
        var j = idx - 1
        while (j >= 0 && s[j] != '\n') {
            if (s[j] != ' ' && s[j] != '\t') return false
            j--
        }
        return true
    }

    private fun peekNextNonSpace(s: String, from: Int): Char {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return if (i < s.length) s[i] else '\u0000'
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'
    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private val OPERATOR_CHARS = setOf('+', '-', '*', '/', '%', '=', '!', '<', '>', '&', '|', '^', '~', '?', ':')
    private val PUNCTUATION_CHARS = setOf('(', ')', '{', '}', '[', ']', ',', '.', ';')
    private val YAML_NUMBER_REGEX = Regex("""^-?(?:0x[0-9a-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)$""")

    private val COMMON_CONSTANTS = setOf("true", "false", "null", "nil", "None", "True", "False", "undefined", "NaN", "Infinity")
    private val TYPE_DECL_KEYWORDS = setOf("class", "interface", "enum", "struct", "trait", "object", "type", "protocol", "record", "typealias", "extends", "implements")
    private val FUNC_DECL_KEYWORDS = setOf("fun", "function", "def", "fn", "func")

    private val MERMAID_DIAGRAM_TYPES = setOf(
        "flowchart", "graph", "sequenceDiagram", "classDiagram", "stateDiagram", "stateDiagram-v2",
        "erDiagram", "gantt", "pie", "journey", "gitGraph", "mindmap", "timeline", "xychart-beta",
        "quadrantChart", "requirementDiagram", "C4Context", "sankey-beta", "block-beta",
    )

    private val MERMAID_KEYWORDS = setOf(
        "tb", "td", "bt", "rl", "lr", "subgraph", "end", "participant", "actor", "activate",
        "deactivate", "note", "loop", "alt", "else", "opt", "par", "and", "critical", "option",
        "break", "rect", "autonumber", "class", "classdef", "style", "linkstyle", "click",
        "direction", "state", "title", "section", "dateformat", "axisformat", "excludes",
        "as", "over", "left", "right", "of",
    )

    private val LANGUAGE_ALIASES: Map<String, String> = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "kotlin" to "kotlin",
        "java" to "java",
        "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "javascript" to "javascript",
        "ts" to "typescript", "tsx" to "typescript", "mts" to "typescript", "typescript" to "typescript",
        "py" to "python", "python" to "python", "python3" to "python",
        "c" to "c", "h" to "c",
        "cpp" to "cpp", "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hxx" to "cpp",
        "cs" to "csharp", "c#" to "csharp", "csharp" to "csharp",
        "go" to "go", "golang" to "go",
        "rs" to "rust", "rust" to "rust",
        "swift" to "swift",
        "dart" to "dart",
        "scala" to "scala",
        "rb" to "ruby", "ruby" to "ruby",
        "php" to "php",
        "lua" to "lua",
        "sh" to "bash", "bash" to "bash", "zsh" to "bash", "shell" to "bash", "fish" to "bash", "ps1" to "bash", "powershell" to "bash",
        "sql" to "sql", "mysql" to "sql", "postgresql" to "sql", "postgres" to "sql", "sqlite" to "sql", "plsql" to "sql",
        "json" to "json", "jsonc" to "json", "json5" to "json",
        "html" to "html", "htm" to "html", "vue" to "html", "svelte" to "html",
        "xml" to "xml", "svg" to "xml", "xhtml" to "xml", "plist" to "xml",
        "css" to "css", "scss" to "css", "sass" to "css", "less" to "css",
        "yaml" to "yaml", "yml" to "yaml",
        "toml" to "toml", "ini" to "ini", "properties" to "ini", "conf" to "ini", "cfg" to "ini", "env" to "ini",
        "dockerfile" to "dockerfile", "docker" to "dockerfile",
        "diff" to "diff", "patch" to "diff",
        "md" to "markdown", "markdown" to "markdown",
        "mermaid" to "mermaid",
        "gradle" to "kotlin", "groovy" to "java",
    )

    private val DEFAULT_SPEC = LangSpec(
        keywords = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return",
            "try", "catch", "finally", "throw", "new", "class", "interface", "enum", "struct",
            "fun", "func", "fn", "def", "function", "val", "var", "let", "const", "import", "export",
            "from", "as", "in", "is", "public", "private", "protected", "static", "async", "await",
        ),
    )

    private val LANG_SPECS: Map<String, LangSpec> = mapOf(
        "kotlin" to LangSpec(
            keywords = setOf(
                "package", "import", "class", "interface", "object", "fun", "val", "var", "typealias",
                "this", "super", "typeof", "if", "else", "when", "try", "catch", "finally", "for", "do",
                "while", "throw", "return", "continue", "break", "as", "is", "in", "by", "constructor",
                "init", "companion", "data", "sealed", "enum", "annotation", "inner", "open", "abstract",
                "final", "override", "private", "protected", "public", "internal", "lateinit", "suspend",
                "inline", "noinline", "crossinline", "reified", "tailrec", "operator", "infix", "external",
                "const", "expect", "actual", "value", "vararg", "out", "where", "get", "set", "it",
            ),
            builtInTypes = setOf(
                "Unit", "Nothing", "Any", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double",
                "Char", "String", "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet",
                "Array", "Pair", "Triple", "Result", "Flow", "StateFlow", "Sequence",
            ),
            nestedBlockComment = true,
            tripleQuote = true,
            atAnnotation = true,
        ),
        "java" to LangSpec(
            keywords = setOf(
                "package", "import", "class", "interface", "enum", "record", "extends", "implements",
                "public", "private", "protected", "static", "final", "abstract", "synchronized",
                "volatile", "transient", "native", "strictfp", "default", "sealed", "non-sealed", "permits",
                "if", "else", "switch", "case", "for", "while", "do", "break", "continue", "return",
                "try", "catch", "finally", "throw", "throws", "new", "this", "super", "instanceof", "var", "yield",
            ),
            builtInTypes = setOf(
                "void", "boolean", "byte", "short", "int", "long", "float", "double", "char",
                "String", "Object", "Integer", "Long", "Double", "Boolean", "List", "Map", "Set", "Optional",
            ),
            tripleQuote = true,
            atAnnotation = true,
        ),
        "javascript" to LangSpec(
            keywords = setOf(
                "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
                "do", "else", "export", "extends", "finally", "for", "function", "if", "import", "in",
                "instanceof", "let", "new", "of", "return", "static", "super", "switch", "this", "throw",
                "try", "typeof", "var", "void", "while", "with", "yield", "async", "await", "from", "as",
            ),
            builtInTypes = setOf("Array", "Object", "String", "Number", "Boolean", "Promise", "Map", "Set", "Date", "RegExp", "Error", "Symbol", "BigInt"),
            backtickString = true,
            atAnnotation = true,
        ),
        "typescript" to LangSpec(
            keywords = setOf(
                "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
                "do", "else", "export", "extends", "finally", "for", "function", "if", "import", "in",
                "instanceof", "let", "new", "of", "return", "static", "super", "switch", "this", "throw",
                "try", "typeof", "var", "void", "while", "with", "yield", "async", "await", "from", "as",
                "interface", "type", "enum", "implements", "private", "protected", "public", "readonly",
                "abstract", "declare", "namespace", "module", "keyof", "infer", "satisfies", "is", "asserts", "override",
            ),
            builtInTypes = setOf(
                "string", "number", "boolean", "any", "unknown", "never", "object", "symbol", "bigint",
                "Array", "Record", "Partial", "Required", "Readonly", "Pick", "Omit", "Promise", "Map", "Set",
            ),
            backtickString = true,
            atAnnotation = true,
        ),
        "python" to LangSpec(
            keywords = setOf(
                "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
                "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
                "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
                "with", "yield", "match", "case", "self", "cls",
            ),
            builtInTypes = setOf("int", "float", "str", "bool", "bytes", "list", "dict", "set", "tuple", "object", "Any", "Optional", "Union"),
            slashComment = false,
            hashComment = true,
            blockComment = false,
            tripleQuote = true,
            atAnnotation = true,
        ),
        "go" to LangSpec(
            keywords = setOf(
                "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
                "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
                "return", "select", "struct", "switch", "type", "var",
            ),
            builtInTypes = setOf(
                "bool", "byte", "complex64", "complex128", "error", "float32", "float64",
                "int", "int8", "int16", "int32", "int64", "rune", "string",
                "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "any", "comparable",
            ),
            backtickString = true,
        ),
        "rust" to LangSpec(
            keywords = setOf(
                "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
                "extern", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move",
                "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait",
                "type", "unsafe", "use", "where", "while",
            ),
            builtInTypes = setOf(
                "bool", "char", "str", "i8", "i16", "i32", "i64", "i128", "isize",
                "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64",
                "String", "Vec", "Option", "Result", "Box", "Rc", "Arc",
            ),
            nestedBlockComment = true,
            singleQuoteString = false,
            hashDirective = true,
        ),
        "c" to LangSpec(
            keywords = setOf(
                "auto", "break", "case", "const", "continue", "default", "do", "else", "enum", "extern",
                "for", "goto", "if", "inline", "register", "restrict", "return", "sizeof", "static",
                "struct", "switch", "typedef", "union", "volatile", "while",
            ),
            builtInTypes = setOf("void", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "bool", "size_t", "uint8_t", "uint32_t", "uint64_t", "int32_t", "int64_t"),
            hashDirective = true,
        ),
        "cpp" to LangSpec(
            keywords = setOf(
                "alignas", "alignof", "auto", "break", "case", "catch", "class", "const", "constexpr",
                "consteval", "constinit", "continue", "co_await", "co_return", "co_yield", "decltype",
                "default", "delete", "do", "else", "enum", "explicit", "export", "extern", "final", "for",
                "friend", "goto", "if", "inline", "mutable", "namespace", "new", "noexcept", "nullptr",
                "operator", "override", "private", "protected", "public", "return", "sizeof", "static",
                "static_assert", "struct", "switch", "template", "this", "throw", "try", "typedef",
                "typeid", "typename", "union", "using", "virtual", "volatile", "while", "concept", "requires",
            ),
            builtInTypes = setOf("void", "bool", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "size_t", "string", "vector", "map", "unique_ptr", "shared_ptr"),
            hashDirective = true,
        ),
        "csharp" to LangSpec(
            keywords = setOf(
                "abstract", "as", "async", "await", "base", "break", "case", "catch", "checked", "class",
                "const", "continue", "default", "delegate", "do", "else", "enum", "event", "explicit",
                "extern", "finally", "fixed", "for", "foreach", "goto", "if", "implicit", "in", "interface",
                "internal", "is", "lock", "namespace", "new", "operator", "out", "override", "params",
                "partial", "private", "protected", "public", "readonly", "record", "ref", "return", "sealed",
                "sizeof", "stackalloc", "static", "struct", "switch", "this", "throw", "try", "typeof",
                "unchecked", "unsafe", "using", "var", "virtual", "volatile", "while", "yield", "init", "required",
            ),
            builtInTypes = setOf("void", "bool", "byte", "sbyte", "char", "decimal", "double", "float", "int", "uint", "long", "ulong", "short", "ushort", "object", "string", "dynamic", "Task", "List"),
            hashDirective = true,
            tripleQuote = true,
        ),
        "swift" to LangSpec(
            keywords = setOf(
                "actor", "as", "associatedtype", "async", "await", "break", "case", "catch", "class",
                "continue", "default", "defer", "deinit", "do", "else", "enum", "extension", "fallthrough",
                "fileprivate", "for", "func", "guard", "if", "import", "in", "init", "inout", "internal",
                "is", "let", "mutating", "nonisolated", "open", "operator", "override", "private", "protocol",
                "public", "repeat", "rethrows", "return", "self", "Self", "some", "any", "static", "struct",
                "subscript", "super", "switch", "throw", "throws", "try", "typealias", "var", "where", "while",
            ),
            builtInTypes = setOf("Int", "Double", "Float", "Bool", "String", "Array", "Dictionary", "Set", "Void", "Optional", "Result"),
            nestedBlockComment = true,
            tripleQuote = true,
            atAnnotation = true,
        ),
        "dart" to LangSpec(
            keywords = setOf(
                "abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class",
                "const", "continue", "default", "deferred", "do", "else", "enum", "export", "extends",
                "extension", "external", "factory", "final", "finally", "for", "get", "hide", "if",
                "implements", "import", "in", "interface", "is", "late", "library", "mixin", "new", "on",
                "operator", "part", "required", "rethrow", "return", "sealed", "set", "show", "static",
                "super", "switch", "sync", "this", "throw", "try", "typedef", "var", "when", "while", "with", "yield",
            ),
            builtInTypes = setOf("void", "bool", "int", "double", "num", "String", "List", "Map", "Set", "Future", "Stream", "Widget", "dynamic", "Object"),
            tripleQuote = true,
            atAnnotation = true,
        ),
        "scala" to LangSpec(
            keywords = setOf(
                "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export", "extends",
                "final", "finally", "for", "given", "if", "implicit", "import", "lazy", "match", "new",
                "object", "override", "package", "private", "protected", "return", "sealed", "super",
                "then", "this", "throw", "trait", "try", "type", "using", "val", "var", "while", "with", "yield",
            ),
            builtInTypes = setOf("Unit", "Boolean", "Int", "Long", "Float", "Double", "Char", "String", "List", "Seq", "Map", "Set", "Option", "Either"),
            nestedBlockComment = true,
            tripleQuote = true,
            atAnnotation = true,
        ),
        "ruby" to LangSpec(
            keywords = setOf(
                "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else", "elsif",
                "end", "ensure", "for", "if", "in", "module", "next", "not", "or", "redo", "rescue",
                "retry", "return", "self", "super", "then", "undef", "unless", "until", "when", "while", "yield", "require",
            ),
            slashComment = false,
            hashComment = true,
            blockComment = false,
        ),
        "php" to LangSpec(
            keywords = setOf(
                "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone",
                "const", "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "enum",
                "extends", "final", "finally", "fn", "for", "foreach", "function", "global", "if",
                "implements", "include", "include_once", "instanceof", "interface", "isset", "match",
                "namespace", "new", "or", "print", "private", "protected", "public", "readonly", "require",
                "require_once", "return", "static", "switch", "throw", "trait", "try", "unset", "use", "var", "while", "yield",
            ),
            hashComment = true,
        ),
        "lua" to LangSpec(
            keywords = setOf(
                "and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if", "in",
                "local", "not", "or", "repeat", "return", "then", "until", "while",
            ),
            slashComment = false,
            dashComment = true,
            blockComment = false,
        ),
        "bash" to LangSpec(
            keywords = setOf(
                "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until",
                "do", "done", "in", "function", "time", "local", "export", "readonly", "unset", "return",
                "exit", "break", "continue", "shift", "source", "echo", "printf", "cd", "pwd", "test",
                "set", "eval", "exec", "trap", "sudo", "param", "foreach",
            ),
            slashComment = false,
            hashComment = true,
            blockComment = false,
            backtickString = true,
        ),
        "sql" to LangSpec(
            keywords = setOf(
                "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
                "create", "alter", "drop", "table", "index", "view", "database", "schema", "trigger",
                "join", "inner", "left", "right", "full", "outer", "cross", "on", "using", "as",
                "and", "or", "not", "in", "is", "between", "like", "ilike", "exists", "case", "when",
                "then", "else", "end", "group", "by", "having", "order", "asc", "desc", "limit", "offset",
                "distinct", "all", "union", "intersect", "except", "with", "recursive", "primary", "key",
                "foreign", "references", "unique", "check", "default", "constraint", "null", "begin",
                "commit", "rollback", "transaction", "returning", "conflict", "do", "nothing", "over", "partition",
            ),
            builtInTypes = setOf(
                "int", "integer", "bigint", "smallint", "tinyint", "decimal", "numeric", "float", "real",
                "double", "boolean", "bool", "char", "varchar", "text", "blob", "date", "time", "timestamp", "json", "jsonb", "uuid",
            ),
            slashComment = false,
            dashComment = true,
            blockComment = true,
            caseInsensitiveKeywords = true,
        ),
        "dockerfile" to LangSpec(
            keywords = setOf(
                "from", "run", "cmd", "label", "maintainer", "expose", "env", "add", "copy", "entrypoint",
                "volume", "user", "workdir", "arg", "onbuild", "stopsignal", "healthcheck", "shell", "as",
            ),
            slashComment = false,
            hashComment = true,
            blockComment = false,
            caseInsensitiveKeywords = true,
        ),
    )
}
