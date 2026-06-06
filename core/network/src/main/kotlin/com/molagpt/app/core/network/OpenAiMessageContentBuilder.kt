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

/**
 * 把领域 [ChatMessage] 转成线格式 [WireMessage]。纯文本时 content 为字符串；
 * 携带远程图片时构造 OpenAI 多模态数组（text + image_url）。
 */
object OpenAiMessageContentBuilder {
    fun build(message: ChatMessage): WireMessage {
        val role = when (message.role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.SYSTEM -> "system"
            Role.TOOL -> "tool"
        }
        val text = message.plainText()
        val images = message.attachments.filter {
            it.mimeType.startsWith("image/") && !it.remoteUrl.isNullOrBlank()
        }

        val content = if (images.isEmpty()) {
            JsonPrimitive(text)
        } else {
            buildJsonArray {
                if (text.isNotEmpty()) {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                }
                images.forEach { img ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", img.remoteUrl!!) }
                        },
                    )
                }
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
