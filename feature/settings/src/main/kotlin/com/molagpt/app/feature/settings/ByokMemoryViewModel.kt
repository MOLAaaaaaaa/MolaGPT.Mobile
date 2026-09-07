package com.molagpt.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.model.ByokMemoryCandidate
import com.molagpt.app.core.model.ByokMemoryEntry
import com.molagpt.app.core.model.ByokMemoryProjection
import com.molagpt.app.core.model.ByokProfileKey
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.storage.ByokMemoryConsolidator
import com.molagpt.app.core.storage.ByokMemoryProjector
import com.molagpt.app.core.storage.ByokMemoryRepository
import com.molagpt.app.core.storage.ByokProviderRepository
import com.molagpt.app.core.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * BYOK 本地记忆页。数据源全部是本机 Room 与 DataStore，**不打任何网络请求**，
 * 因此没有 loading / 重试 / JWT 那套——与服务端 Tracks 的 [PersonalizationViewModel] 是两条独立链路。
 *
 * 投影在这里就地算出来：页面上显示的「注入 N 条」必须与真正发给模型的内容同源，
 * 否则用户按页面判断"这条会不会生效"就会判断错。
 */
class ByokMemoryViewModel(
    private val repository: ByokMemoryRepository,
    private val store: SettingsStore,
    byokProviders: ByokProviderRepository,
    /** 「立即整理」。跑在 application scope，离开本页也会跑完。 */
    private val consolidateNow: suspend () -> ByokMemoryConsolidator.Result = { ByokMemoryConsolidator.Result() },
) : ViewModel() {

    data class Switches(
        /** 总开关。关掉时下面三项一律不生效。 */
        val masterEnabled: Boolean = false,
        val memoryEnabled: Boolean = false,
        val recallEnabled: Boolean = false,
        val autoLearn: Boolean = false,
        val fullScan: Boolean = false,
        val modelKey: String? = null,
        val budgetTokens: Int = ByokMemoryProjector.DEFAULT_BUDGET_TOKENS,
        val allowSensitive: Boolean = false,
        val lastConsolidatedAt: Long = 0L,
    )

    /** `<user_profile>` 的展示行。[editable] 为 false 的是设备直接给出的值，用户改不了也不需要改。 */
    data class ProfileField(
        val label: String,
        val value: String,
        val editable: Boolean,
    )

    val switches: StateFlow<Switches> = store.settings
        .map {
            Switches(
                masterEnabled = it.byokMemoryMasterEnabled,
                memoryEnabled = it.byokMemoryEnabled,
                recallEnabled = it.byokConversationRecallEnabled,
                autoLearn = it.byokMemoryAutoLearn,
                fullScan = it.byokMemoryFullScan,
                modelKey = it.byokMemoryModelKey,
                budgetTokens = it.byokMemoryBudgetTokens,
                allowSensitive = it.byokMemoryAllowSensitive,
                lastConsolidatedAt = it.byokMemoryLastConsolidatedAt,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Switches())

    val entries: StateFlow<List<ByokMemoryEntry>> = repository.observeEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 按固定分节顺序分组，空分节不出现。 */
    val entriesBySection: StateFlow<List<Pair<MemorySection, List<ByokMemoryEntry>>>> = entries
        .map { list ->
            MemorySection.entries.mapNotNull { section ->
                list.filter { it.section == section }.takeIf { it.isNotEmpty() }?.let { section to it }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val candidates: StateFlow<List<ByokMemoryCandidate>> = repository.observeCandidates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 预算跟着开关流一起变：改档位后统计行要立刻反映新的注入/跳过数。 */
    val projection: StateFlow<ByokMemoryProjection> = combine(entries, switches) { list, s ->
        ByokMemoryProjector.project(list, budgetTokens = s.budgetTokens)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ByokMemoryProjection())

    val profileFields: StateFlow<List<ProfileField>> = entries
        .map { list ->
            val device = ByokMemoryProjector.DeviceProfile.current()
            buildList {
                ByokProfileKey.entries.forEach { key ->
                    val value = list.filter { it.profileKey == key }
                        .maxByOrNull { it.effectiveConfidence(System.currentTimeMillis()) }
                        ?.text
                    if (key == ByokProfileKey.PREFERRED_NAME || value != null) {
                        add(ProfileField(key.label, value.orEmpty(), editable = key == ByokProfileKey.PREFERRED_NAME))
                    }
                }
                device.language?.let { add(ProfileField("语言", it, editable = false)) }
                device.timezone?.let { add(ProfileField("时区", it, editable = false)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 整理模型候选：所有已启用 provider 的对话模型。 */
    val modelOptions: StateFlow<List<SettingsViewModel.ModelOption>> = byokProviders.providers
        .map { providers ->
            providers.filter { it.enabled }.flatMap { p ->
                p.models.filter { it.supportsChat }.map {
                    SettingsViewModel.ModelOption("${p.id}::${it.id}", "${p.name} / ${it.displayName}")
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 自动学习开着但跑不起来：没选模型，或选中的模型已被删除/停用。
     * 必须显式提示——否则用户以为在学，实际什么都没发生。
     */
    val autoLearnBlocked: StateFlow<Boolean> = combine(switches, modelOptions) { s, options ->
        s.masterEnabled && s.autoLearn && options.none { it.key == s.modelKey }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    // ── 开关 ────────────────────────────────────────────────────────────────

    fun setMasterEnabled(v: Boolean) = viewModelScope.launch { store.setByokMemoryMasterEnabled(v) }
    fun setMemoryEnabled(v: Boolean) = viewModelScope.launch { store.setByokMemoryEnabled(v) }
    fun setFullScan(v: Boolean) = viewModelScope.launch { store.setByokMemoryFullScan(v) }
    fun setRecallEnabled(v: Boolean) = viewModelScope.launch { store.setByokConversationRecallEnabled(v) }
    fun setAutoLearn(v: Boolean) = viewModelScope.launch { store.setByokMemoryAutoLearn(v) }
    fun setModelKey(key: String?) = viewModelScope.launch { store.setByokMemoryModelKey(key) }
    fun setBudgetTokens(v: Int) = viewModelScope.launch { store.setByokMemoryBudgetTokens(v) }
    fun setAllowSensitive(v: Boolean) = viewModelScope.launch { store.setByokMemoryAllowSensitive(v) }

    private val _consolidating = MutableStateFlow(false)
    val consolidating: StateFlow<Boolean> = _consolidating.asStateFlow()

    fun consolidateNow() = viewModelScope.launch {
        if (_consolidating.value) return@launch
        _consolidating.value = true
        val result = runCatching { consolidateNow.invoke() }
        _consolidating.value = false
        // 「没有需要记录的新内容」只能用在真的问过模型、模型说没有的时候。
        // 请求失败、记忆关着、没有待整理的对话，都是完全不同的处境，
        // 折叠成同一句话会让用户对着一个其实没运行的功能反复点。
        _message.value = result.fold(
            onSuccess = { r ->
                val head = when {
                    r.failed > 0 && (r.written > 0 || r.pending > 0) -> "已更新部分记忆，部分内容整理失败"
                    r.failed > 0 -> "整理失败，请检查网络或模型设置"
                    r.written > 0 && r.pending > 0 -> "已更新 ${r.written} 条，${r.pending} 条待确认"
                    r.written > 0 -> "已更新 ${r.written} 条记忆"
                    r.pending > 0 -> "${r.pending} 条待确认"
                    r.examined == 0 && r.disabled > 0 -> "记忆功能已关闭"
                    r.examined == 0 -> "暂无新内容"
                    else -> "已检查 ${r.examined} 段内容，未发现新记忆"
                }
                head
            },
            onFailure = { "整理失败，请稍后再试" },
        )
    }

    // ── 条目 ────────────────────────────────────────────────────────────────

    fun addEntry(text: String, section: MemorySection, profileKey: ByokProfileKey? = null) = viewModelScope.launch {
        val added = repository.addManual(text, section, profileKey)
        _message.value = if (added == null) "请输入记忆内容" else "记忆已添加"
    }

    fun updateEntry(id: String, text: String, section: MemorySection, profileKey: ByokProfileKey?) =
        viewModelScope.launch {
            if (!repository.updateEntry(id, text, section, profileKey)) _message.value = "请输入记忆内容"
        }

    fun deleteEntry(id: String) = viewModelScope.launch {
        repository.deleteEntry(id)
        _message.value = "记忆已删除"
    }

    /**
     * 改称呼。空值等于删掉这条——用户清空输入框就是不想让模型称呼自己。
     */
    fun setPreferredName(name: String) = viewModelScope.launch {
        val clean = name.trim()
        val existing = entries.value.firstOrNull { it.profileKey == ByokProfileKey.PREFERRED_NAME }
        when {
            clean.isEmpty() && existing != null -> repository.deleteEntry(existing.id, suppress = false)
            clean.isEmpty() -> Unit
            existing != null ->
                repository.updateEntry(existing.id, clean, existing.section, ByokProfileKey.PREFERRED_NAME)
            else ->
                repository.addManual(clean, MemorySection.IDENTITY, ByokProfileKey.PREFERRED_NAME)
        }
    }

    // ── 候选 ────────────────────────────────────────────────────────────────

    fun confirmCandidate(id: String) = viewModelScope.launch {
        repository.confirmCandidate(id)
        _message.value = "记忆已保存"
    }

    fun dismissCandidate(id: String) = viewModelScope.launch {
        repository.dismissCandidate(id)
        _message.value = "已忽略"
    }

    // ── 清除 ────────────────────────────────────────────────────────────────

    fun clearAll() = viewModelScope.launch {
        repository.clearAll()
        _message.value = "记忆已清除"
    }
}
