package com.molagpt.app.core.model

/**
 * BYOK 本地记忆的领域模型与纯函数。**只依赖 Kotlin 标准库**，
 * 让归一化、去重键、敏感信息判定这些规则在 storage 与单测里用的是同一份实现。
 *
 * 与服务端 Tracks 的关系：分节 [MemorySection]、分类 [InsightCategory]、置信度分档
 * [ConfidenceTier] 与状态 [MemoryStatus] 两边共用，因此展示语义一致；
 * 但本地记忆的事实源是设备上的 Room，两条链路不互相同步。
 */
object ByokMemoryScopes {
    /** 首版唯一作用域。表结构与投影器按 scope 写好，为将来的角色/项目私有池预留。 */
    const val GLOBAL = "global"
}

/** 条目是怎么进来的。决定排序优先级与「用户是否确认过」。 */
enum class ByokMemoryOrigin(val wire: String) {
    /** 用户在记忆页手写。永不被自动学习覆盖。 */
    MANUAL("manual"),

    /** 模型通过 save_memory 工具写入，有逐字证据。 */
    TOOL("tool"),

    /** 自动整理提取、并经用户在记忆页确认。可信度等同手动，但来源事实保留。 */
    CONFIRMED("confirmed"),

    /** 回答完成后的自动整理写入。 */
    AUTOMATIC("automatic");

    companion object {
        fun fromWire(v: String?): ByokMemoryOrigin =
            entries.firstOrNull { it.wire.equals(v, ignoreCase = true) } ?: AUTOMATIC
    }
}

/**
 * 画像字段键。`<user_profile>` 块由本地按固定顺序拼装，**模型只能打这个标签、不能改写拼装结果**。
 *
 * 语言与时区不在这里——它们直接取设备值，不需要模型参与，也就没有被污染的可能。
 */
enum class ByokProfileKey(val wire: String, val label: String) {
    PREFERRED_NAME("preferred_name", "称呼"),
    OCCUPATION("occupation", "职业"),
    LOCATION("location", "所在地");

    companion object {
        fun fromWire(v: String?): ByokProfileKey? =
            entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
    }
}

