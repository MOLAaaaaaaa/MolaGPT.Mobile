package com.molagpt.app.core.model

import java.util.Locale
import kotlinx.serialization.Serializable

/** USD / 1,000,000 tokens. */
@Serializable
data class ModelPricing(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    val source: String? = null,
) {
    val isManual: Boolean get() = source.equals("manual", ignoreCase = true)
    val isValid: Boolean get() = listOfNotNull(input, output, cacheRead, cacheWrite).all { it.isFinite() && it >= 0 }
}

fun calculateCostUsd(usage: Usage?, pricing: ModelPricing?): Double? {
    if (pricing == null || !pricing.isValid || usage == null || !usage.costComplete) return null
    val prompt = usage.promptTokens?.takeIf { it >= 0 } ?: return null
    val completion = usage.completionTokens?.takeIf { it >= 0 } ?: return null
    // 缓存写入尚未纳入计费；不能把不完整金额显示成整单费用。
    if ((usage.cacheWriteTokens ?: 0) > 0) return null
    val cached = (usage.cachedTokens ?: 0).coerceIn(0, prompt)
    return ((prompt - cached) * pricing.input + cached * (pricing.cacheRead ?: pricing.input) +
        completion * pricing.output) / 1_000_000.0
}

fun formatCost(usd: Double): String = when {
    usd >= 1000 -> String.format(Locale.US, "$%,.0f", usd)
    usd == 0.0 || usd >= 0.01 -> String.format(Locale.US, "$%.2f", usd)
    else -> String.format(Locale.US, "$%.4f", usd)
}

/** 自动刷新只更新价格，保留模型能力、请求参数和手动价格。 */
fun mergeModelPricing(current: ModelPricing?, incoming: ModelPricing?): ModelPricing? = when {
    current?.isManual == true -> current
    incoming == null -> current
    else -> incoming
}
