package com.molagpt.app.feature.file

import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import java.io.File

/**
 * PDF 文字层抽取，**零依赖、零包体**：直接用系统内置的 pdfium。
 *
 * 取舍过程（结论：不引任何第三方库）：
 * - MuPDF 质量最好，但要把 ~9MB 的 .so 打进包，且是 AGPL/商业双许可；
 *   本 App 当前 release 包才 4MB 出头，翻三倍不可接受。
 * - PdfBox-Android 是 Apache-2.0，但 fontbox 字体资源就有 7.5MB，且它的 PDFTextStripper
 *   在移动端抽文本慢到几十秒起步，性能和体积两头都不满足。
 * - Android 15(API 35) 给 [PdfRenderer.Page] 加了 `getTextContents()`；同一套能力经 SDK
 *   extension 回移到了 [PdfRendererPreV]，覆盖 API 30–34（需 S 扩展版本 ≥ 13）。
 *   底层就是系统里的 pdfium，原生速度，**APK 一个字节都不增加**。
 *
 * 代价是 API 30 以下（或没收到 Mainline 更新）的设备抽不了文字——那部分设备走原有的
 * PDF 二进制通道，行为与改造前一致，不构成回退。
 */
object PdfTextExtractor {

    /** 每份文档的安全上限，防止超大 PDF 把内存和时间吃光。 */
    private const val MAX_PAGES = 500
    private const val MAX_CHARS = 1_000_000

    /** 当前设备能否抽 PDF 文字层。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 35 || hasPreVExtension()

    /** @return 抽出的文本；设备不支持或解析失败返回 null（调用方据此回退到二进制通道）。 */
    fun extract(file: File): String? = runCatching {
        when {
            Build.VERSION.SDK_INT >= 35 -> extractModern(file)
            hasPreVExtension() -> extractPreV(file)
            else -> null
        }
    }.getOrNull()

    private fun hasPreVExtension(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    @RequiresApi(35)
    private fun extractModern(file: File): String = openDescriptor(file).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            buildDocument(renderer.pageCount) { index ->
                renderer.openPage(index).use { page ->
                    page.textContents.joinToString("\n") { it.text }
                }
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun extractPreV(file: File): String = openDescriptor(file).use { pfd ->
        val renderer = PdfRendererPreV(pfd)
        try {
            buildDocument(renderer.pageCount) { index ->
                renderer.openPage(index).use { page ->
                    page.textContents.joinToString("\n") { it.text }
                }
            }
        } finally {
            renderer.close()
        }
    }

    /** 逐页拼接并标注页码——模型引用「第 N 页」时能对得上。 */
    private inline fun buildDocument(pageCount: Int, pageText: (Int) -> String): String {
        val out = StringBuilder()
        val pages = minOf(pageCount, MAX_PAGES)
        for (index in 0 until pages) {
            val text = runCatching { pageText(index) }.getOrDefault("")
            if (text.isBlank()) continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append("--- 第 ").append(index + 1).append(" 页 ---\n").append(text)
            if (out.length >= MAX_CHARS) break
        }
        return out.toString()
    }

    private fun openDescriptor(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
}
