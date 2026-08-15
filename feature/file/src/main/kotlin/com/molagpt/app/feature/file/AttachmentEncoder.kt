package com.molagpt.app.feature.file

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.util.Base64OutputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 托管文件 → `data:` URL（仅用于构造请求，绝不落库）。
 *
 * 三个要点：
 * 1. MIME 按**文件头字节**判，不信 provider 报的 type——新机型的 HEIC/HEIF/AVIF 经常报错；
 * 2. 编码前统一降采样并转 JPEG（很多上游不收 webp/heic），同时按 EXIF 旋正，
 *    否则竖拍照片发过去是躺着的；
 * 3. 用 [Base64OutputStream] 流式编码，而不是 `encodeToString(readBytes())`——
 *    后者会让原始字节和 base64 字符串同时驻留堆内存，大文件直接 OOM。
 */
object AttachmentEncoder {

    /** 单个附件内联上限：超过就不塞进请求，由调用方给模型一条说明。 */
    const val MAX_INLINE_BYTES = 10L * 1024 * 1024

    /** 主流视觉模型内部都会缩到 ~1.5-2K 像素，发更大只是白费 token 和流量。 */
    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 85

    /** 图片：降采样 + EXIF 旋正 + 转 JPEG。GIF 原样保留（可能是动图）。 */
    fun encodeImage(file: File): String? = runCatching {
        val sniffed = sniffImageMime(file)
        if (sniffed == "image/gif") return@runCatching dataUrl(sniffed, streamBase64(file))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
        val rotated = applyExifOrientation(file, decoded)
        try {
            val buffer = ByteArrayOutputStream()
            Base64OutputStream(buffer, Base64.NO_WRAP).use { base64 ->
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, base64)
            }
            dataUrl("image/jpeg", buffer.toString(Charsets.ISO_8859_1.name()))
        } finally {
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    /** 非图片（PDF 等）：原样流式编码，不做任何转换。 */
    fun encodeRaw(file: File, mimeType: String): String? = runCatching {
        if (file.length() > MAX_INLINE_BYTES) return@runCatching null
        dataUrl(mimeType, streamBase64(file))
    }.getOrNull()

    private fun dataUrl(mimeType: String, base64: String) = "data:$mimeType;base64,$base64"

    private fun streamBase64(file: File): String {
        val buffer = ByteArrayOutputStream()
        Base64OutputStream(buffer, Base64.NO_WRAP).use { base64 ->
            file.inputStream().use { it.copyTo(base64, DEFAULT_BUFFER_SIZE) }
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * 按魔数判图片类型。ISO-BMFF 容器（HEIC/AVIF）的 `ftyp` box 在字节 4..8，
     * 主品牌码在 8..12——新机型的 HDR 照片常用 heix/hevc/mif1 等品牌码而不只是 heic。
     */
    private fun sniffImageMime(file: File): String = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(16)
            if (input.read(head) < 12) return@runCatching ""
            if (String(head, 4, 4, Charsets.US_ASCII) == "ftyp") {
                return@runCatching when (String(head, 8, 4, Charsets.US_ASCII)) {
                    "heic", "heix", "heim", "heis",
                    "hevc", "hevx", "hevm", "hevs",
                    "mif1", "msf1", "heif",
                    -> "image/heic"

                    "avif", "avis" -> "image/avif"
                    else -> ""
                }
            }
            when {
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
                head.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "image/png"
                String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

                String(head, 0, 6, Charsets.US_ASCII).let { it == "GIF89a" || it == "GIF87a" } -> "image/gif"
                else -> ""
            }
        }
    }.getOrDefault("")

    private val PNG_MAGIC =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
