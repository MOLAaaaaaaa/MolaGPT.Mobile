package com.molagpt.app.core.model

/**
 * 一条长期记忆条目。服务端权威表 `memory_entries` 的投影（user_data_manager.php `get_memory_entries`）：
 * `{id, section, text, category, confidence(0..1), permanent, half_life_days?, ttl?, user_rating?,
 * first_ts(秒), last_ts(秒), n_recurrence, created_ts(秒), user_set, sources[]}`。
 *
 * 取代旧的 `Insight`：服务端已把「人格洞察」与「事件记忆」合并成统一的长期记忆，
 * 由夜间做梦管线（浅睡摄入 → 深睡巩固 → 每周再巩固）维护，最终投影成 MEMORY.md 注入 system prompt。
 *
 * 置信度分级 / 状态判定等纯展示派生逻辑放本模块（纯 Kotlin、与 UI 解耦），UI 只负责着色。
 */
data class MemoryEntry(
    val id: String,
    val text: String,
    /** 所属分节（原始 wire 值即中文名，UI 经 [MemorySection.fromWire] 映射）。 */
    val section: String? = null,
    /** 原始 category wire 值（可能为未知值，UI 经 [InsightCategory.fromWire] 映射）。 */
    val category: String? = null,
    val confidence: Double = 0.0,
    val permanent: Boolean = false,
    /** 半衰期（天）；null=不衰减。 */
    val halfLifeDays: Double? = null,
    /** 到期时间（unix 秒）；null=无时限。到期条目不再注入。 */
    val ttl: Long? = null,
    /** 用户显式评分；null=未评分。 */
    val userRating: MemoryRating? = null,
    /** 首次观察到的时间（unix 秒，0=未知）。 */
    val firstTs: Long = 0L,
    /** 最近一次强化时间（unix 秒，0=未知则回退 [createdTs]）。 */
    val lastTs: Long = 0L,
    /** 复现次数：这条事实在对话中被观察到多少次。 */
    val recurrence: Int = 1,
    val createdTs: Long = 0L,
    /** 用户手动添加（而非夜间管线自动学习）。 */
    val userSet: Boolean = false,
    /** 溯源锚点：这条记忆从哪些对话学到的（服务端最多返回 3 条）。 */
    val sources: List<MemorySource> = emptyList(),
) {
    val confidenceTier: ConfidenceTier get() = ConfidenceTier.of(confidence)

    /** 距上次强化的天数（需传入当前 unix 秒；用于状态判定）。 */
    fun daysSinceReinforced(nowSeconds: Long): Int {
        val ref = if (lastTs > 0L) lastTs else createdTs
        if (ref <= 0L) return 0
        return ((nowSeconds - ref) / 86_400L).toInt().coerceAtLeast(0)
    }

    /** 演化状态。 */
    fun status(nowSeconds: Long): MemoryStatus =
        MemoryStatus.of(confidence, daysSinceReinforced(nowSeconds))

    /** 时限性条目是否已过期（服务端不再注入，但夜间清扫前仍会返回）。 */
    fun isExpired(nowSeconds: Long): Boolean = ttl != null && ttl < nowSeconds
}

/** 记忆的来源锚点：从哪次对话的哪条消息学到的。 */
data class MemorySource(
    val chatId: String,
    /** 观察时间（unix 秒）。 */
    val ts: Long,
)

/**
 * MEMORY.md 投影统计（`get_memory_entries` 的 `projection` 字段）。
 * 服务端按 token 预算裁剪：超出预算的条目不进入 system prompt。
 */
data class MemoryProjection(
    /** 实际注入的条目数。 */
    val entries: Int = 0,
    /** 因预算不足被跳过的条目数。 */
    val skipped: Int = 0,
    /** 已用 token。 */
    val tokens: Int = 0,
    /** token 预算上限（服务端 DREAM_MEMORY_TOKEN_BUDGET，当前 1024）。 */
    val budget: Int = 0,
) {
    /** 预算占用比例（0..1，budget 为 0 时返回 0）。 */
    val usage: Float get() = if (budget > 0) (tokens.toFloat() / budget).coerceIn(0f, 1f) else 0f
}

