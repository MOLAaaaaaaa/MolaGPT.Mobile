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
import com.molagpt.app.core.model.ByokMcpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val byokToolMcp: Boolean = false,
    val byokToolVision: Boolean = false,
    val byokToolImage: Boolean = false,
    val byokMcpServers: List<ByokMcpServer> = emptyList(),
    /** 联网搜索服务商 id（duckduckgo/tavily/exa）；key 单独经 CredentialStore 加密存储。 */
    val webSearchProvider: String = "duckduckgo",
    /** 联网搜索结果数量上限（1..10）。 */
    val webSearchMaxResults: Int = 6,
    /** 外挂视觉：当前模型不支持视觉时，代理到此视觉模型。总开关沿用 byokToolVision，下面是目标/参数。 */
    val visionProxyEnabled: Boolean = false,
    /** 视觉代理目标 "<providerId>::<modelId>"。 */
    val visionProxyModelKey: String? = null,
    /** 图像生成：支持工具调用的 BYOK 模型可调用此图像服务。总开关沿用 byokToolImage。 */
    val imageGenEnabled: Boolean = false,
    /** 图像生成目标 "<providerId>::<modelId>"。 */
    val imageGenModelKey: String? = null,
    /** 出图尺寸：chat-image 格式为 image_size 档位（0.5K/1K/2K/4K）；OPENAI_IMAGES 格式忽略此值。 */
    val imageGenSize: String = "1K",
    val imageGenStyle: String? = null,
    /** 出图宽高比（image_config.aspect_ratio），仅 chat-image 格式生效。 */
    val imageGenAspectRatio: String = "1:1",
    /** 出图推理开关（仅 GPT-5 Image / Gemini 3 Image 系列生效）。 */
    val imageGenReasoning: Boolean = false,
    /** 出图推理强度（reasoning.effort）。 */
    val imageGenReasoningEffort: String = "medium",
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
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

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
            byokToolMcp = p[Keys.BYOK_TOOL_MCP] ?: false,
            byokToolVision = p[Keys.BYOK_TOOL_VISION] ?: false,
            byokToolImage = p[Keys.BYOK_TOOL_IMAGE] ?: false,
            byokMcpServers = decodeMcpServers(p[Keys.BYOK_MCP_SERVERS]),
            webSearchProvider = p[Keys.WEB_SEARCH_PROVIDER] ?: "duckduckgo",
            webSearchMaxResults = (p[Keys.WEB_SEARCH_MAX_RESULTS] ?: 6L).toInt(),
            visionProxyEnabled = p[Keys.VISION_PROXY_ENABLED] ?: false,
            visionProxyModelKey = p[Keys.VISION_PROXY_MODEL_KEY],
            imageGenEnabled = p[Keys.IMAGE_GEN_ENABLED] ?: false,
            imageGenModelKey = p[Keys.IMAGE_GEN_MODEL_KEY],
            imageGenSize = (p[Keys.IMAGE_GEN_SIZE] ?: "1K").let { v ->
                // 迁移：旧版存的是 "1024x1024" 等 WxH，chat-image 出图需要档位（0.5K/1K/2K/4K）。
                if (v in setOf("0.5K", "1K", "2K", "4K")) v else "1K"
            },
            imageGenStyle = p[Keys.IMAGE_GEN_STYLE],
            imageGenAspectRatio = p[Keys.IMAGE_GEN_ASPECT_RATIO] ?: "1:1",
            imageGenReasoning = p[Keys.IMAGE_GEN_REASONING] ?: false,
            imageGenReasoningEffort = p[Keys.IMAGE_GEN_REASONING_EFFORT] ?: "medium",
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
    suspend fun setByokTools(mcp: Boolean, vision: Boolean, image: Boolean) = edit {
        it[Keys.BYOK_TOOL_MCP] = mcp
        it[Keys.BYOK_TOOL_VISION] = vision
        it[Keys.BYOK_TOOL_IMAGE] = image
    }
    suspend fun setByokMcpServers(servers: List<ByokMcpServer>) = edit {
        it[Keys.BYOK_MCP_SERVERS] = json.encodeToString(servers)
    }
    suspend fun setWebSearchProvider(provider: String) = edit { it[Keys.WEB_SEARCH_PROVIDER] = provider }
    suspend fun setWebSearchMaxResults(max: Int) = edit { it[Keys.WEB_SEARCH_MAX_RESULTS] = max.coerceIn(1, 10).toLong() }
    suspend fun setVisionProxy(enabled: Boolean, modelKey: String?) = edit {
        it[Keys.VISION_PROXY_ENABLED] = enabled
        if (modelKey.isNullOrBlank()) it.remove(Keys.VISION_PROXY_MODEL_KEY) else it[Keys.VISION_PROXY_MODEL_KEY] = modelKey
    }
    suspend fun setImageGenConfig(
        enabled: Boolean,
        modelKey: String?,
        size: String,
        style: String?,
        aspectRatio: String = "1:1",
        reasoning: Boolean = false,
        reasoningEffort: String = "medium",
    ) = edit {
        it[Keys.IMAGE_GEN_ENABLED] = enabled
        if (modelKey.isNullOrBlank()) it.remove(Keys.IMAGE_GEN_MODEL_KEY) else it[Keys.IMAGE_GEN_MODEL_KEY] = modelKey
        it[Keys.IMAGE_GEN_SIZE] = size.ifBlank { "1K" }
        if (style.isNullOrBlank()) it.remove(Keys.IMAGE_GEN_STYLE) else it[Keys.IMAGE_GEN_STYLE] = style
        it[Keys.IMAGE_GEN_ASPECT_RATIO] = aspectRatio.ifBlank { "1:1" }
        it[Keys.IMAGE_GEN_REASONING] = reasoning
        it[Keys.IMAGE_GEN_REASONING_EFFORT] = reasoningEffort.ifBlank { "medium" }
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

    private fun decodeMcpServers(raw: String?): List<ByokMcpServer> =
        raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<List<ByokMcpServer>>(it) }.getOrDefault(emptyList())
        }.orEmpty()

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
        val BYOK_TOOL_MCP = booleanPreferencesKey("byok_tool_mcp")
        val BYOK_TOOL_VISION = booleanPreferencesKey("byok_tool_vision")
        val BYOK_TOOL_IMAGE = booleanPreferencesKey("byok_tool_image")
        val BYOK_MCP_SERVERS = stringPreferencesKey("byok_mcp_servers")
        val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        val WEB_SEARCH_MAX_RESULTS = longPreferencesKey("web_search_max_results")
        val VISION_PROXY_ENABLED = booleanPreferencesKey("vision_proxy_enabled")
        val VISION_PROXY_MODEL_KEY = stringPreferencesKey("vision_proxy_model_key")
        val IMAGE_GEN_ENABLED = booleanPreferencesKey("image_gen_enabled")
        val IMAGE_GEN_MODEL_KEY = stringPreferencesKey("image_gen_model_key")
        val IMAGE_GEN_SIZE = stringPreferencesKey("image_gen_size")
        val IMAGE_GEN_STYLE = stringPreferencesKey("image_gen_style")
        val IMAGE_GEN_ASPECT_RATIO = stringPreferencesKey("image_gen_aspect_ratio")
        val IMAGE_GEN_REASONING = booleanPreferencesKey("image_gen_reasoning")
        val IMAGE_GEN_REASONING_EFFORT = stringPreferencesKey("image_gen_reasoning_effort")
        val CLOUD_SYNC = booleanPreferencesKey("cloud_sync_enabled")
        val TRACKS = booleanPreferencesKey("tracks_enabled")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val SYNC_CURSOR = stringPreferencesKey("sync_cursor_iso")
        val COMPLETION_NOTIFY = booleanPreferencesKey("completion_notify")
    }
}
