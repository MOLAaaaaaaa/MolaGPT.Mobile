package com.molagpt.app.core.network

import android.util.Base64
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Storage namespace, not authentication. Only call for account tokens from the official TLS login. */
object AccountPrincipal {
    fun fromToken(token: String): String? = runCatching {
        val parts = token.split('.')
        require(parts.size == 3)
        val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)
        fromClaims(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject)
    }.getOrNull()

    internal fun fromClaims(claims: JsonObject): String? {
        // The server issues `sub` = account_id; `username` is only a fallback and is unique among active accounts.
        for (key in listOf("user_id", "uid", "sub", "username")) {
            if (key !in claims) continue
            val field = claims[key] as? JsonPrimitive ?: return null
            val value = field.contentOrNull?.takeUnless { it.isBlank() } ?: return null
            if (!field.isString && (key == "username" || field.longOrNull == null)) return null
            val hash = MessageDigest.getInstance("SHA-256").digest("$key:$value".toByteArray(Charsets.UTF_8))
            val prefix = if (key == "username") "name:" else "account:"
            return prefix + hash.joinToString("") { "%02x".format(it) }
        }
        return null
    }
}
