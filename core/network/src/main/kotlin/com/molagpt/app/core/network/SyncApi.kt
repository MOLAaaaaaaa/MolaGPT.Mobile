package com.molagpt.app.core.network

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * sync.php 低层调用（Ktor）。
 *
 * **鉴权用持久登录 JWT**（`credentialStore.jwt`），不是聊天用的 60s 短 token——
 * 同步可能耗时（分块/大列表），短 token 会中途过期。游客无 JWT 时调用方应直接跳过。
 */
class SyncApi(private val http: MolaHttp) {

    private val url: String get() = MolaEndpoints.absolute(MolaEndpoints.SYNC)

    /** full_sync：推 dirty_conversations + 取服务端 full_metadata_list。失败返回 null。 */
    suspend fun fullSync(jwt: String, body: JsonObject): JsonObject? = post(jwt, body)

    /** 取单个会话详情（messages）。 */
    suspend fun fetchConversation(jwt: String, conversationId: String): JsonObject? = post(
        jwt,
        buildJsonObject {
            put("action", "fetch_conversation")
            put("conversation_id", conversationId)
        },
    )

    /** 删除云端会话。 */
    suspend fun deleteConversations(jwt: String, conversationIds: List<String>): Boolean {
        if (conversationIds.isEmpty()) return true
        val resp = post(
            jwt,
            buildJsonObject {
                put("action", "delete")
                putJsonArray("conversation_ids") { conversationIds.forEach { add(it) } }
            },
        )
        return resp?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
    }

    /**
     * 更新用户设置（sync.php `update_setting`）。后端白名单仅允许
     * `cloud_sync_enabled` / `personalized_memory_enabled`，value 必须是布尔。
     */
    suspend fun updateSetting(jwt: String, setting: String, value: Boolean): Boolean {
        val resp = post(
            jwt,
            buildJsonObject {
                put("action", "update_setting")
                put("setting", setting)
                put("value", value)
            },
        )
        return resp?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
    }

    private suspend fun post(jwt: String, body: JsonObject): JsonObject? = runCatching {
        val resp = http.client.post(url) {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(http.json.encodeToString(JsonObject.serializer(), body))
        }
        if (!resp.status.isSuccess()) return null
        http.json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }.getOrNull()
}
