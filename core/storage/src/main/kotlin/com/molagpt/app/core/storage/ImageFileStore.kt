package com.molagpt.app.core.storage

import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * 画图工作台的图片文件。库里只存相对 [root] 的路径，App 数据目录变了也不失效。
 *
 * 文件跟着任务走，不跟着某一轮或某个版本走：续改引用的是上一轮的产出，
 * 失败、取消、重试都不删文件，删任务（或清空）时整批回收，漏网的由 [sweep] 兜底。
 */
class ImageFileStore(val root: File) {

    fun write(taskId: String, bytes: ByteArray, ext: String): String {
        val dir = File(root, taskId).apply { mkdirs() }
        val name = "${UUID.randomUUID()}.${ext.ifBlank { "png" }}"
        File(dir, name).writeBytes(bytes)
        return "$taskId/$name"
    }

    /** 本地文件；远程 URL 返回 null。 */
    fun fileOf(src: String): File? = when {
        src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:") -> null
        src.startsWith("file://") -> Uri.parse(src).path?.let(::File)
        else -> File(root, src)
    }

    /** 给 Coil 和预览用的地址。 */
    fun url(src: String): String = when {
        src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:") || src.startsWith("file://") -> src
        else -> Uri.fromFile(File(root, src)).toString()
    }

    fun read(src: String): ByteArray? = fileOf(src)?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    /** 旧版存的是 file:// 绝对路径；落在 [root] 下的改成相对路径。 */
    fun relativize(src: String): String {
        val file = if (src.startsWith("file://")) Uri.parse(src).path?.let(::File) else null
        file ?: return src
        val rootPath = root.canonicalFile.path + File.separator
        val path = runCatching { file.canonicalFile.path }.getOrDefault(file.path)
        return if (path.startsWith(rootPath)) path.removePrefix(rootPath).replace(File.separatorChar, '/') else src
    }

    fun deleteTaskDir(taskId: String) {
        runCatching { File(root, taskId).deleteRecursively() }
    }

    fun deleteAll() {
        runCatching { root.listFiles()?.forEach { it.deleteRecursively() } }
    }

    /**
     * 删掉没有任何记录引用的文件。只动足够旧的：刚写下、还没来得及落库的结果不能误删。
     */
    fun sweep(referenced: Set<String>, minAgeMs: Long = 10 * 60 * 1000L) {
        if (!root.isDirectory) return
        val cutoff = System.currentTimeMillis() - minAgeMs
        val rootPath = root.path + File.separator
        root.walkBottomUp().forEach { file ->
            if (file == root) return@forEach
            if (file.isDirectory) {
                if (file.list().isNullOrEmpty() && file.lastModified() < cutoff) file.delete()
                return@forEach
            }
            val rel = file.path.removePrefix(rootPath).replace(File.separatorChar, '/')
            if (rel !in referenced && file.lastModified() < cutoff) file.delete()
        }
    }
}
