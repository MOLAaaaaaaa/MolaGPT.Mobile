package com.molagpt.app.core.network

import com.molagpt.app.core.common.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 集中构建网络栈。**Ktor 与 OkHttp 共用同一个 [okHttp] 客户端**（单连接池）：
 * - 简单 JSON 调用（登录/状态/模型/标题/停止）走 Ktor（[client]，带 ContentNegotiation）；
 * - SSE 流式热路径直接用 [okHttp] 的 BufferedSource 逐行读，最稳定，规避 Ktor 3.0 IO API 变动。
 *
 * 固定 UA 由 OkHttp 拦截器统一注入——登录与后续请求共用，杜绝 JWT-UA 校验 401。
 */
class MolaHttp(
    val userAgent: String,
    enableLogging: Boolean = false,
    loadDeviceCookie: () -> String? = { null },
    saveDeviceCookie: (String?) -> Unit = {},
    resolveAccountToken: (String) -> String = { it },
    onAccountTokenRejected: (String) -> Unit = {},
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MolaDeviceCookieJar(loadDeviceCookie, saveDeviceCookie))
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        // SSE：读/整体超时不限制，靠协程取消 + 服务端 stop_stream 收尾。
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build(),
            )
        }
        .build()

    val accountOkHttp: OkHttpClient = okHttp.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val url = chain.request().url
            require(url.isHttps && url.host == "chatgpt.wljay.cn" && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty()) {
                "MolaGPT 账户请求只能访问官方 HTTPS 地址"
            }
            val request = chain.request()
            val authorization = request.header("Authorization")
            val authorized = if (authorization?.startsWith("Bearer ", ignoreCase = true) == true) {
                val token = try {
                    resolveAccountToken(authorization.substring(7))
                } catch (error: java.io.IOException) {
                    throw error
                } catch (_: Exception) {
                    throw java.io.IOException("无法续期登录会话，请稍后重试")
                }
                request.newBuilder().header("Authorization", "Bearer " + token).build()
            } else request
            val response = chain.proceed(authorized)
            if (response.code == 401) {
                authorized.header("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(7)?.let(onAccountTokenRejected)
            }
            response
        }
        .build()

    val client: HttpClient = createClient(okHttp, enableLogging, true)
    val accountClient: HttpClient = createClient(accountOkHttp, enableLogging, false)

    private fun createClient(engineClient: OkHttpClient, enableLogging: Boolean, redirects: Boolean): HttpClient =
        HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = redirects
        engine { preconfigured = engineClient }
        install(ContentNegotiation) { json(json) }
        if (enableLogging) {
            install(Logging) {
                level = LogLevel.INFO
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        Logger.d("Ktor", message)
                    }
                }
            }
        }
    }

    fun close() {
        accountClient.close()
        client.close()
    }
}
