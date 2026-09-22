package com.molagpt.app.core.model

/**
 * 关闭推理的方式。
 *
 * 「省略参数」并不等于「关闭」：混合推理模型（一个模型既能推理也能不推理）普及后，
 * 多数服务商把缺省解释为「沿用服务端默认」，而默认往往是开启。关闭必须显式表达，
 * [UNSUPPORTED] 同样是一个合法结论——有些模型就是关不掉，UI 该照实说。
 */
enum class ReasoningOff {
    /** 不发推理参数即为关闭。 */
    OMIT,

    /** `reasoning: { "enabled": false }`——聚合网关的统一关闭开关。 */
    REASONING_DISABLED,

    /** `thinking: { "type": "disabled" }`。 */
    THINKING_DISABLED,

    /** `enable_thinking: false`。 */
    ENABLE_THINKING_FALSE,

    /** 该服务商未提供关闭手段：UI 不给「关」档，请求也不尝试关。 */
    UNSUPPORTED,
}

/**
 * 一个服务商（或服务商家族）的推理方言。
 *
 * 新增服务商 = 往 [ReasoningDialects.TABLE] 加一条数据，不改代码分支。每条回答四个问题：
 * 开怎么发（[forceKind]，null 表示按模型 ID 推断）、关怎么发（[off]）、
 * 给哪些档位（[effortLevels]）、有没有按模型公布的能力表（[capabilityTable]）。
 *
 * 响应侧的思考字段名不在此表：`reasoning_content` / `reasoning` / `thinking` 三种都由
 * StreamParser 同时接受，不需要按服务商区分。
 */
data class ProviderDialect(
    /** 命中的 host 后缀。 */
    val hosts: List<String>,
    /**
     * 强制使用的参数形状，覆盖按模型 ID 的推断。
     * 聚合网关据此把各家私有参数统一折成自己的形状；直连服务商据此锁定自有格式。
     */
    val forceKind: ThinkingParamKind? = null,
    val off: ReasoningOff,
    /** 该服务商实际接受的档位；null 表示沿用 [ThinkingKinds.effortLevelsFor]。 */
    val effortLevels: List<String>? = null,
    /** 服务商按模型公布推理能力（如 OpenRouter 的 reasoning 对象），逐模型覆盖上面的推断。 */
    val capabilityTable: Boolean = false,
) {
    /**
     * 是否使用统一 `reasoning` 对象（开 `{effort}`、关 `{enabled:false}`），
     * 而非顶层 `reasoning_effort` 字符串。带统一关闭开关即意味着服务商会做参数归一化。
     */
    val usesReasoningObject: Boolean get() = off == ReasoningOff.REASONING_DISABLED
}

/**
 * 服务商按模型公布的推理能力（OpenRouter `/models` 里每个模型的 `reasoning` 对象）。
 * 有这份数据就不必按模型名猜：档位、默认档、能否关闭都是权威的，且由服务商维护。
 */
data class ReasoningCapability(
    /** 该模型强制推理，关不掉。 */
    val mandatory: Boolean = false,
    /** 服务商接受的档位，降序。空表示未公布。 */
    val supportedEfforts: List<String> = emptyList(),
    /** 开启推理时预选的档位。 */
    val defaultEffort: String? = null,
)

/** 服务商推理方言表。 */
object ReasoningDialects {

    private val EFFORT_FULL = listOf(
        ThinkingKinds.MINIMAL,
        ThinkingKinds.LOW,
        ThinkingKinds.MEDIUM,
        ThinkingKinds.HIGH,
        ThinkingKinds.XHIGH,
        ThinkingKinds.MAX,
    )

