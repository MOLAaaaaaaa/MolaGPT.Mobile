package com.molagpt.app.feature.agentcontrol.desktop

import com.molagpt.app.core.common.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 每个远程对话最后一次看到的样子，存在本机，供电脑离线时只读查看。
 * 只是缓存：电脑在线时一律以电脑发来的快照为准，整体替换。
 */
class DesktopSnapshotCache(private val root: () -> File?) {
    private val json = Json { ignoreUnknownKeys = true }

    class Snapshot(val conversation: JsonObject, val messages: JsonArray, val savedAtMs: Long)

    fun load(machineId: String, conversationId: String): Snapshot? = runCatching {
        val file = file(machineId, conversationId)?.takeIf { it.isFile } ?: return null
        val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
        Snapshot(
            conversation = root["conversation"] as? JsonObject ?: JsonObject(emptyMap()),
            messages = root["messages"] as? JsonArray ?: JsonArray(emptyList()),
            savedAtMs = (root["savedAtMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0,
        )
    }.getOrNull()

    fun save(machineId: String, conversationId: String, conversation: JsonObject, messages: JsonArray) {
        val file = file(machineId, conversationId) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("conversation", conversation)
                        put("messages", messages)
                        put("savedAtMs", System.currentTimeMillis())
                    },
                ),
            )
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Logger.w("DesktopRemote", "cache save failed: ${it.message}") }
    }

    fun forget(machineId: String) {
        runCatching { root()?.let { File(it, safe(machineId)).deleteRecursively() } }
    }

    private fun file(machineId: String, conversationId: String): File? =
        root()?.let { File(File(it, safe(machineId)), safe(conversationId) + ".json") }

    private fun safe(name: String): String =
        if (name.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) name
        else MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
}
