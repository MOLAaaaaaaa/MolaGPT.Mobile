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
) {
    /**
     * 胶囊上印的站点名。
     *
     * 从 url 推而不是由后端给：`molagpt_sources` 只有 id/title/url/published_date，
     * 站点自己的名字（「中央社 CNA」这种）压根不在里面。去掉 www 的主机名是诚实的近似，
     * 不会像猜出来的显示名那样猜错。
     */
    val site: String
        get() = host?.removePrefix("www.")?.takeIf { it.isNotEmpty() } ?: url

    /** favicon 服务与网页端同一个，同一个域名两端长一样。 */
    val faviconEndpoint: String?
        get() = host?.let { "https://cn.cravatar.com/favicon/api/index.php?url=$it" }

    private val host: String?
        get() = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }
}

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
    val cacheWriteTokens: Int? = null,
    /** 多轮中有请求未上报完整用量时，不能据已知部分生成整单费用。 */
    val costComplete: Boolean = true,
)
