package com.molagpt.app.core.network

import java.io.File
import java.net.URI
import java.util.UUID
import android.util.AtomicFile
import com.molagpt.app.core.model.ContextTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 原始快照的文件读写；读取失败必须暴露，不能静默丢失签名。 */
object WireHistorySource {
    fun write(raw: String, directory: File, payload: JsonObject = Json.parseToJsonElement(raw) as JsonObject): String {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建原始响应目录" }
        val file = File(directory, "${UUID.randomUUID()}.json")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val writer = output.writer(Charsets.UTF_8)
            writer.write(raw)
            writer.flush()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        return JsonObject(payload.filterKeys { it != "items" } + mapOf(
            "wire_file" to JsonPrimitive(file.toURI().toString()),
            "estimated_tokens" to JsonPrimitive(estimateTokens(payload)),
        )).toString()
    }

    private fun estimateTokens(element: JsonElement): Int = when (element) {
        is JsonObject -> {
            val mime = (element["mimeType"] ?: element["mime_type"] ?: element["media_type"])
                ?.jsonPrimitive?.contentOrNull
            if (mime?.startsWith("image/") == true && element["data"] != null) ContextTokens.IMAGE
            else element.entries.sumOf { (key, value) ->
                if (key == "thoughtSignature" || key == "signature") 0 else estimateTokens(value)
            }
        }
        is JsonArray -> element.sumOf { estimateTokens(it) }
        is JsonPrimitive -> if (element.content.startsWith("data:image/")) ContextTokens.IMAGE
            else ContextTokens.estimate(element.content)
    }

    fun readFile(uri: String): String = try {
        File(URI(uri)).readText()
    } catch (error: Exception) {
        throw IllegalStateException("无法读取 wire history 文件：$uri", error)
    }
}
