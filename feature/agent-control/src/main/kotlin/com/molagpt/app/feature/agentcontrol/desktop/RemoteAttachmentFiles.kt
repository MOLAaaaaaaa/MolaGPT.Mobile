package com.molagpt.app.feature.agentcontrol.desktop

import android.content.Context
import android.net.Uri
import com.molagpt.app.feature.file.AttachmentStore
import com.molagpt.app.feature.file.PickedFileMeta
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 发往电脑的附件在手机上的中转副本。
 *
 * 选中即复制到 cache，复制时算出长度和 SHA-256；之后的分片都从这份副本读。
 * 重试因此不依赖系统选择器给的临时授权，文件内容也不会在算摘要和发送之间变化。
 * 发送成功或移除后删除；进程被杀留下的副本下次进入时清掉。
 */
class RemoteAttachmentFiles(context: Context) {

    private val appContext = context.applicationContext
    private val store = AttachmentStore(appContext)
    private val root: File get() = File(appContext.cacheDir, DIR)

    class Staged(val file: File, val size: Long, val sha256: String)

    class TooLargeException : Exception()

    fun probe(uri: Uri): PickedFileMeta? = store.probe(uri)

    /** 复制一份并计算摘要；读不到返回 null，超过 [MAX_BYTES] 抛 [TooLargeException]。 */
    fun stage(uri: Uri): Staged? {
        val dir = root.apply { if (!exists()) mkdirs() }
        val target = File(dir, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: return null
            input.use {
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw TooLargeException()
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: TooLargeException) {
            target.delete()
            throw error
        } catch (_: Exception) {
            target.delete()
            return null
        }
        if (total == 0L) {
            target.delete()
            return null
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return Staged(target, total, sha256)
    }

    fun delete(file: File?) {
        file?.delete()
    }

    /** 清掉上次进程留下的副本。 */
    fun sweepStale() {
        val deadline = System.currentTimeMillis() - STALE_AFTER_MS
        root.listFiles().orEmpty().forEach { if (it.lastModified() < deadline) it.delete() }
    }

    companion object {
        private const val DIR = "remote-uploads"
        private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

        /** 单个附件上限，与电脑端一致，见协议文档 §5.7。 */
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