/**
 * 记忆分节。服务端固定 5 个且**顺序固定**（dream_store.php `dream_section_for_category`），
 * MEMORY.md 按此顺序渲染。wire 值就是中文名——`add_memory_entry` 的白名单按原文校验。
 */
enum class MemorySection(val wire: String) {
    IDENTITY("身份与背景"),
    PREFERENCE("长期偏好与表达风格"),
    PROJECT("进行中的项目"),
    CONTEXT("近期上下文"),
    PROHIBITION("明确的禁止项");

    /** 展示名与 wire 值一致（服务端分节名本身就是中文）。 */
    val label: String get() = wire

    companion object {
        /** 未知分节回退到「近期上下文」，与服务端 dream_section_for_category 的默认值一致。 */
        fun fromWire(v: String?): MemorySection =
            entries.firstOrNull { it.wire == v } ?: CONTEXT
    }
}

/** 置信度三级。 */
enum class ConfidenceTier(val label: String) {
    CORE("核心印象"),
    KNOWN("初步了解"),
    VAGUE("模糊猜测");

    companion object {
        fun of(confidence: Double): ConfidenceTier = when {
            confidence >= 0.8 -> CORE
            confidence >= 0.4 -> KNOWN
            else -> VAGUE
        }
    }
}

/** 演化状态。先看置信度档，再看距上次强化天数。 */
enum class MemoryStatus(val label: String) {
    ACTIVE("活跃"),
    STABLE("稳定"),
    GROWING("成长"),
    FADING("衰减"),
    WEAK("微弱"),
    QUESTIONED("存疑");

    /** 是否提示「即将过期」（衰减且非永久记忆）。 */
    val nearExpiry: Boolean get() = this == FADING

    companion object {
        fun of(confidence: Double, daysSinceReinforced: Int): MemoryStatus = when {
            confidence >= 0.8 -> if (daysSinceReinforced <= 3) ACTIVE else STABLE
            confidence >= 0.4 -> if (daysSinceReinforced <= 7) GROWING else FADING
            confidence >= 0.1 -> WEAK
            else -> QUESTIONED
        }
    }
}

/**
 * 用户对单条记忆的显式评分（3 档增量语义，Tracks 2.1 反馈闭环）。
 *
 * 与旧的 5 档 `InsightRating`（绝对置信度覆盖）不同：本评分是**增量调整**，
 * 服务端在原置信度上加减 [delta] 并 clip 到 0.05..0.98。再次点击已选中项发
 * [CLEAR_WIRE] 撤销（服务端会先减去旧评分的 delta 再应用新的，故可反复切换）。
 */
enum class MemoryRating(val wire: String, val label: String, val delta: Double) {
    /** 认可：+0.10，且刷新 last_ts 抵抗衰减（= 用户证明「这条现在仍然成立」）。 */
    AGREE("agree", "认可", 0.10),
    DOUBT("doubt", "存疑", -0.15),
    REJECT("reject", "否认", -0.35);

    companion object {
        /** 撤销评分的 wire 值（非枚举项：用 null 表达「未评分」，发送时转成它）。 */
        const val CLEAR_WIRE = "clear"

        fun fromWire(v: String?): MemoryRating? =
            entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }

        /** 服务端置信度 clip 区间（user_data_manager.php handle_rate_memory_entry）。 */
        const val CONFIDENCE_MIN = 0.05
        const val CONFIDENCE_MAX = 0.98
    }
}

/** 记忆分类（9 类）。仅承载 wire 值与中文名；颜色在 UI 层映射。 */
enum class InsightCategory(val wire: String, val label: String) {
    BIOGRAPHICAL_IDENTITY("biographical_identity", "身份认知"),
    CORE_PERSONAL_VALUE("core_personal_value", "核心价值"),
    LONG_TERM_INTEREST("long_term_interest", "长期热忱"),
    HABIT_PATTERN("habit_pattern", "习惯模式"),
    WORK_STYLE("work_style", "工作风格"),
    PROJECT_FOCUS("project_focus", "当前焦点"),
    SITUATIONAL_CONTEXT("situational_context", "即时情境"),
    EPHEMERAL("ephemeral", "瞬时兴趣"),
    EXPLICIT_INSTRUCTION("explicit_instruction", "明确要求");

    companion object {
        fun fromWire(v: String?): InsightCategory? =
            entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
    }
}
