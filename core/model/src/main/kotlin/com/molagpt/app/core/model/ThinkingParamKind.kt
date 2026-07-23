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

    /** OpenAI o 系列 / gpt-5 / Grok / Kimi K3 / 通用 OpenAI-compat：`reasoning_effort`；OpenRouter 走 `reasoning:{effort}`。 */
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

    /** Kimi K2.5：`thinking:{type:"enabled"/"disabled"}`（仅开/关，无档位）。K3 走 [OPENAI_REASONING_EFFORT]。 */
    KIMI,
}

/**
 * 用户可理解的推理行为类别（设置页「推理方式」用，替代厂商名）。
 * 网络层仍按 [ThinkingParamKind] 翻译参数；类别用于展示与手动覆写。
 */
@Serializable
enum class ThinkingBehavior {
    /** 强度档位（low/medium/high…）。 */
    EFFORT,
    /** 思考 token 预算（budget_tokens / thinking_budget / thinkingBudget）。 */
    BUDGET,
    /** 仅开/关，无强度档位。 */
    TOGGLE,
    /** 不支持推理。 */
    NONE,
}

/** 自动侦测来源（设置页自动卡置信度展示）。 */
@Serializable
enum class ThinkingDetectSource {
    /** 用户手动指定。 */
    OVERRIDE,
    /** 服务商能力表（如 OpenRouter supported_parameters）——权威。 */
    CAPABILITY,
    /** 已知服务商 host。 */
    HOST,
    /** 仅按模型名推测——低置信。 */
    HEURISTIC,
}

/**
 * 单模型的推理配置。auto-fill 自模型 ID（[ThinkingKinds.inferFromModelId]），可在模型编辑页手动覆盖。
 * - [effortLevels]：UI 可选的符号档位；非空时优先于方言默认（可追加 max/ultra 等自定义档）。
 * - [defaultEffort]：模型切换时 Composer 重置到的默认档位。
 * - [alwaysOn]：始终开启推理（如 Kimi K3），UI 无「关」停靠点。
 * - [detectSource]：自动侦测来源；null = 旧数据或未标注。
 * - [manualOverride]：用户在设置页手动指定了行为类别，跳过自动识别。
 */
@Serializable
data class ThinkingConfig(
    val kind: ThinkingParamKind = ThinkingParamKind.NONE,
    val effortLevels: List<String> = emptyList(),
    val defaultEffort: String = "medium",
    val alwaysOn: Boolean = false,
    val detectSource: ThinkingDetectSource? = null,
    val manualOverride: Boolean = false,
)

/** 推理参数推断与档位映射。 */
object ThinkingKinds {
    /** UI 层使用的统一符号档位。 */
    const val MINIMAL = "minimal"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val XHIGH = "xhigh"
    const val MAX = "max"

    /**
     * 各 kind 在 UI 可选的符号档位，按家族方言区分（与 Desktop ComposerViewModel 对齐）。
     * 空列表表示该 kind 仅支持开/关（如 KIMI）。off 由「推理」开关表达，故各档不含 none。
     */
    fun effortLevelsFor(kind: ThinkingParamKind): List<String> = when (kind) {
        ThinkingParamKind.NONE, ThinkingParamKind.KIMI -> emptyList()
        ThinkingParamKind.OPENAI_REASONING_EFFORT -> listOf(MINIMAL, LOW, MEDIUM, HIGH, XHIGH)
        ThinkingParamKind.CLAUDE_ADAPTIVE -> listOf(LOW, MEDIUM, HIGH, XHIGH, MAX)
        ThinkingParamKind.DEEPSEEK_THINKING -> listOf(HIGH, MAX)
        ThinkingParamKind.GEMINI -> listOf(MINIMAL, LOW, MEDIUM, HIGH)
        ThinkingParamKind.CLAUDE_BUDGET,
        ThinkingParamKind.QWEN_THINKING_BUDGET,
        -> listOf(LOW, MEDIUM, HIGH)
    }

    /** 模型切换时 Composer 重置到的默认档位（须落在该 kind 的 [effortLevelsFor] 内）。 */
    fun defaultEffortFor(kind: ThinkingParamKind): String = when (kind) {
        ThinkingParamKind.DEEPSEEK_THINKING -> HIGH
        else -> MEDIUM
    }

    /** 默认配置（非推理模型）。 */
    val NONE_CONFIG = ThinkingConfig(ThinkingParamKind.NONE)

