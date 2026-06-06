package com.molagpt.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.network.MolaEndpoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    val username: String? = null,
)

class AuthViewModel(
    private val authService: MolaGptAuthService,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AuthUiState(loggedIn = authService.isLoggedIn, username = authService.username),
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(error = "请输入用户名和密码")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val err = authService.login(username, password)
            _state.value = if (err == null) {
                AuthUiState(loggedIn = true, username = authService.username)
            } else {
                _state.value.copy(loading = false, error = err)
            }
        }
    }

    fun logout() {
        authService.logout()
        _state.value = AuthUiState(loggedIn = false)
    }

    /** 构造 OAuth 授权初始 URL；desktop=1 复用 molagpt:// 一次性 code 回调,android=1 让后端按 Android 身份签 JWT。 */
    fun oauthUrl(provider: String): String {
        val path = when (provider) {
            OAuthProvider.GOOGLE -> "api/auth/google_init.php"
            OAuthProvider.MICROSOFT -> "api/auth/ms_init.php"
            else -> "api/auth/oauth_init.php" // Linux.do
        }
        return MolaEndpoints.absolute(path) + "?desktop=1&android=1"
    }

    /** WebView 拦到 molagpt://oauth_callback?code=… 后调用：用 code 兑换 JWT。 */
    fun loginWithOAuthCode(code: String) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val err = authService.loginWithOAuthCode(code)
            _state.value = if (err == null) {
                AuthUiState(loggedIn = true, username = authService.username)
            } else {
                _state.value.copy(loading = false, error = err)
            }
        }
    }
}

/** OAuth 提供商常量。回调统一走 molagpt://oauth_callback。 */
object OAuthProvider {
    const val LINUXDO = "linuxdo"
    const val GOOGLE = "google"
    const val MICROSOFT = "microsoft"
    const val CALLBACK_SCHEME = "molagpt"
    const val CALLBACK_HOST = "oauth_callback"
}
