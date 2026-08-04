package com.molagpt.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.ConversationStyle
import com.molagpt.app.core.model.MemoryCandidate
import com.molagpt.app.core.model.MemoryEntry
import com.molagpt.app.core.model.MemoryProjection
import com.molagpt.app.core.model.MemoryRating
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.model.StylePreferences
import com.molagpt.app.core.network.ApiResult
import com.molagpt.app.core.network.UserDataApi
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.TracksToggle
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
 * 记忆中心（MolaGPT Tracks）。聚合三类数据：
 * - **长期记忆条目 (memory_entries)**：分节展示 / 评分 / 修正 / 删除 / 手动添加，对接 [UserDataApi]；
 * - **待确认候选 (candidates)**：夜间管线抽出但证据不足的事实，由用户裁决记住或忽略；
 * - **对话风格偏好 (style_preferences)**：多选风格 + 自定义指令；
 * 以及**总开关**：写入收敛在 [TracksToggle]（与账户页共用），进入时按 [accountStatusLoader] 读回服务端值校正。
 *
 * 所有写操作走**乐观更新**：先改本地 state 立即反馈，网络失败再回滚并提示。
 * 失败提示优先用服务端返回的原因——内容护栏会以 422 说明"为什么这条不能记"，
 * 笼统的"保存失败"解释不了。鉴权用持久 JWT（[jwtProvider]）。
 */
