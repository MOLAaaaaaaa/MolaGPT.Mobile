package com.molagpt.app.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class MolaDeviceCookieJar(
    private val load: () -> String?,
    private val save: (String?) -> Unit,
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.isHttps || url.host != "chatgpt.wljay.cn") return
        cookies.lastOrNull { it.name == "mola_did" && it.matches(url) }?.let {
            save(if (it.expiresAt > System.currentTimeMillis()) it.toString() else null)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.isHttps || url.host != "chatgpt.wljay.cn") return emptyList()
        val cookie = load()?.let { Cookie.parse(url, it) } ?: return emptyList()
        return if (cookie.name == "mola_did" && cookie.matches(url) && cookie.expiresAt > System.currentTimeMillis()) listOf(cookie) else emptyList()
    }
}
