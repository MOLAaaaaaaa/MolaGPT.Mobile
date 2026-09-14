package com.molagpt.app.core.markdown

import com.molagpt.app.core.model.RegexGuard
import com.molagpt.app.core.model.ResponseRegexRule

/** 一条规则没跑成：正文保持原样，把原因交给调用方去提示。 */
data class PostProcessFailure(val ruleName: String, val reason: String)

/** [changes] 是实际发生的替换处数，供设置页的预览说清楚「改了几处」。 */
data class PostProcessResult(
    val text: String,
    val failures: List<PostProcessFailure>,
    val changes: Int = 0,
)

/**
 * 回答正文的正则后处理。
 *
 * 两条纪律：
 * 1. **只碰正文**。代码、公式、隐藏标记的区间由 [MarkdownParser.protectedRanges] 给出，
 *    落在里面的匹配一律原样放回——为中文标点写的规则不该伸进 Python 代码块。
 * 2. **失败不吞正文**。正则写错、引用了不存在的分组、或者写出了会指数回溯的表达式，
 *    都只让**那一条规则**失效并把原因报出来，其余规则和原文照常。
 *
 * 关于超时：Android 上中止不了一次已经开始的正则匹配（原因见 [RegexGuard]），所以这里的
 * 上限只保证**调用方不被拖住**：到期就放弃这条规则、正文保持原样。世界书的关键词匹配
 * 走的是同一道闸。
 */
object ResponsePostProcessor {

    /** 单条规则的执行上限。超过就放弃这条规则，正文保持进来时的样子。 */
    const val RULE_TIMEOUT_MS = 250L

    /**
     * 依次施加 [rules]，返回改写后的正文与失败清单。
     *
     * 纯计算、不挂起：CPU 工作交给调用方放到合适的调度器上（见 AppContainer 的注入点）。
     */
    fun apply(markdown: String, rules: List<ResponseRegexRule>): PostProcessResult {
        val active = rules.filter { it.enabled && it.pattern.isNotEmpty() }
        if (markdown.isEmpty() || active.isEmpty()) return PostProcessResult(markdown, emptyList())

        var text = markdown
        var changes = 0
        var failures: MutableList<PostProcessFailure>? = null
        for (rule in active) {
            val regex = runCatching { Regex(rule.pattern) }.getOrNull()
            if (regex == null) {
                failures = (failures ?: mutableListOf()).apply {
                    add(PostProcessFailure(rule.name, "正则表达式无效"))
                }
                continue
            }
            try {
                val counter = IntArray(1)
                text = applyOne(text, regex, rule.replacement, counter)
                changes += counter[0]
            } catch (timeout: RegexGuard.Expired) {
                failures = (failures ?: mutableListOf()).apply {
                    add(PostProcessFailure(rule.name, "执行超过 $RULE_TIMEOUT_MS 毫秒"))
                }
            } catch (invalid: IllegalArgumentException) {
                failures = (failures ?: mutableListOf()).apply {
                    add(PostProcessFailure(rule.name, invalid.message ?: "替换失败"))
                }
            } catch (indexed: IndexOutOfBoundsException) {
                failures = (failures ?: mutableListOf()).apply {
                    add(PostProcessFailure(rule.name, "替换串引用了不存在的分组"))
                }
            } catch (deep: StackOverflowError) {
                // 用户写出的正则不该让整个 App 倒下；这条作废，正文原样留着。
                failures = (failures ?: mutableListOf()).apply {
                    add(PostProcessFailure(rule.name, "正则嵌套过深"))
                }
            }
        }
        return PostProcessResult(text, failures.orEmpty(), changes)
    }

    /** 保存前的即时校验：返回 null 表示这条规则能用。 */
    fun validate(rule: ResponseRegexRule): String? {
        if (rule.pattern.isEmpty()) return "请填写正则表达式"
        runCatching { Regex(rule.pattern) }.onFailure { return "正则表达式无效" }
        return validateReplacement(rule.replacement)
    }

    private fun validateReplacement(template: String): String? {
        var i = 0
        while (i < template.length) {
            when {
                template[i] == '\\' -> {
                    if (i + 1 >= template.length) return "替换串末尾的 \\ 没有转义任何字符"
                    i += 2
                }
                template[i] == '$' && template.getOrNull(i + 1) == '{' -> {
                    val close = template.indexOf('}', i + 2)
                    if (close < 0) return "替换串里的 \${ 没有闭合"
                    i = close + 1
                }
                template[i] == '$' -> {
                    if (template.getOrNull(i + 1)?.isDigit() != true) {
                        return "替换串里的 $ 若表示字面量，需要写成 \\$"
                    }
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    private fun applyOne(
        text: String,
        regex: Regex,
        replacement: String,
        counter: IntArray,
    ): String {
        val protectedRanges = MarkdownParser.protectedRanges(text)
        // 整条规则（含替换串展开）都放进闸内：Android 上中止不了匹配，能保证的是调用方不被拖住。
        return RegexGuard.runBounded(RULE_TIMEOUT_MS) {
            // 匹配按下标递增到来，保护区也是有序不重叠的，所以一个只前进的游标就够了。
            var cursor = 0
            regex.replace(text) { match ->
                val start = match.range.first
                val end = if (match.range.isEmpty()) start else match.range.last
                while (cursor < protectedRanges.size && protectedRanges[cursor].last < start) cursor++
                val inProtected = cursor < protectedRanges.size && protectedRanges[cursor].first <= end
                if (inProtected) {
                    match.value
                } else {
                    val replaced = expand(replacement, match)
                    if (replaced != match.value) counter[0]++
                    replaced
                }
            }
        }
    }

    /**
     * 展开替换串。语义对齐 Java 的 `Matcher.appendReplacement`：`\` 转义下一个字符，
     * `$1` / `${name}` 引用分组。自己展开是为了让「落在保护区的匹配原样放回」这件事可行——
     * 用 [Regex.replace] 的字符串重载就没有逐个匹配拿主意的机会。
     */
    private fun expand(template: String, match: MatchResult): String {
        if (template.isEmpty()) return ""
        if (!template.contains('$') && !template.contains('\\')) return template
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '\\' && i + 1 < template.length -> {
                    out.append(template[i + 1])
                    i += 2
                }
                c == '$' && template.getOrNull(i + 1) == '{' -> {
                    val close = template.indexOf('}', i + 2)
                    require(close >= 0) { "替换串里的 \${ 没有闭合" }
                    val name = template.substring(i + 2, close)
                    val named = match.groups as? MatchNamedGroupCollection
                        ?: throw IllegalArgumentException("当前系统不支持命名分组")
                    out.append(named[name]?.value.orEmpty())
                    i = close + 1
                }
                c == '$' && template.getOrNull(i + 1)?.isDigit() == true -> {
                    // 与 Java 一致：尽量多吃数字，但不越过实际分组数（`$12` 在只有 1 组时是「组 1 + 字面 2」）。
                    var j = i + 1
                    var group = 0
                    while (j < template.length && template[j].isDigit()) {
                        val next = group * 10 + (template[j] - '0')
                        if (next > match.groupValues.lastIndex) break
                        group = next
                        j++
                    }
                    require(j > i + 1) { "替换串引用了不存在的分组" }
                    out.append(match.groupValues[group])
                    i = j
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

}
