package com.molagpt.app.core.model

data class AccountSessionTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
    val generation: Long,
)