    /** Kimi K3：顶层 reasoning_effort，档位 low/high/max，默认 max，始终开启。 */
    val KIMI_K3_CONFIG = ThinkingConfig(
        kind = ThinkingParamKind.OPENAI_REASONING_EFFORT,
        effortLevels = listOf(LOW, HIGH, MAX),
        defaultEffort = MAX,
        alwaysOn = true,
        detectSource = ThinkingDetectSource.HEURISTIC,
    )

    /** 模型 ID 是否为 Kimi K3（含 moonshot/kimi-k3 路径）。 */
    fun isKimiK3(id: String): Boolean {
        val lower = id.lowercase()
        val leaf = lower.substringAfterLast('/')
        return Regex("kimi-k3(?:[.\\-]|$)").containsMatchIn(leaf) ||
            Regex("kimi-k[4-9](?:[.\\-]|$)").containsMatchIn(leaf)
    }

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
        // Kimi K3+：顶层 reasoning_effort（常开）；K2.x 仍为仅开关。
        if (isKimiK3(id)) return ThinkingParamKind.OPENAI_REASONING_EFFORT
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
            // Moonshot 官方：默认 KIMI 开关；K3 由模型 ID 优先识别。
            host.endsWith("api.moonshot.cn") || host.endsWith("api.moonshot.ai") -> ThinkingParamKind.KIMI
            host.contains("dashscope.aliyuncs.com") -> ThinkingParamKind.QWEN_THINKING_BUDGET
            else -> null
        }
    }

    /** 是否为 OpenRouter（统一走 reasoning:{effort} 对象，而非 reasoning_effort 字符串）。 */
    fun isOpenRouter(baseUrl: String): Boolean =
        runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("").lowercase()
            .endsWith("openrouter.ai")

    /** 是否为已知会把各家推理参数归一化的聚合网关（当前仅 OpenRouter）。 */
    fun isAggregatingGateway(baseUrl: String): Boolean = isOpenRouter(baseUrl)

    /**
     * 符号档位 → token 预算（用于 budget 类 kind）。
     * - Claude budget_tokens：4.x 支持 1024~64000+，取 4096/12000/28000 三档。
     * - Gemini thinkingBudget：2.5 Flash 支持 0~24576；Pro 下限 128。取 2048/8192/24576。
     * - Qwen thinking_budget：取 4096/8192/16384。
     */
    fun budgetFor(kind: ThinkingParamKind, effort: String): Int = when (kind) {
        ThinkingParamKind.CLAUDE_BUDGET -> when (effort) { LOW -> 4096; HIGH -> 28000; else -> 12000 }
        ThinkingParamKind.GEMINI -> when (effort) { MINIMAL -> 1024; LOW -> 2048; HIGH -> 24576; else -> 8192 }
        ThinkingParamKind.QWEN_THINKING_BUDGET -> when (effort) { LOW -> 4096; HIGH -> 16384; else -> 8192 }
        else -> 0
    }

    /** 构造一个 kind 的默认配置（含档位列表与默认档位）。 */
    fun configFor(
        kind: ThinkingParamKind,
        alwaysOn: Boolean = false,
        detectSource: ThinkingDetectSource? = null,
    ): ThinkingConfig {
        if (alwaysOn && kind == ThinkingParamKind.OPENAI_REASONING_EFFORT) {
            // K3 模板：覆盖档位与默认。
            return ThinkingConfig(
                kind = kind,
                effortLevels = listOf(LOW, HIGH, MAX),
                defaultEffort = MAX,
                alwaysOn = true,
                detectSource = detectSource,
            )
        }
        return ThinkingConfig(
            kind = kind,
            effortLevels = effortLevelsFor(kind),
            defaultEffort = defaultEffortFor(kind),
            alwaysOn = alwaysOn,
            detectSource = detectSource,
        )
    }

    /** 据模型 ID + baseUrl 构造完整自动配置（含来源标注）。聚合网关强制 EFFORT。 */
    fun autoConfigFor(modelId: String, baseUrl: String, supportedParams: Set<String>? = null): ThinkingConfig? {
        // 能力表显式声明：OpenRouter supported_parameters 含 reasoning / include_reasoning。
        if (supportedParams != null) {
            val capable = supportedParams.any {
                it.equals("reasoning", ignoreCase = true) ||
                    it.equals("include_reasoning", ignoreCase = true) ||
                    it.equals("reasoning_effort", ignoreCase = true)
            }
            if (!capable) return null
            // 聚合网关：统一 effort；K3 仍标 alwaysOn。
            if (isAggregatingGateway(baseUrl)) {
                return if (isKimiK3(modelId)) {
                    KIMI_K3_CONFIG.copy(detectSource = ThinkingDetectSource.CAPABILITY)
                } else {
                    configFor(
                        ThinkingParamKind.OPENAI_REASONING_EFFORT,
                        detectSource = ThinkingDetectSource.CAPABILITY,
                    )
                }
            }
        }

        if (isKimiK3(modelId)) {
            val source = if (hostInferredKind(baseUrl) != null) ThinkingDetectSource.HOST
            else ThinkingDetectSource.HEURISTIC
            return KIMI_K3_CONFIG.copy(detectSource = source)
        }

        if (isAggregatingGateway(baseUrl)) {
            // 无能力表时：名字像推理才给 OR effort。来源标「服务商(HOST)」而非「能力表(CAPABILITY)」，
            // 避免在没读到 supported_parameters 时对用户谎称是「权威识别」。
            val native = inferFromModelId(modelId)
            if (native == ThinkingParamKind.NONE) return null
            return configFor(
                ThinkingParamKind.OPENAI_REASONING_EFFORT,
                detectSource = ThinkingDetectSource.HOST, // OR host 本身即权威兜底
            )
        }

        val byId = inferFromModelId(modelId)
        if (byId != ThinkingParamKind.NONE) {
            val source = ThinkingDetectSource.HEURISTIC
            // 若 host 也能确认同一家族，升级为 HOST。
            val byHost = hostInferredKind(baseUrl)
            val upgraded = if (byHost != null && behaviorOf(byHost) == behaviorOf(byId)) {
                ThinkingDetectSource.HOST
            } else {
                source
            }
            return configFor(byId, detectSource = upgraded)
        }

        val byHost = hostInferredKind(baseUrl) ?: return null
        return configFor(byHost, detectSource = ThinkingDetectSource.HOST)
    }

    /**
     * Composer / 设置页实际使用的档位列表：
     * 模型上持久化的 [ThinkingConfig.effortLevels] 非空时直接用（支持覆写/追加自定义档）；
     * 否则回落到方言默认。
     */
    fun resolveEffortLevels(config: ThinkingConfig): List<String> {
        val custom = normalizeEffortLevels(config.effortLevels)
        return custom.ifEmpty { effortLevelsFor(config.kind) }
    }

    /** 清洗档位列表：去空白、小写、去重，保持用户输入顺序。 */
    fun normalizeEffortLevels(levels: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in levels) {
            val v = raw.trim().lowercase()
            if (v.isNotEmpty()) seen.add(v)
        }
        return seen.toList()
    }

    /** 保存时校正 defaultEffort，确保落在最终档位列表内。 */
    fun resolveDefaultEffort(config: ThinkingConfig): String {
        val levels = resolveEffortLevels(config)
        if (levels.isEmpty()) return defaultEffortFor(config.kind)
        val preferred = config.defaultEffort.trim().lowercase()
        if (preferred.isNotEmpty() && preferred in levels) return preferred
        val dialect = defaultEffortFor(config.kind)
        return if (dialect in levels) dialect else levels.first()
    }

    // ---- 行为类别 / 展示辅助 ----

    /** kind → 用户可理解的行为类别。 */
    fun behaviorOf(kind: ThinkingParamKind): ThinkingBehavior = when (kind) {
        ThinkingParamKind.NONE -> ThinkingBehavior.NONE
        ThinkingParamKind.KIMI -> ThinkingBehavior.TOGGLE
        ThinkingParamKind.CLAUDE_BUDGET,
        ThinkingParamKind.GEMINI,
        ThinkingParamKind.QWEN_THINKING_BUDGET,
        -> ThinkingBehavior.BUDGET
        else -> ThinkingBehavior.EFFORT
    }

    /** 行为类别中文名。 */
    fun behaviorLabel(behavior: ThinkingBehavior): String = when (behavior) {
        ThinkingBehavior.EFFORT -> "强度档位"
        ThinkingBehavior.BUDGET -> "思考预算"
        ThinkingBehavior.TOGGLE -> "仅开关"
        ThinkingBehavior.NONE -> "不支持推理"
    }

    /**
     * 把用户选的行为类别落到一个具体 kind（用于手动指定）。
     * 尽量保留 [preferred] 若已属该类别；否则取该类别的代表 kind。
     */
    fun kindForBehavior(behavior: ThinkingBehavior, preferred: ThinkingParamKind? = null): ThinkingParamKind {
        if (preferred != null && behaviorOf(preferred) == behavior) return preferred
        // 切换行为类别时尽量留在同一家族，避免把 Claude/Gemini 一律折成 Qwen 私有参数。
        return when (behavior) {
            ThinkingBehavior.EFFORT -> when (preferred) {
                ThinkingParamKind.CLAUDE_ADAPTIVE, ThinkingParamKind.CLAUDE_BUDGET ->
                    ThinkingParamKind.CLAUDE_ADAPTIVE // thinking.effort
                ThinkingParamKind.DEEPSEEK_THINKING -> ThinkingParamKind.DEEPSEEK_THINKING
                else -> ThinkingParamKind.OPENAI_REASONING_EFFORT
            }
            ThinkingBehavior.BUDGET -> when (preferred) {
                ThinkingParamKind.CLAUDE_ADAPTIVE, ThinkingParamKind.CLAUDE_BUDGET ->
                    ThinkingParamKind.CLAUDE_BUDGET // budget_tokens
                ThinkingParamKind.GEMINI -> ThinkingParamKind.GEMINI // thinkingBudget
                else -> ThinkingParamKind.QWEN_THINKING_BUDGET
            }
            ThinkingBehavior.TOGGLE -> ThinkingParamKind.KIMI
            ThinkingBehavior.NONE -> ThinkingParamKind.NONE
        }
    }

    /**
     * 实际上写请求时应使用的 kind：聚合网关下预算类强制折算为 OPENAI_REASONING_EFFORT，
     * 避免把 thinking_budget 等家族私有参数发到 OpenRouter。
     */
    fun wireKind(kind: ThinkingParamKind, baseUrl: String): ThinkingParamKind {
        if (kind == ThinkingParamKind.NONE) return kind
        // 聚合网关（OpenRouter 等）统一走 reasoning:{effort}，任何家族私有参数
        // （thinking_budget / enable_thinking / thinking:{type} / budget_tokens 等）都不下发，
        // 由网关自行按目标模型翻译。这样也修好了旧 Claude 配置在 OpenAI 兼容路径下不发任何推理参数的问题。
        if (isAggregatingGateway(baseUrl)) return ThinkingParamKind.OPENAI_REASONING_EFFORT
        return kind
    }

    /** UI 是否应按预算语义展示（映射 token）。聚合网关折算后为 false。 */
    fun showAsBudget(config: ThinkingConfig, baseUrl: String): Boolean =
        isBudgetKind(wireKind(config.kind, baseUrl))

    /** 侦测置信度：高（能力表/服务商/手动）或低（仅模型名）。 */
    fun isHighConfidence(source: ThinkingDetectSource?): Boolean = when (source) {
        ThinkingDetectSource.CAPABILITY, ThinkingDetectSource.HOST, ThinkingDetectSource.OVERRIDE -> true
        ThinkingDetectSource.HEURISTIC, null -> false
    }

    /** 档位符号 → 中文标签；未知自定义档（如用户添加的 turbo）原样返回。 */
    fun effortLabel(effort: String): String = when (effort.lowercase()) {
        MINIMAL -> "极低"
        LOW -> "低"
        MEDIUM -> "中"
        HIGH -> "高"
        XHIGH -> "超高"
        MAX -> "最高"
        "ultra" -> "Ultra"
        "auto" -> "自动"
        "off", "none" -> "关"
        else -> effort
    }

    /** 预算类 kind：档位经 [budgetFor] 映射成固定 token 预算上写请求（UI 据此展示映射值、禁用自定义档）。 */
    fun isBudgetKind(kind: ThinkingParamKind): Boolean = when (kind) {
        ThinkingParamKind.CLAUDE_BUDGET,
        ThinkingParamKind.GEMINI,
        ThinkingParamKind.QWEN_THINKING_BUDGET,
        -> true
        else -> false
    }

    /** 该 kind 实际上写请求的参数名（推理弹层「技术细节」提示行）。 */
    fun wireParamName(kind: ThinkingParamKind): String = when (kind) {
        ThinkingParamKind.OPENAI_REASONING_EFFORT, ThinkingParamKind.DEEPSEEK_THINKING -> "reasoning_effort"
        ThinkingParamKind.CLAUDE_ADAPTIVE -> "thinking.effort"
        ThinkingParamKind.CLAUDE_BUDGET -> "budget_tokens"
        ThinkingParamKind.GEMINI -> "thinkingBudget"
        ThinkingParamKind.QWEN_THINKING_BUDGET -> "thinking_budget"
        ThinkingParamKind.KIMI -> "thinking"
        ThinkingParamKind.NONE -> ""
    }

    /** token 预算短格式：8192 → "8K"、24576 → "24K"、512 → "512"（刻度行标注用）。 */
    fun formatBudgetShort(tokens: Int): String {
        if (tokens < 1024) return tokens.toString()
        val k = tokens / 1024f
        val rounded = (k * 10).toInt() / 10f
        return if (rounded % 1f == 0f) "${rounded.toInt()}K" else "${rounded}K"
    }
}
