package com.molagpt.app.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Provider/model-scoped native protocol messages represented by one visible assistant message. */
internal object NativeWireHistory {
    const val ANTHROPIC_MESSAGES = "anthropic_messages"
    const val GEMINI_GENERATE_CONTENT = "gemini_generate_content"
    private const val VERSION = 1

    fun encode(
        wireApi: String,
        providerId: String,
        modelId: String,
        items: List<JsonObject>,
    ): String = buildJsonObject {
        put("version", VERSION)
        put("wire_api", wireApi)
        put("provider_id", providerId)
        put("model_id", modelId)
        put("items", JsonArray(items))
    }.toString()

    fun decode(
        json: Json,
        raw: String?,
        expectedWireApi: String,
        expectedProviderId: String,
        expectedModelId: String,
    ): List<JsonObject>? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return null
        if (root["wire_api"]?.jsonPrimitive?.contentOrNull != expectedWireApi) return null
        if (root["provider_id"]?.jsonPrimitive?.contentOrNull != expectedProviderId) return null
        if (root["model_id"]?.jsonPrimitive?.contentOrNull != expectedModelId) return null
        return (root["items"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.takeIf { it.isNotEmpty() }
    }
}
