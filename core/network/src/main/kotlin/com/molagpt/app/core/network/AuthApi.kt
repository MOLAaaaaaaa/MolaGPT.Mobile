package com.molagpt.app.core.network

import com.molagpt.app.core.network.dto.LoginRequest
import com.molagpt.app.core.network.dto.LoginResponse
import com.molagpt.app.core.network.dto.OAuthExchangeRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 鉴权相关低层调用（Ktor）。编排（存 token、UA hash 校验）在 feature:auth 的 MolaGptAuthService。 */
class AuthApi(private val http: MolaHttp) {

    /** 用户名 + sha256(密码) 登录。 */
    suspend fun login(username: String, passwordSha256: String): LoginResponse {
        val resp = http.client.post(MolaEndpoints.absolute(MolaEndpoints.LOGIN)) {
            contentType(ContentType.Application.Json)
            header("Origin", "https://chatgpt.wljay.cn")
            header("Referer", "https://chatgpt.wljay.cn/v2/")
            header("X-MolaGPT-Client", UserAgentProvider.CLIENT_MARKER)
            setBody(LoginRequest(username, passwordSha256))
        }
        if (!resp.status.isSuccess()) {
            return LoginResponse(success = false, message = "HTTP ${resp.status.value}: ${resp.bodyAsText().take(200)}")
        }
        return runCatching { resp.body<LoginResponse>() }
            .getOrElse { LoginResponse(success = false, message = "登录响应解析失败") }
    }

    /** 拉账号状态。返回原始 JsonObject（含 user.logged_in / usage / limits / model_status）。401 抛 [MolaAuthExpiredException]。 */
    suspend fun status(jwt: String): JsonObject? {
        val resp = http.client.get(MolaEndpoints.absolute(MolaEndpoints.STATUS)) {
            header(HttpHeaders.Authorization, "Bearer $jwt")
        }
        if (resp.status.value == 401) throw MolaAuthExpiredException()
        if (!resp.status.isSuccess()) return null
        return runCatching { http.json.parseToJsonElement(resp.bodyAsText()).jsonObject }.getOrNull()
    }

    /** 便捷判定：当前 JWT 是否仍登录。 */
    suspend fun isLoggedIn(jwt: String): Boolean = runCatching {
        status(jwt)?.get("user")?.jsonObject?.get("logged_in")?.jsonPrimitive?.content == "true"
    }.getOrDefault(false)

    /** 用 OAuth 一次性 code 兑换会话 JWT（oauth_exchange.php）。返回 {success, token, userInfo:{username}}。 */
    suspend fun oauthExchange(code: String): LoginResponse {
        val resp = http.client.post(MolaEndpoints.absolute(MolaEndpoints.OAUTH_EXCHANGE)) {
            contentType(ContentType.Application.Json)
            header("Origin", "https://chatgpt.wljay.cn")
            header("X-MolaGPT-Client", UserAgentProvider.CLIENT_MARKER)
            setBody(OAuthExchangeRequest(code))
        }
        if (!resp.status.isSuccess()) {
            return LoginResponse(success = false, message = "HTTP ${resp.status.value}: ${resp.bodyAsText().take(200)}")
        }
        return runCatching { resp.body<LoginResponse>() }
            .getOrElse { LoginResponse(success = false, message = "OAuth 兑换响应解析失败") }
    }
}
