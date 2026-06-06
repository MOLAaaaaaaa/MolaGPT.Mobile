package com.molagpt.app.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    /** sha256(明文密码) 的小写十六进制。 */
    val password: String,
)

/** oauth_exchange.php 入参：一次性 handoff code。 */
@Serializable
data class OAuthExchangeRequest(
    val code: String,
)

@Serializable
data class LoginResponse(
    val success: Boolean = false,
    val token: String? = null,
    val message: String? = null,
    val userInfo: UserInfoDto? = null,
)

@Serializable
data class UserInfoDto(
    val username: String? = null,
    val unlimited: Boolean = false,
)

/** model_config_public.php 返回：{ models: { key: {modelName, tipText, apiUrl, ...} } }。 */
@Serializable
data class ModelConfigResponse(
    val models: Map<String, ModelConfigEntry> = emptyMap(),
)

@Serializable
data class ModelConfigEntry(
    val modelName: String? = null,
    val tipText: String? = null,
    val apiUrl: String? = null,
    val supportsThinking: Boolean = false,
    val supportsReasoningEffort: Boolean = false,
    val showImageUpload: Boolean = false,
    @SerialName("show_in_frontend") val showInFrontend: Boolean = true,
    val group: String? = null,
)
