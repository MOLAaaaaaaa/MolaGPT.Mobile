package com.molagpt.app.core.network

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageFragment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 [ChatMessage] 转成 Anthropic / Gemini 原生内容格式。
 * 当 [replaceImagesWithText] 为 true 时，图片附件替换为 `[图片#N]` 文本占位符，
 * 与 [OpenAiMessageContentBuilder] 保持同一全局序号规则，供 `view_image` 工具索引。
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
        message.attachments
            .filter { it.isDocumentInput && !it.remoteUrl.isNullOrBlank() }
            .forEach { attachment ->
                if (attachment.isImage) {
                    if (replaceImagesWithText) {
                        val n = imageOrdinal.incrementAndGet()
                        val label = imagePlaceholderLabel(n, attachment.name)
                        addJsonObject {
                            put("type", "text")
                            put("text", label)
                        }
                        return@forEach
                    }
                }
                val data = parseDataUrl(attachment.remoteUrl!!)
                addJsonObject {
                    put("type", if (attachment.isImage) "image" else "document")
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
        message.attachments
            .filter { it.isDocumentInput && !it.remoteUrl.isNullOrBlank() }
            .forEach { attachment ->
                if (attachment.isImage) {
                    if (replaceImagesWithText) {
                        val n = imageOrdinal.incrementAndGet()
                        addJsonObject { put("text", imagePlaceholderLabel(n, attachment.name)) }
                        return@forEach
                    }
                }
                val data = parseDataUrl(attachment.remoteUrl!!)
                addJsonObject {
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
            }
    }

    private fun ChatMessage.sendableText(): String {
        metadata["sendContent"]?.takeIf { it.isNotBlank() }?.let { return it }
        rawText?.let { return it }
        return fragments.joinToString("\n") { fragment ->
            (fragment as? MessageFragment.Text)?.markdown.orEmpty()
        }
    }

    private val Attachment.isDocumentInput: Boolean
        get() = isImage || mimeType.contains("pdf", ignoreCase = true)

    private val Attachment.isImage: Boolean
        get() = mimeType.startsWith("image/")

    private fun imagePlaceholderLabel(ordinal: Int, fileName: String?): String =
        fileName?.takeIf { it.isNotBlank() }
            ?.let { "[图片#$ordinal: $it]" }
            ?: "[图片#$ordinal]"

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
