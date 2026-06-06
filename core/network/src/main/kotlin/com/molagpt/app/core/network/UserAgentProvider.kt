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

    /** 参数保留仅为调用点兼容；UA 现为固定无版本串。 */
    fun build(versionName: String, sdkInt: Int): String = FIXED_UA

    /** 与后端一致的小写十六进制 sha256(UA)，登录后随 JWT 一起存，用于启动时校验 UA 是否漂移。 */
    fun sha256(ua: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(ua.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
