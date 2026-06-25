package com.molagpt.app.core.storage

import com.molagpt.app.core.model.RetryAttempt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 重试版本的编解码 + metadata 键。版本（含多态 fragments）序列化成字符串塞进消息 metadata，
 * 零 Room 迁移。使用相同 `classDiscriminator="type"` 以复用 MessageFragment 的 sealed 多态。
 */
object RetryAttempts {
    const val KEY_ATTEMPTS = "retryAttempts"
    const val KEY_CURRENT = "retryCurrent"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    private val serializer = ListSerializer(RetryAttempt.serializer())

    fun encode(list: List<RetryAttempt>): String = json.encodeToString(serializer, list)

    fun decode(s: String?): List<RetryAttempt> =
        if (s.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, s) }.getOrDefault(emptyList())
}
