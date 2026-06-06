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
)
