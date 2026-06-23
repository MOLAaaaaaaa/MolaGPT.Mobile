package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ByokMcpServer(
    val id: String,
    val name: String,
    val endpoint: String,
    val token: String? = null,
    /** 自定义请求头名（默认 "Authorization"）；token 以 "Bearer <value>" 写入该头。 */
    val headerName: String = "Authorization",
    val enabled: Boolean = true,
    /** 被用户禁用的 MCP tool name 集合（按 tool name 存；默认空=全部启用）。
     *  与设置页「MCP 服务器详情」逐工具开关同步；运行时 listMcpTools/mcp_call 据此过滤。 */
    val disabledTools: List<String> = emptyList(),
)

fun byokMcpServerTokenKey(id: String): String = "byok.mcp_server.token:$id"

fun ByokMcpServer.withoutToken(): ByokMcpServer = copy(token = null)

/** 联网搜索服务商。DuckDuckGo 免 key（HTML 抓取），Tavily/Exa 走 JSON API 需 key。 */
enum class WebSearchProvider(val id: String, val displayName: String, val needsKey: Boolean) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo（免费）", false),
    TAVILY("tavily", "Tavily", true),
    EXA("exa", "Exa", true);

    companion object {
        fun fromId(id: String?): WebSearchProvider =
            entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

/** 搜索 API key 在 CredentialStore 的存储键（按 provider 分别加密存储，仿 MCP token）。 */
fun webSearchApiKeyKey(provider: String): String = "byok.web_search.api_key:$provider"

/** 运行时联网搜索配置（解密后），由 AppContainer 注入给 ByokChatService。 */
data class WebSearchOptions(
    val provider: WebSearchProvider = WebSearchProvider.DUCKDUCKGO,
    val apiKey: String? = null,
    val maxResults: Int = 6,
)
