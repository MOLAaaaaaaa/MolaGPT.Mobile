package com.molagpt.app.core.network

import android.util.Base64
import com.molagpt.app.core.model.AccountSessionTokens
import com.molagpt.app.core.network.dto.LoginResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Rotates account credentials before a request is sent; chat tokens are left unchanged. */
class AccountSessionManager(
    private val http: MolaHttp,
    private val current: () -> AccountSessionTokens?,
    private val replace: (AccountSessionTokens, String, String, Long) -> Boolean,
    private val invalidate: (AccountSessionTokens) -> Unit,
) {
    private val refreshGate = ReentrantLock()
    private val refreshHttp = http.accountOkHttp.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun resolveToken(requestToken: String): String {
        val requestClaims = claims(requestToken) ?: return requestToken
        if (requestClaims["typ"]?.jsonPrimitive?.contentOrNull != "access") return requestToken
        val requestSid = requestClaims["sid"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("登录凭据缺少会话信息，请重新登录")
        return refreshGate.withLock {
            val snapshot = current() ?: throw MolaAuthExpiredException()
            if (claims(snapshot.accessToken)?.get("sid")?.jsonPrimitive?.contentOrNull != requestSid)
                throw IOException("登录账户已变化，请重试")
            val now = System.currentTimeMillis() / 1000
            if ((snapshot.expiresAt ?: 0L) > now + 60)
                return@withLock snapshot.accessToken
            val refreshToken = snapshot.refreshToken
            if (refreshToken.isNullOrBlank()) {
                invalidate(snapshot)
                throw MolaAuthExpiredException()
            }
            val payload = buildJsonObject { put("refresh_token", refreshToken) }.toString()
            val request = Request.Builder()
                .url(MolaEndpoints.absolute(MolaEndpoints.SESSION_REFRESH))
                .header("X-MolaGPT-Client", UserAgentProvider.CLIENT_MARKER)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            refreshHttp.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    invalidate(snapshot)
                    throw MolaAuthExpiredException()
                }
                if (!response.isSuccessful)
                    throw IOException("会话续期暂不可用，请稍后重试")
                val session = http.json.decodeFromString<LoginResponse>(response.body?.string().orEmpty())
                val access = session.token?.takeIf { it.isNotBlank() }
                val nextRefresh = session.refreshToken?.takeIf { it.isNotBlank() }
                val expires = session.expiresAt
                if (!session.success || access == null || nextRefresh == null ||
                    expires == null || expires <= now ||
                    claims(access)?.get("sid")?.jsonPrimitive?.contentOrNull != requestSid
                ) throw IOException("会话续期响应无效，请重新登录")
                if (!replace(snapshot, access, nextRefresh, expires))
                    throw IOException("登录账户已变化，请重试")
                access
            }
        }
    }

    fun rejectToken(token: String) {
        if (claims(token)?.get("typ")?.jsonPrimitive?.contentOrNull != "access") return
        val snapshot = current() ?: return
        if (snapshot.accessToken == token) invalidate(snapshot)
    }

    private fun claims(token: String): JsonObject? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return@runCatching null
        val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)
        http.json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject
    }.getOrNull()
}
