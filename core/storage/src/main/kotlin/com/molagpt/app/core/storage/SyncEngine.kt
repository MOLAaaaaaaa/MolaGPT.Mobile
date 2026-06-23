package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.network.SyncApi
import com.molagpt.app.core.storage.dao.ConversationDao
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.entity.ConversationEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * pull 元数据合并。鉴权用持久登录 JWT（[jwtProvider]）；游客或未开启云同步直接跳过。
 */
class SyncEngine(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val syncApi: SyncApi,
    private val settingsStore: SettingsStore,
    private val jwtProvider: () -> String?,
    private val cloudSyncEnabled: () -> Boolean,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val pushDebounce = ConcurrentHashMap<String, Job>()

    /** 全量双向同步（登录后 / 手动“立即同步” / 进前台触发）。返回是否成功。
     *  [force]=true 跳过“云同步开关”判定（用于手动同步、刚开启云同步时的首拉），仍要求已登录。 */
    suspend fun syncNow(force: Boolean = false): Boolean = withContext(dispatchers.io) {
        val jwt = jwtProvider()?.takeIf { it.isNotBlank() } ?: return@withContext false
        if (!force && !cloudSyncEnabled()) return@withContext false
        mutex.withLock { runCatching { doSync(jwt) }.getOrDefault(false) }
    }

    /** 单会话改动后的增量推送（每轮对话完成触发）；2s 防抖，复用整体 [syncNow]。 */
    fun schedulePush(sessionId: String) {
        if (jwtProvider().isNullOrBlank() || !cloudSyncEnabled()) return
        pushDebounce.remove(sessionId)?.cancel()
        pushDebounce[sessionId] = scope.launch {
            delay(2_000)
            pushDebounce.remove(sessionId)
            val providerKind = withContext(dispatchers.io) {
                conversationDao.getById(sessionId)?.providerKind
            }
            if (providerKind != ProviderKind.MOLAGPT.name) return@launch
            runCatching { syncNow() }
        }
    }

    /**
     * 点开会话时按需拉取消息（懒加载）。仅对「占位会话」(只有元数据、无本地消息) 发一次 fetch_conversation。
     * 已 materialize 的会话直接返回 false（不重复拉）。
     */
    suspend fun loadConversationIfNeeded(
        sessionId: String,
        onFetchStart: () -> Unit = {},
    ): Boolean = withContext(dispatchers.io) {
        val jwt = jwtProvider()?.takeIf { it.isNotBlank() } ?: return@withContext false
        val conv = conversationDao.getById(sessionId) ?: return@withContext false
        if (!conv.placeholder) return@withContext false
        onFetchStart() // 确认占位、即将发起网络拉取 → 通知 UI 开始转圈（非占位/未登录在此之前已 return）
        val convId = SyncMapper.conversationIdOf(sessionId)
        val detail = syncApi.fetchConversation(jwt, convId) ?: return@withContext false
        val msgsArr = (detail["conversation"] as? JsonObject)?.get("messages") as? JsonArray
            ?: return@withContext false
        messageDao.deleteBySession(sessionId)
        msgsArr.forEachIndexed { index, mEl ->
            (mEl as? JsonObject)?.let { messageDao.upsert(SyncMapper.jsonToMessage(sessionId, it, index)) }
        }
        conversationDao.markLoaded(sessionId)
        true
    }

    /** 账户切换/登出：清理只有元数据、没有正文的占位会话，避免旧账户条目残留。 */
    suspend fun prunePlaceholders() = withContext(dispatchers.io) {
        conversationDao.deletePlaceholders()
    }

    private suspend fun doSync(jwt: String): Boolean {
        // 1) 推送删除墓碑，云端确认后硬删本地。
        val deleted = conversationDao.getDeleted()
        if (deleted.isNotEmpty()) {
            val ids = deleted.map { SyncMapper.conversationIdOf(it.sessionId) }
            if (syncApi.deleteConversations(jwt, ids)) {
                deleted.forEach {
                    messageDao.deleteBySession(it.sessionId)
                    conversationDao.purge(it.sessionId)
                }
            }
        }

        // 2) 组 dirty（首次同步=epoch 则全量推送本地已有会话；占位会话不参与推送）。
        val cursor = settingsStore.syncCursorIso()
        val firstSync = cursor == SyncMapper.EPOCH_ISO
        val dirty = if (firstSync) conversationDao.getAllActive() else conversationDao.getDirty()
        val dirtyJson = dirty.map { conv ->
            val msgs = messageDao.getAllBySession(conv.sessionId).map { SyncMapper.messageToJson(it) }
            buildJsonObject {
                put("metadata", SyncMapper.conversationMetadata(conv))
                put("messages", JsonArray(msgs))
            }
        }

        val payload = buildJsonObject {
            put("action", "full_sync")
            put("last_sync_timestamp", cursor)
            put("dirty_conversations", JsonArray(dirtyJson))
        }
        val result = syncApi.fullSync(jwt, payload) ?: return false
        if (result["success"]?.jsonPrimitive?.booleanOrNull != true) return false

        // 3) push 成功 → 清脏。
        dirty.forEach { conversationDao.markSynced(it.sessionId) }

        // 4) pull：**只合并元数据**（懒加载）。新会话落占位行(无消息)，已有会话只更新元数据。
        //    消息等用户点开时由 loadConversationIfNeeded 拉取——同步阶段绝不逐条 fetch，千条对话也不卡。
        val serverList = result["full_metadata_list"] as? JsonArray ?: JsonArray(emptyList())
        for (el in serverList) {
            val meta = el as? JsonObject ?: continue
            val convId = meta["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val sessionId = SyncMapper.sessionIdOf(convId)
            val serverUpdated = SyncMapper.parseIso(meta["updated_at"]?.jsonPrimitive?.contentOrNull)
            val title = meta["title"]?.jsonPrimitive?.contentOrNull ?: "新对话"
            val model = meta["model"]?.jsonPrimitive?.contentOrNull
            val local = conversationDao.getById(sessionId)
            when {
                local == null -> {
                    val createdAt = SyncMapper.parseIso(meta["time"]?.jsonPrimitive?.contentOrNull)
                        .takeIf { it > 0 } ?: serverUpdated
                    conversationDao.upsert(
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
                    conversationDao.updateMetaFromSync(sessionId, title, model, serverUpdated)
                else -> Unit // 本地不旧 → 跳过
            }
        }

        // 5) 更新游标与展示时间。
        result["new_sync_timestamp"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            settingsStore.setSyncCursorIso(it)
        }
        settingsStore.setLastSyncAt(System.currentTimeMillis())
        return true
    }
}
