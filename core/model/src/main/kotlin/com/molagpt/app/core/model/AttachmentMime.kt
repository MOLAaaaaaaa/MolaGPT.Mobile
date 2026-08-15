package com.molagpt.app.core.model

import java.util.Locale

/** 附件按「怎么送进模型」分类的结果。 */
enum class AttachmentKind {
    /** 走多模态图片通道（image_url / image / inlineData）。 */
    IMAGE,

    /** 可抽文字层的文档；抽不出来时降级为一条说明。 */
    PDF,

    /** 纯文本/结构化文本，直接解码内联。 */
    TEXT,

    /** 能抽出文本的二进制容器（OOXML），需专门的解析器。 */
    DOCUMENT,

    /** 无法转成文本，不接收。 */
    UNSUPPORTED,
}

/**
 * 附件 MIME 归类。
 *
 * 放在 :core:model 而不是散落各模块：网络层（content builder）和聊天层（类型准入、
 * 提示词构造）必须用同一套判断。各写一份的后果已经出现过——docx 的
 * `application/vnd.openxmlformats-officedocument.wordprocessingml.document` 因为子串
 * 命中 "xml" 被当成文本类，ZIP 字节按 UTF-8 解码成乱码进了提示词并永久落库。
 * 所以这里一律**锚定匹配**（全等/前缀/后缀），不用 contains，并且二进制容器先排除。
 */
object AttachmentMime {

    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private const val OOXML_PREFIX = "application/vnd.openxmlformats-officedocument"

    /** 老版 Office 二进制格式：没有可移植的抽取方案，明确不收，而不是硬解成乱码。 */
    private val LEGACY_OFFICE = setOf(
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
    )

    /** 不以 `text/` 开头、但内容确实是纯文本的类型。逐个列举，不做模糊匹配。 */
    private val STRUCTURED_TEXT = setOf(
        "application/json",
        "application/ld+json",
        "application/xml",
        "application/javascript",
        "application/x-javascript",
        "application/ecmascript",
        "application/typescript",
        "application/yaml",
        "application/x-yaml",
        "application/toml",
        "application/sql",
        "application/x-sql",
        "application/csv",
        "application/graphql",
        "application/x-sh",
        "application/x-shellscript",
        "application/x-python",
        "application/x-tex",
        "application/x-latex",
    )

    /** 扩展名 → MIME。用于 provider 只给 `application/octet-stream` 时兜底。 */
    private val EXT_TO_MIME = mapOf(
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "mdx" to "text/markdown",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "json" to "application/json",
        "jsonl" to "application/json",
        "xml" to "application/xml",
        "yml" to "application/yaml",
        "yaml" to "application/yaml",
        "toml" to "application/toml",
        "ini" to "text/plain",
        "conf" to "text/plain",
        "properties" to "text/plain",
        "sql" to "application/sql",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "mjs" to "application/javascript",
        "ts" to "application/typescript",
        "tsx" to "application/typescript",
        "jsx" to "application/javascript",
        "py" to "text/x-python",
        "kt" to "text/x-kotlin",
        "kts" to "text/x-kotlin",
        "java" to "text/x-java",
        "c" to "text/x-c",
        "h" to "text/x-c",
        "cpp" to "text/x-c",
        "cc" to "text/x-c",
        "hpp" to "text/x-c",
        "cs" to "text/plain",
        "go" to "text/x-go",
        "rs" to "text/x-rust",
        "rb" to "text/x-ruby",
        "php" to "text/x-php",
        "swift" to "text/x-swift",
        "dart" to "text/x-dart",
        "sh" to "application/x-sh",
        "bat" to "text/plain",
        "ps1" to "text/plain",
        "gradle" to "text/plain",
        "pdf" to "application/pdf",
        "docx" to DOCX,
        "xlsx" to XLSX,
        "pptx" to PPTX,
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heic",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
    )

