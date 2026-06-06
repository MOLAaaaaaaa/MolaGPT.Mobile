package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.network.dto.AltchaChallenge
import com.molagpt.app.core.network.dto.AuthTokenResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 统一短 token 管理。
 *
 * **任何对话/上传/停止/标题请求前都先取一个 60 秒有效的短 JWT**，再用它调 chat proxy。
 * 游客与登录用户走同一条路径，唯一区别是换 token 时带不带本地长 token：
 *   GET challenge.php → 解 ALTCHA → GET auth.php(头 X-Altcha-Payload；登录态再带 Authorization 长token)。
 *
 * - 缓存短 token，剩余有效期 < [REFRESH_THRESHOLD_MS] 才刷新；[Mutex] 保证并发只刷一次。
 * - 登录失效自动降级游客（不打断）：本地有长 token 但服务端回 ua_mismatch / 非 registered →
 *   清本地长 token，用本次拿到的游客短 token 继续。续签 token 则回存。
 *
 * 不直接依赖 :core:storage（避免与 storage→network 形成环），长 token 读写经构造期注入的 lambda。
 */
class ShortTokenManager(
    private val http: MolaHttp,
    private val altchaSolver: AltchaSolver,
    private val dispatchers: DispatcherProvider,
    /** 读本地长 token（登录态）；游客为 null。 */
    private val longTokenProvider: () -> String?,
    /** auth.php 续签了长 token 时回存。 */
    private val onRenewedLongToken: (String) -> Unit,
    /** 长 token 失效（ua_mismatch / 被降级）时清登录态。 */
    private val onLoginInvalidated: () -> Unit,
) {
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiresAtMs: Long = 0L
    private val mutex = Mutex()

    /** 取一个有效短 token；命中缓存则直接返回，否则换取。失败抛 [MolaApiException]。 */
    suspend fun freshToken(): String {
        cached()?.let { return it }
        return mutex.withLock {
            cached() ?: refresh()
        }
    }

    /** 登录/登出后调用：丢弃缓存，强制下次以新身份重新换取。 */
    fun invalidate() {
        cachedToken = null
        cachedExpiresAtMs = 0L
    }

    private fun cached(): String? {
        val t = cachedToken ?: return null
        return if (System.currentTimeMillis() < cachedExpiresAtMs - REFRESH_THRESHOLD_MS) t else null
    }

    private suspend fun refresh(): String = withContext(dispatchers.io) {
        val challenge = fetchChallenge()
        val number = altchaSolver.solve(challenge)
            ?: throw MolaApiException(null, "人机验证求解失败，请重试")
        val payload = altchaSolver.buildPayload(challenge, number)
        val longToken = longTokenProvider()

        val resp = http.client.get(MolaEndpoints.absolute(MolaEndpoints.SHORT_TOKEN_AUTH)) {
            header(HEADER_ALTCHA, payload)
            header(HttpHeaders.CacheControl, "no-cache")
            if (!longToken.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $longToken")
        }
        when (resp.status.value) {
            403 -> throw MolaApiException(403, "人机验证失败，请重试")
            429 -> throw MolaApiException(429, "请求过于频繁，请稍后再试")
        }
        if (!resp.status.isSuccess()) {
            throw MolaApiException(resp.status.value, "获取令牌失败：HTTP ${resp.status.value}")
        }
        val body = runCatching { resp.body<AuthTokenResponse>() }
            .getOrElse { throw MolaApiException(null, "令牌响应解析失败") }
        body.error?.let { throw MolaApiException(null, "鉴权失败：$it") }
        val token = body.token.takeIf { it.isNotBlank() }
            ?: throw MolaApiException(null, "服务端未返回令牌")

        // 登录失效降级：本地带了长 token，但服务端没认（UA 不符或非 registered）→ 清登录、继续用游客短 token。
        if (!longToken.isNullOrBlank() && (body.uaMismatch || body.userType != "registered")) {
            Logger.w("ShortToken", "login downgraded to guest (uaMismatch=${body.uaMismatch}, type=${body.userType})")
            onLoginInvalidated()
        } else if (!body.renewedLoginToken.isNullOrBlank()) {
            onRenewedLongToken(body.renewedLoginToken!!)
        }

        cachedToken = token
        cachedExpiresAtMs = System.currentTimeMillis() + TOKEN_TTL_MS
        token
    }

    private suspend fun fetchChallenge(): AltchaChallenge {
        val resp = http.client.get(MolaEndpoints.absolute(MolaEndpoints.CHALLENGE)) {
            header(HttpHeaders.CacheControl, "no-cache")
        }
        if (!resp.status.isSuccess()) {
            throw MolaApiException(resp.status.value, "获取验证挑战失败：HTTP ${resp.status.value}")
        }
        return runCatching { resp.body<AltchaChallenge>() }
            .getOrElse { throw MolaApiException(null, "验证挑战解析失败") }
    }

    private companion object {
        const val HEADER_ALTCHA = "X-Altcha-Payload"
        const val TOKEN_TTL_MS = 60_000L
        const val REFRESH_THRESHOLD_MS = 15_000L
    }
}
