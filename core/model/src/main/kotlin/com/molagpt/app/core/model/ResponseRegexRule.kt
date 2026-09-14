package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/**
 * 一条回答后处理规则：把模型写出来的正文按正则替换掉。
 *
 * 规则按列表顺序依次施加，**只在回答流结束后跑一次**，处理结果直接落库——
 * 于是复制、重试、搜索、起标题拿到的都是同一份文本，不会出现「看到的」和「存下的」不一致。
 *
 * [replacement] 沿用 Java/Kotlin 的替换串语法：`$1` / `${name}` 引用捕获组，
 * 字面的 `$` 与 `\` 需要用反斜杠转义。
 */
@Serializable
data class ResponseRegexRule(
    val id: String,
    val name: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
    /** 规则列表中的可选说明；为空时由界面显示表达式与替换内容。 */
    val description: String? = null,
)

object ResponseRegexRules {
    /** 中日韩统一表意文字与假名/谚文，判断「这是中文语境」用。与桌面端同一份字符类。 */
    const val CJK: String =
        "[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF" +
            "\\u31F0-\\u31FF\\u1100-\\u11FF\\u3130-\\u318F\\uAC00-\\uD7AF]"

    const val BUILTIN_COMMA_ID: String = "builtin-cjk-comma"
    const val BUILTIN_QUOTE_ID: String = "builtin-cjk-quote"

    /**
     * 开箱即用的两条：中文之间的半角逗号、包着中文的半角引号。
     *
     * 用编号分组而不是命名分组，是因为 `(?<name>)` 在 Android API 26 以下的 `Pattern` 里
     * 根本编译不过，而本项目 minSdk 是 23。
     */
    val DEFAULTS: List<ResponseRegexRule> = listOf(
        ResponseRegexRule(
            id = BUILTIN_COMMA_ID,
            name = "中文逗号",
            pattern = "(?<=$CJK),(?=$CJK)",
            replacement = "，",
            description = "中日韩文字间的半角逗号 → ，",
        ),
        ResponseRegexRule(
            id = BUILTIN_QUOTE_ID,
            name = "中文引号",
            pattern = "\"(?=[^\"\\r\\n]*$CJK)([^\"\\r\\n]*)\"",
            replacement = "“$1”",
            description = "含中日韩文字的半角双引号 → “ ”",
        ),
    )
}

/**
 * 对已完成回答正文的改写入口。
 *
 * 声明在领域层、实现在 `:core:markdown`（需要 markdown 保护区），由 AppContainer 注入
 * `ChatRepository`——落库那一层因此既不依赖 markdown 解析，也不需要知道用户的设置。
 */
fun interface ResponseTextProcessor {
    /** 返回改写后的正文；规则关闭、为空或全部失败时应原样返回 [markdown]。 */
    suspend fun process(markdown: String): String
}
