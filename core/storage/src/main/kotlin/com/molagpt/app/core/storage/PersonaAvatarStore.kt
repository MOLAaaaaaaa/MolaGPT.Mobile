package com.molagpt.app.core.storage

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 角色卡头像的本机托管目录。
 *
 * **刻意不和聊天附件共用目录**：附件的孤儿回收是按「还有没有消息引用它」扫的
 * （见 `ChatRepository.referencedAttachmentPaths`），头像混进去会被当成没人要的文件删掉。
 * 这里自己扫自己的引用，两套回收互不越界。
 */
class PersonaAvatarStore(context: Context) {

    private val appContext = context.applicationContext
    private val root: File get() = File(appContext.filesDir, DIR)

    /** 写入一张头像，返回相对路径；写失败返回 null（角色照样能存，只是退回矢量图标）。 */
    fun save(bytes: ByteArray): String? = runCatching {
        if (bytes.isEmpty()) return null
        root.mkdirs()
        val name = "${UUID.randomUUID().toString().replace("-", "").take(16)}.png"
        File(root, name).writeBytes(bytes)
        "$DIR/$name"
    }.getOrNull()

    fun resolve(relativePath: String?): File? {
        val path = relativePath?.takeIf { it.isNotBlank() } ?: return null
        // 只认自己目录下的相对路径，挡住 `../` 之类的越界写法。
        val name = path.removePrefix("$DIR/")
        if (name.isEmpty() || name.contains('/') || name.contains('\\')) return null
        return File(root, name).takeIf { it.isFile }
    }

    fun delete(relativePath: String?) {
        resolve(relativePath)?.delete()
    }

    /** 清掉没有任何角色引用的头像文件，返回删除数量。 */
    fun sweep(referenced: Set<String>): Int {
        val files = root.listFiles() ?: return 0
        val keep = referenced.mapNotNull { resolve(it)?.name }.toSet()
        var removed = 0
        for (file in files) {
            if (file.name !in keep && file.delete()) removed++
        }
        return removed
    }

    private companion object {
        const val DIR = "persona_avatars"
    }
}
