package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/**
 * 推理/思考参数的种类。不同模型家族的推理参数名、语义、取值完全不同，
 * 统一用 [reasoningEffort]（符号档位 low/medium/high）在 UI 层表达，由网络层按 [kind] 翻译成各家 API 参数。
 */
@Serializable
enum class ThinkingParamKind {
    /** 非推理模型，不发送任何推理参数。 */
    NONE,

    /** OpenAI o 系列 / gpt-5 / Grok / 通用 OpenAI-compat：`reasoning_effort`；OpenRouter 走 `reasoning:{effort}`。 */
    OPENAI_REASONING_EFFORT,

    /** Anthropic Claude 3.7/4.x：`thinking:{type:"adaptive", effort}`。 */
    CLAUDE_ADAPTIVE,

    /** Anthropic 预算式：`thinking:{type:"enabled", budget_tokens}`。 */
    CLAUDE_BUDGET,

    /** DeepSeek 混合推理：`thinking:{type:"enabled"/"disabled"}` + `reasoning_effort`。 */
    DEEPSEEK_THINKING,

    /** Gemini 2.5/3：原生 `generationConfig.thinkingConfig.thinkingBudget`；OpenAI-compat 走 `reasoning_effort`。 */
    GEMINI,

    /** Qwen3 / QwQ：`enable_thinking` + `thinking_budget`。 */
    QWEN_THINKING_BUDGET,

    /** Kimi K2.5+：`thinking:{type:"enabled"/"disabled"}`（仅开/关，无档位）。 */
    KIMI,
}

/**
 * 单模型的推理配置。auto-fill 自模型 ID（[ThinkingKinds.inferFromModelId]），可在模型编辑页手动覆盖。
 * - [effortLevels]：UI 可选的符号档位（low/medium/high...）；KIMI 为空（仅开/关）。
 * - [defaultEffort]：模型切换时 Composer 重置到的默认档位。
 */
@Serializable
data class ThinkingConfig(
    val kind: ThinkingParamKind = ThinkingParamKind.NONE,
    val effortLevels: List<String> = emptyList(),
    val defaultEffort: String = "medium",
)

/** 推理参数推断与档位映射。 */
object ThinkingKinds {
    /** UI 层使用的统一符号档位。 */
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    /** 各 kind 在 UI 可选的符号档位。空列表表示该 kind 仅支持开/关（如 KIMI）。 */
    fun effortLevelsFor(kind: ThinkingParamKind): List<String> = when (kind) {
        ThinkingParamKind.NONE, ThinkingParamKind.KIMI -> emptyList()
        ThinkingParamKind.OPENAI_REASONING_EFFORT,
        ThinkingParamKind.CLAUDE_ADAPTIVE,
        ThinkingParamKind.CLAUDE_BUDGET,
        ThinkingParamKind.DEEPSEEK_THINKING,
        ThinkingParamKind.GEMINI,
        ThinkingParamKind.QWEN_THINKING_BUDGET,
        -> listOf(LOW, MEDIUM, HIGH)
    }

    fun defaultEffortFor(kind: ThinkingParamKind): String = MEDIUM

    /** 默认配置（非推理模型）。 */
    val NONE_CONFIG = ThinkingConfig(ThinkingParamKind.NONE)

