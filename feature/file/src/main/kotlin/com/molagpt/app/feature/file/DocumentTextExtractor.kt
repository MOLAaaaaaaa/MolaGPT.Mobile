package com.molagpt.app.feature.file

import com.molagpt.app.core.model.AttachmentKind
import com.molagpt.app.core.model.AttachmentMime
import java.io.File
import java.nio.charset.Charset

/** 抽取结果。[text] 为空表示文件里没有可提取的文字层（扫描件 / 纯图片文档）。 */
data class ExtractedText(
    val text: String,
    /** 截断前的总字符数，供提示词如实披露截断比例。 */
    val totalChars: Int,
)

/**
 * 附件 → 纯文本。
 *
 * 一律**在端上抽文字层，不做 OCR**：OCR 慢、贵、易错，而绝大多数用户上传的文档
 * 本来就带文字层。抽不出来时如实告诉模型「可能是扫描件」，让它自己说明情况，
 * 比猜一遍更可靠。PDF 走系统 pdfium，不引第三方库（见 [PdfTextExtractor]）。
 *
 * 结果按 `路径 + mtime + size` 缓存：同一份文档在多轮对话里会被反复送进上下文，
 * 不缓存的话每轮都要全量重解，手机上开销可观。
 */
class DocumentTextExtractor(private val store: AttachmentStore) {

    private val cache = object : LinkedHashMap<String, ExtractedText>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ExtractedText>) = size > CACHE_ENTRIES
    }

    /**
     * @return 抽取结果；`null` 表示这个类型/这台设备没有本地抽取通道（图片走视觉；老设备上的
     *         PDF 走原生二进制），调用方据此决定走别的路。文件读不到时同样返回 `null`。
     */
    fun extract(relativePath: String?, mimeType: String): ExtractedText? {
        val file = store.resolve(relativePath) ?: return null
        val key = "${file.path}|${file.lastModified()}|${file.length()}"
        synchronized(cache) { cache[key] }?.let { return it }

        val extracted = when (AttachmentMime.classify(mimeType, file.name)) {
            AttachmentKind.TEXT -> readAsText(file)
            AttachmentKind.DOCUMENT -> DocxParser.parse(file)
            AttachmentKind.PDF -> PdfTextExtractor.extract(file) ?: return null
            AttachmentKind.IMAGE, AttachmentKind.UNSUPPORTED -> return null
        }

        val clean = sanitize(extracted)
        val result = ExtractedText(text = clean, totalChars = clean.length)
        synchronized(cache) { cache[key] = result }
        return result
    }

    /** 按 BOM 判编码；没有 BOM 一律按 UTF-8 解，非法字节由解码器替换成 U+FFFD 而不是抛异常。 */
    private fun readAsText(file: File): String = runCatching {
        val bytes = file.readBytes()
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))

            else -> String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    /**
     * 去掉 NUL、孤立控制字符和零宽 BOM：它们会污染 JSON 请求体，模型侧也读不出意义。
     * 全部按码位比较，避免源码里出现不可见字符。0x0A=LF，0x09=TAB，0xFEFF=BOM。
     */
    private fun sanitize(raw: String): String =
        raw.filter { it.code == 0x0A || it.code == 0x09 || (it.code >= 0x20 && it.code != 0xFEFF) }.trim()

    private companion object {
        const val CACHE_ENTRIES = 24
    }
}
