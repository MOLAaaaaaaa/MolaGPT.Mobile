package com.molagpt.app.di

import android.content.Context
import com.molagpt.app.core.network.UserAgentProvider
import com.molagpt.app.core.storage.CredentialStore
import org.json.JSONObject
import java.io.File

internal object DebugCredentialImporter {
    private const val FILE_NAME = "debug_credentials.json"

    fun importIfPresent(context: Context, credentials: CredentialStore, userAgent: String): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return false

        return runCatching {
            val json = JSONObject(file.readText())
            val jwt = json.optString("jwt").takeIf { it.isNotBlank() }
                ?: return@runCatching false
            val username = json.optString("username").takeIf { it.isNotBlank() }
            credentials.save(
                jwt = jwt,
                username = username,
                uaHash = UserAgentProvider.sha256(userAgent),
            )
            true
        }.getOrDefault(false).also {
            file.delete()
        }
    }
}
