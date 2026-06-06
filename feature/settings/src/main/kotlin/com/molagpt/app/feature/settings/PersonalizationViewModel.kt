package com.molagpt.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.ConversationStyle
import com.molagpt.app.core.model.Insight
import com.molagpt.app.core.model.InsightRating
import com.molagpt.app.core.model.StylePreferences
import com.molagpt.app.core.network.SyncApi
import com.molagpt.app.core.network.UserDataApi
import com.molagpt.app.core.storage.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个性化回答管理。聚合两类数据：
 * - **人格洞察 (insights)**：拉取 / 评分 / 修正 / 删除 / 清除，对接 [UserDataApi]（user_data_manager.php）；
 * - **对话风格偏好 (style_preferences)**：多选风格 + 自定义指令；
 * 以及**总开关**：本地 [SettingsStore.tracksEnabled] 为 UI 真相，写入时经 [SyncApi.updateSetting] 同步服务端
 * (`personalized_memory_enabled`)，进入时按 [accountStatusLoader] 读回服务端值校正。
 *
 * 所有写操作走**乐观更新**：先改本地 state 立即反馈，网络失败再回滚并提示。鉴权用持久 JWT（[jwtProvider]）。
 */
class PersonalizationViewModel(
    private val userDataApi: UserDataApi,
    private val syncApi: SyncApi,
    private val store: SettingsStore,
    private val jwtProvider: () -> String?,
    /** 读回服务端开关初值（status.php → AccountStatus.personalizedMemoryEnabled）。 */
    private val accountStatusLoader: suspend () -> AccountStatus?,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    /** 总开关：本地 DataStore 为 UI 真相。 */
    val enabled: StateFlow<Boolean> =
        store.settings.map { it.tracksEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _insights = MutableStateFlow<List<Insight>>(emptyList())
    val insights: StateFlow<List<Insight>> = _insights.asStateFlow()

    /** 用户对各洞察的评分（仅本地高亮选中态，key=insight.id）。 */
    private val _ratings = MutableStateFlow<Map<String, InsightRating>>(emptyMap())
    val ratings: StateFlow<Map<String, InsightRating>> = _ratings.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _togglingMaster = MutableStateFlow(false)
    val togglingMaster: StateFlow<Boolean> = _togglingMaster.asStateFlow()

    private val _style = MutableStateFlow(StylePreferences())
    val style: StateFlow<StylePreferences> = _style.asStateFlow()
    private val _styleDirty = MutableStateFlow(false)
    val styleDirty: StateFlow<Boolean> = _styleDirty.asStateFlow()
    private val _savingStyle = MutableStateFlow(false)
    val savingStyle: StateFlow<Boolean> = _savingStyle.asStateFlow()

    /** 一次性提示（Snackbar）。UI 显示后调 [clearMessage]。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { load() }

    fun clearMessage() { _message.value = null }

    /** 首次/重进加载：并行拉洞察 + 风格 + 读回开关。 */
    fun load() {
        val jwt = jwt() ?: run { _loading.value = false; return }
        viewModelScope.launch {
            _loading.value = true
            withContext(dispatchers.io) {
                val insightsJob = async { userDataApi.getInsights(jwt) }
                val styleJob = async { userDataApi.getStylePreferences(jwt) }
                val statusJob = async { runCatching { accountStatusLoader() }.getOrNull() }

                insightsJob.await()?.let { _insights.value = it }
                styleJob.await()?.let { _style.value = it; _styleDirty.value = false }
                // 服务端开关为准：与本地不一致则校正本地（多设备一致）。
                statusJob.await()?.personalizedMemoryEnabled?.let { server ->
                    if (server != enabled.value) store.setTracksEnabled(server)
                }
            }
            _loading.value = false
        }
    }

    /** 手动刷新洞察（保留当前风格编辑态）。 */
    fun refresh() {
        val jwt = jwt() ?: return
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val fresh = withContext(dispatchers.io) { userDataApi.getInsights(jwt) }
            if (fresh != null) { _insights.value = fresh; _ratings.value = emptyMap() }
            else _message.value = "刷新失败，请稍后再试"
            _refreshing.value = false
        }
    }

    /** 总开关：乐观写本地 + 同步服务端，失败回滚。 */
    fun setEnabled(value: Boolean) {
        val jwt = jwt() ?: run { _message.value = "请先登录"; return }
        viewModelScope.launch {
            store.setTracksEnabled(value)
            _togglingMaster.value = true
            val ok = withContext(dispatchers.io) {
                syncApi.updateSetting(jwt, "personalized_memory_enabled", value)
            }
            if (!ok) {
                store.setTracksEnabled(!value)
                _message.value = "设置同步失败，请稍后再试"
            }
            _togglingMaster.value = false
        }
    }

    /** 评分：乐观把本地置信度调到目标值，失败回滚；成功后触发后台重分析。 */
    fun rate(insightId: String, rating: InsightRating) {
        val jwt = jwt() ?: return
        val snapshot = _insights.value
        val target = targetConfidence(rating)
        _insights.value = snapshot.map {
            if (it.id == insightId) it.copy(confidence = target, lastReinforcedTs = nowSeconds()) else it
        }
        _ratings.value = _ratings.value + (insightId to rating)
        viewModelScope.launch {
            val ok = withContext(dispatchers.io) { userDataApi.rateInsight(jwt, insightId, rating) }
            if (ok) {
                withContext(dispatchers.io) { userDataApi.triggerEvolution(jwt) }
                _message.value = "已记录反馈 · MolaGPT 将据此调整"
            } else {
                _insights.value = snapshot
                _ratings.value = _ratings.value - insightId
                _message.value = "评分提交失败"
            }
        }
    }

    /** 修正印象文本：乐观更新，失败回滚。 */
    fun updateText(insightId: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) { _message.value = "印象内容不能为空"; return }
        val jwt = jwt() ?: return
        val snapshot = _insights.value
        _insights.value = snapshot.map { if (it.id == insightId) it.copy(text = text) else it }
        viewModelScope.launch {
            val ok = withContext(dispatchers.io) { userDataApi.updateInsight(jwt, insightId, text) }
            if (ok) _message.value = "印象已更新"
            else { _insights.value = snapshot; _message.value = "更新失败" }
        }
    }

    /** 删除单条印象：乐观移除，失败恢复。 */
    fun delete(insightId: String) {
        val jwt = jwt() ?: return
        val snapshot = _insights.value
        _insights.value = snapshot.filterNot { it.id == insightId }
        viewModelScope.launch {
            val ok = withContext(dispatchers.io) { userDataApi.deleteInsight(jwt, insightId) }
            if (ok) _message.value = "已删除该印象"
            else { _insights.value = snapshot; _message.value = "删除失败" }
        }
    }

    /** 清除全部人格洞察。 */
    fun clearInsights() {
        val jwt = jwt() ?: return
        val snapshot = _insights.value
        _insights.value = emptyList()
        viewModelScope.launch {
            val ok = withContext(dispatchers.io) { userDataApi.deleteAllInsights(jwt) }
            if (ok) _message.value = "已清除全部人格洞察"
            else { _insights.value = snapshot; _message.value = "清除失败" }
        }
    }

    /** 清除全部对话记忆（长期事件记忆；不影响上方洞察列表展示）。 */
    fun clearMemories() {
        val jwt = jwt() ?: return
        viewModelScope.launch {
            val ok = withContext(dispatchers.io) { userDataApi.deleteAllEventMemories(jwt) }
            _message.value = if (ok) "已清除全部对话记忆" else "清除失败"
        }
    }

    // —— 对话风格 ——

    fun toggleStyle(s: ConversationStyle) {
        _style.value = _style.value.toggled(s)
        _styleDirty.value = true
    }

    fun setCustomInstruction(text: String) {
        val clamped = text.take(StylePreferences.CUSTOM_INSTRUCTION_MAX)
        _style.value = _style.value.copy(customInstruction = clamped)
        _styleDirty.value = true
    }

    fun saveStyle() {
        val jwt = jwt() ?: run { _message.value = "请先登录"; return }
        if (_savingStyle.value) return
        viewModelScope.launch {
            _savingStyle.value = true
            val ok = withContext(dispatchers.io) { userDataApi.updateStylePreferences(jwt, _style.value) }
            if (ok) { _styleDirty.value = false; _message.value = "风格偏好已保存" }
            else _message.value = "保存失败，请稍后再试"
            _savingStyle.value = false
        }
    }

    private fun jwt(): String? = jwtProvider()?.takeIf { it.isNotBlank() }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    /** 评分到目标置信度的服务端契约映射。 */
    private fun targetConfidence(r: InsightRating): Double = when (r) {
        InsightRating.STRONG_AGREE -> 0.95
        InsightRating.AGREE -> 0.9
        InsightRating.SOMEWHAT -> 0.75
        InsightRating.DISAGREE -> 0.3
        InsightRating.STRONG_DISAGREE -> 0.05
    }
}