    /** 去掉参数与大小写差异：`Text/Plain; charset=UTF-8` → `text/plain`。 */
    fun normalize(mimeType: String?): String =
        mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)

    /**
     * 取实际生效的 MIME：provider 报 `application/octet-stream` 或空时按扩展名兜底。
     * 文件选择器对 .md / .log / .kt 这类经常只给 octet-stream，不兜底会被判成不支持。
     */
    fun resolve(mimeType: String?, fileName: String?): String {
        val normalized = normalize(mimeType)
        if (normalized.isNotEmpty() &&
            normalized != "application/octet-stream" &&
            normalized != "*/*" &&
            normalized != "application/unknown"
        ) {
            return normalized
        }
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return EXT_TO_MIME[ext] ?: normalized.ifEmpty { "application/octet-stream" }
    }

    fun isImage(mimeType: String?): Boolean = normalize(mimeType).startsWith("image/")

    fun isPdf(mimeType: String?): Boolean = with(normalize(mimeType)) {
        endsWith("/pdf") || endsWith("-pdf") || endsWith("+pdf")
    }

    /** OOXML 家族（docx/xlsx/pptx）：名字里带 openxmlformats，实际是 ZIP。 */
    fun isOoxml(mimeType: String?): Boolean = normalize(mimeType).startsWith(OOXML_PREFIX)

    fun isLegacyOffice(mimeType: String?): Boolean = normalize(mimeType) in LEGACY_OFFICE

    /** 可直接按 UTF-8 解码的纯文本。二进制容器一律先排除，杜绝解出乱码。 */
    fun isTextLike(mimeType: String?): Boolean {
        val m = normalize(mimeType)
        if (m.isEmpty()) return false
        if (isOoxml(m) || isLegacyOffice(m) || isPdf(m) || isImage(m)) return false
        if (m.startsWith("text/")) return true
        if (m.endsWith("+xml") || m.endsWith("+json") || m.endsWith("+yaml")) return true
        return m in STRUCTURED_TEXT
    }

    /** 目前只有 docx 有解析器；xlsx/pptx 归到 UNSUPPORTED，等各自 parser 落地再放行。 */
    fun isParsableDocument(mimeType: String?): Boolean = normalize(mimeType) == DOCX

    fun classify(mimeType: String?, fileName: String? = null): AttachmentKind {
        val m = resolve(mimeType, fileName)
        return when {
            isImage(m) -> AttachmentKind.IMAGE
            isPdf(m) -> AttachmentKind.PDF
            isParsableDocument(m) -> AttachmentKind.DOCUMENT
            isTextLike(m) -> AttachmentKind.TEXT
            else -> AttachmentKind.UNSUPPORTED
        }
    }

    fun isSupportedForByok(mimeType: String?, fileName: String? = null): Boolean =
        classify(mimeType, fileName) != AttachmentKind.UNSUPPORTED

    /** 供 UI 提示：为什么这个附件收不了。null 表示能收。 */
    fun unsupportedReason(mimeType: String?, fileName: String? = null): String? {
        val m = resolve(mimeType, fileName)
        if (classify(m) != AttachmentKind.UNSUPPORTED) return null
        return when {
            isLegacyOffice(m) -> "旧版 Office 格式（.doc/.xls/.ppt）无法提取文字，请转存为 .docx 或 PDF 后再上传"
            isOoxml(m) -> "暂不支持该 Office 格式，请转存为 PDF 后再上传"
            else -> "BYOK 支持图片、PDF、Word(.docx) 和文本文件"
        }
    }

    /** 气泡上的类型角标。 */
    fun label(mimeType: String?, fileName: String? = null): String {
        val m = resolve(mimeType, fileName)
        return when (classify(m)) {
            AttachmentKind.IMAGE -> "图片"
            AttachmentKind.PDF -> "PDF"
            AttachmentKind.DOCUMENT -> "DOCX"
            AttachmentKind.TEXT, AttachmentKind.UNSUPPORTED ->
                fileName?.substringAfterLast('.', "")?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "文件"
        }
    }
}
