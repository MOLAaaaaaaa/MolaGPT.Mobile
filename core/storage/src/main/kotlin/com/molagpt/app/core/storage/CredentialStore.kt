package com.molagpt.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密凭据存储：JWT、用户名、登录时的 UA hash。
 * UA hash 用于启动时校验 UA 是否漂移——漂移则静默清 token，避免无限 401。
 *
 * EncryptedSharedPreferences 在个别设备/密钥轮换时可能初始化失败，故降级到普通 prefs 兜底。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "mola_creds",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.applicationContext.getSharedPreferences("mola_creds_plain", Context.MODE_PRIVATE)
    }

    var jwt: String?
        get() = prefs.getString(KEY_JWT, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_JWT) else putString(KEY_JWT, value) }.apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_USERNAME) else putString(KEY_USERNAME, value) }.apply()

    var uaHash: String?
        get() = prefs.getString(KEY_UA_HASH, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_UA_HASH) else putString(KEY_UA_HASH, value) }.apply()

    val isLoggedIn: Boolean get() = !jwt.isNullOrBlank()

    fun save(jwt: String, username: String?, uaHash: String) {
        prefs.edit()
            .putString(KEY_JWT, jwt)
            .putString(KEY_USERNAME, username)
            .putString(KEY_UA_HASH, uaHash)
            .apply()
    }

    fun clear() = prefs.edit()
        .remove(KEY_JWT)
        .remove(KEY_USERNAME)
        .remove(KEY_UA_HASH)
        .apply()

    fun saveSecret(key: String, value: String?) {
        prefs.edit()
            .apply { if (value.isNullOrBlank()) remove(key) else putString(key, value) }
            .apply()
    }

    fun loadSecret(key: String): String? = prefs.getString(key, null)

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val KEY_JWT = "molagpt.jwt"
        const val KEY_USERNAME = "molagpt.username"
        const val KEY_UA_HASH = "molagpt.ua_hash"
    }
}
