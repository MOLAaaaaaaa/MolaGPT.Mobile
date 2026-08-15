package com.molagpt.app.core.network

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageFragment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 [ChatMessage] 转成 Anthropic / Gemini 原生内容格式。
 * 当 [replaceImagesWithText] 为 true 时，图片附件替换为 `[图片#N]` 文本占位符，
 * 与 [OpenAiMessageContentBuilder] 保持同一全局序号规则（都取自 [AttachmentParts]），
 * 供 `view_image` 工具索引。
 */
internal object ByokMessageContentBuilder {
    fun anthropicContent(
        message: ChatMessage,
        replaceImagesWithText: Boolean = false,
        imageOrdinal: AtomicInteger = AtomicInteger(0),
    ): JsonArray = buildJsonArray {
        val text = message.sendableText()
        if (text.isNotBlank()) {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        AttachmentParts.orderedImages(message).forEach { attachment ->
            val n = imageOrdinal.incrementAndGet()
            if (replaceImagesWithText || attachment.unavailable) {
                addJsonObject {
                    put("type", "text")
                    put("text", AttachmentParts.imageLabel(n, attachment))
                }
                return@forEach
            }
            val data = parseDataUrl(attachment.remoteUrl!!)
            addJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    if (data != null) {
                        put("type", "base64")
                        put("media_type", data.mimeType.ifBlank { attachment.mimeType })
                        put("data", data.base64)
                    } else {
                        put("type", "url")
                        put("url", attachment.remoteUrl!!)
                    }
                })
            }
        }
        AttachmentParts.unavailableDocuments(message).forEach { attachment ->
            addJsonObject {
                put("type", "text")
                put("text", AttachmentParts.unavailableLabel(attachment))
            }
        }
        AttachmentParts.binaryDocuments(message).forEach { attachment ->
            val data = parseDataUrl(attachment.remoteUrl!!)
            addJsonObject {
                put("type", "document")
                put("source", buildJsonObject {
                    if (data != null) {
                        put("type", "base64")
                        put("media_type", data.mimeType.ifBlank { attachment.mimeType })
                        put("data", data.base64)
                    } else {
                        put("type", "url")
                        put("url", attachment.remoteUrl!!)
                    }
                })
            }
        }
    }

    fun geminiParts(
        message: ChatMessage,
        replaceImagesWithText: Boolean = false,
        imageOrdinal: AtomicInteger = AtomicInteger(0),
    ): JsonArray = buildJsonArray {
        val text = message.sendableText()
        if (text.isNotBlank()) addJsonObject { put("text", text) }
        AttachmentParts.orderedImages(message).forEach { attachment ->
            val n = imageOrdinal.incrementAndGet()
            if (replaceImagesWithText || attachment.unavailable) {
                addJsonObject { put("text", AttachmentParts.imageLabel(n, attachment)) }
            } else {
                addJsonObject { putMediaPart(attachment) }
            }
        }
        AttachmentParts.unavailableDocuments(message).forEach { attachment ->
            addJsonObject { put("text", AttachmentParts.unavailableLabel(attachment)) }
        }
        AttachmentParts.binaryDocuments(message).forEach { attachment ->
            addJsonObject { putMediaPart(attachment) }
        }
    }

    private fun JsonObjectBuilder.putMediaPart(attachment: Attachment) {
        val data = parseDataUrl(attachment.remoteUrl!!)
        if (data != null) {
            put("inlineData", buildJsonObject {
                put("mimeType", data.mimeType.ifBlank { attachment.mimeType })
                put("data", data.base64)
            })
        } else {
            put("fileData", buildJsonObject {
                put("mimeType", attachment.mimeType)
                put("fileUri", attachment.remoteUrl!!)
            })
        }
    }

    private fun ChatMessage.sendableText(): String {
        metadata["sendContent"]?.takeIf { it.isNotBlank() }?.let { return it }
        rawText?.let { return it }
        return fragments.joinToString("\n") { fragment ->
            (fragment as? MessageFragment.Text)?.markdown.orEmpty()
        }
    }

    private fun parseDataUrl(value: String): DataUrl? {
        if (!value.startsWith("data:", ignoreCase = true)) return null
        val comma = value.indexOf(',')
        if (comma <= 5) return null
        val meta = value.substring(5, comma)
        if (!meta.contains(";base64", ignoreCase = true)) return null
        val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
        val data = value.substring(comma + 1).takeIf { it.isNotBlank() } ?: return null
        return DataUrl(mime, data)
    }

    private data class DataUrl(val mimeType: String, val base64: String)
}
