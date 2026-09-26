package com.molagpt.app.core.model

import kotlin.math.ceil

/** 上下文压缩的触发与保留参数。 */
object ContextCompactionPolicy {
    /** 上下文达到窗口的这一比例时自动压缩（1M 窗口约 872K）。 */
    const val TRIGGER_RATIO = 0.872

    /** 压缩后原样保留的最近上下文，不超过窗口的 1/4。 */
    const val KEEP_RECENT_TOKENS = 20_000

    /** 压缩进行中的提示。 */
    const val PROGRESS_LABEL = "正在压缩对话"

    fun threshold(window: Int): Int = (window * TRIGGER_RATIO).toInt()

    fun keepRecentTokens(window: Int): Int = minOf(KEEP_RECENT_TOKENS, window / 4)
}

/**
 * 不依赖分词器的 token 估算。
 *
 * 中日韩字符按 1 token/字计，其余按约 3.3 字符/token 计。chars/4 这类英文经验值会把中文
 * 低估 2–4 倍，而低估的代价是请求超长；这里宁可偏高。有服务商实测用量时，调用方用实测值校准。
 */
object ContextTokens {
    const val IMAGE = 1_200
    const val DOCUMENT = 3_000
    const val MESSAGE_OVERHEAD = 4

    fun estimate(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        return estimate(text, 0, text.length)
    }

    fun estimate(text: String, start: Int, end: Int): Int {
        var cjk = 0
        var other = 0
        for (i in start until end) {
            val ch = text[i]
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + ceil(other * 0.3).toInt()
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '⺀'..'鿿' ||
            ch in '가'..'힯' ||
            ch in '豈'..'﫿' ||
            ch in '＀'..'￯'
}

/**
 * 识别「请求超出模型上下文」的报错。
 *
 * 各服务商措辞不同，只能按文本匹配。限流类报错里也可能出现 "too many tokens"，先排除。
 * 识别错了的代价是多做一次压缩，所以中文规则只收紧匹配「上下文 + 超出」这种组合。
 */
object ContextOverflow {
    private val OVERFLOW = listOf(
        Regex("prompt is too long", RegexOption.IGNORE_CASE),
        Regex("request_too_large", RegexOption.IGNORE_CASE),
        Regex("input is too long for requested model", RegexOption.IGNORE_CASE),
        Regex("exceeds the context window", RegexOption.IGNORE_CASE),
        Regex("""exceeds (?:the )?(?:model'?s )?maximum context length""", RegexOption.IGNORE_CASE),
        Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE),
        Regex("""maximum prompt length is \d+""", RegexOption.IGNORE_CASE),
        Regex("reduce the length of the messages", RegexOption.IGNORE_CASE),
        Regex("""maximum context length is \d+ tokens""", RegexOption.IGNORE_CASE),
        Regex("""exceeds (?:the )?maximum allowed input length""", RegexOption.IGNORE_CASE),
        Regex("""is longer than the model'?s context length""", RegexOption.IGNORE_CASE),
        Regex("""exceeds the limit of \d+""", RegexOption.IGNORE_CASE),
        Regex("exceeds the available context size", RegexOption.IGNORE_CASE),
        Regex("greater than the context length", RegexOption.IGNORE_CASE),
        Regex("context window exceeds limit", RegexOption.IGNORE_CASE),
        Regex("exceeded model token limit", RegexOption.IGNORE_CASE),
        Regex("""too large for model with \d+ maximum context length""", RegexOption.IGNORE_CASE),
        Regex("""configured context size is [\d,]+""", RegexOption.IGNORE_CASE),
        Regex("model_context_window_exceeded", RegexOption.IGNORE_CASE),
        Regex("""prompt too long; exceeded (?:max )?context length""", RegexOption.IGNORE_CASE),
        Regex("range of input length should be", RegexOption.IGNORE_CASE),
        Regex("prompt exceeds max length", RegexOption.IGNORE_CASE),
        Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE),
        Regex("too many tokens", RegexOption.IGNORE_CASE),
        Regex("token limit exceeded", RegexOption.IGNORE_CASE),
        Regex("上下文.{0,8}(超出|超过|超长|过长)|超出.{0,8}上下文"),
    )

    private val NOT_OVERFLOW = listOf(
        Regex("rate limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE),
        Regex("""^(Throttling error|Service unavailable):""", RegexOption.IGNORE_CASE),
    )

    /** 报错里写明的上限（token），用来修正估错的窗口。 */
    private val LIMITS = listOf(
        Regex("""maximum context length is ([\d,]+)""", RegexOption.IGNORE_CASE),
        Regex("""context window of ([\d,]+)""", RegexOption.IGNORE_CASE),
        Regex("""[\d,]+ tokens? > ([\d,]+) maximum""", RegexOption.IGNORE_CASE),
        Regex("""maximum number of tokens allowed \(([\d,]+)\)""", RegexOption.IGNORE_CASE),
        Regex("""model'?s context length \(([\d,]+) tokens?\)""", RegexOption.IGNORE_CASE),
        Regex("""maximum context length \(([\d,]+)\)""", RegexOption.IGNORE_CASE),
        Regex("""maximum prompt length is ([\d,]+)""", RegexOption.IGNORE_CASE),
        Regex("""context size is ([\d,]+)""", RegexOption.IGNORE_CASE),
        Regex("""exceeded model token limit:? ([\d,]+)""", RegexOption.IGNORE_CASE),
    )

    fun matches(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        if (NOT_OVERFLOW.any { it.containsMatchIn(message) }) return false
        return OVERFLOW.any { it.containsMatchIn(message) }
    }

    fun declaredLimit(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        for (pattern in LIMITS) {
            val value = pattern.find(message)?.groupValues?.getOrNull(1)
                ?.replace(",", "")
                ?.toIntOrNull()
            if (value != null && value >= MIN_PLAUSIBLE_WINDOW) return value
        }
        return null
    }

    private const val MIN_PLAUSIBLE_WINDOW = 1_000
}

/** 为什么压缩。 */
enum class ContextCompactionReason {
    /** 发送前上下文达到阈值。 */
    THRESHOLD,

    /** 请求被服务商以超长拒绝。 */
    OVERFLOW,

    /** 用户手动压缩。 */
    MANUAL,
    ;

    /** 发生在一次回答生成的过程中（而不是两轮对话之间）。 */
    val duringReply: Boolean get() = this != MANUAL
}

/**
 * 一条压缩记录。[anchorMessageId] 及之前的消息在请求里由 [summary] 代替；
 * 对话列表按 [createdAt] 把它显示在压缩发生的时间点。
 */
data class ContextCompactionMark(
    val id: String,
    val anchorMessageId: String,
    val summary: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val createdAt: Long,
    val reason: ContextCompactionReason = ContextCompactionReason.THRESHOLD,
) {
    val tokensSaved: Int get() = (tokensBefore - tokensAfter).coerceAtLeast(0)
}

/**
 * 一次正在进行的压缩。[result] 非空表示检查点已经写入、记录还没从数据库读回；
 * 这段时间由它顶替那条记录，列表里同一行不会先消失再出现。
 */
data class ContextCompactionProgress(
    /** 与生成的检查点、也就是 [ContextCompactionMark.id] 相同。 */
    val id: String,
    val reason: ContextCompactionReason,
    val startedAt: Long,
    /** 分段摘要时的当前段（从 1 起）与总段数。 */
    val step: Int = 1,
    val total: Int = 1,
    val result: ContextCompactionMark? = null,
) {
    val running: Boolean get() = result == null
}
