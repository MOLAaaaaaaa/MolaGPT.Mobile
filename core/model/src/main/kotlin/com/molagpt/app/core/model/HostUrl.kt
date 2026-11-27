package com.molagpt.app.core.model

import java.net.URI
import java.net.URISyntaxException

/** 安全的 HTTP/HTTPS URL 解析与严格域名边界匹配，避免单条异常配置抛异常拖垮列表。 */
object HostUrl {
    fun parseHttpHost(rawUrl: String): String? = try {
        URI(rawUrl.trim()).takeIf {
            it.scheme.equals("https", ignoreCase = true) || it.scheme.equals("http", ignoreCase = true)
        }?.host?.lowercase()?.takeIf { it.isNotEmpty() }
    } catch (_: URISyntaxException) {
        null
    }

    fun matchesDomain(host: String?, domain: String): Boolean {
        val h = host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val d = domain.lowercase().trimStart('.')
        return h == d || h.endsWith(".$d")
    }

    fun isOfficialOpenAiResponses(baseUrl: String, chatPath: String): Boolean = try {
        val url = URI(baseUrl.trimEnd('/') + "/" + chatPath.trimStart('/'))
        url.scheme.equals("https", ignoreCase = true) &&
            url.host.equals("api.openai.com", ignoreCase = true) &&
            url.userInfo == null &&
            (url.port == -1 || url.port == 443) &&
            (url.rawPath == "/v1/responses" || url.rawPath == "/v1/responses/")
    } catch (_: URISyntaxException) {
        false
    }
}
