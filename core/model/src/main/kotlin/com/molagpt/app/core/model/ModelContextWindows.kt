package com.molagpt.app.core.model

/**
 * 按模型 ID 推断上下文窗口，只在没有更权威的来源时使用。优先级：
 * 1. 用户在模型详情里填写的值；
 * 2. 服务商 `/models` 声明的值；
 * 3. 本表；
 * 4. [CONSERVATIVE_DEFAULT]。
 *
 * **这些是压缩阈值的依据，不是 API 限额。** 窗口写小了只是压缩得早一些，写大了会拖到
 * 请求已经超长才压缩——所以来源不一致时一律往小取。模型迭代比这张表快，查不到是常态，
 * 落到默认值即可，用户也随时能填真实数字；不要为了填空而猜一个大窗口。
 *
 * 与桌面端 `ModelContextWindows.cs` 同一张表，改动时两端一起改。
 */
object ModelContextWindows {
    private const val SMALL = 128_000
    private const val MEDIUM = 200_000
    private const val LARGE = 400_000

    /** 1M 档统一取整。1,000,000 与 1,048,576 的差异相对压缩预留可以忽略。 */
    private const val HUGE = 1_000_000

    /** 查不到时的假设值。未知模型更可能是小窗口，往大猜会直接丢掉一次回答。 */
    const val CONSERVATIVE_DEFAULT = SMALL

    /**
     * 先匹配带版本的规则，再匹配家族兜底。版本分隔符写成 `[.\-]`：同一个模型两种写法都有
     * （`sonnet-4.6` 与 `sonnet-4-6`）。
     */
    private val RULES: List<Pair<Regex, Int>> = listOf(
        // 阿里云百炼用后缀开启长窗口，优先于所有家族规则。
        rx("""\[1m\]""") to HUGE,

        // Anthropic：Opus / Sonnet 4.6 起为 1M，其余 200K。版本组必须严格收口，
        // 否则 claude-3-5-sonnet-20241022 的日期会被当成版本号。
        rx("""(opus|sonnet)[.\-]?(4[.\-][6-9]|[5-9])(\D|$)""") to HUGE,
        rx("""claude|anthropic|opus|sonnet|haiku""") to MEDIUM,

        // Google：Gemini 3.x 文本模型 1M，图像变体窗口小得多。
        rx("""gemini.*(image|vision-preview)""") to SMALL,
        rx("""gemini[.\-]?[3-9]""") to HUGE,
        rx("""gemini""") to SMALL,

        // OpenAI：同代的 chat 与推理档窗口不同，窄的先匹配。
        rx("""gpt[.\-]?5[.\d]*[.\-]?chat""") to SMALL,
        rx("""gpt[.\-]?[5-9]|(^|/)o[1-9]""") to LARGE,
        rx("""gpt[.\-]?4\.1""") to HUGE,
        rx("""gpt[.\-]?4""") to SMALL,

        rx("""deepseek""") to HUGE,
        rx("""kimi|moonshot""") to HUGE,

        rx("""glm[.\-]?[5-9]""") to HUGE,
        rx("""glm|chatglm""") to SMALL,

        rx("""qwen[.\-]?3[.\-][6-9]|qwen[.\-]?[4-9]""") to HUGE,
        rx("""qwen|qwq""") to SMALL,

        // Llama 4 Scout 标称 10M，那是位置编码上限而非可用窗口，不收录。
        rx("""llama[.\-]?4""") to HUGE,
        rx("""llama""") to SMALL,

        rx("""mistral|magistral|devstral|codestral""") to SMALL,
    )

    /** 表里查到的窗口；查不到返回 null，调用方据此区分「已知」与「按默认值估计」。 */
    fun resolve(modelId: String?): Int? {
        if (modelId.isNullOrBlank()) return null
        return RULES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(modelId) }?.second
    }

    /** 声明值优先，其次查表，最后默认值。 */
    fun resolveOrDefault(modelId: String?, declared: Int?): Int =
        declared?.takeIf { it > 0 } ?: resolve(modelId) ?: CONSERVATIVE_DEFAULT

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
}