class PersonalizationViewModel(
    private val userDataApi: UserDataApi,
    private val tracksToggle: TracksToggle,
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

    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    /** 按服务端固定分节顺序分组（空分节不出现）。 */
    val entriesBySection: StateFlow<List<Pair<MemorySection, List<MemoryEntry>>>> =
        _entries.map { list ->
            MemorySection.entries.mapNotNull { section ->
                list.filter { MemorySection.fromWire(it.section) == section }
                    .takeIf { it.isNotEmpty() }
                    ?.let { section to it }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _candidates = MutableStateFlow<List<MemoryCandidate>>(emptyList())
    val candidates: StateFlow<List<MemoryCandidate>> = _candidates.asStateFlow()

    /** MEMORY.md 投影统计（注入了几条、占了多少 token 预算）。 */
    private val _projection = MutableStateFlow(MemoryProjection())
    val projection: StateFlow<MemoryProjection> = _projection.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _togglingMaster = MutableStateFlow(false)
    val togglingMaster: StateFlow<Boolean> = _togglingMaster.asStateFlow()
    private val _addingEntry = MutableStateFlow(false)
    val addingEntry: StateFlow<Boolean> = _addingEntry.asStateFlow()

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

    /** 首次/重进加载：并行拉记忆条目 + 候选 + 风格 + 读回开关。 */
    fun load() {
        val jwt = jwt() ?: run { _loading.value = false; return }
        viewModelScope.launch {
            _loading.value = true
            withContext(dispatchers.io) {
                val entriesJob = async { userDataApi.getMemoryEntries(jwt) }
                val candidatesJob = async { userDataApi.getCandidates(jwt) }
                val styleJob = async { userDataApi.getStylePreferences(jwt) }
                val statusJob = async { runCatching { accountStatusLoader() }.getOrNull() }

                entriesJob.await()?.let {
                    _entries.value = it.entries
                    _projection.value = it.projection
                }
                candidatesJob.await()?.let { _candidates.value = it }
                styleJob.await()?.let { _style.value = it; _styleDirty.value = false }
                // 服务端开关为准：与本地不一致则校正本地（多设备一致）。
                statusJob.await()?.personalizedMemoryEnabled?.let { server ->
                    if (server != enabled.value) store.setTracksEnabled(server)
                }
            }
            _loading.value = false
        }
    }

    /** 手动刷新记忆条目与候选（保留当前风格编辑态）。 */
    fun refresh() {
        val jwt = jwt() ?: return
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val (fresh, cands) = withContext(dispatchers.io) {
                val e = async { userDataApi.getMemoryEntries(jwt) }
                val c = async { userDataApi.getCandidates(jwt) }
                e.await() to c.await()
            }
            if (fresh != null) {
                _entries.value = fresh.entries
                _projection.value = fresh.projection
            } else {
                _message.value = "刷新失败，请稍后再试"
            }
            cands?.let { _candidates.value = it }
            _refreshing.value = false
        }
    }

    /** 总开关：写入委托 [TracksToggle]（乐观 + 回滚都在里面），这里只负责提示。 */
    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            _togglingMaster.value = true
            val result = withContext(dispatchers.io) { tracksToggle.setTracksEnabled(value) }
            result.message?.let { _message.value = it }
            _togglingMaster.value = false
        }
    }

    // —— 记忆条目 ——

    /**
     * 评分：乐观按增量调整本地置信度，失败回滚。
     * [rating] 为 null 表示撤销（再次点击已选中项）。
     */
    fun rateEntry(entryId: String, rating: MemoryRating?) {
        val jwt = jwt() ?: return
        val snapshot = _entries.value
        _entries.value = snapshot.map { entry ->
            if (entry.id != entryId) return@map entry
            entry.copy(
                confidence = predictedConfidence(entry, rating),
                userRating = rating,
                // 认可 = "这条现在仍然成立"，服务端会刷新 last_ts 抵抗衰减，本地同步预估。
                lastTs = if (rating == MemoryRating.AGREE) nowSeconds() else entry.lastTs,
            )
        }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { userDataApi.rateMemoryEntry(jwt, entryId, rating) }
            if (result.succeeded) {
                withContext(dispatchers.io) { userDataApi.triggerEvolution(jwt) }
                _message.value = if (rating == null) "已取消评分" else "已记录反馈 · MolaGPT 将据此调整"
            } else {
                _entries.value = snapshot
                _message.value = result.errorOr("评分提交失败")
            }
        }
    }

    /** 修正记忆文本：乐观更新，失败回滚。 */
    fun updateEntry(entryId: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) { _message.value = "记忆内容不能为空"; return }
        if (text.length > MEMORY_TEXT_MAX) { _message.value = "记忆内容不能超过 $MEMORY_TEXT_MAX 字"; return }
        val jwt = jwt() ?: return
        val snapshot = _entries.value
        _entries.value = snapshot.map { if (it.id == entryId) it.copy(text = text) else it }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { userDataApi.updateMemoryEntry(jwt, entryId, text) }
            if (result.succeeded) _message.value = "记忆已更新"
            else { _entries.value = snapshot; _message.value = result.errorOr("更新失败") }
        }
    }

    /** 删除单条记忆：乐观移除，失败恢复。 */
    fun deleteEntry(entryId: String) {
        val jwt = jwt() ?: return
        val snapshot = _entries.value
        _entries.value = snapshot.filterNot { it.id == entryId }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { userDataApi.deleteMemoryEntry(jwt, entryId) }
            if (result.succeeded) _message.value = "已删除该记忆"
            else { _entries.value = snapshot; _message.value = result.errorOr("删除失败") }
        }
    }

    /**
     * 手动添加一条记忆。
     *
     * 不做乐观插入：服务端会规范化文本（改写成第三人称）并生成 id，本地无从预测；
     * 且内容护栏可能拒绝。成功后重拉列表拿权威结果。
     */
    fun addEntry(text: String, section: MemorySection) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { _message.value = "记忆内容不能为空"; return }
        if (trimmed.length > MEMORY_TEXT_MAX) { _message.value = "记忆内容不能超过 $MEMORY_TEXT_MAX 字"; return }
        val jwt = jwt() ?: run { _message.value = "请先登录"; return }
        if (_addingEntry.value) return
        viewModelScope.launch {
            _addingEntry.value = true
            val result = withContext(dispatchers.io) { userDataApi.addMemoryEntry(jwt, trimmed, section) }
            if (result.succeeded) {
                _message.value = "已添加到记忆"
                reloadEntries(jwt)
            } else {
                // 内容护栏的拒绝原因（敏感属性 / 危险内容）必须原样透出，否则用户不知道该改什么。
                _message.value = result.errorOr("添加失败，请稍后再试")
            }
            _addingEntry.value = false
        }
    }

    /** 清除全部长期记忆。 */
    fun clearAllMemories() {
        val jwt = jwt() ?: return
        val snapshot = _entries.value
        val snapshotProjection = _projection.value
        _entries.value = emptyList()
        _projection.value = MemoryProjection(budget = snapshotProjection.budget)
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { userDataApi.deleteAllMemories(jwt) }
            if (result.succeeded) {
                _message.value = "已清除全部记忆"
            } else {
                _entries.value = snapshot
                _projection.value = snapshotProjection
                _message.value = result.errorOr("清除失败")
            }
        }
    }

    // —— 待确认候选 ——

    /**
     * 确认记住一条候选：先入库（带 candidate_id，服务端沿用已规范化的文本），
     * 再标记候选已处理（suppress=false，不写 tombstone）。
     */
    fun acceptCandidate(candidate: MemoryCandidate) {
        val jwt = jwt() ?: run { _message.value = "请先登录"; return }
        val snapshot = _candidates.value
        _candidates.value = snapshot.filterNot { it.id == candidate.id }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                userDataApi.addMemoryEntry(jwt, candidate.text, candidate.section, candidateId = candidate.id)
            }
            if (!result.succeeded) {
                _candidates.value = snapshot
                _message.value = result.errorOr("添加失败，请稍后再试")
                return@launch
            }
            withContext(dispatchers.io) { userDataApi.dismissCandidate(jwt, candidate.id, suppress = false) }
            _message.value = "已记住"
            reloadEntries(jwt)
        }
    }

    /** 忽略一条候选：写 tombstone，夜间管线不再重复建议。 */
    fun dismissCandidate(candidateId: String) {
        val jwt = jwt() ?: return
        val snapshot = _candidates.value
        _candidates.value = snapshot.filterNot { it.id == candidateId }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                userDataApi.dismissCandidate(jwt, candidateId, suppress = true)
            }
            if (result.succeeded) _message.value = "已忽略 · 不会再次建议"
            else { _candidates.value = snapshot; _message.value = result.errorOr("操作失败") }
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
            val result = withContext(dispatchers.io) { userDataApi.updateStylePreferences(jwt, _style.value) }
            if (result.succeeded) { _styleDirty.value = false; _message.value = "风格偏好已保存" }
            else _message.value = result.errorOr("保存失败，请稍后再试")
            _savingStyle.value = false
        }
    }

    // —— 内部 ——

    /** 写操作后重拉条目（服务端可能改写文本 / 重算投影，本地无从预测）。 */
    private suspend fun reloadEntries(jwt: String) {
        withContext(dispatchers.io) { userDataApi.getMemoryEntries(jwt) }?.let {
            _entries.value = it.entries
            _projection.value = it.projection
        }
    }

    private fun jwt(): String? = jwtProvider()?.takeIf { it.isNotBlank() }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    /**
     * 本地预估评分后的置信度，镜像服务端公式
     * （user_data_manager.php `handle_rate_memory_entry`）：先减去旧评分的增量再加上新的，
     * 故来回切换评分不会累积漂移。
     */
    private fun predictedConfidence(entry: MemoryEntry, rating: MemoryRating?): Double {
        val prev = entry.userRating?.delta ?: 0.0
        val next = rating?.delta ?: 0.0
        return (entry.confidence - prev + next)
            .coerceIn(MemoryRating.CONFIDENCE_MIN, MemoryRating.CONFIDENCE_MAX)
    }

    /** 优先用服务端的中文原因，没有才回落到通用文案。 */
    private fun ApiResult<*>.errorOr(fallback: String): String =
        (this as? ApiResult.Err)?.message ?: fallback

    private companion object {
        /** 服务端 add/update 的文本长度上限。 */
        const val MEMORY_TEXT_MAX = 300
    }
}
