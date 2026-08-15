package com.molagpt.app.core.network

import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.network.dto.WireMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把领域 [ChatMessage] 转成线格式 [WireMessage]。纯文本时 content 为字符串；
 * 携带远程图片时构造 OpenAI 多模态数组（text + image_url）。
 *
 * 当 [replaceImagesWithText] 为 true 时，图片不会作为 image_url 发送，而是替换为
 * `[图片#N]` 文本占位符（N 按 [imageOrdinal] 全局递增）。用于 BYOK 非视觉模型开启
 * 外挂视觉时：模型看到占位符后可通过 `view_image` 工具按索引调 vision proxy 分析图片。
 */
object OpenAiMessageContentBuilder {
    fun build(
        message: ChatMessage,
        includeFileParts: Boolean = false,
        replaceImagesWithText: Boolean = false,
        imageOrdinal: AtomicInteger = AtomicInteger(0),
    ): WireMessage {
        val role = when (message.role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.SYSTEM -> "system"
            Role.TOOL -> "tool"
        }
        val text = message.plainText()
        val images = AttachmentParts.orderedImages(message)
        // OpenAI 的 file_data 只接受 data URL，远程 URL 形态的 PDF 在这里发不出去。
        val files = if (includeFileParts) {
            AttachmentParts.binaryDocuments(message)
                .filter { it.remoteUrl!!.startsWith("data:", ignoreCase = true) }
        } else {
            emptyList()
        }
        val missing = AttachmentParts.unavailableDocuments(message)

        if (images.isEmpty() && files.isEmpty() && missing.isEmpty()) {
            return WireMessage(role, JsonPrimitive(text))
        }

        val content = buildJsonArray {
            if (text.isNotEmpty()) {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            }
            images.forEach { img ->
                // 每张图都消耗一个序号，不论这次是否真的发得出去——序号必须与
                // AttachmentParts.orderedImages 的下标严格对应，view_image 才能按 N 取到正确的图。
                val n = imageOrdinal.incrementAndGet()
                if (replaceImagesWithText || img.unavailable) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", AttachmentParts.imageLabel(n, img))
                        },
                    )
                } else {
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", img.remoteUrl!!) }
                        },
                    )
                }
            }
            // 丢失的附件明确告知模型，而不是从请求里静默消失。
            missing.forEach { file ->
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", AttachmentParts.unavailableLabel(file))
                    },
                )
            }
            files.forEach { file ->
                add(
                    buildJsonObject {
                        put("type", "file")
                        putJsonObject("file") {
                            put("filename", file.name)
                            put("file_data", file.remoteUrl!!)
                        }
                    },
                )
            }
        }
        return WireMessage(role, content)
    }

    /** 取消息的可发送纯文本：优先使用同步保留的原始发送内容，否则 rawText，再拼回可见 fragment。 */
    private fun ChatMessage.plainText(): String {
        metadata["sendContent"]?.takeIf { it.isNotBlank() }?.let { return it }
        rawText?.let { return it }
        return fragments.mapNotNull { frag ->
            when (frag) {
                is MessageFragment.Text -> frag.markdown
                is MessageFragment.CodeBlock -> "```${frag.language ?: ""}\n${frag.code}\n```"
                is MessageFragment.Latex -> if (frag.display) "$$${frag.expr}$$" else "$${frag.expr}$"
                is MessageFragment.Mermaid -> "```mermaid\n${frag.source}\n```"
                else -> null
            }
        }.joinToString("\n")
    }
}