    /**
     * 据模型 ID 推断 kind（纯名称匹配）。
     *
     * 覆盖范围须 ⊇ [looksLikeByokReasoningModel]（network 模块）认得的推理模型集——
     * 凡那边判为推理模型的，这里都不能返回 NONE，否则该模型的推理 UI 会显示但请求体不带推理参数。
     * 没有专属参数格式的推理模型（glm/minimax/hunyuan/magistral 等）统一兜到 OPENAI_REASONING_EFFORT（标准 reasoning_effort）。
     */
    fun inferFromModelId(id: String): ThinkingParamKind {
        val lower = id.lowercase()
        if (lower.contains("-non-reasoning")) return ThinkingParamKind.NONE
        val leaf = lower.substringAfterLast('/')
        // Anthropic Claude（含 OpenRouter 的 anthropic/claude-* 路径）
        if (lower.contains("claude") || lower.contains("sonnet") ||
            lower.contains("opus") || lower.contains("haiku")
        ) return ThinkingParamKind.CLAUDE_ADAPTIVE
        // DeepSeek
        if (lower.contains("deepseek")) return ThinkingParamKind.DEEPSEEK_THINKING
        // Kimi / Moonshot
        if (lower.contains("kimi") || lower.contains("moonshot")) return ThinkingParamKind.KIMI
        // Qwen 思考版：仅 qwen3 / qwq（qwen-max/plus/turbo 等非思考版不发 enable_thinking）。
        if (lower.contains("qwen3") || lower.contains("qwq")) return ThinkingParamKind.QWEN_THINKING_BUDGET
        // Gemini
        if (lower.contains("gemini")) return ThinkingParamKind.GEMINI
        // 其余推理模型 → 标准 reasoning_effort（与 looksLikeByokReasoningModel 保持一致）。
        if (leaf.startsWith("o1") || leaf.startsWith("o3") || leaf.startsWith("o4") ||
            leaf.startsWith("gpt-5") || lower.contains("gpt-oss") ||
            lower.contains("reasoning") || lower.contains("reasoner") ||
            lower.contains("thinking") || lower.contains("think") ||
            Regex("-(?:r|R)\\d+").containsMatchIn(leaf) ||
            lower.contains("grok-4") || lower.contains("grok-3-mini") ||
            lower.contains("hunyuan-t1") || lower.contains("glm-zero-preview") ||
            lower.contains("glm-5") || Regex("glm-4\\.\\d").containsMatchIn(lower) ||
            Regex("minimax-m[23]").containsMatchIn(lower) || lower.contains("mimo-v2-flash") ||
            lower.contains("magistral") || lower.contains("seed-oss") ||
            lower.contains("gemma-4") || lower.contains("pangu-pro-moe")
        ) return ThinkingParamKind.OPENAI_REASONING_EFFORT
        return ThinkingParamKind.NONE
    }

    /** 据 baseUrl host 推断 kind（模型 ID 启发式漏掉时的兜底）。null 表示无已知兜底。 */
    fun hostInferredKind(baseUrl: String): ThinkingParamKind? {
        val host = runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("").lowercase()
        return when {
            host.endsWith("openrouter.ai") -> ThinkingParamKind.OPENAI_REASONING_EFFORT
            host.endsWith("api.deepseek.com") -> ThinkingParamKind.DEEPSEEK_THINKING
            host.endsWith("api.moonshot.cn") -> ThinkingParamKind.KIMI
            host.contains("dashscope.aliyuncs.com") -> ThinkingParamKind.QWEN_THINKING_BUDGET
            else -> null
        }
    }

    /** 是否为 OpenRouter（统一走 reasoning:{effort} 对象，而非 reasoning_effort 字符串）。 */
    fun isOpenRouter(baseUrl: String): Boolean =
        runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("").lowercase()
            .endsWith("openrouter.ai")

    /**
     * 符号档位 → token 预算（用于 budget 类 kind）。
     * - Claude budget_tokens：4.x 支持 1024~64000+，取 4096/12000/28000 三档。
     * - Gemini thinkingBudget：2.5 Flash 支持 0~24576；Pro 下限 128。取 2048/8192/24576。
     * - Qwen thinking_budget：取 4096/8192/16384。
     */
    fun budgetFor(kind: ThinkingParamKind, effort: String): Int = when (kind) {
        ThinkingParamKind.CLAUDE_BUDGET -> when (effort) { LOW -> 4096; HIGH -> 28000; else -> 12000 }
        ThinkingParamKind.GEMINI -> when (effort) { LOW -> 2048; HIGH -> 24576; else -> 8192 }
        ThinkingParamKind.QWEN_THINKING_BUDGET -> when (effort) { LOW -> 4096; HIGH -> 16384; else -> 8192 }
        else -> 0
    }

    /** 构造一个 kind 的默认配置（含档位列表与默认档位）。 */
    fun configFor(kind: ThinkingParamKind): ThinkingConfig = ThinkingConfig(
        kind = kind,
        effortLevels = effortLevelsFor(kind),
        defaultEffort = defaultEffortFor(kind),
    )
}
