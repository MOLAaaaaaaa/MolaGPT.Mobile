package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/**
 * 一次「重试版本」快照。
 * 重生成时把旧答案存为一版、新答案追加为新版,用户可在版本间切换。
 * 整个列表序列化进助手消息的 metadata（不改 Room 表结构）。
 */
@Serializable
data class RetryAttempt(
    val fragments: List<MessageFragment> = emptyList(),
    val rawText: String? = null,
    val model: String? = null,
    val modelDisplayName: String? = null,
    /** [MessageStatus] 的 name；读回用 valueOf 容错(默认 COMPLETE)。 */
    val status: String = "COMPLETE",
)
