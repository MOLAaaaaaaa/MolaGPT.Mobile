package com.molagpt.app.core.storage

import com.molagpt.app.core.network.SyncApi

/**
 * 个性化记忆总开关的**单一写入口**。
 *
 * 这个开关有三个存放地：本地 DataStore [SettingsStore.tracksEnabled]（UI 真相）、
 * 服务端 `user.settings.personalized_memory_enabled`（多设备一致的真相）、
 * 以及 `status.php` 的回读值（进入个性化页时用来校正本地）。
 *
 * 账户页与个性化页都能改它。收敛到这里之前两处各写了一份乐观更新逻辑，行为并不一致——
 * 账户页那份在无 JWT 时**静默 return**：本地已改、服务端没同步、用户毫不知情，
 * 下次进个性化页会被服务端值悄悄改回去。现在统一由 [setTracksEnabled] 处理，
 * 调用方只需按返回的 [TracksToggleResult] 决定提示什么。
 */
class TracksToggle(
    private val store: SettingsStore,
    private val syncApi: SyncApi,
    /** 持久登录 JWT 提供者。 */
    private val jwtProvider: () -> String?,
) {
    /**
     * 乐观写本地 + 同步服务端，失败回滚本地。
     *
     * 必须在 IO 上下文调用（内部会打网络）。
     */
    suspend fun setTracksEnabled(value: Boolean): TracksToggleResult {
        val jwt = jwtProvider()?.takeIf { it.isNotBlank() }
            ?: return TracksToggleResult.NotLoggedIn

        store.setTracksEnabled(value)
        val ok = runCatching { syncApi.updateSetting(jwt, SETTING_KEY, value) }.getOrDefault(false)
        if (ok) return TracksToggleResult.Success

        store.setTracksEnabled(!value)
        return TracksToggleResult.SyncFailed
    }

    private companion object {
        /** 后端 `update_setting` 白名单里的键名。 */
        const val SETTING_KEY = "personalized_memory_enabled"
    }
}

/** [TracksToggle.setTracksEnabled] 的结果。失败态各自携带面向用户的提示语。 */
sealed interface TracksToggleResult {
    data object Success : TracksToggleResult

    /** 未登录：本地**未改动**（改了也无处同步，只会在下次校正时被静默还原）。 */
    data object NotLoggedIn : TracksToggleResult

    /** 同步失败：本地已回滚。 */
    data object SyncFailed : TracksToggleResult

    val message: String?
        get() = when (this) {
            Success -> null
            NotLoggedIn -> "请先登录 MolaGPT 账号"
            SyncFailed -> "设置同步失败，请稍后再试"
        }
}
