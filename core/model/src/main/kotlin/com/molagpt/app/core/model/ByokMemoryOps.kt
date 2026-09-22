package com.molagpt.app.core.model

/**
 * 记忆整理模型输出的一次操作。**这是不可信数据**——每个字段在落库前都要过一遍应用侧校验，
 * 提示词里的约束只能提高命中率，不能作为安全边界。
 */
data class ByokMemoryOp(
    val type: Type,
    /** `reinforce` / `supersede` / `forget` 指向的既有条目 id。 */
    val targetId: String? = null,
    val text: String? = null,
    val section: MemorySection? = null,
    val profileKey: ByokProfileKey? = null,
    val topic: String? = null,
    val group: String? = null,
    val summary: String? = null,
    /** 用户逐字原话。缺失或对不上本轮用户消息的一律丢弃。 */
    val quote: String? = null,
    val confidence: Double = 0.0,
) {
    enum class Type(val wire: String) {
        /** 新事实。 */
        ADD("add"),

        /** 同一事实再次出现，只刷新时间与复现次数。 */
        REINFORCE("reinforce"),

        /** 用户纠正了旧事实：旧的作废、新的写入。 */
        SUPERSEDE("supersede"),

        /** 用户明确表示不再成立。 */
        FORGET("forget"),

        /** 证据不够硬，交给用户裁决。 */
        CANDIDATE("candidate");

        companion object {
            fun fromWire(v: String?): Type? =
                entries.firstOrNull { it.wire.equals(v?.trim(), ignoreCase = true) }
        }
    }

    companion object {
        /**
         * 单次整理最多接受的操作数。超出部分丢弃。
         *
         * 整理是按窗口做的（十几条消息），不是按单条消息，所以这里给的是窗口的额度；
         * 但仍要封顶——模型在长窗口上很容易把每句闲聊都当成"稳定事实"输出。
         */
        const val MAX_OPS = 8

        /** 直接晋升的置信度门槛，低于此进候选。 */
        const val PROMOTE_CONFIDENCE = 0.75
    }
}

/** 整理请求要用到的一条既有记忆。只把 id 与正文发给模型，置信度与来源不外泄。 */
data class ByokMemoryDigestEntry(
    val id: String,
    val section: MemorySection,
    val text: String,
)

data class ByokMemoryTopicAssignment(
    val title: String,
    val group: String,
    val summary: String,
    val entryIds: List<String>,
)
