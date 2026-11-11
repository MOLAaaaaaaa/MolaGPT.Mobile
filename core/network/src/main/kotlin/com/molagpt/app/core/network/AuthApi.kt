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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/** 鉴权相关低层调用（Ktor）。编排（存 token、UA hash 校验）在 feature:auth 的 MolaGptAuthService。 */
class AuthApi(private val http: MolaHttp) {

    suspend fun logout(jwt: String): Boolean {
        val resp = http.accountClient.post(MolaEndpoints.absolute(MolaEndpoints.LOGOUT)) {
            header(HttpHeaders.Authorization, "Bearer $jwt")
        }
        if (!resp.status.isSuccess()) return false
        return runCatching {
            http.json.parseToJsonElement(resp.bodyAsText()).jsonObject["success"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)
    }

    /** 用户名 + sha256(密码) 登录。 */
    suspend fun login(username: String, passwordSha256: String): LoginResponse = withTimeoutOrNull(20_000L) {
        val resp = http.accountClient.post(MolaEndpoints.absolute(MolaEndpoints.LOGIN)) {
            contentType(ContentType.Application.Json)
            header("Origin", "https://chatgpt.wljay.cn")
            header("Referer", "https://chatgpt.wljay.cn/v2/")
            header("X-MolaGPT-Client", UserAgentProvider.CLIENT_MARKER)
            setBody(LoginRequest(username, passwordSha256))
        }
        if (!resp.status.isSuccess()) {
            // 登录限频（429）、账号服务不可用（503）都带 message，只显示那一句
            val body = resp.bodyAsText()
            return@withTimeoutOrNull LoginResponse(
                success = false,
                message = molaServerErrorMessage(resp.status.value, body)
                    ?: "HTTP ${resp.status.value}: ${body.take(200)}",
            )
        }
        try { resp.body<LoginResponse>() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { LoginResponse(success = false, message = "登录响应解析失败") }
    } ?: LoginResponse(success = false, message = "登录超时，请重试")

    /** 拉账号状态。返回原始 JsonObject（含 user.logged_in / usage / limits / model_status）。401 抛 [MolaAuthExpiredException]。 */
    suspend fun status(jwt: String? = null): JsonObject? = withTimeoutOrNull(20_000L) {
        val resp = http.accountClient.get(MolaEndpoints.absolute(MolaEndpoints.STATUS)) {
            jwt?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (resp.status.value == 401) throw MolaAuthExpiredException()
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw MolaApiException(
                resp.status.value,
                molaServerErrorMessage(resp.status.value, body)
                    ?: "读取账号状态失败：HTTP ${resp.status.value}",
            )
        }
        runCatching { http.json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw MolaApiException(null, "账号状态响应解析失败") }
    } ?: throw MolaApiException(null, "读取账号状态超时，请重试")

    /** 便捷判定：当前 JWT 是否仍登录。 */
    suspend fun isLoggedIn(jwt: String): Boolean = runCatching {
        status(jwt)?.get("user")?.jsonObject?.get("logged_in")?.jsonPrimitive?.content == "true"
    }.getOrDefault(false)

    /** 用 OAuth 一次性 code 兑换会话 JWT（oauth_exchange.php）。返回 {success, token, userInfo:{username}}。 */
    suspend fun oauthExchange(code: String, verifier: String): LoginResponse = withTimeoutOrNull(20_000L) {
        val resp = http.accountClient.post(MolaEndpoints.absolute(MolaEndpoints.OAUTH_EXCHANGE)) {
            contentType(ContentType.Application.Json)
            header("Origin", "https://chatgpt.wljay.cn")
            header("X-MolaGPT-Client", UserAgentProvider.CLIENT_MARKER)
            setBody(OAuthExchangeRequest(code, verifier))
        }
        if (!resp.status.isSuccess()) {
            val body = resp.bodyAsText()
            return@withTimeoutOrNull LoginResponse(
                success = false,
                message = molaServerErrorMessage(resp.status.value, body)
                    ?: "HTTP ${resp.status.value}: ${body.take(200)}",
            )
        }
        try { resp.body<LoginResponse>() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { LoginResponse(success = false, message = "OAuth 兑换响应解析失败") }
    } ?: LoginResponse(success = false, message = "登录超时，请重新发起")
    /** 取得一次性支持页面入口；访问令牌始终留在 Authorization 请求头。 */
    suspend fun membershipPortal(jwt: String, productKey: String? = null): String = withTimeoutOrNull(20_000L) {
        val response = http.accountClient.post(MolaEndpoints.absolute(MolaEndpoints.MEMBERSHIP_PORTAL)) {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {})
        }
        if (response.status.value == 401) throw MolaAuthExpiredException()
        if (!response.status.isSuccess()) throw MolaApiException(response.status.value, "暂时无法打开支持页面，请稍后重试")
        val body = http.json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (body["success"]?.jsonPrimitive?.booleanOrNull != true)
            throw MolaApiException(null, "暂时无法打开支持页面")
        val raw = body["url"]?.jsonPrimitive?.contentOrNull
            ?: throw MolaApiException(null, "支持入口响应无效")
        val url = MolaEndpoints.absolute(raw).toHttpUrl()
        if (url.encodedPath != "/v2/membership" || url.query != null ||
            url.fragment?.startsWith("ticket=") != true || url.fragment!!.length <= 7)
            throw MolaApiException(null, "支持入口响应无效")
        if (productKey == null) url.toString()
        else url.newBuilder().addQueryParameter("product_key", productKey).build().toString()
    } ?: throw MolaApiException(null, "打开支持页面超时，请重试")

}
