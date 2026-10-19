package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.DesktopConversation
import com.molagpt.app.core.model.DesktopInfo
import com.molagpt.app.core.model.DesktopLinkState
import com.molagpt.app.core.model.RemoteApproval
import com.molagpt.app.core.model.RemoteAttachmentRules
import com.molagpt.app.core.model.RemoteDraftDefaults
import com.molagpt.app.core.model.RemoteModelChoice
import com.molagpt.app.core.model.RemoteModelGroup
import com.molagpt.app.core.model.RemotePersona
import com.molagpt.app.core.model.RemoteSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 电脑拒绝了请求，或请求没能送到。[code] 见协议文档；[message] 可直接给人看。 */
class DesktopRemoteException(val code: String, message: String) : Exception(message)

/** 电脑推来的事件。[data] 的形状由 [event] 决定，见协议文档 §5.3。 */
data class DesktopPush(
    val machineId: String,
    val conversationId: String,
    val event: String,
    val seq: Long,
    val data: JsonObject,
)

/** 与每台电脑的配对身份。按账号隔离，由 app 层加密保存。 */
interface RemotePairStore {
    fun load(machineId: String): RemotePair?
    fun save(machineId: String, pair: RemotePair, expectedJwt: String): Boolean
    fun remove(machineId: String)
    fun hasAny(): Boolean
}

/**
 * 手机远控电脑上 MolaGPT 对话的客户端。
 *
 *  - 电脑列表与各电脑的对话列表走 HTTP（`agent_desktops.php`），电脑睡着也能看到它。
 *  - 内容与操作走账号的实时中转（`/agent-realtime`，terminal 角色）：服务器只转发，不存。
 *    需要内容的页面用 [acquire] / [release] 表明自己在看，没人看时断开。
 *  - 同账号可一键配对，也可使用配对码；普通操作均使用配对密钥签名。
 */
