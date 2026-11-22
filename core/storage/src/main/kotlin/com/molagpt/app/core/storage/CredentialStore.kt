package com.molagpt.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import com.molagpt.app.core.model.AccountSessionTokens
import com.molagpt.app.core.network.AccountPrincipal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistoryIdentity(val owner: String?, val generation: Long, val resetPending: Boolean = false) {
    companion object { const val GUEST = "guest" }
    val canReadOfficial get() = owner != null && !resetPending
    val canSync get() = canReadOfficial && owner != GUEST
}

class SyncSession(val identity: HistoryIdentity, val jwt: String)

/**
 * 加密凭据存储：JWT、用户名、登录时的 UA hash。
 * UA hash 用于启动时校验 UA 是否漂移——漂移则静默清 token，避免无限 401。
 *
 * 安全存储不可用时不将凭据写入普通 SharedPreferences。
 */
class CredentialStore(context: Context, prefsName: String = DEFAULT_PREFS_NAME) {

    private val gate = Any()
    private val transient = mutableMapOf<String, String>()
    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            context.applicationContext,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        if (prefsName == DEFAULT_PREFS_NAME) {
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
        }
        val existingJwt = encrypted.getString(KEY_JWT, null)
        if (existingJwt != null && encrypted.getString(KEY_LAST_OWNER, null) == null) {
            AccountPrincipal.fromToken(existingJwt)?.let { owner ->
                encrypted.edit().putString(KEY_LAST_OWNER, owner).apply()
            }
        }
        encrypted
    }.getOrNull()
    private var sessionGeneration = prefs?.getLong(KEY_GENERATION, 0L) ?: 0L

    val isSecureStorageAvailable: Boolean get() = prefs != null

    private fun securePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("Android 安全存储不可用，请检查设备安全设置后重新登录。")

    val jwt: String?
        get() = synchronized(gate) { prefs?.getString(KEY_JWT, null) }

    private fun identity(): HistoryIdentity {
        val token = jwt
        val owner = if (token == null) HistoryIdentity.GUEST else AccountPrincipal.fromToken(token)
        return HistoryIdentity(owner, sessionGeneration, prefs?.getBoolean(KEY_RESET_PENDING, false) == true)
    }
    private val _historyIdentity = MutableStateFlow(identity())
    val historyIdentity = _historyIdentity.asStateFlow()

    // The flag and the credentials change in the same commit. A process death between
    // logout and Room cleanup must not expose yesterday's cache to the next login.
    fun finishHistoryReset(expected: HistoryIdentity) = synchronized(gate) {
        if (expected.resetPending && expected == historyIdentity.value) {
            check(securePrefs().edit().remove(KEY_RESET_PENDING).commit())
            _historyIdentity.value = identity()
        }
    }

    fun syncSession(): SyncSession? = synchronized(gate) {
        val identity = historyIdentity.value
        if (identity.canSync) jwt?.let { SyncSession(identity, it) } else null
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
        val secure = securePrefs()
        val previousOwner = secure.getString(KEY_LAST_OWNER, null)
            ?: secure.getString(KEY_JWT, null)?.let(AccountPrincipal::fromToken)
        val nextOwner = AccountPrincipal.fromToken(jwt)
        val switching = previousOwner != null && nextOwner != previousOwner
        val resetPending = switching || secure.getBoolean(KEY_RESET_PENDING, false)
        check(secure.edit()
            .apply {
                if (resetPending) putBoolean(KEY_RESET_PENDING, true)
                if (nextOwner != null) putString(KEY_LAST_OWNER, nextOwner) else remove(KEY_LAST_OWNER)
            }
            .putLong(KEY_GENERATION, sessionGeneration + 1)
            .putString(KEY_JWT, jwt)
            .putString(KEY_USERNAME, username)
            .putString(KEY_UA_HASH, uaHash)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt ?: 0L)
            .commit()) { "无法保存登录凭据" }
        sessionGeneration++
        _historyIdentity.value = identity()
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
            secure.getString(KEY_REFRESH_TOKEN, null) != expected.refreshToken ||
            AccountPrincipal.fromToken(accessToken) != AccountPrincipal.fromToken(expected.accessToken)
        ) return@synchronized false
        check(secure.edit().putString(KEY_JWT, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt).commit()) { "无法保存续期凭据" }
        true
    }

    fun clear() = synchronized(gate) {
        val secure = prefs ?: return@synchronized
        if (secure.getString(KEY_JWT, null) != null || secure.getString(KEY_LAST_OWNER, null) != null) {
            clearLocked(evictHistory = true)
        }
    }

    /** 被动会话失效（如 401 / 刷新失败）：只清过期凭据并保留归属，同号重登不丢本地记录，换号再清库。 */
    fun clearIfCurrent(expected: String): Boolean = synchronized(gate) {
        val secure = prefs ?: return@synchronized false
        if (secure.getString(KEY_JWT, null) != expected) return@synchronized false
        clearLocked(evictHistory = false, invalidatedToken = expected)
        true
    }

    private fun clearLocked(evictHistory: Boolean, invalidatedToken: String? = null) {
        val secure = securePrefs()
        val preservedOwner = if (evictHistory) null else {
            secure.getString(KEY_LAST_OWNER, null) ?: invalidatedToken?.let(AccountPrincipal::fromToken)
        }
        check(secure.edit()
            .remove(KEY_JWT)
            .remove(KEY_USERNAME)
            .remove(KEY_UA_HASH)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply {
                if (evictHistory) {
                    remove(KEY_LAST_OWNER)
                    putBoolean(KEY_RESET_PENDING, true)
                } else if (preservedOwner != null) {
                    putString(KEY_LAST_OWNER, preservedOwner)
                }
            }
            .putLong(KEY_GENERATION, sessionGeneration + 1)
            .commit()) { "无法持久化清除本地登录凭据" }
        sessionGeneration++
        _historyIdentity.value = identity()
    }

    fun replaceJwtIfCurrent(expected: String, replacement: String): Boolean = synchronized(gate) {
        val secure = prefs ?: return@synchronized false
        if (secure.getString(KEY_JWT, null) != expected ||
            AccountPrincipal.fromToken(replacement) != AccountPrincipal.fromToken(expected)) return@synchronized false
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
        const val DEFAULT_PREFS_NAME = "mola_creds"
        const val KEY_JWT = "molagpt.jwt"
        const val KEY_USERNAME = "molagpt.username"
        const val KEY_UA_HASH = "molagpt.ua_hash"
        const val KEY_REFRESH_TOKEN = "molagpt.refresh_token"
        const val KEY_EXPIRES_AT = "molagpt.expires_at"
        const val KEY_LAST_OWNER = "molagpt.last_owner"
        const val KEY_RESET_PENDING = "molagpt.history_reset_pending"
        const val KEY_GENERATION = "molagpt.login_generation"
        val TRANSIENT_KEYS = setOf("agent.device_id", "molagpt.device_cookie")
    }
}
