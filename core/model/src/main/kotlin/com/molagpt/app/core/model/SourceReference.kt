package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/** 引用来源（联网搜索 / Deep Research 的 citation）。 */
@Serializable
data class SourceReference(
    val title: String,
    val url: String,
    val snippet: String? = null,
    val index: Int? = null,
    val faviconUrl: String? = null,
)

/** Token 用量（来自 SSE 的 usage 字段）。 */
@Serializable
data class Usage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** 内部思考 token 数（usage.completion_tokens_details.reasoning_tokens）；用于运行时自校正。 */
    val reasoningTokens: Int? = null,
    /** prompt 中命中缓存的 token 数（各家键名不同，见各 provider 的解析）；含在 [promptTokens] 内。 */
    val cachedTokens: Int? = null,
)
