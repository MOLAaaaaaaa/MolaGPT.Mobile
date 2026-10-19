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
        if (!matches(root, expectedWireApi, expectedProviderId, expectedModelId)) return null
        val file = root["wire_file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val payload = if (file == null) {
            root
        } else {
            val content = WireHistorySource.readFile(file)
            runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return null
        }
        if (!matches(payload, expectedWireApi, expectedProviderId, expectedModelId)) return null
        return (payload["items"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun matches(
        root: JsonObject,
        expectedWireApi: String,
        expectedProviderId: String,
        expectedModelId: String,
    ): Boolean =
        root["version"]?.jsonPrimitive?.intOrNull == VERSION &&
            root["wire_api"]?.jsonPrimitive?.contentOrNull == expectedWireApi &&
            root["provider_id"]?.jsonPrimitive?.contentOrNull == expectedProviderId &&
            root["model_id"]?.jsonPrimitive?.contentOrNull == expectedModelId
}
