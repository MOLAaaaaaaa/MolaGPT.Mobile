package com.molagpt.app.core.network

import com.molagpt.app.core.model.EvidenceAction
import com.molagpt.app.core.model.Insight
import com.molagpt.app.core.model.InsightEvidence
import com.molagpt.app.core.model.InsightRating
import com.molagpt.app.core.model.InsightVersion
import com.molagpt.app.core.model.StylePreferences
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 个性化数据接口（`user_data_manager.php`）：人格洞察 (insights) 与对话风格偏好 (style_preferences)。
 *
 * **鉴权用持久登录 JWT**（同 [SyncApi]，账户级数据；游客无 JWT 时调用方应跳过）。所有写操作返回
 * 是否成功；[getInsights]/[getStylePreferences] 解析失败返回 null（与「成功但空」区分，便于 UI 容错）。
 * insight 的 `id` 是服务端 insights map 的键名，rate/update/delete 都按它定位（由 get_insights 的对象内字段带回）。
 */
class UserDataApi(private val http: MolaHttp) {

    private val url: String get() = MolaEndpoints.absolute(MolaEndpoints.USER_DATA)

    /** 拉取全部人格洞察。失败返回 null；成功（含空）返回列表。 */
    suspend fun getInsights(jwt: String): List<Insight>? {
        val resp = post(jwt, buildJsonObject { put("action", "get_insights") }) ?: return null
        if (resp["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        val arr = resp["insights"] as? JsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { index, el -> (el as? JsonObject)?.let { parseInsight(it, index) } }
    }

    /** 评分（按认同度调整置信度）。 */
    suspend fun rateInsight(jwt: String, insightId: String, rating: InsightRating): Boolean =
        ok(post(jwt, buildJsonObject {
            put("action", "rate_single_insight")
            put("insight_index", insightId)
            put("rating", rating.wire)
        }))

    /** 修正印象文本。 */
    suspend fun updateInsight(jwt: String, insightId: String, newText: String): Boolean =
        ok(post(jwt, buildJsonObject {
            put("action", "update_single_insight")
            put("insight_index", insightId)
            put("new_text", newText)
        }))

    /** 删除单条印象。 */
    suspend fun deleteInsight(jwt: String, insightId: String): Boolean =
        ok(post(jwt, buildJsonObject {
            put("action", "delete_single_insight")
            put("insight_index", insightId)
        }))

    /** 清除全部人格洞察（短期记忆）。 */
    suspend fun deleteAllInsights(jwt: String): Boolean =
        ok(post(jwt, buildJsonObject { put("action", "delete_all_insights") }))

    /** 清除全部对话记忆（长期事件记忆）。 */
    suspend fun deleteAllEventMemories(jwt: String): Boolean =
        ok(post(jwt, buildJsonObject { put("action", "delete_all_event_memories") }))

    /** 触发一次后台洞察重分析（评分/修正后调用，稍后刷新可见新结果）。 */
    suspend fun triggerEvolution(jwt: String): Boolean =
        ok(post(jwt, buildJsonObject { put("action", "trigger_user_evolution") }))

    /** 读取对话风格偏好。失败返回 null。 */
    suspend fun getStylePreferences(jwt: String): StylePreferences? {
        val resp = post(jwt, buildJsonObject { put("action", "get_style_preferences") }) ?: return null
        if (resp["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        val prefs = resp["preferences"] as? JsonObject ?: return StylePreferences()
        val styles = (prefs["styles"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val custom = prefs["custom_instruction"]?.jsonPrimitive?.contentOrNull ?: ""
        return StylePreferences(styles = styles, customInstruction = custom)
    }

    /** 保存对话风格偏好（styles 多选 + 自定义指令）。 */
    suspend fun updateStylePreferences(jwt: String, prefs: StylePreferences): Boolean =
        ok(post(jwt, buildJsonObject {
            put("action", "update_style_preferences")
            put("preferences", buildJsonObject {
                putJsonArray("styles") { prefs.styles.forEach { add(it) } }
                put("custom_instruction", prefs.customInstruction)
            })
        }))

    // —— 解析 ——

    private fun parseInsight(obj: JsonObject, index: Int): Insight {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: index.toString()
        val evidence = (obj["evidence_history"] as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            InsightEvidence(
                action = EvidenceAction.fromWire(o["action"]?.jsonPrimitive?.contentOrNull),
                ts = o["ts"].asLong(),
                evidence = o["evidence"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        val prev = (obj["previous_version"] as? JsonObject)?.let { p ->
            InsightVersion(
                text = p["insight_text"]?.jsonPrimitive?.contentOrNull ?: "",
                confidence = p["confidence"].asDouble(),
            )
        }
        return Insight(
            id = id,
            text = obj["insight_text"]?.jsonPrimitive?.contentOrNull ?: "",
            confidence = obj["confidence"].asDouble(),
            category = obj["category"]?.jsonPrimitive?.contentOrNull,
            permanent = obj["permanent"]?.jsonPrimitive?.booleanOrNull ?: false,
            createdTs = obj["created_ts"].asLong(),
            lastReinforcedTs = obj["last_reinforced_ts"].asLong(),
            sourceConversationCount = (obj["source_conversations"] as? JsonArray)?.size ?: 0,
            evidence = evidence,
            previousVersion = prev,
        )
    }

    private fun kotlinx.serialization.json.JsonElement?.asLong(): Long {
        val p = this as? JsonPrimitive ?: return 0L
        return p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun kotlinx.serialization.json.JsonElement?.asDouble(): Double {
        val p = this as? JsonPrimitive ?: return 0.0
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun ok(resp: JsonObject?): Boolean = resp?.get("success")?.jsonPrimitive?.booleanOrNull ?: false

    private suspend fun post(jwt: String, body: JsonObject): JsonObject? = runCatching {
        val resp = http.client.post(url) {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(http.json.encodeToString(JsonObject.serializer(), body))
        }
        if (!resp.status.isSuccess()) return null
        http.json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
    }.getOrNull()
}
