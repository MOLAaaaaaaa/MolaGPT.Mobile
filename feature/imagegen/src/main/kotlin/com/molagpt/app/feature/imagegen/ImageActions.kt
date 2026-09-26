package com.molagpt.app.feature.imagegen

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 结果图的保存与分享。按原文件的字节写出，不经 Bitmap 重新压缩。 */
internal object ImageActions {
    private const val SHARED_DIR = "shared"

    /** 返回成功保存的张数。 */
    suspend fun save(context: Context, urls: List<String>): Int = withContext(Dispatchers.IO) {
        urls.count { url -> readBytes(url)?.let { writeToGallery(context, it) } == true }
    }

    suspend fun share(context: Context, urls: List<String>) {
        val uris = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            urls.mapIndexedNotNull { index, url ->
                val bytes = readBytes(url) ?: return@mapIndexedNotNull null
                val file = File(dir, "MolaGPT_${System.currentTimeMillis()}_$index.${extensionOf(bytes)}")
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun readBytes(url: String): ByteArray? = runCatching {
        when {
            url.startsWith("file://") -> Uri.parse(url).path?.let { File(it).readBytes() }
            url.startsWith("http://") || url.startsWith("https://") ->
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    inputStream.use { it.readBytes() }
                }
            url.startsWith("data:") -> android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
            else -> File(url).takeIf { it.isFile }?.readBytes()
        }
    }.getOrNull()

    fun writeToGallery(context: Context, bytes: ByteArray): Boolean {
        val ext = extensionOf(bytes)
        val resolver = context.contentResolver
        val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val collection = if (pending) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val name = "MolaGPT_${System.currentTimeMillis()}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(bytes))
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
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
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
}
