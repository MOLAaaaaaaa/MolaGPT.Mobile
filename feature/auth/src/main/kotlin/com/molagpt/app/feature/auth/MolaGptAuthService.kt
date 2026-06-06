package com.molagpt.app.feature.auth

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.network.AuthApi
import com.molagpt.app.core.network.UserAgentProvider
import com.molagpt.app.core.storage.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 登录编排：
 *  - 密码先 sha256 再发；
 *  - 登录成功后存 JWT + 用户名 + **当前 UA 的 sha256**；
 *  - 启动时 [isJwtValidForUa] 校验 UA 是否漂移——漂移则清 token，避免无限 401。
 *
 * [userAgent] 必须与对话请求所用的固定 UA 完全一致（由 :app 统一构造贯穿全程）。
 */
class MolaGptAuthService(
    private val authApi: AuthApi,
    private val credentials: CredentialStore,
    private val userAgent: String,
    private val dispatchers: DispatcherProvider,
    /** 登录态变化（登录/登出/UA 漂移清除）后回调——用于让短 token 缓存失效，立即按新身份换取。 */
    private val onAuthChanged: () -> Unit = {},
) {
    val isLoggedIn: Boolean get() = credentials.isLoggedIn
    val username: String? get() = credentials.username
    val currentJwt: String? get() = credentials.jwt

    private val _account = MutableStateFlow(snapshot())
    /** 响应式登录态（设置页据此显示游客/用户名，登录/登出即时刷新；多 VM 共享同一 service 实例）。 */
    val account: StateFlow<AccountState> = _account.asStateFlow()

    /** 登录。成功返回 null；失败返回错误消息。 */
    suspend fun login(username: String, password: String): String? = withContext(dispatchers.io) {
        val hash = sha256Hex(password)
        val resp = authApi.login(username, hash)
        if (resp.success && !resp.token.isNullOrBlank()) {
            credentials.save(
                jwt = resp.token!!,
                username = resp.userInfo?.username ?: username,
                uaHash = UserAgentProvider.sha256(userAgent),
            )
            notifyAuthChanged()
            null
        } else {
            resp.message ?: "登录失败"
        }
    }

    fun logout() {
        credentials.clear()
        notifyAuthChanged()
    }

    /** OAuth 回调：用一次性 handoff code 兑换 JWT 并登录。成功返回 null，失败返回错误消息。 */
    suspend fun loginWithOAuthCode(code: String): String? = withContext(dispatchers.io) {
        if (code.isBlank()) return@withContext "OAuth code 为空"
        val resp = authApi.oauthExchange(code)
        if (resp.success && !resp.token.isNullOrBlank()) {
            credentials.save(
                jwt = resp.token!!,
                username = resp.userInfo?.username ?: "OAuth 用户",
                uaHash = UserAgentProvider.sha256(userAgent),
            )
            notifyAuthChanged()
            null
        } else {
            resp.message ?: "OAuth 登录失败"
        }
    }

    /** 启动时调用：JWT 存在且其绑定的 UA hash 与当前 UA 一致才算有效；否则静默清除。 */
    fun ensureJwtValidForUa() {
        if (!credentials.isLoggedIn) return
        val stored = credentials.uaHash
        if (stored == null || !stored.equals(UserAgentProvider.sha256(userAgent), ignoreCase = true)) {
            credentials.clear()
            notifyAuthChanged()
        }
    }

    private fun snapshot() = AccountState(credentials.isLoggedIn, credentials.username)

    private fun notifyAuthChanged() {
        _account.value = snapshot()
        onAuthChanged()
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 登录态快照（设置页账号区使用）。 */
data class AccountState(val loggedIn: Boolean, val username: String?)
