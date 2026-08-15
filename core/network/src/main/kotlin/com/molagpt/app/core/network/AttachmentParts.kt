package com.molagpt.app.core.network

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.AttachmentMime
import com.molagpt.app.core.model.ChatMessage

/**
 * 附件 → 请求 part 的选取规则。三处必须严格一致，所以收在一个地方：
 * OpenAI 线格式、Anthropic/Gemini 原生格式，以及 `view_image` 工具按序号取图。
 *
 * 关键约定：**不可用的图片照样占一个序号**。否则历史里某张图丢了之后，后面所有
 * `[图片#N]` 标记与实际下标就整体错位，模型按 N 取图会取到别的图或直接越界。
 */
internal object AttachmentParts {

    /** 参与 `[图片#N]` 编号的图片，顺序即编号顺序。含不可用的占位项。 */
    fun orderedImages(message: ChatMessage): List<Attachment> =
        message.attachments.filter {
            AttachmentMime.isImage(it.mimeType) && (it.unavailable || !it.remoteUrl.isNullOrBlank())
        }

    /** 需要以二进制形式随消息发送的文档（目前是 PDF；文本/DOCX 已在提示词里内联）。 */
    fun binaryDocuments(message: ChatMessage): List<Attachment> =
        message.attachments.filter {
            !it.unavailable && AttachmentMime.isPdf(it.mimeType) && !it.remoteUrl.isNullOrBlank()
        }

    /** 非图片的不可用附件——图片的说明由 [imageLabel] 负责，避免同一附件出现两条说明。 */
    fun unavailableDocuments(message: ChatMessage): List<Attachment> =
        message.attachments.filter { it.unavailable && !AttachmentMime.isImage(it.mimeType) }

    fun imageLabel(ordinal: Int, attachment: Attachment): String {
        val name = attachment.name.takeIf { it.isNotBlank() }
        val suffix = if (attachment.unavailable) "（文件已丢失，无法查看）" else ""
        return if (name != null) "[图片#$ordinal: $name]$suffix" else "[图片#$ordinal]$suffix"
    }

    fun unavailableLabel(attachment: Attachment): String =
        "[附件 ${attachment.name} 已丢失，无法读取其内容]"
}
