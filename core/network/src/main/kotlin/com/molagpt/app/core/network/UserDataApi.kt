package com.molagpt.app.core.network

import com.molagpt.app.core.model.InsightCategory
import com.molagpt.app.core.model.MemoryCandidate
import com.molagpt.app.core.model.MemoryEntry
import com.molagpt.app.core.model.MemoryProjection
import com.molagpt.app.core.model.MemoryRating
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.model.MemorySource
import com.molagpt.app.core.model.StylePreferences
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 写操作结果。服务端的内容护栏会以 **422 + 具体中文原因**拒绝写入
 * （敏感属性 / 危险内容 / 无有效文字），总开关关闭时是 **403**。
 * 这些消息对用户是刚需——「保存失败」四个字无法解释为什么被拒，故写操作不用 Boolean。
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** [message] 为服务端返回的中文原因；null=网络层失败（无响应体）。 */
    data class Err(val message: String?) : ApiResult<Nothing>

    val succeeded: Boolean get() = this is Ok

    companion object {
        /** 无返回值的成功。 */
        val OK: ApiResult<Unit> = Ok(Unit)
    }
}

/**
 * 个性化数据接口（`user_data_manager.php`）：长期记忆条目 (memory_entries)、待确认候选 (candidates)
 * 与对话风格偏好 (style_preferences)。
 *
 * **鉴权用持久登录 JWT**（同 [SyncApi]，账户级数据；游客无 JWT 时调用方应跳过）。
 *
 * 两套返回契约，刻意区分：
 * - **读**取返回 `T?`：null=拉取失败，空列表=确实没有（UI 据此区分错误态与空态）；
 * - **写**入返回 [ApiResult]：失败时携带服务端的中文原因（内容护栏 422 / 总开关 403）。
 *
 * 记忆由服务端夜间「做梦管线」维护（浅睡摄入 → 深睡巩固 → 每周再巩固），
 * 最终投影成 MEMORY.md 注入 system prompt；客户端只做展示与用户裁决。
 */
class UserDataApi(private val http: MolaHttp) {

    private val url: String get() = MolaEndpoints.absolute(MolaEndpoints.USER_DATA)

    // —— 长期记忆条目 ——

