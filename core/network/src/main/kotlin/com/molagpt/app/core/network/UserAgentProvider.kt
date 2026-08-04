package com.molagpt.app.core.network

import java.security.MessageDigest

/**
 * **固定 User-Agent**。MolaGPT 的 JWT 把 `sha256(UA)` 写进 token，每次请求校验；UA 一变即 401。
 * 使用无版本号的固定 UA：App 升级不会改变 UA、不会强制重登；且必须与后端
 * `oauth_common.php / oauth_jwt_ua_for_state` 的 Android 分支字符串**完全一致**，OAuth 签发的 JWT
 * 才能匹配本端后续请求的 UA。
 */
object UserAgentProvider {
    const val CLIENT_MARKER = "MolaGPT-Android"

    /** 固定 UA（与后端 oauth_jwt_ua_for_state 的 'MolaGPT-Android (Android)' 严格一致）。 */
    const val FIXED_UA = "MolaGPT-Android (Android)"

    /**
     * 浏览器 UA，**仅用于下载任意第三方图片 URL**（图床/CDN 常有防盗链，会拒绝非浏览器 UA）。
     *
     * 不要用在别处：
     * - MolaGPT 后端必须用 [FIXED_UA]，JWT 绑定 sha256(UA)，一变即 401；
     * - DuckDuckGo HTML 端点实测对浏览器 UA 反而返回 202 反爬页（[FIXED_UA] 才能正常拿到结果）；
     * - 各 LLM 厂商 API 由 API key 鉴权，不做 UA 过滤，[FIXED_UA] 即可。
     *
     * 设备段沿用 Chrome UA reduction 后的固定串（'Android 10; K'），只有 Chrome 主版本号会随时间变旧，
     * 但防盗链一般只看 Mozilla 前缀，无需跟进升级。
     */
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Mobile Safari/537.36"

    /** 参数保留仅为调用点兼容；UA 现为固定无版本串。 */
    fun build(versionName: String, sdkInt: Int): String = FIXED_UA

    /** 与后端一致的小写十六进制 sha256(UA)，登录后随 JWT 一起存，用于启动时校验 UA 是否漂移。 */
    fun sha256(ua: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(ua.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
