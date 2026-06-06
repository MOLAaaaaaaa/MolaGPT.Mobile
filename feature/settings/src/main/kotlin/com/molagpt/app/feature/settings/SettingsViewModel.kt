package com.molagpt.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.network.SyncApi
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val store: SettingsStore,
    private val syncEngine: SyncEngine,
    private val syncApi: SyncApi,
    /** 持久登录 JWT 提供者；同步个性化开关到服务端用（游客为空时仅本地）。 */
    private val jwtProvider: () -> String?,
    /** 拉账号状态（短 token → status.php → AccountStatus）；由工厂注入，VM 不直接碰网络细节。 */
    private val accountStatusLoader: suspend () -> AccountStatus?,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _status = MutableStateFlow<AccountStatus?>(null)
    val status: StateFlow<AccountStatus?> = _status.asStateFlow()
    private val _statusLoading = MutableStateFlow(false)
    val statusLoading: StateFlow<Boolean> = _statusLoading.asStateFlow()

    init { refreshStatus() }

    /** 拉/刷新配额状态。屏幕在登录态变化时（LaunchedEffect）会再调一次：游客与登录可用配额不同。 */
    fun refreshStatus() {
        if (_statusLoading.value) return
        viewModelScope.launch {
            _statusLoading.value = true
            runCatching { withContext(dispatchers.io) { accountStatusLoader() } }
                .onSuccess { if (it != null) _status.value = it }
            _statusLoading.value = false
        }
    }

    fun setThemeMode(v: String) = viewModelScope.launch { store.setThemeMode(v) }
    fun setEnterToSend(v: Boolean) = viewModelScope.launch { store.setEnterToSend(v) }
    fun setTemperature(v: Double) = viewModelScope.launch { store.setTemperature(v) }
    fun setUseThinking(v: Boolean) = viewModelScope.launch { store.setUseThinking(v) }
    fun setReasoningEffort(v: String) = viewModelScope.launch { store.setReasoningEffort(v) }
    fun setTools(network: Boolean, steel: Boolean, code: Boolean) =
        viewModelScope.launch { store.setTools(network, steel, code) }

    fun setCloudSync(v: Boolean) = viewModelScope.launch {
        store.setCloudSyncEnabled(v)
        // 开启时立即做一次全量同步（force 跳过开关判定，把云端历史拉下来 / 本地推上去）。
        if (v && !_syncing.value) {
            _syncing.value = true
            runCatching { syncEngine.syncNow(force = true) }
            _syncing.value = false
        }
    }

    fun setTracks(v: Boolean) = viewModelScope.launch {
        // 乐观写本地并同步服务端 personalized_memory_enabled；失败时回滚本地。
        store.setTracksEnabled(v)
        val jwt = jwtProvider()?.takeIf { it.isNotBlank() } ?: return@launch
        val ok = runCatching {
            withContext(dispatchers.io) { syncApi.updateSetting(jwt, "personalized_memory_enabled", v) }
        }.getOrDefault(false)
        if (!ok) store.setTracksEnabled(!v)
    }

    fun setCompletionNotify(v: Boolean) = viewModelScope.launch { store.setCompletionNotify(v) }

    /** 立即同步：触发完整双向云同步（成功后引擎会更新 lastSyncAt）。 */
    fun syncNow() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        runCatching { syncEngine.syncNow(force = true) }
        _syncing.value = false
    }
}
