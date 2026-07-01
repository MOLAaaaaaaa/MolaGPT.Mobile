package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.AgentModelInfo
import com.molagpt.app.core.model.AgentPhase
import com.molagpt.app.core.model.AgentToolStatus
import com.molagpt.app.core.model.RelayCommandOp
import com.molagpt.app.core.model.RelayEnvelope
import com.molagpt.app.core.model.RelayEvent
import com.molagpt.app.core.model.RelaySessionMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Agent relay 客户端——手机侧。
 * 复用登录 JWT 鉴权（免 ALTCHA）,连 `chatgpt.wljay.cn/v2/api/auth/agent_*.php`。
 *
 *  - [listSessions]: 拉账号下所有会话
 *  - [eventHistory]: 一次性读取服务器持有的 transcript 快照
 *  - [streamEvents]: 普通 JSON 增量轮询收事件,断线重试 + since 追更
 *  - [sendPrompt] / [createSession]: 入队命令给桌面执行
 */
class AgentControlService(
    private val http: MolaHttp,
    private val dispatchers: DispatcherProvider,
    private val jwtProvider: () -> String?,
    private val deviceIdProvider: () -> String? = { null },
    private val deviceNameProvider: () -> String? = { null },
) {
    private val base = MolaEndpoints.BASE_URL.trimEnd('/')

    /** 拉取账号下所有 agent 会话元信息（桌面不在线时也可读：服务器持有数据）。 */
    suspend fun listSessions(): List<RelaySessionMeta> {
        val jwt = jwtProvider() ?: return emptyList()
        Logger.d("AgentRelay", "listSessions: jwt=OK (${jwt.length} chars)")
        val req = Request.Builder()
            .url("$base/api/auth/agent_sessions.php")
            .header("Authorization", "Bearer $jwt")
            .get()
            .apply { addDeviceHeaders(this) }
            .build()
        return withContext(dispatchers.io) {
            runCatching {
                Logger.d("AgentRelay", "sending request to ${req.url}")
                http.okHttp.newCall(req).execute().use { resp ->
                    Logger.d("AgentRelay", "response: HTTP ${resp.code}")
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(200)
                        Logger.w("AgentRelay", "HTTP ${resp.code}: $body")
                        return@use emptyList()
                    }
                    val text = resp.body?.string().orEmpty().trimStart('\uFEFF')
                    Logger.d("AgentRelay", "response body (${text.length} chars)")
                    val list = parseSessions(text)
                    Logger.d("AgentRelay", "parsed ${list.size} sessions; availableModels=${list.map { it.availableModels?.size ?: 0 }}")
                    list
                }
            }.getOrElse { ex ->
                Logger.w("AgentRelay", "listSessions failed", ex)
                emptyList()
            }
        }
    }

    /**
     * 订阅一个会话的事件流。从 [sinceSeq] 起重放，之后轮询增量。
     *
     * 这里刻意不用服务器 SSE：当前线上经 Cloudflare/PHP-FPM 时长连接会稳定遇到 524，
     * 详情首屏不能依赖它。1s 级轮询对 relay MVP 足够实时，也更容易恢复。
     */
    fun streamEventBatches(sessionId: String, sinceSeq: Long = 0L): Flow<List<RelayEnvelope>> = flow {
        var lastSeq = sinceSeq
        var attempt = 0
        while (true) {
            try {
                val batch = fetchEvents(sessionId, lastSeq)
                val fresh = batch.events
                    .asSequence()
                    .filter { it.seq > lastSeq }
                    .sortedBy { it.seq }
                    .toList()
                if (fresh.isNotEmpty()) {
                    lastSeq = fresh.maxOf { it.seq }
                    emit(fresh)
                }
                if (batch.latestSeq > lastSeq) lastSeq = batch.latestSeq
                attempt = 0
                delay(if (fresh.isNotEmpty()) 250 else 1000)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Logger.w("AgentRelay", "event poll failed: ${e.message}")
                delay((1000 + attempt * 500).coerceAtMost(10000).toLong())
                attempt++
            }
        }
    }.flowOn(dispatchers.io)

    fun streamEvents(sessionId: String, sinceSeq: Long = 0L): Flow<RelayEnvelope> = flow {
        streamEventBatches(sessionId, sinceSeq).collect { batch ->
            for (env in batch) emit(env)
        }
    }

    suspend fun eventHistory(sessionId: String, sinceSeq: Long = 0L): List<RelayEnvelope> =
        fetchEvents(sessionId, sinceSeq)
            .events
            .asSequence()
            .filter { it.seq > sinceSeq }
            .sortedBy { it.seq }
            .toList()

    private suspend fun fetchEvents(sessionId: String, sinceSeq: Long): EventBatch = withContext(dispatchers.io) {
        val jwt = jwtProvider() ?: throw Exception("missing jwt")
        val encodedSessionId = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        val req = Request.Builder()
            .url("$base/api/auth/agent_event_history.php?session=$encodedSessionId&since=$sinceSeq")
            .header("Authorization", "Bearer $jwt")
            .get()
            .build()
        http.okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty().trimStart('\uFEFF')
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${text.take(160)}")
            }
            parseEventBatch(text, sinceSeq)
        }
    }

    /**
     * 入队一条命令。[cmdId] 用于去重；op 如 "Send" / "Interrupt" / "Close" / "New"。
     */
    private suspend fun sendCommand(
        sessionId: String,
        op: RelayCommandOp,
        cmdId: String,
        payload: JsonObject? = null,
    ): Boolean = withContext(dispatchers.io) {
        val jwt = jwtProvider() ?: return@withContext false
        val body = buildJsonObject {
            put("sessionId", sessionId)
            put("cmdId", cmdId)
            put("op", op.name)
            if (payload != null) {
                put("payloadJson", http.json.encodeToString(JsonObject.serializer(), payload))
            }
        }
        val req = Request.Builder()
            .url("$base/api/auth/agent_command.php")
            .header("Authorization", "Bearer $jwt")
            .post(
                http.json.encodeToString(JsonObject.serializer(), body)
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()
        runCatching<Boolean> {
            http.okHttp.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun addDeviceHeaders(builder: Request.Builder) {
        deviceIdProvider()?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-MolaGPT-Agent-Device-Id", it)
        }
        deviceNameProvider()?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-MolaGPT-Agent-Device-Name", it)
        }
    }

    suspend fun sendPrompt(sessionId: String, cmdId: String, text: String): Boolean =
        sendCommand(
            sessionId = sessionId,
            cmdId = cmdId,
            op = RelayCommandOp.Send,
            payload = buildJsonObject {
                put("text", text)
                putJsonArray("images") {}
            },
        )

    suspend fun switchOptions(
        sessionId: String,
        cmdId: String,
        model: String? = null,
        reasoningEffort: String? = null,
        permissionMode: Int? = null,
        approvalPolicy: Int? = null,
    ): Boolean = sendCommand(
        sessionId = sessionId,
        cmdId = cmdId,
        op = RelayCommandOp.SwitchOptions,
        payload = buildJsonObject {
            model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoningEffort", it) }
            permissionMode?.let { put("permissionMode", it) }
            approvalPolicy?.let { put("approvalPolicy", it) }
        },
    )

    suspend fun interrupt(sessionId: String, cmdId: String): Boolean =
        sendCommand(sessionId, RelayCommandOp.Interrupt, cmdId)

    suspend fun approve(
        sessionId: String,
        cmdId: String,
        permissionId: String,
        choice: String,
    ): Boolean = sendCommand(
        sessionId = sessionId,
        cmdId = cmdId,
        op = RelayCommandOp.Approve,
        payload = buildJsonObject {
            put("permissionId", permissionId)
            put("choice", choice)
        },
    )

    suspend fun close(sessionId: String, cmdId: String): Boolean =
        sendCommand(sessionId, RelayCommandOp.Close, cmdId)

    suspend fun createSession(
        sessionId: String,
        cmdId: String,
        backendId: String,
        workingDirectory: String?,
        title: String?,
        model: String?,
    ): Boolean = sendCommand(
        sessionId = sessionId,
        cmdId = cmdId,
        op = RelayCommandOp.New,
        payload = buildJsonObject {
            put("backendId", backendId)
            workingDirectory?.takeIf { it.isNotBlank() }?.let { put("workingDirectory", it) }
            title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        },
    )

    private fun parseSessions(text: String): List<RelaySessionMeta> {
        val root = runCatching { http.json.parseToJsonElement(text).asObject() }.getOrNull()
            ?: return emptyList()
        val sessions = root.array("sessions") ?: run {
            Logger.w("AgentRelay", "no 'sessions' key in response: ${text.take(200)}")
            return emptyList()
        }
        return sessions.mapNotNull { (it as? JsonObject)?.toSessionMeta() }
    }

    private fun parseEventBatch(text: String, fallbackSeq: Long): EventBatch {
        val root = runCatching { http.json.parseToJsonElement(text).asObject() }.getOrNull()
            ?: throw Exception("bad event history json")
        val events = root.array("events")
            ?.mapNotNull { (it as? JsonObject)?.toEnvelope() }
            .orEmpty()
        val latestSeq = root.long("latestSeq", "latest_seq", "LatestSeq")
            ?: events.maxOfOrNull { it.seq }
            ?: fallbackSeq
        return EventBatch(events = events, latestSeq = latestSeq)
    }

    private fun JsonObject.toEnvelope(): RelayEnvelope? {
        val envelope = obj("event")?.takeIf { it.long("seq", "Seq") != null && it.obj("event", "Event") != null }
            ?: this
        val sessionId = envelope.string("sessionId", "session_id", "SessionId")
            ?: return null
        val seq = envelope.long("seq", "Seq") ?: return null
        val event = envelope.obj("event", "Event")?.toRelayEvent() ?: RelayEvent.Unknown
        return RelayEnvelope(sessionId = sessionId, seq = seq, event = event)
    }

    private fun JsonObject.toRelayEvent(): RelayEvent {
        val k = kind("kind", "Kind")
        return when (k) {
            "userPrompt" -> RelayEvent.UserPrompt(text = string("text", "Text").orEmpty())
            "answerSnapshot" -> RelayEvent.AnswerSnapshot(text = string("text", "Text").orEmpty())
            "thinkingSnapshot" -> RelayEvent.ThinkingSnapshot(text = string("text", "Text").orEmpty())
            "turnFailed" -> RelayEvent.TurnFailed(message = string("message", "Message").orEmpty())
            "turnDone" -> {
                val usage = obj("usage", "Usage")
                RelayEvent.TurnDone(
                    inputTokens = usage?.int("inputTokens", "input_tokens", "InputTokens"),
                    outputTokens = usage?.int("outputTokens", "output_tokens", "OutputTokens"),
                    totalTokens = usage?.int("totalTokens", "total_tokens", "TotalTokens"),
                )
            }
            "toolProgress", "ToolProgress" -> {
                val tool = obj("tool", "Tool") ?: this
                RelayEvent.ToolProgress(
                    id = tool.string("id", "Id").orEmpty(),
                    name = tool.string("name", "Name").orEmpty(),
                    status = tool.toolStatus("status", "Status"),
                    title = tool.string("title", "Title"),
                    argumentsJson = tool.string("argumentsJson", "arguments_json", "ArgumentsJson"),
                    resultPreview = tool.string("resultPreview", "result_preview", "ResultPreview"),
                )
            }
            "permissionPrompt" -> {
                val permission = obj("permission", "Permission") ?: this
                RelayEvent.PermissionPrompt(
                    id = permission.string("id", "Id").orEmpty(),
                    toolName = permission.string("toolName", "tool_name", "ToolName").orEmpty(),
                    description = permission.string("description", "Description"),
                    argumentsJson = permission.string("argumentsJson", "arguments_json", "ArgumentsJson"),
                )
            }
            else -> RelayEvent.Unknown
        }
    }

    private fun JsonObject.toSessionMeta(): RelaySessionMeta? {
        val conversationId = string("conversationId", "conversation_id", "ConversationId")
            ?: return null
        return RelaySessionMeta(
            conversationId = conversationId,
            backendId = string("backendId", "backend_id", "BackendId").orEmpty(),
            title = string("title", "Title").orEmpty(),
            workingDirectory = string("workingDirectory", "working_directory", "cwd", "WorkingDirectory").orEmpty(),
            workspaceKey = string("workspaceKey", "workspace_key", "WorkspaceKey"),
            workspaceName = string("workspaceName", "workspace_name", "WorkspaceName"),
            model = string("model", "Model"),
            reasoningEffort = string("reasoningEffort", "reasoning_effort", "ReasoningEffort"),
            permissionMode = enumOrdinal(AGENT_PERMISSION_MODES, default = 1, "permissionMode", "permission_mode", "PermissionMode"),
            approvalPolicy = nullableEnumOrdinal(CODEX_APPROVAL_POLICIES, "approvalPolicy", "approval_policy", "ApprovalPolicy"),
            phase = enumOrdinal(AGENT_PHASES, default = AgentPhase.Idle.ordinal, "phase", "Phase"),
            needsAttention = bool("needsAttention", "needs_attention", "NeedsAttention") ?: false,
            seq = long("seq", "Seq") ?: 0L,
            updatedAtMs = long("updatedAtMs", "updated_at_ms", "UpdatedAtMs") ?: 0L,
            activityAtMs = long("activityAtMs", "activity_at_ms", "ActivityAtMs") ?: 0L,
            // 手写解析必须显式取 availableModels——桌面从 CLI 实时发现的可切换模型目录
            // （Claude initialize.models / Codex model/list）。漏了它模型选择器就只剩"默认"。
            availableModels = array("availableModels", "available_models", "AvailableModels")?.let { arr ->
                runCatching {
                    http.json.decodeFromJsonElement(ListSerializer(AgentModelInfo.serializer()), arr)
                }.getOrNull()
            },
        )
    }

    private fun JsonObject.kind(vararg names: String): String? =
        string(*names)?.let { raw ->
            KIND_ALIASES[raw] ?: raw.replaceFirstChar { it.lowercaseChar() }
        }

    private fun JsonObject.toolStatus(vararg names: String): AgentToolStatus {
        val raw = firstPrimitive(*names) ?: return AgentToolStatus.Started
        raw.intOrNull?.let { return AgentToolStatus.fromOrdinal(it) }
        val text = raw.contentOrNull ?: return AgentToolStatus.Started
        return AgentToolStatus.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
            ?: AgentToolStatus.Started
    }

    private fun JsonObject.enumOrdinal(names: Array<String>, default: Int, vararg keys: String): Int =
        nullableEnumOrdinal(names, *keys) ?: default

    private fun JsonObject.nullableEnumOrdinal(names: Array<String>, vararg keys: String): Int? {
        val raw = firstPrimitive(*keys) ?: return null
        raw.intOrNull?.let { return it }
        val text = raw.contentOrNull ?: return null
        return names.indexOfFirst { it.equals(text, ignoreCase = true) }.takeIf { it >= 0 }
    }

    private fun JsonObject.string(vararg names: String): String? =
        firstPrimitive(*names)?.contentOrNull

    private fun JsonObject.int(vararg names: String): Int? =
        firstPrimitive(*names)?.intOrNull

    private fun JsonObject.long(vararg names: String): Long? =
        firstPrimitive(*names)?.longOrNull

    private fun JsonObject.bool(vararg names: String): Boolean? =
        firstPrimitive(*names)?.booleanOrNull

    private fun JsonObject.obj(vararg names: String): JsonObject? =
        names.firstNotNullOfOrNull { this[it] as? JsonObject }

    private fun JsonObject.array(vararg names: String): JsonArray? =
        names.firstNotNullOfOrNull { this[it] as? JsonArray }

    private fun JsonObject.firstPrimitive(vararg names: String): JsonPrimitive? =
        names.firstNotNullOfOrNull { this[it] as? JsonPrimitive }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private companion object {
        private val AGENT_PHASES = arrayOf("Idle", "Spawning", "Running", "Waiting", "Completed", "Failed")
        private val AGENT_PERMISSION_MODES = arrayOf("Plan", "AcceptEdits", "Default", "BypassPermissions")
        private val CODEX_APPROVAL_POLICIES = arrayOf("Untrusted", "OnRequest", "Never")
        private val KIND_ALIASES = mapOf(
            "UserPrompt" to "userPrompt",
            "ToolProgress" to "toolProgress",
            "PermissionPrompt" to "permissionPrompt",
            "AnswerSnapshot" to "answerSnapshot",
            "ThinkingSnapshot" to "thinkingSnapshot",
            "TurnDone" to "turnDone",
            "TurnFailed" to "turnFailed",
        )
    }

    private data class EventBatch(
        val events: List<RelayEnvelope>,
        val latestSeq: Long,
    )
}
