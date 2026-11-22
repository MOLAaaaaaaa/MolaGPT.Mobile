package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import androidx.room.withTransaction
import com.molagpt.app.core.network.ConversationSyncApi
import com.molagpt.app.core.storage.entity.ConversationEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 云同步引擎：负责本地 Room 与服务端同步数据的双向合并。
 *
 * 同步阶段只合并 `full_metadata_list` 元数据：新会话落「占位」行（无消息），已有会话只更新元数据。
 * 消息正文在点开会话时经 [loadConversationIfNeeded] 按需拉取，避免大账号首次登录时拉取全部正文。
 *
 * 流程：push 删除墓碑 → push dirty(首次=全量，仅 materialize 的会话) → 服务端 updated_at 后写赢 → 清脏 →
 * pull 元数据合并。每个任务固定一次登录身份，登出或重新登录使旧任务失效。
 */
class SyncEngine(
    private val database: MolaDatabase,
    private val syncApi: ConversationSyncApi,
    private val settingsStore: SettingsStore,
    private val sessionProvider: () -> SyncSession?,
    private val identity: StateFlow<HistoryIdentity>,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
) {
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()
    private val mutex = Mutex()
    private val pushDebounce = ConcurrentHashMap<String, Job>()

    /** Read the original device preference, including on the first run after upgrading. */
    suspend fun syncNow(expected: HistoryIdentity = identity.value): Boolean =
        whileCurrent(expected) { session ->
            if (!settingsStore.cloudSyncEnabled()) return@whileCurrent false
            doSync(session)
        }

    private fun checkCurrent(expected: HistoryIdentity) {
        if (identity.value != expected) throw StaleSession()
    }

    private class StaleSession : CancellationException()

    private suspend fun whileCurrent(
        expected: HistoryIdentity,
        block: suspend (SyncSession) -> Boolean,
    ): Boolean = withContext(dispatchers.io) {
        val session = sessionProvider()?.takeIf { it.identity == expected && expected.canSync }
            ?: return@withContext false
        coroutineScope {
            val work = async {
                mutex.withLock {
                    checkCurrent(expected)
                    try {
                        block(session)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            val watcher = launch {
                identity.first { it != expected }
                work.cancel(StaleSession())
            }
            try {
                work.await()
            } catch (_: StaleSession) {
                false
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

    /** 单会话改动后的增量推送（每轮对话完成触发）；2s 防抖，复用整体 [syncNow]。 */
    fun schedulePush(sessionId: String) {
        val expected = identity.value
        if (!expected.canSync) return
        pushDebounce.remove(sessionId)?.cancel()
        pushDebounce[sessionId] = scope.launch {
            delay(2_000)
            pushDebounce.remove(sessionId)
            if (identity.value != expected || conversationDao.officialById(sessionId) == null) return@launch
            syncNow(expected = expected)
        }
    }

    /**
     * 点开会话时按需拉取消息（懒加载）。仅对「占位会话」(只有元数据、无本地消息) 发一次 fetch_conversation。
     * 已 materialize 的会话直接返回 false（不重复拉）。
     */
    suspend fun loadConversationIfNeeded(
        sessionId: String,
        expected: HistoryIdentity = identity.value,
        onFetchStart: () -> Unit = {},
    ): Boolean = whileCurrent(expected) { session ->
        val conv = conversationDao.officialById(sessionId) ?: return@whileCurrent false
        if (!conv.placeholder || conv.deletedAt != null) return@whileCurrent false
        val remoteId = SyncMapper.conversationIdOf(sessionId)
        onFetchStart() // 确认占位、即将发起网络拉取 → 通知 UI 开始转圈（非占位/未登录在此之前已 return）
        checkCurrent(session.identity)
        val detail = syncApi.fetchConversation(session.jwt, remoteId) ?: return@whileCurrent false
        val msgsArr = (detail["conversation"] as? JsonObject)?.get("messages") as? JsonArray
            ?: return@whileCurrent false
        val messages = SyncMapper.jsonToMessages(sessionId, msgsArr)
        transaction(session.identity) {
            if (conversationDao.getById(sessionId, true)?.placeholder != true) return@transaction false
            messageDao.replaceAll(sessionId, messages)
            conversationDao.markLoaded(sessionId)
            true
        }
    }

    private suspend fun doSync(session: SyncSession): Boolean {
        // 1) 推送删除墓碑，云端确认后硬删本地。
        val deleted = conversationDao.getDeleted()
        if (deleted.isNotEmpty()) {
            val ids = deleted.map { SyncMapper.conversationIdOf(it.sessionId) }
            checkCurrent(session.identity)
            if (!syncApi.deleteConversations(session.jwt, ids)) return false
            transaction(session.identity) {
                deleted.forEach {
                    if (conversationDao.purge(it.sessionId) != 0) messageDao.deleteBySession(it.sessionId)
                }
            }
        }

        // 2) 组 dirty（首次同步=epoch 则全量推送本地已有会话；占位会话不参与推送）。
        val cursor = settingsStore.syncCursorIso()
        val firstSync = cursor == SyncMapper.EPOCH_ISO
        val (dirty, dirtyJson) = transaction(session.identity) {
            val rows = if (firstSync) conversationDao.getAllActive() else conversationDao.getDirty()
            rows to rows.map { conv ->
                val msgs = messageDao.getAllBySession(conv.sessionId, true).map { SyncMapper.messageToJson(it) }
                buildJsonObject {
                    put("metadata", SyncMapper.conversationMetadata(conv))
                    put("messages", JsonArray(msgs))
                }
            }
        }

        val payload = buildJsonObject {
            put("action", "full_sync")
            put("last_sync_timestamp", cursor)
            put("dirty_conversations", JsonArray(dirtyJson))
        }
        checkCurrent(session.identity)
        val result = syncApi.fullSync(session.jwt, payload) ?: return false
        if (result["success"]?.jsonPrimitive?.booleanOrNull != true) return false

        // 3) push 成功 → 清脏。
        transaction(session.identity) {
            dirty.forEach { conversationDao.markSynced(it.sessionId, it.updatedAt) }
            mergeMetadata(result)
        }
        settingsStore.recordSync(result["new_sync_timestamp"]?.jsonPrimitive?.contentOrNull) { identity.value == session.identity }
        checkCurrent(session.identity)
        return true
    }

    private suspend fun mergeMetadata(result: JsonObject) {
        // 4) pull：**只合并元数据**（懒加载）。新会话落占位行(无消息)，已有会话只更新元数据。
        //    消息等用户点开时由 loadConversationIfNeeded 拉取——同步阶段绝不逐条 fetch，千条对话也不卡。
        val serverList = result["full_metadata_list"] as? JsonArray ?: JsonArray(emptyList())
        for (el in serverList) {
            val meta = el as? JsonObject ?: continue
            val convId = meta["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val serverUpdated = SyncMapper.parseIso(meta["updated_at"]?.jsonPrimitive?.contentOrNull)
            val title = meta["title"]?.jsonPrimitive?.contentOrNull ?: "新对话"
            val model = meta["model"]?.jsonPrimitive?.contentOrNull
            val sessionId = SyncMapper.sessionIdOf(convId)
            val local = conversationDao.officialById(sessionId)
            when {
                local == null -> {
                    val createdAt = SyncMapper.parseIso(meta["time"]?.jsonPrimitive?.contentOrNull)
                        .takeIf { it > 0 } ?: serverUpdated
                    conversationDao.insert(
                        ConversationEntity(
                            sessionId = sessionId,
                            title = title,
                            model = model,
                            createdAt = createdAt,
                            updatedAt = serverUpdated,
                            messageCount = 0,
                            visibleInList = true,
                            dirty = false,
                            deletedAt = null,
                            placeholder = true, // 仅元数据，消息懒加载
                        ),
                    )
                }
                local.deletedAt != null -> Unit // 本地已删待 push，不拉回
                serverUpdated > local.updatedAt ->
                    conversationDao.updateMetaFromSync(local.sessionId, title, model, serverUpdated)
                else -> Unit // 本地不旧 → 跳过
            }
        }
    }
}
