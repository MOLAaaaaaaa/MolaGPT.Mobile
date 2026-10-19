package com.molagpt.app.core.storage

import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.network.WireHistorySource
import java.io.File
import java.util.UUID
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 大快照只在回放时读取；历史消息和上下文估算使用小引用。文件写完后才允许消息引用它。 */
class WireHistoryStore(private val filesDir: File) {
    private val root = File(filesDir, "wire_history")

    fun storeMetadata(sessionId: String, metadata: Map<String, String>): Map<String, String> {
        var stored = metadata
        for (key in ChatMessageMetadataKeys.WIRE_HISTORY) {
            val raw = metadata[key] ?: continue
            if (raw.length <= INLINE_CHARS) continue
            val reference = WireHistorySource.write(raw, File(root, sessionId))
            stored = stored + (key to reference)
        }
        return stored
    }

    fun deleteSession(sessionId: String) {
        val dir = File(root, sessionId)
        check(!dir.exists() || dir.deleteRecursively()) { "无法删除原始响应目录" }
    }

    fun sweep(metadataRows: List<String>) {
        val referenced = metadataRows.flatMap { row ->
            val metadata = MessageJson.decodeMeta(row)
            ChatMessageMetadataKeys.WIRE_HISTORY.mapNotNull { key ->
                metadata[key]?.let { Json.parseToJsonElement(it) as JsonObject }
                    ?.get("wire_file")?.jsonPrimitive?.contentOrNull
                    ?.let { File(URI(it)).path }
            }
        }.toHashSet()
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        root.walkBottomUp().forEach { file ->
            if (file == root || file.lastModified() >= cutoff) return@forEach
            if (file.isDirectory) {
                if (file.list().isNullOrEmpty()) file.delete()
            } else if (file.path !in referenced) file.delete()
        }
    }

    /** 旧行只能分块读取；SELECT * 会在升级前就触发 CursorWindow 异常。 */
    internal fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT messageId, sessionId, length(metadataJson) FROM messages WHERE length(metadataJson) > $INLINE_CHARS").use { rows ->
            while (rows.moveToNext()) {
                val messageId = rows.getString(0)
                val sessionId = rows.getString(1)
                val length = rows.getInt(2)
                val raw = buildString(length) {
                    var offset = 1
                    while (offset <= length) {
                        db.query(
                            "SELECT substr(metadataJson, ?, ?) FROM messages WHERE messageId = ?",
                            arrayOf<Any>(offset, CHUNK_CHARS, messageId),
                        ).use { chunk ->
                            check(chunk.moveToFirst())
                            append(chunk.getString(0))
                        }
                        offset += CHUNK_CHARS
                    }
                }
                val metadata = Json.decodeFromString<Map<String, String>>(raw)
                val stored = storeMetadata(sessionId, metadata)
                if (stored === metadata) continue
                val fragments = db.query("SELECT fragmentsJson FROM messages WHERE messageId = ?", arrayOf(messageId)).use { cursor ->
                    check(cursor.moveToFirst())
                    MessageJson.decodeFragments(cursor.getString(0))
                }
                val recovered = if (fragments.any { it is MessageFragment.Image }) emptyList()
                    else recoverGeminiImages(metadata[ChatMessageMetadataKeys.GEMINI_WIRE_HISTORY])
                db.execSQL(
                    "UPDATE messages SET metadataJson = ?, fragmentsJson = ? WHERE messageId = ?",
                    arrayOf(MessageJson.encodeMeta(stored), MessageJson.encodeFragments(fragments + recovered), messageId),
                )
            }
        }
    }

    private fun recoverGeminiImages(raw: String?): List<MessageFragment.Image> {
        if (raw == null) return emptyList()
        val wire = Json.parseToJsonElement(raw) as JsonObject
        val items = wire["items"] as? JsonArray ?: return emptyList()
        return buildList {
            for (item in items) {
                val parts = (item as JsonObject)["parts"] as? JsonArray ?: continue
                for (part in parts) {
                    val obj = part as JsonObject
                    val inline = (obj["inlineData"] ?: obj["inline_data"]) as? JsonObject ?: continue
                    val mime = (inline["mimeType"] ?: inline["mime_type"])?.jsonPrimitive?.contentOrNull ?: continue
                    if (!mime.startsWith("image/")) continue
                    val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: continue
                    val dir = File(filesDir, "gen_images")
                    check(dir.isDirectory || dir.mkdirs()) { "无法创建图片目录" }
                    val image = File(dir, "${UUID.randomUUID()}.${mime.substringAfter('/')}")
                    image.outputStream().use { output ->
                        output.write(Base64.decode(data, Base64.DEFAULT))
                        output.fd.sync()
                    }
                    add(MessageFragment.Image(Ids.newFragmentId(), image.toURI().toString()))
                }
            }
        }
    }

    companion object {
        private const val INLINE_CHARS = 64 * 1024
        private const val CHUNK_CHARS = 128 * 1024
    }
}
