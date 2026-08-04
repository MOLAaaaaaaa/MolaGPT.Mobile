package com.molagpt.app.core.model

/**
 * 一条**待确认候选**：夜间浅睡摄入相位抽出、但证据不足以自动晋升为长期记忆的事实，
 * 交给用户裁决（user_data_manager.php `get_candidates`，服务端最多返回 6 条）。
 *
 * [text] 是 LLM 规范化后的第三人称事实（确认后即以此入库），[quote] 是用户当时的**逐字原话**。
 * UI 必须同时展示两者——条目文本经过改写，只看它用户无法确认自己是否真说过这句。
 *
 * 处置两条路径：
 * - 记住 → `add_memory_entry(candidate_id=...)` 后 `dismiss_candidate(suppress=false)`；
 * - 忽略 → `dismiss_candidate(suppress=true)`，写 tombstone，夜间管线不再重复建议。
 */
data class MemoryCandidate(
    val id: String,
    /** 规范化后的第三人称事实（入库文本）。 */
    val text: String,
    /** 用户逐字原话；null=服务端未留存（2.2 之前的旧候选）。 */
    val quote: String? = null,
    /** 来源对话 id。 */
    val sourceChatId: String? = null,
    /** 观察时间（unix 秒）。 */
    val observedTs: Long = 0L,
    /** 建议归入的分节（服务端按 category 推导）。 */
    val section: MemorySection = MemorySection.CONTEXT,
)
