package com.molagpt.app.feature.settings

import com.molagpt.app.core.network.UserAgentProvider
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object CommunityUrls {
    const val MobileGitHubUrl = "https://github.com/MOLAaaaaaaa/MolaGPT.Mobile"
    const val AfdianHomeUrl = "https://afdian.com/a/molagpt"
    const val SponsorsApiUrl = "https://chatgpt.wljay.cn/v2/api/sponsors.php?page=1"
}

data class MembershipProduct(val id: String, val name: String, val price: String, val durationDays: Int)

suspend fun fetchMembershipProducts(): Result<List<MembershipProduct>> = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL("https://chatgpt.wljay.cn/v2/api/auth/membership_products.php").openConnection()
        conn.setRequestProperty("User-Agent", UserAgentProvider.FIXED_UA)
        conn.setRequestProperty("X-MolaGPT-Client", UserAgentProvider.CLIENT_MARKER)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val root = remoteFeedJson.parseToJsonElement(conn.getInputStream().bufferedReader().readText().trimStart(Char(0xFEFF))) as JsonObject
        check((root["success"] as? JsonPrimitive)?.contentOrNull == "true")
        (root["products"] as JsonArray).map { item ->
            val product = item as JsonObject
            MembershipProduct(
                id = (product["id"] as JsonPrimitive).content,
                name = (product["name"] as JsonPrimitive).content,
                price = (product["price_cny"] as JsonPrimitive).content,
                durationDays = (product["duration_days"] as JsonPrimitive).content.toInt(),
            )
        }
    }
}

data class SponsorEntry(
    val name: String,
    val planName: String,
    val donateDateText: String,
)

data class SponsorsSnapshot(
    val totalCount: Int,
    val items: List<SponsorEntry>,
)

suspend fun fetchSponsors(): Result<SponsorsSnapshot> = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(CommunityUrls.SponsorsApiUrl).openConnection()
        conn.setRequestProperty("User-Agent", UserAgentProvider.FIXED_UA)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val text = conn.getInputStream().bufferedReader().readText().trimStart(Char(0xFEFF))
        val root = remoteFeedJson.parseToJsonElement(text) as? JsonObject
            ?: error("服务返回格式异常")
        val ec = (root["ec"] as? JsonPrimitive)?.intOrNull ?: 0
        val data = root["data"] as? JsonObject
        if (ec != 200 || data == null) {
            val em = (root["em"] as? JsonPrimitive)?.contentOrNull
            error(em?.takeIf { it.isNotBlank() } ?: "服务返回异常")
        }

        val list = (data["list"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val user = obj["user"] as? JsonObject
            val name = (user?.get("name") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "匿名"
            val plan = obj["current_plan"] as? JsonObject
            val planName = (plan?.get("name") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "发电支持"
            val ts = obj.readUnixSeconds("last_pay_time")
                ?: obj.readUnixSeconds("first_pay_time")
                ?: obj.readUnixSeconds("create_time")
            SponsorEntry(
                name = name,
                planName = planName,
                donateDateText = formatUnixDate(ts),
            )
        }
        val total = (data["total_count"] as? JsonPrimitive)?.intOrNull ?: list.size
        SponsorsSnapshot(totalCount = total, items = list)
    }
}

private fun JsonObject.readUnixSeconds(key: String): Long? {
    val prim = this[key] as? JsonPrimitive ?: return null
    val v = prim.longOrNull ?: prim.contentOrNull?.toLongOrNull() ?: return null
    return v.takeIf { it > 0L }
}

private fun formatUnixDate(unixSeconds: Long?): String {
    if (unixSeconds == null || unixSeconds <= 0L) return "—"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(unixSeconds * 1000L))
    }.getOrDefault("—")
}