/** 一条本地长期记忆。 */
data class ByokMemoryEntry(
    val id: String,
    val scope: String = ByokMemoryScopes.GLOBAL,
    val text: String,
    val normalizedKey: String,
    val section: MemorySection = MemorySection.CONTEXT,
    val category: InsightCategory? = null,
    val profileKey: ByokProfileKey? = null,
    val confidence: Double = 0.6,
    /** null = 不衰减。 */
    val halfLifeDays: Double? = null,
    /** 硬过期时间（ms）；null = 无时限。 */
    val expiresAt: Long? = null,
    val permanent: Boolean = false,
    val origin: ByokMemoryOrigin = ByokMemoryOrigin.AUTOMATIC,
    val recurrence: Int = 1,
    val firstObservedAt: Long = 0L,
    val lastReinforcedAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 溯源锚点；列表查询默认不填充，详情才带。 */
    val evidence: List<ByokMemoryEvidence> = emptyList(),
) {
    /**
     * 读取时计算的有效置信度，取代 Android 侧的定时衰减任务。
     *
     * 手动条目与 permanent 恒为 1.0：用户亲手写下的事实不该因为没人再提起而淡出。
     */
    fun effectiveConfidence(nowMillis: Long): Double {
        if (permanent || origin == ByokMemoryOrigin.MANUAL) return 1.0
        val halfLife = halfLifeDays?.takeIf { it > 0.0 } ?: return confidence
        val ref = if (lastReinforcedAt > 0L) lastReinforcedAt else createdAt
        if (ref <= 0L) return confidence
        val days = (nowMillis - ref).coerceAtLeast(0L).toDouble() / MILLIS_PER_DAY
        return confidence * Math.pow(0.5, days / halfLife)
    }

    fun isExpired(nowMillis: Long): Boolean = expiresAt != null && expiresAt < nowMillis

    /** 是否够格进入本次注入（预算裁剪之前的门槛）。 */
    fun isInjectable(nowMillis: Long): Boolean =
        !isExpired(nowMillis) && effectiveConfidence(nowMillis) >= MIN_INJECT_CONFIDENCE

    companion object {
        const val MAX_TEXT_CHARS = 300
        const val MIN_INJECT_CONFIDENCE = 0.15
        const val MAX_CONFIDENCE = 0.98
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}

/** 条目的来源锚点。只记录 `USER` 消息——助手、工具与联网结果都不能作为证据。 */
data class ByokMemoryEvidence(
    val entryId: String,
    val sessionId: String,
    val messageId: String,
    /** 用户逐字原话，最多 [MAX_QUOTE_CHARS] 字符。 */
    val quote: String,
    val observedAt: Long,
) {
    companion object {
        const val MAX_QUOTE_CHARS = 180
    }
}

/** 尚未晋升的低置信度事实，等用户裁决。 */
data class ByokMemoryCandidate(
    val id: String,
    val scope: String = ByokMemoryScopes.GLOBAL,
    val text: String,
    val normalizedKey: String,
    val section: MemorySection = MemorySection.CONTEXT,
    val category: InsightCategory? = null,
    val profileKey: ByokProfileKey? = null,
    val confidence: Double = 0.5,
    val sourceSessionId: String,
    val sourceMessageId: String,
    /** 用户逐字原话。UI 必须与 [text] 同时展示：条目文本经过改写，只看它用户无法确认自己是否真说过。 */
    val quote: String,
    val createdAt: Long,
)

/**
 * 一次注入的投影结果。记忆页的统计行与会话内面板读同一个对象，
 * 不各自再算一遍「这次到底发出去了几条」。
 */
data class ByokMemoryProjection(
    /** 拼装好的提示块；为空表示本次不注入。 */
    val block: String = "",
    /** 实际进入 [block] 的条目。 */
    val injected: List<ByokMemoryEntry> = emptyList(),
    /** 因预算不足被跳过的条目数。 */
    val skipped: Int = 0,
    val tokens: Int = 0,
    val budget: Int = 0,
) {
    val isEmpty: Boolean get() = block.isBlank()
    val usage: Float get() = if (budget > 0) (tokens.toFloat() / budget).coerceIn(0f, 1f) else 0f
}

/**
 * 去重键：去掉空白与标点、统一大小写后的文本。
 *
 * 目的是让「我喜欢简洁的回答。」与「我喜欢简洁的回答」命中同一条，
 * 从而让重复表述走强化路径而不是堆出第二条几乎一样的记忆。
 * suppression 也按这个键匹配，所以它同时决定了「删掉的事实还能不能复活」。
 */
fun normalizeMemoryKey(text: String): String = buildString(text.length) {
    text.forEach { ch ->
        when {
            ch.isLetterOrDigit() -> append(ch.lowercaseChar())
            // CJK 标点与字母数字之外的一切（空白、ASCII 标点、全角标点）全部丢弃。
            else -> Unit
        }
    }
}

/**
 * 保守 token 估算。CJK 一字一 token，其余按 3.5 字符一 token 向上取整。
 *
 * 有意高估：预算的作用是防止记忆块挤占对话上下文，估多了只是少注入一两条，
 * 估少了会让请求体超出用户模型的窗口。不引入厂商 tokenizer——四套协议各不相同，
 * 为了一个 2000 token 的软目标背一套分词表不划算。
 */
fun estimateMemoryTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    text.forEach { ch -> if (isCjk(ch)) cjk++ else other++ }
    return cjk + Math.ceil(other / 3.5).toInt()
}

private fun isCjk(ch: Char): Boolean {
    val code = ch.code
    return code in 0x3000..0x303F || // CJK 标点
        code in 0x3400..0x4DBF || // 扩展 A
        code in 0x4E00..0x9FFF || // 基本区
        code in 0xF900..0xFAFF || // 兼容表意
        code in 0xFF00..0xFFEF || // 全角
        code in 0x3040..0x30FF || // 假名
        code in 0xAC00..0xD7AF // 谚文
}
