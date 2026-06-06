package com.molagpt.app.core.storage

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.MessageFragment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** fragments / metadata 的 JSON 编解码（多态 sealed 序列化器由 :core:model 生成）。 */
internal object MessageJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    private val fragmentsSerializer = ListSerializer(MessageFragment.serializer())
    private val attachmentsSerializer = ListSerializer(Attachment.serializer())
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun encodeFragments(list: List<MessageFragment>): String =
        json.encodeToString(fragmentsSerializer, list)

    fun decodeFragments(s: String): List<MessageFragment> =
        if (s.isBlank()) emptyList()
        else runCatching { json.decodeFromString(fragmentsSerializer, s) }.getOrDefault(emptyList())

    fun encodeAttachments(list: List<Attachment>): String =
        json.encodeToString(attachmentsSerializer, list)

    fun decodeAttachments(s: String?): List<Attachment> =
        if (s.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(attachmentsSerializer, s) }.getOrDefault(emptyList())

    fun encodeMeta(m: Map<String, String>): String =
        json.encodeToString(mapSerializer, m)

    fun decodeMeta(s: String): Map<String, String> =
        if (s.isBlank()) emptyMap()
        else runCatching { json.decodeFromString(mapSerializer, s) }.getOrDefault(emptyMap())
}
