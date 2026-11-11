package com.molagpt.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import com.molagpt.app.core.model.AccountSessionTokens

/**
 * 加密凭据存储：JWT、用户名、登录时的 UA hash。
 * UA hash 用于启动时校验 UA 是否漂移——漂移则静默清 token，避免无限 401。
 *
 * 安全存储不可用时不将凭据写入普通 SharedPreferences。
 */
class CredentialStore(context: Context) {

    private val gate = Any()
    private var sessionGeneration = 0L
    private val transient = mutableMapOf<String, String>()
    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            context.applicationContext,
            "mola_creds",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/mola_creds_plain.xml")
        if (legacyFile.isFile) {
            val legacy = context.applicationContext.getSharedPreferences("mola_creds_plain", Context.MODE_PRIVATE)
            val missing = legacy.all.filter { (key, value) -> value is String && !encrypted.contains(key) }
            if (missing.isNotEmpty()) {
                val edit = encrypted.edit()
                missing.forEach { (key, value) -> edit.putString(key, value as String) }
                check(edit.commit()) { "无法迁移安全存储" }
            }
            check(legacy.edit().clear().commit()) { "无法清理旧凭据" }
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                context.applicationContext.deleteSharedPreferences("mola_creds_plain")
            } else {
                legacyFile.delete()
            }
        }
        encrypted
    }.getOrNull()

    val isSecureStorageAvailable: Boolean get() = prefs != null

    private fun securePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("Android 安全存储不可用，请检查设备安全设置后重新登录。")

    var jwt: String?
        get() = prefs?.getString(KEY_JWT, null)
        set(value) = synchronized(gate) {
            securePrefs().edit().apply { if (value == null) remove(KEY_JWT) else putString(KEY_JWT, value) }.apply()
        }

    var username: String?
        get() = prefs?.getString(KEY_USERNAME, null)
        set(value) = synchronized(gate) {
            securePrefs().edit().apply { if (value == null) remove(KEY_USERNAME) else putString(KEY_USERNAME, value) }.apply()
        }

    var uaHash: String?
        get() = prefs?.getString(KEY_UA_HASH, null)
        set(value) = synchronized(gate) {
            securePrefs().edit().apply { if (value == null) remove(KEY_UA_HASH) else putString(KEY_UA_HASH, value) }.apply()
        }

    val isLoggedIn: Boolean get() = !jwt.isNullOrBlank()

    fun save(
        jwt: String,
        username: String?,
        uaHash: String,
        refreshToken: String? = null,
        expiresAt: Long? = null,
    ) = synchronized(gate) {
        check(securePrefs().edit()
            .putString(KEY_JWT, jwt)
            .putString(KEY_USERNAME, username)
            .putString(KEY_UA_HASH, uaHash)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt ?: 0L)
            .commit()) { "无法保存登录凭据" }
        sessionGeneration++
    }

    fun sessionTokens(): AccountSessionTokens? = synchronized(gate) {
        val secure = prefs ?: return@synchronized null
        val token = secure.getString(KEY_JWT, null) ?: return@synchronized null
        AccountSessionTokens(
            accessToken = token,
            refreshToken = secure.getString(KEY_REFRESH_TOKEN, null),
            expiresAt = secure.getLong(KEY_EXPIRES_AT, 0L).takeIf { it > 0L },
            generation = sessionGeneration,
        )
    }

    fun replaceSessionIfCurrent(
        expected: AccountSessionTokens,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ): Boolean = synchronized(gate) {
        val secure = prefs ?: return@synchronized false
        if (sessionGeneration != expected.generation ||
            secure.getString(KEY_JWT, null) != expected.accessToken ||
            secure.getString(KEY_REFRESH_TOKEN, null) != expected.refreshToken
        ) return@synchronized false
        check(secure.edit().putString(KEY_JWT, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt).commit()) { "无法保存续期凭据" }
        true
    }

    fun clear() = synchronized(gate) {
        val persisted = prefs?.edit()?.remove(KEY_JWT)?.remove(KEY_USERNAME)?.remove(KEY_UA_HASH)
            ?.remove(KEY_REFRESH_TOKEN)?.remove(KEY_EXPIRES_AT)?.commit() ?: true
        // Invalidate in-flight refreshes even if the storage write reports failure.
        sessionGeneration++
        check(persisted) { "无法持久化清除本地登录凭据" }
    }

    fun clearIfCurrent(expected: String): Boolean = synchronized(gate) {
        val secure = prefs ?: return@synchronized false
        if (secure.getString(KEY_JWT, null) != expected) return@synchronized false
        val persisted = secure.edit().remove(KEY_JWT).remove(KEY_USERNAME).remove(KEY_UA_HASH)
            .remove(KEY_REFRESH_TOKEN).remove(KEY_EXPIRES_AT).commit()
        sessionGeneration++
        check(persisted) { "无法持久化清除本地登录凭据" }
        true
    }

    fun replaceJwtIfCurrent(expected: String, replacement: String): Boolean = synchronized(gate) {
        val secure = prefs ?: return@synchronized false
        if (secure.getString(KEY_JWT, null) != expected) return@synchronized false
        secure.edit().putString(KEY_JWT, replacement).apply()
        true
    }

    fun saveSecret(key: String, value: String?, synchronous: Boolean = false) {
        val secure = prefs
        if (secure == null && key in TRANSIENT_KEYS) {
            synchronized(gate) {
                if (value.isNullOrBlank()) transient.remove(key) else transient[key] = value
            }
            return
        }
        val editor = securePrefs().edit()
            .apply { if (value.isNullOrBlank()) remove(key) else putString(key, value) }
        if (synchronous) {
            check(editor.commit()) { "无法保存安全凭据" }
        } else editor.apply()
    }

    fun loadSecret(key: String): String? =
        prefs?.getString(key, null) ?: synchronized(gate) { transient[key] }

    fun removeSecret(key: String) {
        if (prefs == null && key in TRANSIENT_KEYS) {
            synchronized(gate) { transient.remove(key) }
        } else {
            securePrefs().edit().remove(key).apply()
        }
    }

    private companion object {
        const val KEY_JWT = "molagpt.jwt"
        const val KEY_USERNAME = "molagpt.username"
        const val KEY_UA_HASH = "molagpt.ua_hash"
        const val KEY_REFRESH_TOKEN = "molagpt.refresh_token"
        const val KEY_EXPIRES_AT = "molagpt.expires_at"
        val TRANSIENT_KEYS = setOf("agent.device_id", "molagpt.device_cookie")
    }
}
