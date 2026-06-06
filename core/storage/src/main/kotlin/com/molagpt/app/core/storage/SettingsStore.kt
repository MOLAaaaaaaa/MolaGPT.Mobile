package com.molagpt.app.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "mola_settings")

private const val DEFAULT_MODEL_ID = "auto"

/** 用户设置聚合：一次拿全，避免聊天 VM 订阅多个流。 */
data class AppSettings(
    val themeMode: String = "auto", // auto | light | dark
    val defaultModel: String? = DEFAULT_MODEL_ID,
    val throttleMs: Long = 16L,
    val enterToSend: Boolean = false,
    val temperature: Double = 0.7,
    val useThinking: Boolean = false,
    val reasoningEffort: String = "medium",
    val toolNetwork: Boolean = false,
    val toolSteel: Boolean = false,
    val toolCode: Boolean = true,
    /** 云同步开关（个人中心）。 */
    val cloudSyncEnabled: Boolean = false,
    /** MolaGPT Tracks（个性化记忆）开关。 */
    val tracksEnabled: Boolean = false,
    /** 上次云同步时间戳（ms，0=从未）。 */
    val lastSyncAt: Long = 0L,
    /** 后台对话完成通知开关（个人中心）。 */
    val completionNotify: Boolean = true,
)

/** DataStore 设置存储。 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME] ?: "auto",
            defaultModel = p[Keys.DEFAULT_MODEL] ?: DEFAULT_MODEL_ID,
            throttleMs = p[Keys.THROTTLE_MS] ?: 16L,
            enterToSend = p[Keys.ENTER_TO_SEND] ?: false,
            temperature = p[Keys.TEMPERATURE] ?: 0.7,
            useThinking = p[Keys.USE_THINKING] ?: false,
            reasoningEffort = p[Keys.REASONING_EFFORT] ?: "medium",
            toolNetwork = p[Keys.TOOL_NETWORK] ?: false,
            toolSteel = p[Keys.TOOL_STEEL] ?: false,
            toolCode = p[Keys.TOOL_CODE] ?: true,
            cloudSyncEnabled = p[Keys.CLOUD_SYNC] ?: false,
            tracksEnabled = p[Keys.TRACKS] ?: false,
            lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0L,
            completionNotify = p[Keys.COMPLETION_NOTIFY] ?: true,
        )
    }

    suspend fun setThemeMode(v: String) = edit { it[Keys.THEME] = v }
    suspend fun setDefaultModel(v: String?) = edit { if (v == null) it.remove(Keys.DEFAULT_MODEL) else it[Keys.DEFAULT_MODEL] = v }
    suspend fun setThrottleMs(v: Long) = edit { it[Keys.THROTTLE_MS] = v }
    suspend fun setEnterToSend(v: Boolean) = edit { it[Keys.ENTER_TO_SEND] = v }
    suspend fun setTemperature(v: Double) = edit { it[Keys.TEMPERATURE] = v }
    suspend fun setUseThinking(v: Boolean) = edit { it[Keys.USE_THINKING] = v }
    suspend fun setReasoningEffort(v: String) = edit { it[Keys.REASONING_EFFORT] = v }
    suspend fun setTools(network: Boolean, steel: Boolean, code: Boolean) = edit {
        it[Keys.TOOL_NETWORK] = network
        it[Keys.TOOL_STEEL] = steel
        it[Keys.TOOL_CODE] = code
    }
    suspend fun setCloudSyncEnabled(v: Boolean) = edit { it[Keys.CLOUD_SYNC] = v }
    suspend fun setTracksEnabled(v: Boolean) = edit { it[Keys.TRACKS] = v }
    suspend fun setLastSyncAt(v: Long) = edit { it[Keys.LAST_SYNC_AT] = v }
    suspend fun setCompletionNotify(v: Boolean) = edit { it[Keys.COMPLETION_NOTIFY] = v }

    /** 云同步游标（服务端 ISO last_sync_timestamp）；与展示用的 [lastSyncAt] 分开存。 */
    suspend fun syncCursorIso(): String =
        context.settingsDataStore.data.map { it[Keys.SYNC_CURSOR] ?: SyncMapper.EPOCH_ISO }.first()

    suspend fun setSyncCursorIso(v: String) = edit { it[Keys.SYNC_CURSOR] = v }

    /** 登出/删除云端数据后重置游标，下次按首次全量同步处理。 */
    suspend fun resetSyncCursor() = edit { it.remove(Keys.SYNC_CURSOR) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val THROTTLE_MS = longPreferencesKey("throttle_ms")
        val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val USE_THINKING = booleanPreferencesKey("use_thinking")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val TOOL_NETWORK = booleanPreferencesKey("tool_network")
        val TOOL_STEEL = booleanPreferencesKey("tool_steel")
        val TOOL_CODE = booleanPreferencesKey("tool_code")
        val CLOUD_SYNC = booleanPreferencesKey("cloud_sync_enabled")
        val TRACKS = booleanPreferencesKey("tracks_enabled")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val SYNC_CURSOR = stringPreferencesKey("sync_cursor_iso")
        val COMPLETION_NOTIFY = booleanPreferencesKey("completion_notify")
    }
}
