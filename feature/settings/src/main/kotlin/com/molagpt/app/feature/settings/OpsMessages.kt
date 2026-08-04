package com.molagpt.app.feature.settings

import com.molagpt.app.core.network.UserAgentProvider
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** 运营下发的公告消息（服务端 android-messages.json）。 */
@Serializable
data class OpsMessage(
    val id: String,
    val title: String,
    val body: String,
    val url: String? = null,
    val urlLabel: String? = null,
)

@Serializable
private data class OpsMessagesFeed(val messages: List<OpsMessage> = emptyList())

private const val OpsMessagesUrl = "https://chatgpt.wljay.cn/v2/android-messages.json"

/** 拉取运营消息；网络/解析失败返回 null（区别于"成功但为空"的 emptyList）。 */
suspend fun fetchOpsMessages(): List<OpsMessage>? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(OpsMessagesUrl).openConnection()
        conn.setRequestProperty("User-Agent", UserAgentProvider.FIXED_UA)
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        // 去掉可能的 UTF-8 BOM，避免 JSON 解析失败。
        val text = conn.getInputStream().bufferedReader().readText().trimStart(Char(0xFEFF))
        remoteFeedJson.decodeFromString<OpsMessagesFeed>(text).messages
            .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.body.isNotBlank() }
    }.getOrNull()
}
