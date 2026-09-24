package com.molagpt.app.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ResponseRegexRule
import com.molagpt.app.core.model.ResponseRegexRules
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
    /** 默认模型阵营；null 表示旧数据，由 [defaultModel] + 本地 BYOK 列表反查。 */
    val defaultProviderKind: ProviderKind? = null,
    /** 默认 BYOK 的 providerId；MolaGPT / 未设置时为 null。 */
    val defaultProviderId: String? = null,
    val throttleMs: Long = 16L,
    val enterToSend: Boolean = false,
    /** 是否在聊天页顶栏显示 Agent 控制快捷按钮。 */
    val showAgentControlShortcut: Boolean = false,
    /** 是否在聊天页顶栏显示图像工作台快捷按钮。 */
    val showImageWorkbenchShortcut: Boolean = false,
    val temperature: Double = 0.7,
    val useThinking: Boolean = false,
    val reasoningEffort: String = "medium",
    val toolNetwork: Boolean = true,
    val toolSteel: Boolean = true,
    val toolCode: Boolean = true,
    val byokMcpServers: List<ByokMcpServer> = emptyList(),
    /** 联网搜索服务商 id（duckduckgo/tavily/exa）；key 单独经 CredentialStore 加密存储。 */
    val webSearchProvider: String = "duckduckgo",
    /** 联网搜索结果数量上限（1..10）。 */
    val webSearchMaxResults: Int = 6,
    /** 外挂视觉：当前模型不支持视觉时，代理到此视觉模型。 */
    val visionProxyEnabled: Boolean = false,
    /** 视觉代理目标 "<providerId>::<modelId>"。 */
    val visionProxyModelKey: String? = null,
    /** 图像生成：支持工具调用的 BYOK 模型可调用此图像服务。 */
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
    /** 自动会话标题：首轮回答完成后让模型给会话起名（消耗用户自己的额度）。 */
    val autoTitleEnabled: Boolean = true,
    /** 标题模型 "<providerId>::<modelId>"；null = 跟随当前对话模型。 */
    val titleModelKey: String? = null,
    /** 云同步开关（个人中心）。 */
    val cloudSyncEnabled: Boolean = false,
    /** MolaGPT Tracks（个性化记忆）开关。 */
    val tracksEnabled: Boolean = false,
    /**
     * BYOK 本地记忆总开关。默认关闭——开启后记忆会随对话发给用户自己配置的 API 服务，
     * 这一步必须由用户主动做出。下面三项子功能都从属于它。
     */
    val byokMemoryMasterEnabled: Boolean = false,
    /**
     * 记忆注入：对话时把已记住的信息带进 system 提示。
     *
     * 三项子功能默认都开：隐私上的那道门是总开关，用户主动打开总开关之后，
     * 再让他逐个去找哪个子项没开才生效，只会让功能看起来是坏的。
     */
    val byokMemoryEnabled: Boolean = true,
    /** 主动回忆：允许 BYOK 模型检索本机历史对话。 */
    val byokConversationRecallEnabled: Boolean = true,
    /** 自动学习：按窗口整理新记忆。未选择整理模型时不运行，因此开着也不会凭空产生用量。 */
    val byokMemoryAutoLearn: Boolean = true,
    /**
     * 整理范围。false = 省 token（每个会话首次整理只读最后若干条）；
     * true = 完整（不截首窗，分批把所有没整理过的内容读完）。
     */
    val byokMemoryFullScan: Boolean = false,
    /** 记忆整理模型 "<providerId>::<modelId>"；null = 未选择，自动学习不运行（不回退到会话模型）。 */
    val byokMemoryModelKey: String? = null,
    /** 记忆块的 token 预算上限。超出的条目留在库里但本轮不注入。 */
    val byokMemoryBudgetTokens: Int = 2000,
    /**
     * 允许把密钥、证件、支付号码这类内容写进记忆。默认关闭。
     *
     * 记忆一旦写入就会随后续每一轮对话发给 API 服务，凭据混进去等于反复外发；
     * 但确有用户要记住账号一类的信息，所以给开关而不是一刀切。
     */
    val byokMemoryAllowSensitive: Boolean = false,
    /** 「清除全部记忆」的时间点（ms）。早于它的消息不再被自动整理重新扫描。 */
    val byokMemoryResetAt: Long = 0L,
    /** 上次记忆整理的时间点（ms，0=从未）。时间阈值据此判断，也用于记忆页展示。 */
    val byokMemoryLastConsolidatedAt: Long = 0L,
    /** 上次云同步时间戳（ms，0=从未）。 */
    val lastSyncAt: Long = 0L,
    /** 后台对话完成通知开关（个人中心）。 */
    val completionNotify: Boolean = true,
    /** 回答后处理总开关：关闭后一条规则都不跑，模型写什么就存什么。 */
    val responsePostProcessingEnabled: Boolean = true,
    /** 后处理规则，按列表顺序依次施加。未配置过时是内置的中文标点两条。 */
    val responseRegexRules: List<ResponseRegexRule> = ResponseRegexRules.DEFAULTS,
    /** 可视化回答：给 BYOK 模型注入内嵌组件与网页的输出协议。长期偏好，不是每轮的模式。 */
    val visualAnswersEnabled: Boolean = true,
    /** 回答里的整页 HTML 显示成可运行的卡片；关闭后显示为代码块，标题栏仍可运行。 */
    val htmlAsCard: Boolean = true,
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
            defaultProviderKind = p[Keys.DEFAULT_PROVIDER_KIND]?.let { raw ->
                runCatching { ProviderKind.valueOf(raw) }.getOrNull()
            },
            defaultProviderId = p[Keys.DEFAULT_PROVIDER_ID],
            throttleMs = p[Keys.THROTTLE_MS] ?: 16L,
            enterToSend = p[Keys.ENTER_TO_SEND] ?: false,
            showAgentControlShortcut = p[Keys.SHOW_AGENT_CONTROL_SHORTCUT] ?: false,
            showImageWorkbenchShortcut = p[Keys.SHOW_IMAGE_WORKBENCH_SHORTCUT] ?: false,
            temperature = p[Keys.TEMPERATURE] ?: 0.7,
            useThinking = p[Keys.USE_THINKING] ?: false,
            reasoningEffort = p[Keys.REASONING_EFFORT] ?: "medium",
            toolNetwork = p[Keys.TOOL_NETWORK] ?: true,
            toolSteel = p[Keys.TOOL_STEEL] ?: true,
            toolCode = p[Keys.TOOL_CODE] ?: true,
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
            autoTitleEnabled = p[Keys.AUTO_TITLE_ENABLED] ?: true,
            titleModelKey = p[Keys.TITLE_MODEL_KEY],
            byokMemoryMasterEnabled = p[Keys.BYOK_MEMORY_MASTER] ?: false,
            byokMemoryEnabled = p[Keys.BYOK_MEMORY_ENABLED] ?: true,
            byokConversationRecallEnabled = p[Keys.BYOK_MEMORY_RECALL] ?: true,
            byokMemoryAutoLearn = p[Keys.BYOK_MEMORY_AUTO_LEARN] ?: true,
            byokMemoryFullScan = p[Keys.BYOK_MEMORY_FULL_SCAN] ?: false,
            byokMemoryModelKey = p[Keys.BYOK_MEMORY_MODEL_KEY],
            byokMemoryBudgetTokens = (p[Keys.BYOK_MEMORY_BUDGET] ?: 2000L).toInt(),
            byokMemoryAllowSensitive = p[Keys.BYOK_MEMORY_ALLOW_SENSITIVE] ?: false,
            byokMemoryResetAt = p[Keys.BYOK_MEMORY_RESET_AT] ?: 0L,
            byokMemoryLastConsolidatedAt = p[Keys.BYOK_MEMORY_LAST_CONSOLIDATED_AT] ?: 0L,
            cloudSyncEnabled = p[Keys.CLOUD_SYNC] ?: false,
            tracksEnabled = p[Keys.TRACKS] ?: false,
            lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0L,
            completionNotify = p[Keys.COMPLETION_NOTIFY] ?: true,
            responsePostProcessingEnabled = p[Keys.RESPONSE_POST_PROCESSING] ?: true,
            responseRegexRules = decodeRegexRules(p[Keys.RESPONSE_REGEX_RULES]),
            visualAnswersEnabled = p[Keys.VISUAL_ANSWERS] ?: true,
            htmlAsCard = p[Keys.HTML_AS_CARD] ?: true,
        )
    }

    suspend fun setThemeMode(v: String) = edit { it[Keys.THEME] = v }

    /** 写入默认模型及其阵营（新对话 / 冷启动按需拉模型会读这里）。 */
    suspend fun setDefaultModelSelection(
        modelId: String,
        providerKind: ProviderKind,
        providerId: String?,
    ) = edit {
        it[Keys.DEFAULT_MODEL] = modelId.trim().ifBlank { DEFAULT_MODEL_ID }
        it[Keys.DEFAULT_PROVIDER_KIND] = providerKind.name
        if (providerId.isNullOrBlank() || providerKind != ProviderKind.BYOK) {
            it.remove(Keys.DEFAULT_PROVIDER_ID)
        } else {
            it[Keys.DEFAULT_PROVIDER_ID] = providerId
        }
    }

    suspend fun setThrottleMs(v: Long) = edit { it[Keys.THROTTLE_MS] = v }
    suspend fun setEnterToSend(v: Boolean) = edit { it[Keys.ENTER_TO_SEND] = v }
    suspend fun setShowAgentControlShortcut(v: Boolean) = edit { it[Keys.SHOW_AGENT_CONTROL_SHORTCUT] = v }
    suspend fun setShowImageWorkbenchShortcut(v: Boolean) = edit { it[Keys.SHOW_IMAGE_WORKBENCH_SHORTCUT] = v }
    suspend fun setTemperature(v: Double) = edit { it[Keys.TEMPERATURE] = v }
    suspend fun setUseThinking(v: Boolean) = edit { it[Keys.USE_THINKING] = v }
    suspend fun setReasoningEffort(v: String) = edit { it[Keys.REASONING_EFFORT] = v }
    suspend fun setWebAccessTools(enabled: Boolean) = edit {
        it[Keys.TOOL_NETWORK] = enabled
        it[Keys.TOOL_STEEL] = enabled
    }
    suspend fun setCodeTool(enabled: Boolean) = edit { it[Keys.TOOL_CODE] = enabled }
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
    suspend fun setAutoTitle(enabled: Boolean, modelKey: String?) = edit {
        it[Keys.AUTO_TITLE_ENABLED] = enabled
        if (modelKey.isNullOrBlank()) it.remove(Keys.TITLE_MODEL_KEY) else it[Keys.TITLE_MODEL_KEY] = modelKey
    }
    suspend fun setByokMemoryMasterEnabled(v: Boolean) = edit { it[Keys.BYOK_MEMORY_MASTER] = v }
    suspend fun setByokMemoryEnabled(v: Boolean) = edit { it[Keys.BYOK_MEMORY_ENABLED] = v }
    suspend fun setByokMemoryFullScan(v: Boolean) = edit { it[Keys.BYOK_MEMORY_FULL_SCAN] = v }
    suspend fun setByokConversationRecallEnabled(v: Boolean) = edit { it[Keys.BYOK_MEMORY_RECALL] = v }
    suspend fun setByokMemoryAutoLearn(v: Boolean) = edit { it[Keys.BYOK_MEMORY_AUTO_LEARN] = v }
    suspend fun setByokMemoryModelKey(modelKey: String?) = edit {
        if (modelKey.isNullOrBlank()) it.remove(Keys.BYOK_MEMORY_MODEL_KEY) else it[Keys.BYOK_MEMORY_MODEL_KEY] = modelKey
    }

    /** 预算上限。范围由 UI 的档位约束，这里再 clamp 一次防止异常值让记忆块吃掉整个上下文。 */
    suspend fun setByokMemoryBudgetTokens(v: Int) = edit {
        it[Keys.BYOK_MEMORY_BUDGET] = v.coerceIn(200, 16_000).toLong()
    }

    /** 「清除全部记忆」时调用：记下新的学习起点，早于它的消息不再被重新处理。 */
    suspend fun setByokMemoryAllowSensitive(v: Boolean) = edit { it[Keys.BYOK_MEMORY_ALLOW_SENSITIVE] = v }

    suspend fun setByokMemoryResetAt(v: Long) = edit { it[Keys.BYOK_MEMORY_RESET_AT] = v }

    suspend fun setByokMemoryLastConsolidatedAt(v: Long) = edit { it[Keys.BYOK_MEMORY_LAST_CONSOLIDATED_AT] = v }

    suspend fun setCloudSyncEnabled(v: Boolean) = edit { it[Keys.CLOUD_SYNC] = v }
    suspend fun setTracksEnabled(v: Boolean) = edit { it[Keys.TRACKS] = v }
    suspend fun setLastSyncAt(v: Long) = edit { it[Keys.LAST_SYNC_AT] = v }
    suspend fun setCompletionNotify(v: Boolean) = edit { it[Keys.COMPLETION_NOTIFY] = v }

    suspend fun setResponsePostProcessingEnabled(v: Boolean) = edit { it[Keys.RESPONSE_POST_PROCESSING] = v }
    suspend fun setVisualAnswersEnabled(v: Boolean) = edit { it[Keys.VISUAL_ANSWERS] = v }
    suspend fun setHtmlAsCard(v: Boolean) = edit { it[Keys.HTML_AS_CARD] = v }

    /** 保存规则列表。空列表也要落盘：那是「我把规则都删了」，不能被当成「没配置过」回灌默认值。 */
    suspend fun setResponseRegexRules(rules: List<ResponseRegexRule>) = edit {
        it[Keys.RESPONSE_REGEX_RULES] = json.encodeToString(rules)
    }

    /** 恢复内置的中文标点两条：把键删掉即可，读取时自然回到默认。 */
    suspend fun resetResponseRegexRules() = edit { it.remove(Keys.RESPONSE_REGEX_RULES) }

    /** 云同步游标（服务端 ISO last_sync_timestamp）；与展示用的 [lastSyncAt] 分开存。 */
    suspend fun syncCursorIso(): String =
        context.settingsDataStore.data.map { it[Keys.SYNC_CURSOR] ?: SyncMapper.EPOCH_ISO }.first()

    suspend fun setSyncCursorIso(v: String) = edit { it[Keys.SYNC_CURSOR] = v }

    /** 登出/删除云端数据后重置游标，下次按首次全量同步处理。 */
    suspend fun resetSyncCursor() = edit { it.remove(Keys.SYNC_CURSOR) }

    /** 已弹过的运营消息 id（每个 id 只弹一次）；不进 [AppSettings] 流，避免无关订阅方重组。 */
    suspend fun seenOpsMessageIds(): Set<String> =
        context.settingsDataStore.data.map { it[Keys.SEEN_OPS_MESSAGE_IDS] ?: emptySet() }.first()

    suspend fun addSeenOpsMessageId(id: String) = edit {
        it[Keys.SEEN_OPS_MESSAGE_IDS] = (it[Keys.SEEN_OPS_MESSAGE_IDS] ?: emptySet()) + id
    }

    /** 服务端已下架的消息 id 顺带清理，防止已读集无限增长；重新上架即重新展示。 */
    suspend fun retainSeenOpsMessageIds(valid: Set<String>) = edit {
        it[Keys.SEEN_OPS_MESSAGE_IDS] = (it[Keys.SEEN_OPS_MESSAGE_IDS] ?: emptySet()) intersect valid
    }

    /** 已展示过的本地 Promo 卡 id（每个 id 只弹一次）；内置卡数量极少，加 50 上限防膨胀。 */
    suspend fun seenPromoIds(): Set<String> =
        context.settingsDataStore.data.map { it[Keys.SEEN_PROMO_IDS] ?: emptySet() }.first()

    suspend fun addSeenPromoId(id: String) = edit {
        it[Keys.SEEN_PROMO_IDS] = ((it[Keys.SEEN_PROMO_IDS] ?: emptySet()) + id).toList().takeLast(50).toSet()
    }

    /** 已点过"知道了"的命令失败 key（每个只弹一次）；不进 [AppSettings] 流，避免无关订阅方重组。 */
    suspend fun seenAgentCommandFailureKeys(): Set<String> =
        context.settingsDataStore.data.map { it[Keys.SEEN_AGENT_COMMAND_FAILURES] ?: emptySet() }.first()

    suspend fun addSeenAgentCommandFailureKey(key: String) = edit {
        it[Keys.SEEN_AGENT_COMMAND_FAILURES] =
            ((it[Keys.SEEN_AGENT_COMMAND_FAILURES] ?: emptySet()) + key).toList().takeLast(100).toSet()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun decodeMcpServers(raw: String?): List<ByokMcpServer> =
        raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<List<ByokMcpServer>>(it) }.getOrDefault(emptyList())
        }.orEmpty()

    /** 键不存在 = 从未配置过，回落到内置规则；存在（哪怕是空数组）就按用户存的来。 */
    private fun decodeRegexRules(raw: String?): List<ResponseRegexRule> =
        if (raw == null) {
            ResponseRegexRules.DEFAULTS
        } else {
            runCatching { json.decodeFromString<List<ResponseRegexRule>>(raw) }
                .map { rules ->
                    rules.map { rule ->
                        if (rule.description != null) rule
                        else ResponseRegexRules.DEFAULTS.firstOrNull {
                            it.id == rule.id && it.pattern == rule.pattern && it.replacement == rule.replacement
                        }?.let { rule.copy(description = it.description) } ?: rule
                    }
                }
                .getOrDefault(ResponseRegexRules.DEFAULTS)
        }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_PROVIDER_KIND = stringPreferencesKey("default_provider_kind")
        val DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        val THROTTLE_MS = longPreferencesKey("throttle_ms")
        val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        val SHOW_AGENT_CONTROL_SHORTCUT = booleanPreferencesKey("show_agent_control_shortcut")
        val SHOW_IMAGE_WORKBENCH_SHORTCUT = booleanPreferencesKey("show_image_workbench_shortcut")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val USE_THINKING = booleanPreferencesKey("use_thinking")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val TOOL_NETWORK = booleanPreferencesKey("tool_network")
        val TOOL_STEEL = booleanPreferencesKey("tool_steel")
        val TOOL_CODE = booleanPreferencesKey("tool_code")
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
        val AUTO_TITLE_ENABLED = booleanPreferencesKey("auto_title_enabled")
        val TITLE_MODEL_KEY = stringPreferencesKey("title_model_key")
        val BYOK_MEMORY_MASTER = booleanPreferencesKey("byok_memory_master_enabled")
        val BYOK_MEMORY_ENABLED = booleanPreferencesKey("byok_memory_enabled")
        val BYOK_MEMORY_FULL_SCAN = booleanPreferencesKey("byok_memory_full_scan")
        val BYOK_MEMORY_RECALL = booleanPreferencesKey("byok_conversation_recall_enabled")
        val BYOK_MEMORY_AUTO_LEARN = booleanPreferencesKey("byok_memory_auto_learn")
        val BYOK_MEMORY_MODEL_KEY = stringPreferencesKey("byok_memory_model_key")
        val BYOK_MEMORY_BUDGET = longPreferencesKey("byok_memory_budget_tokens")
        val BYOK_MEMORY_ALLOW_SENSITIVE = booleanPreferencesKey("byok_memory_allow_sensitive")
        val BYOK_MEMORY_LAST_CONSOLIDATED_AT = longPreferencesKey("byok_memory_last_consolidated_at")
        val BYOK_MEMORY_RESET_AT = longPreferencesKey("byok_memory_reset_at")
        val CLOUD_SYNC = booleanPreferencesKey("cloud_sync_enabled")
        val TRACKS = booleanPreferencesKey("tracks_enabled")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val SYNC_CURSOR = stringPreferencesKey("sync_cursor_iso")
        val COMPLETION_NOTIFY = booleanPreferencesKey("completion_notify")
        val RESPONSE_POST_PROCESSING = booleanPreferencesKey("response_post_processing_enabled")
        val VISUAL_ANSWERS = booleanPreferencesKey("visual_answers_enabled")
        val HTML_AS_CARD = booleanPreferencesKey("html_as_card")
        val RESPONSE_REGEX_RULES = stringPreferencesKey("response_regex_rules")
        val SEEN_OPS_MESSAGE_IDS = stringSetPreferencesKey("seen_ops_message_ids")
        val SEEN_PROMO_IDS = stringSetPreferencesKey("seen_promo_ids")
        val SEEN_AGENT_COMMAND_FAILURES = stringSetPreferencesKey("seen_agent_command_failure_keys")
    }
}