    /**
     * 一个服务商一条。顺序即匹配优先级。
     *
     * 未命中的 host 落到 [FALLBACK]：形状按模型 ID 推断，关闭方式按形状推断——
     * 这正是识别不了的情形，所以 UI 会标低置信，并由「关了却仍在思考」的观测来纠正。
     */
    val TABLE: List<ProviderDialect> = listOf(
        // OpenRouter：统一 reasoning 对象，并按模型公布 mandatory / supported_efforts。
        ProviderDialect(
            hosts = listOf("openrouter.ai"),
            forceKind = ThinkingParamKind.OPENAI_REASONING_EFFORT,
            off = ReasoningOff.REASONING_DISABLED,
            effortLevels = EFFORT_FULL,
            capabilityTable = true,
        ),
        ProviderDialect(
            hosts = listOf("api.deepseek.com"),
            forceKind = ThinkingParamKind.DEEPSEEK_THINKING,
            off = ReasoningOff.THINKING_DISABLED,
        ),
        // Moonshot 官方：K2.x 仅开关；K3 由模型 ID 优先识别为 effort 形状。
        ProviderDialect(
            hosts = listOf("api.moonshot.cn", "api.moonshot.ai"),
            forceKind = ThinkingParamKind.KIMI,
            off = ReasoningOff.THINKING_DISABLED,
        ),
        ProviderDialect(
            hosts = listOf("dashscope.aliyuncs.com"),
            forceKind = ThinkingParamKind.QWEN_THINKING_BUDGET,
            off = ReasoningOff.ENABLE_THINKING_FALSE,
        ),
        // 阶跃星辰：reasoning_effort 仅 low/medium/high，且文档未提供任何关闭手段。
        // 实测 2026-09-22：推理关闭时仍返回思考内容，且 usage 里 reasoning_tokens 报 0。
        ProviderDialect(
            hosts = listOf("api.stepfun.com"),
            forceKind = ThinkingParamKind.OPENAI_REASONING_EFFORT,
            off = ReasoningOff.UNSUPPORTED,
            effortLevels = listOf(ThinkingKinds.LOW, ThinkingKinds.MEDIUM, ThinkingKinds.HIGH),
        ),
        // 原生协议：Anthropic 不发 thinking 即为关闭；Gemini 不发 thinkingConfig
        // （thinkingBudget=0 在 Gemini 3 Pro 上会 400，宁可让它思考——解析时会跳过 thought 分片）。
        ProviderDialect(
            hosts = listOf("api.anthropic.com"),
            off = ReasoningOff.OMIT,
        ),
        ProviderDialect(
            hosts = listOf("generativelanguage.googleapis.com"),
            off = ReasoningOff.OMIT,
        ),
    )

    /** 未知服务商：形状与关闭方式都按模型推断出的 kind 走。 */
    private val FALLBACK = ProviderDialect(hosts = emptyList(), off = ReasoningOff.OMIT)

    private fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("").lowercase()

    /** 命中的方言条目；未知 host 返回 [FALLBACK]。 */
    fun forBaseUrl(baseUrl: String): ProviderDialect {
        val host = hostOf(baseUrl)
        if (host.isEmpty()) return FALLBACK
        return TABLE.firstOrNull { entry ->
            entry.hosts.any { host == it || host.endsWith(".$it") }
        } ?: FALLBACK
    }

    /** 该服务商是否按模型公布推理能力表（OpenRouter 的 reasoning 对象）。 */
    fun hasCapabilityTable(baseUrl: String): Boolean = forBaseUrl(baseUrl).capabilityTable

    /**
     * 关闭推理该发什么。
     *
     * 服务商声明优先；未知服务商回落到「该参数形状自带的禁用开关」——
     * DeepSeek/Kimi 的 `thinking.type`、Qwen 的 `enable_thinking` 本就是开关字段，
     * 即使 host 认不出来也能安全地显式关闭。effort 形状没有通用禁用值，只能省略。
     */
    fun offFor(baseUrl: String, kind: ThinkingParamKind): ReasoningOff {
        val entry = forBaseUrl(baseUrl)
        if (entry.hosts.isNotEmpty()) return entry.off
        return when (kind) {
            ThinkingParamKind.DEEPSEEK_THINKING, ThinkingParamKind.KIMI -> ReasoningOff.THINKING_DISABLED
            ThinkingParamKind.QWEN_THINKING_BUDGET -> ReasoningOff.ENABLE_THINKING_FALSE
            else -> ReasoningOff.OMIT
        }
    }
}
