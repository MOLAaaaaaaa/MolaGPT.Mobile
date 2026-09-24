package com.molagpt.app.core.render.visual

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 组件图片与网页 / 表格文件的导出：存相册、走系统分享。
 *
 * 分享用的文件放在 cache/shared/，经 FileProvider 交出去（file_paths.xml 只开放这一个子目录）。
 * 每次分享前清掉一小时前的旧文件，目录不会一直长。
 */
object VisualExport {
    private const val SHARED_DIR = "shared"

    suspend fun savePng(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val name = "MolaGPT_${System.currentTimeMillis()}.png"
        val resolver = context.contentResolver
        val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val collection = if (pending) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (pending) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MolaGPT")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Images.Media.DATA,
                    "${Environment.getExternalStorageDirectory()}/${Environment.DIRECTORY_PICTURES}/MolaGPT/$name",
                )
            }
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext false
        runCatching {
            val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            if (!written) error("write failed")
            if (pending) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    suspend fun sharePng(context: Context, bitmap: Bitmap, baseName: String) {
        val file = withContext(Dispatchers.IO) {
            sharedFile(context, "$baseName.png").also { f ->
                f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        shareFile(context, file, "image/png", "分享图片")
    }

    /** 写一个文本文件再分享。[bom] 给 CSV 用：不带 BOM 时 Excel 会把中文读成乱码。 */
    suspend fun shareText(context: Context, fileName: String, mime: String, text: String, bom: Boolean = false) {
        val file = withContext(Dispatchers.IO) {
            sharedFile(context, fileName).also { f ->
                f.outputStream().use { out ->
                    if (bom) out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
        shareFile(context, file, mime, "分享文件")
    }

    fun safeName(raw: String?, fallback: String): String {
        val cleaned = raw.orEmpty().filter { it !in "\\/:*?\"<>|\n\r\t" }.trim().take(48)
        return cleaned.ifEmpty { fallback }
    }

    private fun sharedFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return File(dir, name)
    }

    private fun shareFile(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
