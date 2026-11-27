package com.molagpt.app.feature.chat

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.AttachmentKind
import com.molagpt.app.core.model.AttachmentMime
import com.molagpt.app.feature.file.AttachmentEncoder
import com.molagpt.app.feature.file.AttachmentStore
import com.molagpt.app.feature.file.DocumentTextExtractor

/** 一次 IO 准备的产物：请求用的附件副本 + 要注入的提示词。 */
internal data class PreparedAttachments(val attachments: List<Attachment>, val hint: String?)

/**
 * 附件提示词与 BYOK 附件水合（纯组装逻辑，与 ViewModel 生命周期解耦）。
 */
internal class ChatAttachmentAssembler(
    private val attachmentStore: AttachmentStore,
    private val documentExtractor: DocumentTextExtractor,
) {
    fun buildSandboxHint(attachments: List<Attachment>): String? {
        if (attachments.isEmpty()) return null
        val lines = attachments.mapIndexed { index, attachment ->
            val urlInfo = attachment.remoteUrl?.takeIf { it.isNotBlank() }?.let { "\n   公网URL: $it" } ?: ""
            "${index + 1}. ${attachment.typeLabel()}：${attachment.name} → Python访问路径: ${attachment.sandboxInputPath()}$urlInfo"
        }
        return "用户已上传以下文件到沙箱：\n${lines.joinToString("\n")}"
    }

    fun prepareByokAttachments(attachments: List<Attachment>): PreparedAttachments {
        val hydrated = hydrateByokAttachments(attachments)
        return PreparedAttachments(hydrated, buildByokAttachmentHint(hydrated))
    }

    fun buildByokAttachmentHint(attachments: List<Attachment>): String? {
        val sections = attachments.mapNotNull { attachment -> attachment.toHintSection() }
        if (sections.isEmpty()) return null
        return buildString {
            append("用户已附加以下文件：\n\n")
            append(sections.joinToString("\n\n"))
            append("\n\n")
            append(ATTACHMENT_FIDELITY_NOTE)
        }
    }

    fun hydrateByokAttachments(attachments: List<Attachment>): List<Attachment> =
        attachments.map { attachment ->
            attachment.remoteUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { return@map attachment }

            val file = attachmentStore.resolve(attachment.localPath)
                ?: return@map attachment.copy(remoteUrl = null, thumbnailUrl = null, unavailable = true)

            val kind = AttachmentMime.classify(attachment.mimeType, attachment.name)
            val encoded = when (kind) {
                AttachmentKind.IMAGE -> AttachmentEncoder.encodeImage(file)
                AttachmentKind.PDF ->
                    if (documentExtractor.extract(attachment.localPath, attachment.mimeType)?.text.isNullOrBlank()) {
                        AttachmentEncoder.encodeRaw(file, attachment.mimeType)
                    } else {
                        null
                    }
                AttachmentKind.TEXT, AttachmentKind.DOCUMENT, AttachmentKind.UNSUPPORTED -> null
            }
            val imageEncodeFailed = kind == AttachmentKind.IMAGE && encoded == null
            attachment.copy(remoteUrl = encoded, thumbnailUrl = null, unavailable = imageEncodeFailed)
        }

    private fun Attachment.toHintSection(): String? {
        val isImage = AttachmentMime.isImage(mimeType)
        if (unavailable) {
            val reason = if (isImage) "图片文件丢失，无法读取" else "文件丢失，无法读取"
            return "<attached_file name=\"${name.attr()}\" mime=\"${mimeType.attr()}\" error=\"$reason\" />"
        }
        if (isImage) return null

        val extracted = documentExtractor.extract(localPath, mimeType)
        val text = extracted?.text.orEmpty()

        if (text.isNotBlank()) {
            val head = text.take(MAX_INLINE_TEXT_CHARS)
            val truncated = if (head.length < extracted!!.totalChars) {
                " truncated=\"${head.length}/${extracted.totalChars}\""
            } else {
                ""
            }
            return "<attached_file name=\"${name.attr()}\" mime=\"${mimeType.attr()}\"$truncated>\n" +
                head + "\n</attached_file>"
        }

        if (!remoteUrl.isNullOrBlank()) {
            val why = if (extracted != null) "未提取到文字层，可能是扫描件或纯图片文档；" else ""
            return "<attached_file name=\"${name.attr()}\" mime=\"${mimeType.attr()}\" " +
                "delivery=\"binary\" note=\"${why}已附上文件原件\" />"
        }

        val reason = when {
            !AttachmentMime.isPdf(mimeType) -> "无法转换为文本"
            (attachmentStore.resolve(localPath)?.length() ?: 0L) > AttachmentEncoder.MAX_INLINE_BYTES ->
                "文件超过 ${AttachmentEncoder.MAX_INLINE_BYTES / 1024 / 1024}MB，未随消息发送"
            else -> "文件读取失败，未随消息发送"
        }
        return "<attached_file name=\"${name.attr()}\" mime=\"${mimeType.attr()}\" error=\"$reason\" />"
    }

    private fun String.attr(): String = replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun Attachment.typeLabel(): String = when {
        AttachmentMime.isImage(mimeType) -> "图片文件"
        AttachmentMime.isPdf(mimeType) -> "PDF 文件"
        else -> "数据文件"
    }

    private fun Attachment.sandboxInputPath(): String {
        val fileName = sandboxPath
            ?.replace('\\', '/')
            ?.split('/')
            ?.lastOrNull { it.isNotBlank() }
            ?: name
        return "/input/$fileName"
    }

    companion object {
        const val MAX_INLINE_TEXT_CHARS = 12_000
        const val ATTACHMENT_FIDELITY_NOTE =
            "说明：以上仅为从文件中提取的纯文本。文件内的图片、图表、公式、扫描页、手写内容和" +
                "复杂表格排版无法提取，可能存在你看不到的信息。若用户的问题涉及这些内容，" +
                "请主动说明你只能看到文本部分。"
    }
}
