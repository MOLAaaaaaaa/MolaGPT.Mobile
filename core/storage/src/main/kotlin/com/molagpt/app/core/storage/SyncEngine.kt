package com.molagpt.app.core.storage

import androidx.room.withTransaction
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.network.CloudConversationDetail
import com.molagpt.app.core.network.ConversationSyncApi
import com.molagpt.app.core.storage.entity.ConversationEntity
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Merges metadata in the background and refreshes conversation bodies on demand. */
class SyncEngine(
    private val database: MolaDatabase,
    private val syncApi: ConversationSyncApi,
    private val settingsStore: SettingsStore,
    private val sessionProvider: () -> SyncSession?,
    private val identity: StateFlow<HistoryIdentity>,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val isActive: (String) -> Boolean = { false },
    private val wireHistoryStore: WireHistoryStore? = null,
) {
    private val conversations = database.conversationDao()
    private val messages = database.messageDao()
    private val streamTasks = database.streamTaskDao()
    private val mutex = Mutex()
    private val pushDebounce = ConcurrentHashMap<String, Job>()

    suspend fun syncNow(expected: HistoryIdentity = identity.value): Boolean =
        whileCurrent(expected, false) { session ->
            mutex.withLock {
                checkCurrent(expected)
                if (settingsStore.cloudSyncEnabled()) doSync(session) else false
            }
        }

    private fun checkCurrent(expected: HistoryIdentity) {
        if (identity.value != expected) throw StaleSession()
    }

    private class StaleSession : CancellationException()

    private suspend fun <T> whileCurrent(
        expected: HistoryIdentity,
        fallback: T,
        block: suspend (SyncSession) -> T,
    ): T = withContext(dispatchers.io) {
        val session = sessionProvider()?.takeIf { it.identity == expected && expected.canSync }
            ?: return@withContext fallback
        coroutineScope {
            val work = async {
                try {
                    checkCurrent(expected)
                    block(session)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Logger.w("SyncEngine", "会话同步失败", error)
                    fallback
                }
            }
            val watcher = launch { identity.first { it != expected }; work.cancel(StaleSession()) }
            try {
                work.await()
            } catch (_: StaleSession) {
                fallback
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun <T> transaction(expected: HistoryIdentity, body: suspend () -> T): T =
        database.withTransaction {
            checkCurrent(expected)
            body().also { checkCurrent(expected) }
        }

    fun schedulePush(sessionId: String) {
        val expected = identity.value
        if (!expected.canSync) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(2_000)
            if (identity.value == expected && conversations.officialById(sessionId) != null) syncNow(expected)
        }
        pushDebounce.put(sessionId, job)?.cancel()
        job.invokeOnCompletion { pushDebounce.remove(sessionId, job) }
        job.start()
    }

    enum class HistoryLoad { CURRENT, UPDATED, UNAVAILABLE, CONFLICT }

    suspend fun loadConversationIfNeeded(
        sessionId: String,
        expected: HistoryIdentity = identity.value,
        keepLocalCopy: Boolean = false,
    ): HistoryLoad = whileCurrent(expected, HistoryLoad.UNAVAILABLE) { session ->
        val local = transaction(expected) {
            conversations.officialById(sessionId)?.takeIf {
                it.deletedAt == null && !hasActiveStream(sessionId)
            }
        } ?: return@whileCurrent HistoryLoad.UNAVAILABLE
        if (!local.needsCloudBody) return@whileCurrent HistoryLoad.CURRENT
        val detail = fetch(session, sessionId)?.let { parseDetail(sessionId, it) }
            ?: return@whileCurrent HistoryLoad.UNAVAILABLE
        if (local.remoteVersion != null && detail.meta.version < local.remoteVersion) return@whileCurrent HistoryLoad.UNAVAILABLE
        val imported = SyncMapper.jsonToMessages(sessionId, detail.messages)
        val fingerprint = fingerprint(detail.messages)
        transaction(expected) {
            val current = conversations.officialById(sessionId) ?: return@transaction HistoryLoad.UNAVAILABLE
            if (!matches(local)) return@transaction HistoryLoad.UNAVAILABLE
            val old = messages.getAllBySession(sessionId, true)
            val localBody = old.map(SyncMapper::messageToJson)
            val equalBody = detail.messages == JsonArray(localBody) ||
                (current.changed(CloudChange.LEGACY) && localBody == imported.map(SyncMapper::messageToJson))

            if (current.hasBodyChanges && !equalBody && fingerprint != current.bodyFingerprint) {
                if (!keepLocalCopy || old.isEmpty()) return@transaction HistoryLoad.CONFLICT
                val copyId = Ids.newSessionId()
                check(conversations.insert(current.copy(
                    sessionId = copyId,
                    title = current.title + "（本机副本）",
                    placeholder = false,
                    dirty = false,
                    syncChanges = 0,
                    remoteVersion = null,
                    bodyVersion = null,
                    bodyFingerprint = null,
                    localRevision = 0,
                )) != -1L)
                messages.insertAll(old.map { row ->
                    row.copy(
                        sessionId = copyId,
                        messageId = Ids.newMessageId(),
                        metadataJson = wireHistoryStore?.copyMetadata(copyId, row.metadataJson) ?: row.metadataJson,
                    )
                })
            } else if (!current.placeholder && (current.hasBodyChanges || current.changed(CloudChange.LEGACY))) {
                // Unchanged remote body permits a new base; matching body acknowledges old local content.
                // The old dirty bit did not identify which metadata fields the user actually changed.
                val pending = current.syncChanges and CloudChange.LEGACY.inv() and
                    (if (equalBody) CloudChange.BODY.inv() else -1)
                conversations.update(merge(current, detail.meta).copy(
                    bodyVersion = detail.meta.version,
                    bodyFingerprint = fingerprint,
                    syncChanges = pending,
                    dirty = false,
                ))
                return@transaction HistoryLoad.UPDATED
            }

            val known = old.associateBy(SyncMapper::messageToJson)
            val reconciled = imported.mapIndexed { index, row ->
                val existing = known[detail.messages[index]] ?: known[SyncMapper.messageToJson(row)]
                existing?.copy(createdAt = row.createdAt, updatedAt = maxOf(existing.updatedAt, row.createdAt)) ?: row
            }.let { rows -> if (rows.map { it.messageId }.toSet().size == rows.size) rows else imported }
            messages.replaceAll(sessionId, reconciled)
            val base = when {
                current.hasBodyChanges -> current.copy(dirty = false, syncChanges = 0)
                current.changed(CloudChange.LEGACY) -> current.copy(dirty = false, syncChanges = current.syncChanges and CloudChange.LEGACY.inv())
                else -> current
            }
            conversations.update(
                merge(base, detail.meta).copy(
                    placeholder = false,
                    bodyVersion = detail.meta.version,
                    bodyFingerprint = fingerprint,
                    messageCount = reconciled.size,
                    visibleInList = reconciled.isNotEmpty(),
                    lastMessagePreview = reconciled.lastOrNull()?.rawText?.take(60),
                ),
            )
            HistoryLoad.UPDATED
        }
    }

    private suspend fun doSync(session: SyncSession): Boolean {
        val cursor = settingsStore.syncCursorIso()
        // The push fast path returns an empty list. Only the pull-only response is a complete list.
        val pull = pull(session, cursor) ?: return false
        val missing = transaction(session.identity) {
            for ((id, meta) in pull.rows) {
                val local = conversations.officialById(id)
                if (local == null) {
                    conversations.insert(
                        ConversationEntity(
                            sessionId = id, title = meta.title, model = meta.model,
                            createdAt = meta.createdAt, updatedAt = meta.version,
                            pinned = meta.pinned ?: false, visibleInList = true, placeholder = true,
                            remoteVersion = meta.version,
                        ),
                    )
                } else if (local.deletedAt == null && !hasActiveStream(id) &&
                    (local.remoteVersion == null || meta.version > local.remoteVersion)
                ) {
                    conversations.update(if (local.hasBodyChanges) local.copy(remoteVersion = meta.version) else merge(local, meta))
                }
            }
            conversations.getConfirmed().filter { it.sessionId !in pull.rows && !hasActiveStream(it.sessionId) }
        }

        for (row in missing) {
            // A missing/unreadable list file can look like a valid empty list. Verify absence of the body separately.
            if (syncApi.fetchConversation(session.jwt, SyncMapper.conversationIdOf(row.sessionId)) != CloudConversationDetail.Missing) {
                return false
            }
        }
        for (row in missing) {
            val removed = transaction(session.identity) {
                val current = conversations.officialById(row.sessionId)
                if (current?.localRevision != row.localRevision || current.remoteVersion != row.remoteVersion ||
                    current.deletedAt != null || current.syncChanges != 0 || hasActiveStream(row.sessionId)
                ) return@transaction false
                messages.deleteBySession(row.sessionId)
                database.contextCheckpointDao().deleteBySession(row.sessionId)
                database.contextCheckpointDao().deleteUsageBySession(row.sessionId)
                conversations.deleteById(row.sessionId)
                true
            }
            if (removed) wireHistoryStore?.deleteSession(row.sessionId)
        }

        val deleted = transaction(session.identity) {
            conversations.getDeleted().filter { SyncMapper.validConversationId(SyncMapper.conversationIdOf(it.sessionId)) }
        }
        for (batch in deleted.chunked(100)) {
            if (!syncApi.deleteConversations(session.jwt, batch.map { SyncMapper.conversationIdOf(it.sessionId) })) return false
            transaction(session.identity) { batch.forEach { conversations.purge(it.sessionId, it.localRevision) } }
        }

        var valid = true
        val dirty = transaction(session.identity) { conversations.getDirty().map { it.sessionId } }
        for (id in dirty) {
            val remote = pull.rows[id]
            val row = transaction(session.identity) {
                conversations.officialById(id)?.takeIf { it.deletedAt == null && it.syncChanges != 0 && !hasActiveStream(id) }
            } ?: continue
            if (!SyncMapper.validConversationId(SyncMapper.conversationIdOf(id))) continue
            val body = if (remote == null || row.hasBodyChanges) transaction(session.identity) {
                if (!matches(row) || row.placeholder) return@transaction null
                JsonArray(messages.getAllBySession(id, true).map(SyncMapper::messageToJson))
            } ?: continue else null
            if (body?.isEmpty() == true) continue

            if (remote == null) {
                if (row.remoteVersion != null || syncApi.fetchConversation(session.jwt, SyncMapper.conversationIdOf(id)) != CloudConversationDetail.Missing) continue
            } else if (body != null) {
                val detail = fetch(session, id)?.let { parseDetail(id, it) } ?: continue
                if (detail.meta.raw != remote.raw) continue
                val remoteFingerprint = fingerprint(detail.messages)
                if (row.bodyVersion != remote.version || row.bodyFingerprint != remoteFingerprint || row.changed(CloudChange.LEGACY)) {
                    // An exact match can confirm a previous write after process death. Otherwise preserve both copies.
                    if (detail.messages == body &&
                        outgoingMeta(row, remote, remote.version).withoutVersion() == remote.raw.withoutVersion()
                    ) {
                        confirm(session.identity, row, remote, remoteFingerprint)
                    } else if (row.bodyVersion == remote.version && row.bodyFingerprint != remoteFingerprint) {
                        transaction(session.identity) {
                            if (matches(row)) {
                                val current = conversations.officialById(id) ?: return@transaction
                                conversations.update(current.copy(bodyVersion = null, bodyFingerprint = null))
                            }
                        }
                    }
                    continue
                }
            }
            val now = System.currentTimeMillis()
            // PHP accepts only a strictly newer whole second. Choosing the next second makes
            // every intervening accepted update at least as new as this write.
            val version = if (remote == null) row.updatedAt else (remote.version / 1_000 + 1) * 1_000
            if (version > now) continue
            val metadata = outgoingMeta(row, remote, version)
            if (!transaction(session.identity) { matches(row) }) continue
            val sent = syncApi.fullSync(session.jwt, payload(cursor, JsonArray(listOf(buildJsonObject {
                put("metadata", metadata)
                put("messages", body ?: JsonArray(emptyList()))
            }))))
            if (sent?.get("success") != JsonPrimitive(true)) {
                valid = false
                continue
            }
            // List writes and body writes can fail independently on the server, even on HTTP success.
            val confirmed = fetch(session, id)?.let { parseDetail(id, it) }
            if (confirmed?.meta?.raw == metadata && (body == null || confirmed.messages == body)) {
                val verified = fingerprint(confirmed.messages).takeIf { body != null || it == row.bodyFingerprint }
                confirm(session.identity, row, confirmed.meta, verified)
            } else valid = false
        }

        if (valid) settingsStore.recordSync(pull.cursor) { identity.value == session.identity }
        checkCurrent(session.identity)
        return valid
    }

    private suspend fun hasActiveStream(id: String): Boolean = isActive(id) || streamTasks.hasOfficialTask(id)

    private suspend fun matches(row: ConversationEntity): Boolean {
        val current = conversations.officialById(row.sessionId) ?: return false
        return current.deletedAt == null && current.localRevision == row.localRevision &&
            current.remoteVersion == row.remoteVersion && current.bodyVersion == row.bodyVersion && !hasActiveStream(row.sessionId)
    }

    private suspend fun confirm(identity: HistoryIdentity, sent: ConversationEntity, meta: RemoteMeta, verifiedFingerprint: String?) {
        transaction(identity) {
            val current = conversations.officialById(sent.sessionId) ?: return@transaction
            if (current.deletedAt != null || current.remoteVersion != sent.remoteVersion || current.bodyVersion != sent.bodyVersion) return@transaction
            val unchanged = current.localRevision == sent.localRevision && !hasActiveStream(sent.sessionId)
            val row = if (unchanged) {
                merge(current.copy(syncChanges = 0, dirty = false), meta).copy(updatedAt = maxOf(current.updatedAt, meta.version))
            } else current.copy(remoteVersion = meta.version)
            conversations.update(row.copy(
                bodyVersion = if (verifiedFingerprint != null) meta.version else current.bodyVersion,
                bodyFingerprint = verifiedFingerprint ?: current.bodyFingerprint,
            ))
        }
    }

    private fun merge(local: ConversationEntity, cloud: RemoteMeta): ConversationEntity =
        local.copy(
            title = if (local.changed(CloudChange.TITLE or CloudChange.LEGACY)) local.title else cloud.title,
            model = if (local.changed(CloudChange.MODEL or CloudChange.LEGACY)) local.model else cloud.model,
            pinned = if (local.changed(CloudChange.PIN or CloudChange.LEGACY)) local.pinned else
                cloud.pinned ?: (local.pinned && (local.remoteVersion == null || local.remoteVersion == cloud.version)),
            updatedAt = if (local.syncChanges != 0) local.updatedAt else cloud.version,
            remoteVersion = cloud.version,
        )

    private fun outgoingMeta(row: ConversationEntity, remote: RemoteMeta?, version: Long): JsonObject =
        if (remote == null) SyncMapper.conversationMetadata(row.copy(updatedAt = version))
        else buildJsonObject {
            remote.raw.forEach { (key, value) -> put(key, value) }
            put("updated_at", SyncMapper.formatIso(version))
            if (row.changed(CloudChange.TITLE) || row.changed(CloudChange.LEGACY)) put("title", row.title)
            if (row.changed(CloudChange.MODEL) || row.changed(CloudChange.LEGACY)) put("model", row.model ?: "auto")
            if (row.changed(CloudChange.PIN) || row.changed(CloudChange.LEGACY) && row.pinned != (remote.pinned ?: false)) {
                put("pinned", row.pinned)
            }
        }

    private fun fingerprint(body: JsonArray): String =
        MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun ConversationEntity.changed(flag: Int): Boolean = syncChanges and flag != 0
    private fun JsonObject.withoutVersion(): Map<String, kotlinx.serialization.json.JsonElement> = filterKeys { it != "updated_at" }

    private suspend fun fetch(session: SyncSession, id: String): JsonObject? =
        (syncApi.fetchConversation(session.jwt, SyncMapper.conversationIdOf(id)) as? CloudConversationDetail.Found)?.conversation

    private fun parseDetail(id: String, value: JsonObject): Detail? {
        val meta = (value["metadata"] as? JsonObject)?.let(::parseMetadata)?.takeIf { it.id == id } ?: return null
        val body = value["messages"] as? JsonArray ?: return null
        if (body.isEmpty() || body.any { it !is JsonObject || it.string("role").isNullOrBlank() || "content" !in it }) return null
        return Detail(meta, body)
    }

    private suspend fun pull(session: SyncSession, cursor: String): Pull? {
        val result = syncApi.fullSync(session.jwt, payload(cursor, JsonArray(emptyList()))) ?: return null
        if (result["success"] != JsonPrimitive(true) || result["has_more"] != JsonPrimitive(false) ||
            (result["updated_content"] as? JsonArray)?.isEmpty() != true
        ) return null
        val timestamp = result.string("new_sync_timestamp")?.takeIf { SyncMapper.parseIso(it) > 0 } ?: return null
        val array = result["full_metadata_list"] as? JsonArray ?: return null
        val rows = LinkedHashMap<String, RemoteMeta>()
        for (item in array) {
            val meta = (item as? JsonObject)?.let(::parseMetadata) ?: return null
            if (rows.put(meta.id, meta) != null) return null
        }
        return Pull(rows, timestamp)
    }

    private fun parseMetadata(raw: JsonObject): RemoteMeta? {
        val id = raw.string("id")?.takeIf(SyncMapper::validConversationId) ?: return null
        val version = SyncMapper.parseIso(raw.string("updated_at")).takeIf { it > 0 } ?: return null
        val title = raw.string("title") ?: return null
        val model = raw.string("model")
        if (model == null && raw["model"] != null && raw["model"] != kotlinx.serialization.json.JsonNull) return null
        val pinned = raw["pinned"]?.let { (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull ?: return null }
        val created = SyncMapper.parseIso(raw.string("time")).takeIf { it > 0 } ?: version
        return RemoteMeta(SyncMapper.sessionIdOf(id), title, model, pinned, version, created, raw)
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun payload(cursor: String, dirty: JsonArray): JsonObject = buildJsonObject {
        put("action", "full_sync")
        put("last_sync_timestamp", cursor)
        put("dirty_conversations", dirty)
    }

    private data class RemoteMeta(
        val id: String, val title: String, val model: String?, val pinned: Boolean?,
        val version: Long, val createdAt: Long, val raw: JsonObject,
    )
    private data class Detail(val meta: RemoteMeta, val messages: JsonArray)
    private data class Pull(val rows: Map<String, RemoteMeta>, val cursor: String)
}