class DesktopRemoteService(
    private val http: MolaHttp,
    private val dispatchers: DispatcherProvider,
    private val jwtProvider: () -> String?,
    private val pairs: RemotePairStore,
    /** 当前账号的电脑列表缓存文件；未登录时为 null。 */
    private val listCacheFile: () -> File? = { null },
) {
    private val base = MolaEndpoints.BASE_URL.trimEnd('/')
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val jsonHttp = http.okHttp.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 长连接：没有整体超时，靠中转每 20 秒的 ping 与 OkHttp 的 pingInterval 发现断线。
    private val socketHttp = http.okHttp.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _link = MutableStateFlow(DesktopLinkState.Disconnected)
    val link: StateFlow<DesktopLinkState> = _link.asStateFlow()

    private val _onlineMachines = MutableStateFlow<Set<String>>(emptySet())
    /** 此刻连在中转上的电脑；只在 [link] 为 Connected 时可信。 */
    val onlineMachines: StateFlow<Set<String>> = _onlineMachines.asStateFlow()

    private val _pushes = MutableSharedFlow<DesktopPush>(extraBufferCapacity = 512)
    val pushes: SharedFlow<DesktopPush> = _pushes.asSharedFlow()

    private val _listChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 电脑的对话列表可能变了（中转提示）。 */
    val listChanged: SharedFlow<Unit> = _listChanged.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketJwt: String? = null
    @Volatile private var users = 0
    private var loop: Job? = null

    // ---- 电脑列表 -------------------------------------------------------------

    private val _desktops = MutableStateFlow<List<DesktopInfo>?>(null)
    /** [_desktops] 属于哪个账号的缓存文件；同一账号已有结果时，读缓存不能盖掉更新的网络结果。 */
    @Volatile private var desktopsOwner: File? = null

    /**
     * 最近一次拿到的电脑列表；null 表示当前账号还没有任何结果。
     *
     * 每次 [listDesktops] 成功都会更新它并写进本地缓存，[restoreDesktops] 在冷启动和切换账号时先读回缓存。
     * 侧边栏等列表直接展示它，打开时不必等网络，刷新回来原地替换。
     * 缓存里的在线、忙碌状态可能已过时，连上中转或刷新后即被纠正。
     */
    val desktops: StateFlow<List<DesktopInfo>?> = _desktops.asStateFlow()

    /** 换成当前账号的本地缓存（未登录或没有缓存时清空）。登录态变化时调用。 */
    suspend fun restoreDesktops() = withContext(dispatchers.io) {
        val file = listCacheFile()
        if (file != null && file == desktopsOwner && _desktops.value != null) return@withContext
        desktopsOwner = file
        _desktops.value = file?.takeIf { it.isFile }?.let { cached ->
            runCatching { parseDesktops(cached.readText()) }
                .onFailure { Logger.w(TAG, "desktop list cache unreadable: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun listDesktops(): List<DesktopInfo> = withContext(dispatchers.io) {
        val jwt = jwtProvider() ?: return@withContext emptyList()
        val request = Request.Builder()
            .url("$base/api/auth/agent_desktops.php")
            .header("Authorization", "Bearer $jwt")
            .get()
            .build()
        val body = jsonHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DesktopRemoteException("http", "无法读取电脑列表（HTTP ${response.code}）")
            response.body!!.string()
        }
        val desktops = parseDesktops(body)
        // 请求途中换了账号：结果属于上一个账号，不能写进当前账号的列表和缓存。
        if (jwtProvider() == jwt) {
            val file = listCacheFile()
            desktopsOwner = file
            _desktops.value = desktops
            file?.let { saveListCache(it, body) }
        }
        desktops
    }

    private fun parseDesktops(body: String): List<DesktopInfo> {
        val root = http.json.parseToJsonElement(body) as? JsonObject
        return (root?.get("desktops") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toDesktop() }
    }

    private fun saveListCache(file: File, body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(body)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Logger.w(TAG, "desktop list cache save failed: ${it.message}") }
    }

    // ---- 电脑上的模型 ---------------------------------------------------------

    private val modelGroupsCache = ConcurrentHashMap<String, List<RemoteModelGroup>>()
    /** [modelGroupsCache] 属于哪个账号；换了账号就整个作废。 */
    @Volatile private var modelGroupsOwner: File? = null

    /**
     * 上次从这台电脑取到的模型；没取过为 null。只在内存里，切换模型菜单时先显示它，
     * 再由 [modelGroups] 在后台刷新，不必每次都等电脑回话。
     */
    fun cachedModelGroups(machineId: String): List<RemoteModelGroup>? {
        if (listCacheFile() != modelGroupsOwner) return null
        return modelGroupsCache[machineId]
    }

    /** 向电脑取它的模型，并记进 [cachedModelGroups]。 */
    suspend fun modelGroups(machineId: String): List<RemoteModelGroup> {
        val owner = listCacheFile()
        val groups = parseModelGroups(request(machineId, "models"))
        // 请求途中换了账号：结果不属于当前账号。
        if (listCacheFile() == owner) {
            if (owner != modelGroupsOwner) {
                modelGroupsCache.clear()
                modelGroupsOwner = owner
            }
            modelGroupsCache[machineId] = groups
        }
        return groups
    }

    /** 从列表里移除一台电脑，并忘掉与它的配对。电脑再次上线会重新出现，需要重新配对。 */
    suspend fun forgetDesktop(machineId: String) = withContext(dispatchers.io) {
        pairs.remove(machineId)
        val jwt = jwtProvider() ?: return@withContext
        val request = Request.Builder()
            .url("$base/api/auth/agent_desktops.php?machineId=${java.net.URLEncoder.encode(machineId, "UTF-8")}")
            .header("Authorization", "Bearer $jwt")
            .delete()
            .build()
        runCatching { jsonHttp.newCall(request).execute().close() }
    }

    fun isPaired(machineId: String): Boolean = pairs.load(machineId) != null

    /** 是否与任何电脑配对过；没有就不必轮询电脑列表。 */
    fun hasAnyPair(): Boolean = pairs.hasAny()

    // ---- 连接 -----------------------------------------------------------------

    /** 有页面需要实时内容；与 [release] 成对调用。 */
    @Synchronized
    fun acquire() {
        users++
        // 没有连接时马上就会去连：先报「连接中」，免得刚进页面的人先看到一闪「已断开」。
        if (socket == null) _link.value = DesktopLinkState.Connecting
        if (loop?.isActive != true) loop = scope.launch { runSocket() }
        wake.trySend(Unit)
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        wake.trySend(Unit)
    }

    /** 网络恢复、回到前台时立刻重连，而不是等退避。 */
    fun reconnectNow() {
        socket?.takeIf { socketJwt != jwtProvider() }?.let {
            it.cancel()
            closed(it)
        }
        if (socket == null && users > 0) _link.value = DesktopLinkState.Connecting
        wake.trySend(Unit)
    }

    private suspend fun runSocket() {
        var attempt = 0
        while (true) {
            if (users == 0) {
                // 没人看就断开；留一点余量，翻页式的离开再进入不必重连。
                val stillIdle = withTimeoutOrNull(IDLE_DISCONNECT_MS) { while (users == 0) wake.receive(); false } ?: true
                if (stillIdle) {
                    socket?.close(1000, "idle")
                    socket = null
                    socketJwt = null
                    _link.value = DesktopLinkState.Disconnected
                    while (users == 0) wake.receive()
                }
            }
            socket?.takeIf { socketJwt != jwtProvider() }?.let {
                it.cancel()
                closed(it)
            }
            if (socket == null) {
                _link.value = DesktopLinkState.Connecting
                val opened = runCatching { connect() }.onFailure {
                    Logger.w(TAG, "connect failed: ${it.message}")
                }.getOrDefault(false)
                if (!opened) {
                    _link.value = DesktopLinkState.Disconnected
                    val backoff = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
                    attempt++
                    withTimeoutOrNull(backoff) { wake.receive() }
                    continue
                }
                attempt = 0
            }
            // 连接期间只等两件事：断开（connect 里的回调把 socket 置空并唤醒），或使用者变化。
            wake.receive()
        }
    }

    private suspend fun connect(): Boolean {
        val jwt = jwtProvider() ?: return false
        val ticketRequest = Request.Builder()
            .url("$base/api/auth/agent_realtime.php?role=terminal")
            .header("Authorization", "Bearer $jwt")
            .get()
            .build()
        val ticket = withContext(dispatchers.io) {
            jsonHttp.newCall(ticketRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                (http.json.parseToJsonElement(response.body!!.string()) as? JsonObject)?.string("ticket")
            }
        } ?: return false
        val opened = CompletableDeferred<Boolean>()
        val url = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/agent-realtime"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $ticket").build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                runCatching { onFrame(text) }.onFailure { Logger.w(TAG, "frame: ${it.message}") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                closed(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                closed(webSocket)
            }
        }
        val created = socketHttp.newWebSocket(request, listener)
        socketJwt = jwt
        socket = created
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() } ?: false
        if (!ok) {
            created.cancel()
            if (socket === created) {
                socket = null
                socketJwt = null
            }
        }
        return ok
    }

    private fun closed(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        socketJwt = null
        _link.value = DesktopLinkState.Disconnected
        _onlineMachines.value = emptySet()
        val failed = DesktopRemoteException("disconnected", "连接已断开")
        pending.values.forEach { it.completeExceptionally(failed) }
        pending.clear()
        wake.trySend(Unit)
    }

    private fun onFrame(text: String) {
        val frame = http.json.parseToJsonElement(text) as? JsonObject ?: return
        val from = frame.string("from")
        val body = frame["body"] as? JsonObject
        if (from != null && body != null) {
            body.string("re")?.let { id -> pending.remove(id)?.complete(body); return }
            val event = body.string("ev") ?: return
            val conversationId = body.string("conversationId") ?: return
            _pushes.tryEmit(
                DesktopPush(
                    machineId = from,
                    conversationId = conversationId,
                    event = event,
                    seq = body.long("seq") ?: 0,
                    data = body["data"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
            )
            return
        }
        when (frame.string("kind")) {
            "connected" -> {
                _onlineMachines.value = (frame["machines"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                _link.value = DesktopLinkState.Connected
                _listChanged.tryEmit(Unit)
            }
            "presence" -> {
                val machine = frame.string("machineId") ?: return
                _onlineMachines.value = if (frame.bool("online") == true) _onlineMachines.value + machine
                else _onlineMachines.value - machine
                _listChanged.tryEmit(Unit)
            }
            "undeliverable" -> frame.string("ref")?.let { id ->
                pending.remove(id)?.completeExceptionally(DesktopRemoteException("offline", "电脑已离线"))
            }
            "sessions" -> if (frame.string("sessionId").isNullOrEmpty()) _listChanged.tryEmit(Unit)
        }
    }

    // ---- 请求 -----------------------------------------------------------------

    /**
     * 向 [machineId] 发一个请求并等它的答复。电脑拒绝或送不到时抛 [DesktopRemoteException]。
     * 调用方需已 [acquire]；连接尚未建立时最多等 [CONNECT_TIMEOUT_MS]。
     */
    suspend fun request(machineId: String, op: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        val pair = pairs.load(machineId) ?: throw DesktopRemoteException("unpaired", "配对已失效，请重新配对")
        val reply = send(machineId, op, http.json.encodeToString(JsonObject.serializer(), args), pair)
        return reply["data"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** 不关心结果的请求（如离开页面时的 close），在服务自己的作用域里发出，不随页面取消。 */
    fun requestQuietly(machineId: String, op: String, args: JsonObject) {
        scope.launch { runCatching { request(machineId, op, args) } }
    }

    /** 用电脑上显示的配对码与它配对。成功后返回电脑名。 */
    suspend fun pair(machineId: String, code: String, deviceName: String, deviceId: String): String {
        val normalized = RemotePairing.normalizeCode(code)
        if (normalized.length != RemotePairing.CODE_LENGTH) throw DesktopRemoteException("bad_code", "请输入 8 位配对码")
        return pairDevice(machineId, normalized, deviceName, deviceId)
    }

    /** 在中继已验证的同账号连接内配对，无需输入配对码。 */
    suspend fun pairWithAccount(machineId: String, deviceName: String, deviceId: String): String =
        pairDevice(machineId, null, deviceName, deviceId)

    private suspend fun pairDevice(machineId: String, code: String?, deviceName: String, deviceId: String): String {
        val jwt = jwtProvider() ?: throw DesktopRemoteException("auth", "请先登录 MolaGPT 账号")
        val offer = withContext(dispatchers.default) { RemotePairing.offer(code ?: RemotePairing.ACCOUNT_PAIRING_CONTEXT) }
        val args = buildJsonObject {
            put("pub", offer.publicKey)
            if (code != null) put("mac", offer.mac)
            put("device", deviceName)
            put("deviceId", deviceId)
        }
        val reply = send(machineId, if (code == null) "pair_account" else "pair", http.json.encodeToString(JsonObject.serializer(), args), null)
        val data = reply["data"] as? JsonObject ?: throw DesktopRemoteException("bad_reply", "身份验证失败，请重新配对")
        val pair = withContext(dispatchers.default) {
            val publicKey = data.string("pub").orEmpty()
            val proof = data.string("mac").orEmpty()
            val pairId = data.string("pairId").orEmpty()
            if (code == null) RemotePairing.acceptAccount(offer, publicKey, proof, pairId)
            else RemotePairing.accept(code, offer, publicKey, proof, pairId)
        } ?: throw DesktopRemoteException("bad_reply", "身份验证失败，请重新配对")
        if (!pairs.save(machineId, pair, jwt) || jwt != jwtProvider()) {
            throw DesktopRemoteException("auth", "账号已变更，请重新配对")
        }
        _listChanged.tryEmit(Unit)
        return data.string("machineName").orEmpty()
    }

    private suspend fun send(machineId: String, op: String, args: String, pair: RemotePair?): JsonObject {
        val jwt = jwtProvider() ?: throw DesktopRemoteException("auth", "请先登录 MolaGPT 账号")
        if (socketJwt != jwt) reconnectNow()
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { link.first { it == DesktopLinkState.Connected && socketJwt == jwt } }
            ?: throw DesktopRemoteException("disconnected", "无法连接服务，请检查网络")
        if (jwt != jwtProvider()) throw DesktopRemoteException("auth", "账号已变更，请重新连接")
        val connection = socket ?: throw DesktopRemoteException("disconnected", "连接已断开")
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        val body = buildJsonObject {
            put("v", 1)
            put("id", id)
            put("op", op)
            put("ts", ts)
            put("a", args)
            put("pair", pair?.pairId.orEmpty())
            put("sig", pair?.let { RemotePairing.sign(it, id, op, ts, args) }.orEmpty())
        }
        val frame = buildJsonObject {
            put("to", machineId)
            put("body", body)
        }
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val sent = connection === socket && socketJwt == jwt && jwtProvider() == jwt &&
            connection.send(http.json.encodeToString(JsonObject.serializer(), frame))
        if (!sent) {
            pending.remove(id)
            throw DesktopRemoteException("disconnected", "连接已断开")
        }
        val reply = try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            pending.remove(id)
            throw DesktopRemoteException("timeout", "电脑未响应")
        }
        if (jwt != jwtProvider()) throw DesktopRemoteException("auth", "账号已变更，请重新连接")
        if (reply.bool("ok") != true) {
            if (reply.string("code") == "unpaired") pairs.remove(machineId)
            throw DesktopRemoteException(reply.string("code") ?: "error", reply.string("message") ?: "操作失败")
        }
        return reply
    }

    // ---- 解析 -----------------------------------------------------------------

    private fun JsonObject.toDesktop(): DesktopInfo? {
        val machineId = string("machineId") ?: return null
        return DesktopInfo(
            machineId = machineId,
            machineName = string("machineName")?.takeIf { it.isNotBlank() } ?: "电脑",
            appVersion = string("appVersion").orEmpty(),
            online = bool("online") == true,
            lastSeenAtMs = long("lastSeenAtMs") ?: 0,
            conversations = (this["conversations"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.toConversation() },
        )
    }

    private fun JsonObject.toConversation(): DesktopConversation? = DesktopConversation(
        id = string("id") ?: return null,
        title = string("title")?.takeIf { it.isNotBlank() } ?: "新对话",
        modelLabel = string("modelLabel"),
        providerLabel = string("providerLabel"),
        updatedAtMs = long("updatedAtMs") ?: 0,
        busy = bool("busy") == true,
        pendingApprovals = int("pendingApprovals") ?: 0,
    )

    companion object {
        private const val TAG = "DesktopRemote"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val IDLE_DISCONNECT_MS = 30_000L
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        fun parseSettings(data: JsonObject?): RemoteSettings? {
            data ?: return null
            val network = data["network"] as? JsonObject
            val thinking = data["thinking"] as? JsonObject
            val attachments = data["attachments"] as? JsonObject
            return RemoteSettings(
                providerId = data.string("providerId") ?: return null,
                modelId = data.string("modelId") ?: return null,
                model = data.string("model") ?: data.string("modelId") ?: return null,
                provider = data.string("provider").orEmpty(),
                family = data.string("family") ?: "byok",
                networkAvailable = network?.bool("available") == true,
                networkOn = network?.bool("on") == true,
                thinkingToggle = thinking?.bool("toggle") == true,
                thinkingOn = thinking?.bool("on") == true,
                effortLevels = (thinking?.get("levels") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull },
                effort = thinking?.string("effort"),
                attachments = attachments?.let {
                    RemoteAttachmentRules(images = it.bool("images") == true, files = it.bool("files") == true)
                },
            )
        }

        fun parseModelGroups(data: JsonObject): List<RemoteModelGroup> =
            (data["groups"] as? JsonArray).orEmpty().mapNotNull { element ->
                val group = element as? JsonObject ?: return@mapNotNull null
                RemoteModelGroup(
                    providerId = group.string("providerId") ?: return@mapNotNull null,
                    provider = group.string("provider").orEmpty(),
                    family = group.string("family") ?: "byok",
                    models = (group["models"] as? JsonArray).orEmpty().mapNotNull { item ->
                        val model = item as? JsonObject ?: return@mapNotNull null
                        RemoteModelChoice(
                            id = model.string("id") ?: return@mapNotNull null,
                            label = model.string("label") ?: model.string("id")!!,
                        )
                    },
                ).takeIf { it.models.isNotEmpty() }
            }

        fun parseDraftDefaults(data: JsonObject): RemoteDraftDefaults? = RemoteDraftDefaults(
            settings = parseSettings(data["settings"] as? JsonObject) ?: return null,
            personaId = data.string("personaId"),
            personas = (data["personas"] as? JsonArray).orEmpty().mapNotNull { item ->
                val persona = item as? JsonObject ?: return@mapNotNull null
                RemotePersona(
                    id = persona.string("id") ?: return@mapNotNull null,
                    name = persona.string("name").orEmpty(),
                    preview = persona.string("preview").orEmpty(),
                )
            },
        )

        fun parseApproval(data: JsonObject): RemoteApproval? = RemoteApproval(
            requestId = data.string("requestId") ?: return null,
            conversationId = data.string("conversationId").orEmpty(),
            kind = data.string("kind") ?: "tool",
            toolName = data.string("toolName").orEmpty(),
            displayName = data.string("displayName")?.takeIf { it.isNotBlank() } ?: data.string("toolName").orEmpty(),
            summary = data.string("summary"),
            argumentsPreview = data.string("argumentsPreview"),
            codePreview = data.string("codePreview"),
            resolvedPath = data.string("resolvedPath"),
            capabilities = (data["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            options = (data["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            createdAtMs = data.long("createdAtMs") ?: 0,
        )
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
