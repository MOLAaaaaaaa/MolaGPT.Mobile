package com.molagpt.app.feature.auth

import android.net.Uri
import android.util.Base64
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.network.AuthApi
import com.molagpt.app.core.network.MolaEndpoints
import com.molagpt.app.core.network.UserAgentProvider
import com.molagpt.app.core.network.dto.LoginResponse
import com.molagpt.app.core.storage.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class MolaGptAuthService(
    private val authApi: AuthApi,
    private val credentials: CredentialStore,
    private val userAgent: String,
    private val dispatchers: DispatcherProvider,
    private val onAuthChanged: () -> Unit = {},
) {
    private val sessionGate = Any()
    private var sessionGeneration = 0L
    private var lastOAuthCode: String? = null
    val isLoggedIn: Boolean get() = credentials.isLoggedIn
    val username: String? get() = credentials.username
    val currentJwt: String? get() = credentials.jwt

    private val _account = MutableStateFlow(snapshot())
    val account: StateFlow<AccountState> = _account.asStateFlow()
    private val _oauth = MutableStateFlow(OAuthLoginState())
    val oauth: StateFlow<OAuthLoginState> = _oauth.asStateFlow()

    suspend fun login(username: String, password: String): String? = withContext(dispatchers.io) {
        if (!credentials.isSecureStorageAvailable)
            return@withContext "Android 安全存储不可用，请检查设备安全设置后重试。"
        val generation = synchronized(sessionGate) {
            cancelOAuthLocked()
            ++sessionGeneration
        }
        try {
            applyLogin(authApi.login(username, sha256Hex(password)), generation, username)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "无法连接账户服务，请稍后重试。"
        }
    }

    fun beginOAuth(provider: String): String = synchronized(sessionGate) {
        check(credentials.isSecureStorageAvailable) { "Android 安全存储不可用，请检查设备安全设置后重试。" }
        val path = when (provider) {
            OAuthProvider.GOOGLE -> "api/auth/google_init.php"
            OAuthProvider.MICROSOFT -> "api/auth/ms_init.php"
            OAuthProvider.LINUXDO -> "api/auth/oauth_init.php"
            else -> error("不支持的登录方式")
        }
        fun encode(bytes: ByteArray) =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val random = SecureRandom()
        val verifier = encode(ByteArray(32).also(random::nextBytes))
        val state = encode(ByteArray(32).also(random::nextBytes))
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val record = JSONObject().put("state", state).put("verifier", verifier)
            .put("expires_at", System.currentTimeMillis() + 10 * 60 * 1000)
        credentials.saveSecret(OAUTH_ATTEMPT, record.toString(), synchronous = true)
        sessionGeneration++
        _oauth.value = OAuthLoginState()
        MolaEndpoints.absolute(path) + "?desktop=1&android=1&code_challenge=" + challenge +
            "&code_challenge_method=S256&client_state=" + state
    }

    fun cancelOAuth() = synchronized(sessionGate) {
        sessionGeneration++
        cancelOAuthLocked()
    }

    private fun cancelOAuthLocked() {
        if (credentials.isSecureStorageAvailable) credentials.removeSecret(OAUTH_ATTEMPT)
        _oauth.value = OAuthLoginState()
    }

    suspend fun completeOAuthCallback(uri: Uri): String? = withContext(dispatchers.io) {
        if (uri.scheme != OAuthProvider.CALLBACK_SCHEME || uri.host != OAuthProvider.CALLBACK_HOST ||
            uri.userInfo != null || uri.port != -1 || (!uri.path.isNullOrEmpty() && uri.path != "/") ||
            uri.fragment != null || uri.getQueryParameter("token") != null
        ) return@withContext "登录回调无效，请重新发起。"
        val code = uri.getQueryParameters("code").singleOrNull()?.takeIf { it.isNotBlank() }
        val state = uri.getQueryParameters("state").singleOrNull()
        if (code == null || state.isNullOrBlank()) return@withContext "登录回调无效，请重新发起。"

        val attempt = synchronized(sessionGate) {
            if (lastOAuthCode == code) return@withContext null
            val raw = credentials.loadSecret(OAUTH_ATTEMPT)
            val record = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (record == null || record.optString("state") != state ||
                record.optLong("expires_at") <= System.currentTimeMillis()
            ) return@withContext "登录请求已失效，请重新发起。"
            val verifier = record.optString("verifier")
            if (verifier.isBlank()) return@withContext "登录请求已失效，请重新发起。"
            lastOAuthCode = code
            credentials.removeSecret(OAUTH_ATTEMPT)
            _oauth.value = OAuthLoginState(loading = true)
            verifier to ++sessionGeneration
        }
        val error = try {
            applyLogin(authApi.oauthExchange(code, attempt.first), attempt.second, "OAuth 用户")
        } catch (e: CancellationException) {
            synchronized(sessionGate) {
                if (sessionGeneration == attempt.second) _oauth.value = OAuthLoginState()
            }
            throw e
        } catch (_: Exception) {
            "无法完成登录，请重新发起。"
        }
        synchronized(sessionGate) {
            if (sessionGeneration != attempt.second) return@withContext null
            _oauth.value = OAuthLoginState(error = error)
        }
        error
    }

    private fun applyLogin(response: LoginResponse, generation: Long, fallbackName: String): String? {
        val token = response.token
        if (!response.success || token.isNullOrBlank()) return response.message ?: "登录失败"
        if (response.sessionProtocol != 2 || response.refreshToken.isNullOrBlank() ||
            (response.expiresAt ?: 0L) <= System.currentTimeMillis() / 1000
        ) return "服务器未返回有效会话，请重新登录。"
        synchronized(sessionGate) {
            if (generation != sessionGeneration) return "登录状态已变化，请重试。"
            credentials.save(
                jwt = token,
                username = response.userInfo?.username ?: fallbackName,
                uaHash = UserAgentProvider.sha256(userAgent),
                refreshToken = response.refreshToken,
                expiresAt = response.expiresAt,
            )
        }
        notifyAuthChanged()
        return null
    }

    fun logout() {
        synchronized(sessionGate) {
            sessionGeneration++
            cancelOAuthLocked()
            credentials.clear()
        }
        notifyAuthChanged()
    }

    suspend fun logoutRemote(): Boolean = withContext(dispatchers.io) {
        val (token, generation) = synchronized(sessionGate) { credentials.jwt to sessionGeneration }
        val revoked = if (token.isNullOrBlank()) true else try {
            withTimeoutOrNull(5_000) { authApi.logout(token) } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        var cleared = false
        synchronized(sessionGate) {
            if (generation == sessionGeneration) {
                sessionGeneration++
                cancelOAuthLocked()
                credentials.clear()
                cleared = true
            }
        }
        if (cleared) notifyAuthChanged()
        revoked
    }

    fun logoutIfCurrent(expectedJwt: String): Boolean {
        synchronized(sessionGate) {
            if (!credentials.clearIfCurrent(expectedJwt)) return false
            sessionGeneration++
            cancelOAuthLocked()
        }
        notifyAuthChanged()
        return true
    }

    fun ensureJwtValidForUa() {
        if (!credentials.isLoggedIn || credentials.sessionTokens()?.refreshToken != null) return
        val stored = credentials.uaHash
        if (stored == null || !stored.equals(UserAgentProvider.sha256(userAgent), ignoreCase = true)) logout()
    }

    private fun snapshot() = AccountState(credentials.isLoggedIn, credentials.username)
    private fun notifyAuthChanged() {
        _account.value = snapshot()
        onAuthChanged()
    }
    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object { const val OAUTH_ATTEMPT = "molagpt.oauth_attempt" }
}

data class AccountState(val loggedIn: Boolean, val username: String?)
data class OAuthLoginState(val loading: Boolean = false, val error: String? = null)
