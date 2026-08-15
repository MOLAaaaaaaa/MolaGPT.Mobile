package com.molagpt.app.feature.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.molagpt.app.core.model.AttachmentMime
import java.io.File
import java.util.Locale
import java.util.UUID

/** 选中文件的元信息（不读内容）。 */
data class PickedFileMeta(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/**
 * BYOK 附件的本地托管目录。
 *
 * 选中即复制到 `filesDir/attachments/<uuid>.<ext>`，此后一切读取都走这份副本。
 * 之前直接把 `content://` URI 存进库，重开 App 后 SAF 的临时授权已经失效
 * （`OpenDocument` 的授权挂在 task 上，划掉最近任务或重启设备就没了，代码里也
 * 没有 `takePersistableUriPermission`），历史会话里的图片和 PDF 会静默从请求中消失。
 * 托管副本同时也挡住了「原文件被用户删掉/改名/移动」和「云盘 provider 离线」。
 *
 * 库里存的是**相对**路径，解析时再拼当前 `filesDir`——App 数据目录整体迁移后依然有效。
 */
class AttachmentStore(context: Context) {

    private val appContext = context.applicationContext
    private val root: File get() = File(appContext.filesDir, DIR)

    /** 只查元信息，不读文件内容——大文件不必为了拿个名字先整份读进内存。 */
    fun probe(uri: Uri): PickedFileMeta? = runCatching {
        val resolver = appContext.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                }
            }
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "attachment_${System.currentTimeMillis()}"
        PickedFileMeta(
            name = resolvedName,
            mimeType = AttachmentMime.resolve(resolver.getType(uri), resolvedName),
            sizeBytes = size,
        )
    }.getOrNull()

    /**
     * 流式复制到托管目录，返回相对路径；失败或超过 [MAX_FILE_BYTES] 返回 null。
     *
     * 大小在复制过程中兜底校验：provider 报的 SIZE 可能缺失或不准，只信 cursor 会漏。
     */
    fun save(uri: Uri, meta: PickedFileMeta): String? {
        if (meta.sizeBytes > MAX_FILE_BYTES) return null
        val dir = root.apply { if (!exists()) mkdirs() }
        val target = File(dir, buildFileName(meta.name, meta.mimeType))
        val copied = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) return@runCatching false
                        output.write(buffer, 0, read)
                    }
                    true
                }
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            target.delete()
            return null
        }
        return "$DIR/${target.name}"
    }

    /** 相对路径 → 实际文件；不存在、不可读或越界返回 null。 */
    fun resolve(relativePath: String?): File? {
        val path = relativePath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(appContext.filesDir, path)
        // 相对路径来自本地库，正常不会越界；仍做规范化校验，避免任何写入路径出错时读到目录外。
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(base.path + File.separator)) return null
        return canonical.takeIf { it.isFile && it.canRead() }
    }

    fun exists(relativePath: String?): Boolean = resolve(relativePath) != null

    /** 删会话/消息时回收托管副本。 */
    fun delete(relativePaths: Collection<String>) {
        relativePaths.forEach { path -> resolve(path)?.delete() }
    }

    /**
     * 清理没有任何消息引用的托管文件。[referenced] 传当前库里所有 attachment 的相对路径。
     * 会话删除、编辑截断都会留下孤儿文件，靠这一步统一回收。
     */
    fun sweep(referenced: Set<String>): Int {
        val keep = referenced.mapNotNull { it.substringAfterLast('/').takeIf(String::isNotBlank) }.toSet()
        var removed = 0
        root.listFiles().orEmpty().forEach { file ->
            if (file.name !in keep && file.delete()) removed++
        }
        return removed
    }

    private fun buildFileName(displayName: String, mimeType: String): String {
        val ext = displayName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?: extensionForMime(mimeType)
        return if (ext.isBlank()) UUID.randomUUID().toString() else "${UUID.randomUUID()}.$ext"
    }

    private fun extensionForMime(mimeType: String): String = when {
        AttachmentMime.isPdf(mimeType) -> "pdf"
        mimeType == AttachmentMime.DOCX -> "docx"
        mimeType.startsWith("image/") -> mimeType.substringAfter('/').substringBefore('+')
        AttachmentMime.isTextLike(mimeType) -> "txt"
        else -> "bin"
    }

    companion object {
        const val DIR = "attachments"

        /** 单个附件上限：磁盘便宜，但没有上限时一个超大文件会一路撑到 base64 编码。 */
        const val MAX_FILE_BYTES = 64L * 1024 * 1024

        /**
         * 相对路径 → 供 Coil 显示的 `file://` URL。**只用于渲染，不要落库**：
         * 绝对路径含 App 数据目录前缀，跨安装/迁移后会失效，库里始终只存相对路径。
         */
        fun displayUrl(context: Context, relativePath: String?): String? {
            val path = relativePath?.takeIf { it.isNotBlank() } ?: return null
            val file = File(context.applicationContext.filesDir, path)
            return if (file.isFile) "file://${file.absolutePath}" else null
        }
    }
}
