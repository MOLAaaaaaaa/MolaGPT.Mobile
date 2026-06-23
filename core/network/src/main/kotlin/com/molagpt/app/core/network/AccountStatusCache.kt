package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.AccountStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 账号配额状态缓存（容器级，单例）。
 *
 * 设置页 / 个性化页的 ViewModel 随导航返回会被重建，若各自 init 拉取就会“每次回到设置页都刷新整张额度表”。
 * 这里把状态上提到容器：导航返回只读缓存，仅在 **超出新鲜窗口**、**显式刷新** 或 **登录态变化(invalidate)** 时才打网络。
 *
 * 注意：用 [nowProvider] 注入时间，便于把 System.currentTimeMillis 这类不可测调用隔离在边界。
 */
class AccountStatusCache(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val loader: suspend () -> AccountStatus?,
    private val freshnessWindowMs: Long = 60_000L,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val _status = MutableStateFlow<AccountStatus?>(null)
    val status: StateFlow<AccountStatus?> = _status.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var lastLoadedAt: Long = 0L
    private val mutex = Mutex()

    /** 缓存是否仍新鲜（有值且在窗口内）。 */
    private fun isFresh(): Boolean = _status.value != null && (nowProvider() - lastLoadedAt) < freshnessWindowMs

    /**
     * 确保有可用状态：默认 [force]=false 时，新鲜则直接复用（不打网络）。
     * 导航返回应调用 force=false；用户点“刷新”用 force=true。
     */
    fun ensure(force: Boolean = false) {
        if (!force && (isFresh() || _loading.value)) return
        scope.launch {
            mutex.withLock {
                if (!force && isFresh()) return@withLock
                _loading.value = true
                runCatching { withContext(dispatchers.io) { loader() } }
                    .onSuccess {
                        if (it != null) {
                            _status.value = it
                            lastLoadedAt = nowProvider()
                        }
                    }
                _loading.value = false
            }
        }
    }

    /** 登录态变化：清空缓存，下次 ensure 必拉取（游客/登录可用额度不同）。 */
    fun invalidate() {
        _status.value = null
        lastLoadedAt = 0L
    }
}
