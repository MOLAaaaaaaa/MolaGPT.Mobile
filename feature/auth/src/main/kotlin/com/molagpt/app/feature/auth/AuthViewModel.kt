package com.molagpt.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class AuthViewModel(private val authService: MolaGptAuthService) : ViewModel() {
    private val _state = MutableStateFlow(
        AuthUiState(loggedIn = authService.isLoggedIn, username = authService.username),
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private var passwordLoginInProgress = false

    init {
        viewModelScope.launch {
            authService.account.collect {
                _state.value = _state.value.copy(loggedIn = it.loggedIn, username = it.username)
            }
        }
        viewModelScope.launch {
            authService.oauth.collect {
                if (!passwordLoginInProgress) _state.value = _state.value.copy(loading = it.loading, error = it.error)
            }
        }
    }

    fun login(username: String, password: String) {
        if (_state.value.loading) return
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(error = "请输入用户名和密码")
            return
        }
        passwordLoginInProgress = true
        authService.cancelOAuth()
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val err = try {
                authService.login(username, password)
            } finally {
                passwordLoginInProgress = false
            }
            _state.value = if (err == null) {
                AuthUiState(loggedIn = true, username = authService.username)
            } else _state.value.copy(loading = false, error = err)
        }
    }

    fun logout() {
        viewModelScope.launch {
            val revoked = authService.logoutRemote()
            _state.value = AuthUiState(
                loggedIn = authService.isLoggedIn,
                username = authService.username,
                error = if (revoked) null else "服务器会话撤销未确认，请联系管理员撤销旧会话。",
            )
        }
    }

    fun oauthUrl(provider: String): String? = try {
        authService.beginOAuth(provider)
    } catch (e: Exception) {
        _state.value = _state.value.copy(error = e.message ?: "无法发起登录")
        null
    }

    fun oauthLaunchFailed() {
        authService.cancelOAuth()
        _state.value = _state.value.copy(error = "无法打开浏览器，请检查默认浏览器设置。")
    }

    override fun onCleared() {
        authService.cancelOAuth()
        super.onCleared()
    }
}

object OAuthProvider {
    const val LINUXDO = "linuxdo"
    const val GOOGLE = "google"
    const val MICROSOFT = "microsoft"
    const val CALLBACK_SCHEME = "molagpt"
    const val CALLBACK_HOST = "oauth_callback"
}