    /** 拉取全部活跃记忆条目 + MEMORY.md 投影统计。失败返回 null；成功（含空）返回结果。 */
    suspend fun getMemoryEntries(jwt: String): MemoryEntriesResult? {
        val resp = post(jwt, buildJsonObject { put("action", "get_memory_entries") }) ?: return null
        if (!resp.isSuccess) return null
        val entries = (resp["entries"] as? JsonArray)
            ?.mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { parseEntry(it, i) } }
            .orEmpty()
        return MemoryEntriesResult(
            entries = entries,
            projection = (resp["projection"] as? JsonObject)?.let { parseProjection(it) } ?: MemoryProjection(),
            memoryEnabled = resp["memory_enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }

    /**
     * 新增一条记忆。
     *
     * [candidateId] 非空表示「确认某条候选」——该文本已由摄入相位规范化并过完护栏，服务端直接沿用；
     * 手动输入路径（null）则会在服务端补规范化 + 内容护栏，可能以 422 拒绝。
     */
    suspend fun addMemoryEntry(
        jwt: String,
        text: String,
        section: MemorySection,
        candidateId: String? = null,
    ): ApiResult<Unit> = write(jwt, buildJsonObject {
        put("action", "add_memory_entry")
        put("text", text)
        put("section", section.wire)
        candidateId?.let { put("candidate_id", it) }
    })

    suspend fun updateMemoryEntry(jwt: String, entryId: String, newText: String): ApiResult<Unit> =
        write(jwt, buildJsonObject {
            put("action", "update_memory_entry")
            put("entry_id", entryId)
            put("new_text", newText)
        })

    suspend fun deleteMemoryEntry(jwt: String, entryId: String): ApiResult<Unit> =
        write(jwt, buildJsonObject {
            put("action", "delete_memory_entry")
            put("entry_id", entryId)
        })

    /** 评分。[rating] 为 null 表示撤销评分（发 `clear`）。 */
    suspend fun rateMemoryEntry(jwt: String, entryId: String, rating: MemoryRating?): ApiResult<Unit> =
        write(jwt, buildJsonObject {
            put("action", "rate_memory_entry")
            put("entry_id", entryId)
            put("rating", rating?.wire ?: MemoryRating.CLEAR_WIRE)
        })

    /** 清除全部长期记忆（服务端已把「洞察」与「事件记忆」合并，此操作清空两者）。 */
    suspend fun deleteAllMemories(jwt: String): ApiResult<Unit> =
        write(jwt, buildJsonObject { put("action", "delete_all_memories") })

    /** 触发一次后台记忆巩固（稍后刷新可见新结果）。 */
    suspend fun triggerEvolution(jwt: String): Boolean =
        write(jwt, buildJsonObject { put("action", "trigger_user_evolution") }).succeeded

    // —— 待确认候选 ——

    /** 拉取待确认候选。失败返回 null；成功（含空）返回列表。 */
    suspend fun getCandidates(jwt: String): List<MemoryCandidate>? {
        val resp = post(jwt, buildJsonObject { put("action", "get_candidates") }) ?: return null
        if (!resp.isSuccess) return null
        val arr = resp["candidates"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> (el as? JsonObject)?.let { parseCandidate(it) } }
    }

    /**
     * 处置一条候选。
     *
     * [suppress] = true（忽略）：写 tombstone，夜间管线不再重复建议；
     * false（确认后清理）：仅标记已处理——实际写入由 [addMemoryEntry] 完成。
     */
    suspend fun dismissCandidate(jwt: String, candidateId: String, suppress: Boolean): ApiResult<Unit> =
        write(jwt, buildJsonObject {
            put("action", "dismiss_candidate")
            put("candidate_id", candidateId)
            put("suppress", suppress)
        })

    // —— 对话风格 ——

    /** 读取对话风格偏好。失败返回 null。 */
    suspend fun getStylePreferences(jwt: String): StylePreferences? {
        val resp = post(jwt, buildJsonObject { put("action", "get_style_preferences") }) ?: return null
        if (!resp.isSuccess) return null
        val prefs = resp["preferences"] as? JsonObject ?: return StylePreferences()
        val styles = (prefs["styles"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val custom = prefs["custom_instruction"]?.jsonPrimitive?.contentOrNull ?: ""
        return StylePreferences(styles = styles, customInstruction = custom)
    }

    /** 保存对话风格偏好（styles 多选 + 自定义指令）。 */
    suspend fun updateStylePreferences(jwt: String, prefs: StylePreferences): ApiResult<Unit> =
        write(jwt, buildJsonObject {
            put("action", "update_style_preferences")
            put("preferences", buildJsonObject {
                putJsonArray("styles") { prefs.styles.forEach { add(it) } }
                put("custom_instruction", prefs.customInstruction)
            })
        })

    // —— 解析 ——

    private fun parseEntry(obj: JsonObject, index: Int): MemoryEntry {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: index.toString()
        val sources = (obj["sources"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val chatId = o["chat_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            MemorySource(chatId = chatId, ts = o["ts"].asLong())
        }.orEmpty()
        return MemoryEntry(
            id = id,
            text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
            section = obj["section"]?.jsonPrimitive?.contentOrNull,
            category = obj["category"]?.jsonPrimitive?.contentOrNull,
            confidence = obj["confidence"].asDouble(),
            permanent = obj["permanent"].asBool(),
            halfLifeDays = obj["half_life_days"]?.asDoubleOrNull(),
            ttl = obj["ttl"]?.asLongOrNull(),
            userRating = MemoryRating.fromWire(obj["user_rating"]?.jsonPrimitive?.contentOrNull),
            firstTs = obj["first_ts"].asLong(),
            lastTs = obj["last_ts"].asLong(),
            recurrence = obj["n_recurrence"].asInt().coerceAtLeast(1),
            createdTs = obj["created_ts"].asLong(),
            userSet = obj["user_set"].asBool(),
            sources = sources,
        )
    }

    private fun parseCandidate(obj: JsonObject): MemoryCandidate? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val text = obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return MemoryCandidate(
            id = id,
            text = text,
            quote = obj["quote"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            sourceChatId = obj["source_chat_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            observedTs = obj["observed_ts"].asLong(),
            section = MemorySection.fromWire(obj["section"]?.jsonPrimitive?.contentOrNull),
        )
    }

    private fun parseProjection(obj: JsonObject) = MemoryProjection(
        entries = obj["entries"].asInt(),
        skipped = obj["skipped"].asInt(),
        tokens = obj["tokens"].asInt(),
        budget = obj["budget"].asInt(),
    )

    // —— JSON 取值：服务端 PDO 会把数值列以字符串返回，故一律容忍字符串形态 ——

    private fun kotlinx.serialization.json.JsonElement?.asLongOrNull(): Long? {
        val p = this as? JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun kotlinx.serialization.json.JsonElement?.asLong(): Long = asLongOrNull() ?: 0L

    private fun kotlinx.serialization.json.JsonElement?.asDoubleOrNull(): Double? {
        val p = this as? JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement?.asDouble(): Double = asDoubleOrNull() ?: 0.0

    private fun kotlinx.serialization.json.JsonElement?.asInt(): Int {
        val p = this as? JsonPrimitive ?: return 0
        return p.intOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
    }

    /** SQLite 的布尔列常以 0/1（或其字符串）回传，`booleanOrNull` 对此返回 null。 */
    private fun kotlinx.serialization.json.JsonElement?.asBool(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        p.booleanOrNull?.let { return it }
        val s = p.contentOrNull?.trim() ?: return false
        return s == "1" || s.equals("true", ignoreCase = true)
    }

    private val JsonObject.isSuccess: Boolean
        get() = this["success"]?.jsonPrimitive?.booleanOrNull ?: false

    /** 写操作：成功返回 [ApiResult.Ok]，失败带上服务端 `message`。 */
    private suspend fun write(jwt: String, body: JsonObject): ApiResult<Unit> {
        val resp = post(jwt, body) ?: return ApiResult.Err(null)
        if (resp.isSuccess) return ApiResult.OK
        return ApiResult.Err(resp["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
    }

    /**
     * 发一次请求并解析 JSON 对象。
     *
     * 非 2xx **不提前返回**：内容护栏的 422 / 总开关的 403 都带着有用的 `message` 体，
     * 提前 return 会把用户最需要看到的拒绝原因丢掉。由 [write] 按 `success` 字段判定成败。
     */
    private suspend fun post(jwt: String, body: JsonObject): JsonObject? = runCatching {
        val resp = http.client.post(url) {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(http.json.encodeToString(JsonObject.serializer(), body))
        }
        http.json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
    }.getOrNull()
}

/** [UserDataApi.getMemoryEntries] 的结果：条目列表 + 投影统计 + 服务端开关状态。 */
data class MemoryEntriesResult(
    val entries: List<MemoryEntry>,
    val projection: MemoryProjection,
    /** 服务端 `personalized_memory_enabled` 的当前值。 */
    val memoryEnabled: Boolean,
)
