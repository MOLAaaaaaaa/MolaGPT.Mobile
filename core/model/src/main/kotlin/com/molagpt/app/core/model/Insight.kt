package com.molagpt.app.core.model

/**
 * 个性化「人格洞察」画像。服务端 insights schema：
 * 每条 `{id, insight_text, confidence(0..1), category, permanent?, created_ts(秒), last_reinforced_ts(秒)?,
 * source_conversations[]?, evidence_history[]?, previous_version?}`。
 *
 * [id] 是服务端 insights map 的键名（rate/update/delete 都按它定位），由 `get_insights` 的对象内字段带回。
 * 置信度分级 / 状态 / 分类等纯展示派生逻辑放本模块（纯 Kotlin、可单测、与 UI 解耦），UI 只负责着色。
 */
data class Insight(
    val id: String,
    val text: String,
    val confidence: Double,
    /** 原始 category wire 值（可能为未知值，UI 经 [InsightCategory.fromWire] 映射）。 */
    val category: String? = null,
    val permanent: Boolean = false,
    /** 创建时间（unix 秒，0=未知）。 */
    val createdTs: Long = 0L,
    /** 上次强化时间（unix 秒，0=未知则回退 [createdTs]）。 */
    val lastReinforcedTs: Long = 0L,
    /** 形成该洞察所依据的对话条数。 */
    val sourceConversationCount: Int = 0,
    val evidence: List<InsightEvidence> = emptyList(),
    val previousVersion: InsightVersion? = null,
) {
    val confidenceTier: ConfidenceTier get() = ConfidenceTier.of(confidence)

    /** 距上次强化的天数（需传入当前 unix 秒；用于状态判定）。 */
    fun daysSinceReinforced(nowSeconds: Long): Int {
        val ref = if (lastReinforcedTs > 0L) lastReinforcedTs else createdTs
        if (ref <= 0L) return 0
        return ((nowSeconds - ref) / 86_400L).toInt().coerceAtLeast(0)
    }

    /** 演化状态。 */
    fun status(nowSeconds: Long): InsightStatus =
        InsightStatus.of(confidence, daysSinceReinforced(nowSeconds))
}

/** 单条洞察的演化记录（创建/强化/驳斥/修正）。 */
data class InsightEvidence(
    val action: EvidenceAction,
    /** 发生时间（unix 秒）。 */
    val ts: Long,
    val evidence: String? = null,
)

/** 修改前的旧版本（用户手动修正后保留）。 */
data class InsightVersion(
    val text: String,
    val confidence: Double,
)

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
enum class InsightStatus(val label: String) {
    ACTIVE("活跃"),
    STABLE("稳定"),
    GROWING("成长"),
    FADING("衰减"),
    WEAK("微弱"),
    QUESTIONED("存疑");

    /** 是否提示「即将过期」（衰减且非永久记忆）。 */
    val nearExpiry: Boolean get() = this == FADING

    companion object {
        fun of(confidence: Double, daysSinceReinforced: Int): InsightStatus = when {
            confidence >= 0.8 -> if (daysSinceReinforced <= 3) ACTIVE else STABLE
            confidence >= 0.4 -> if (daysSinceReinforced <= 7) GROWING else FADING
            confidence >= 0.1 -> WEAK
            else -> QUESTIONED
        }
    }
}

/** 演化动作（evidence_history.action）。 */
enum class EvidenceAction(val wire: String, val label: String) {
    NEW("NEW", "创建"),
    REINFORCE("REINFORCE", "强化"),
    CONTRADICT("CONTRADICT", "驳斥"),
    MODIFY("MODIFY", "修正"),
    UNKNOWN("", "更新");

    companion object {
        fun fromWire(v: String?): EvidenceAction =
            entries.firstOrNull { it.wire.equals(v, ignoreCase = true) } ?: UNKNOWN
    }
}

/** 洞察分类（8 类）。仅承载 wire 值与中文名；颜色在 UI 层映射。 */
enum class InsightCategory(val wire: String, val label: String) {
    BIOGRAPHICAL_IDENTITY("biographical_identity", "身份认知"),
    CORE_PERSONAL_VALUE("core_personal_value", "核心价值"),
    LONG_TERM_INTEREST("long_term_interest", "长期热忱"),
    HABIT_PATTERN("habit_pattern", "习惯模式"),
    WORK_STYLE("work_style", "工作风格"),
    PROJECT_FOCUS("project_focus", "当前焦点"),
    SITUATIONAL_CONTEXT("situational_context", "即时情境"),
    EPHEMERAL("ephemeral", "瞬时兴趣");

    companion object {
        fun fromWire(v: String?): InsightCategory? =
            entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
    }
}

/** 用户对单条洞察的认同度评分（5 档）。 */
enum class InsightRating(val wire: String, val label: String, val positive: Boolean) {
    STRONG_AGREE("strong_agree", "非常符合", true),
    AGREE("agree", "很符合", true),
    SOMEWHAT("somewhat", "比较符合", true),
    DISAGREE("disagree", "不太符合", false),
    STRONG_DISAGREE("strong_disagree", "完全不符", false),
}
