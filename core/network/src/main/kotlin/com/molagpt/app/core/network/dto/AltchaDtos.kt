package com.molagpt.app.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ALTCHA proof-of-work 与短 token 换取相关的线格式。
 *
 * 流程：
 *  1. GET challenge.php → [AltchaChallenge]
 *  2. 暴力解出 number，构造 [AltchaSolution] → JSON → base64 → 头 `X-Altcha-Payload`
 *  3. GET auth.php（带上述头；登录态再带 `Authorization: Bearer 长token`）→ [AuthTokenResponse]
 */
@Serializable
data class AltchaChallenge(
    /** 固定 "SHA-256"。 */
    val algorithm: String = "SHA-256",
    /** 目标哈希（小写十六进制）：sha256(salt + number)。 */
    val challenge: String,
    /** 求解上界（服务端当前为 100000）。 */
    val maxnumber: Int = 100_000,
    /** 盐值，内含 `?expires=<ts>&` 段，拼接时必须原样使用。 */
    val salt: String,
    /** 服务端 HMAC 签名，原样回传。 */
    val signature: String,
)

/** 提交给 auth.php 的解。字段集合与 verify_altcha_solution 校验一致。 */
@Serializable
data class AltchaSolution(
    val algorithm: String,
    val challenge: String,
    val number: Int,
    val salt: String,
    val signature: String,
)

/** auth.php 返回。token 为 60 秒有效的短 JWT。 */
@Serializable
data class AuthTokenResponse(
    val token: String,
    @SerialName("user_type") val userType: String? = null,
    val username: String? = null,
    @SerialName("ua_mismatch") val uaMismatch: Boolean = false,
    @SerialName("renewed_login_token") val renewedLoginToken: String? = null,
    /** 错误分支：altcha_required / altcha_invalid 等。 */
    val error: String? = null,
)
