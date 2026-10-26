package com.molagpt.app.core.network

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.DesktopConversation
import com.molagpt.app.core.model.DesktopInfo
import com.molagpt.app.core.model.DesktopLinkState
import com.molagpt.app.core.model.RemoteModelGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 匿名配对记录：一台电脑一份，master 永不出本机。 */
data class AnonPairRecord(
    val deviceId: String,
    val pairId: ByteArray,
    val master: ByteArray,
    val machineName: String,
    /** 电脑经端到端通道发来的密文目录密钥；重配对后更新。 */
    val directoryKey: ByteArray?,
)

/** 匿名配对的本地存储，app 层加密实现。 */
interface AnonPairStore {
    fun list(): List<AnonPairRecord>
    fun load(deviceId: String): AnonPairRecord?
    fun save(record: AnonPairRecord)
    fun remove(deviceId: String)
}

class AnonRemoteException(val code: String, message: String) : Exception(message)

/**
 * 无账号远控：用配对码 + 校验码与电脑建立端到端密钥，之后所有业务帧都是
 * AES-GCM 密文，服务器只转发。每台已配对电脑一条 terminal 连接。
 */
class AnonRemoteService(
    private val http: MolaHttp,
    private val dispatchers: DispatcherProvider,
    private val pairs: AnonPairStore,
    private val grants: AnonGrantSigner,
    private val phoneName: String,
    private val phoneDeviceId: String,
    /** 电脑报来它的账号机器 id：同一台电脑的账号配对就此多余。 */
    private val onAccountTwin: (accountMachineId: String) -> Unit = {},
) : RemoteDesktopChannel {
    private val base = MolaEndpoints.BASE_URL.trimEnd('/')
    private val authority = "chatgpt.wljay.cn"
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val jsonHttp = http.okHttp.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val socketHttp = http.okHttp.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, AnonConnection>()
    private val _link = MutableStateFlow(DesktopLinkState.Disconnected)
    override val link: StateFlow<DesktopLinkState> = _link.asStateFlow()

    private val _onlineMachines = MutableStateFlow<Set<String>>(emptySet())
    override val onlineMachines: StateFlow<Set<String>> = _onlineMachines.asStateFlow()

    private val _pushes = MutableSharedFlow<DesktopPush>(extraBufferCapacity = 512)
    override val pushes: SharedFlow<DesktopPush> = _pushes.asSharedFlow()

    private val _listChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val listChanged: SharedFlow<Unit> = _listChanged.asSharedFlow()

    @Volatile private var users = 0
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val skew = java.util.concurrent.atomic.AtomicLong(0)

    private val modelGroupsCache = ConcurrentHashMap<String, List<RemoteModelGroup>>()

    // ---- lifecycle ---------------------------------------------------------------

    @Synchronized
    override fun acquire() {
        users++
        if (connections.isEmpty() && pairs.list().isEmpty()) return
        // 已有连上的电脑时保持「已连接」：之后没有状态变化会再把它改回来。
        if (connections.values.none { it.isReady }) _link.value = DesktopLinkState.Connecting
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        wake.trySend(Unit)
    }

    @Synchronized
    override fun release() {
        users = (users - 1).coerceAtLeast(0)
        wake.trySend(Unit)
    }

    override fun reconnectNow() {
        connections.values.forEach { it.reconnect() }
        if (connections.isNotEmpty() && users > 0) _link.value = DesktopLinkState.Connecting
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (true) {
            if (users == 0) {
                val stillIdle = withTimeoutOrNull(IDLE_DISCONNECT_MS) { while (users == 0) wake.receive(); false } ?: true
                if (stillIdle) {
                    connections.values.forEach { it.stop() }
                    connections.clear()
                    _link.value = DesktopLinkState.Disconnected
                    _onlineMachines.value = emptySet()
                    while (users == 0) wake.receive()
                }
            }
            ensureConnections()
            wake.receive()
            attempt = 0
        }
    }

    // The loop and a request can both get here; two connections for one desktop
    // would fight over the same grant on the relay.
    @Synchronized
    private fun ensureConnections() {
        for (record in pairs.list()) {
            if (!grants.has(record.deviceId)) {
                // 授权密钥不在了（换机/清数据）：本地记录无法使用，等用户重新配对。
                continue
            }
            if (connections.containsKey(record.deviceId)) continue
            val connection = AnonConnection(record)
            connections[record.deviceId] = connection
            connection.start()
        }
    }

    private fun refreshAggregate() {
        val values = connections.values.toList()
        _onlineMachines.value = values.filter { it.isReady }.map { it.deviceId }.toSet()
        _link.value = when {
            values.isEmpty() -> DesktopLinkState.Disconnected
            values.any { it.isReady } -> DesktopLinkState.Connected
            else -> DesktopLinkState.Connecting
        }
        _listChanged.tryEmit(Unit)
    }

    // ---- requests ------------------------------------------------------------------

    override suspend fun request(machineId: String, op: String, args: JsonObject): JsonObject {
        val connection = awaitConnection(machineId)
        val reply = connection.request(op, args)
        return reply.optObj("data") ?: JsonObject(emptyMap())
    }

    override fun requestQuietly(machineId: String, op: String, args: JsonObject) {
        scope.launch { runCatching { request(machineId, op, args) } }
    }

    private suspend fun awaitConnection(deviceId: String): AnonConnection {
        val record = pairs.load(deviceId) ?: throw DesktopRemoteException("unpaired", "配对已失效，请重新配对")
        if (!grants.has(deviceId)) throw DesktopRemoteException("unpaired", "授权密钥已失效，请重新配对")
        val connection = connections[deviceId] ?: run {
            ensureConnections()
            connections[deviceId] ?: throw DesktopRemoteException("offline", "电脑已离线")
        }
        withTimeout(CONNECT_TIMEOUT_MS) { connection.ready.await() }
        return connection
    }

    override suspend fun modelGroups(machineId: String): List<RemoteModelGroup> {
        val groups = DesktopRemoteService.parseModelGroups(request(machineId, "models"))
        modelGroupsCache[machineId] = groups
        return groups
    }

    override fun cachedModelGroups(machineId: String): List<RemoteModelGroup>? = modelGroupsCache[machineId]

    // ---- pairing ---------------------------------------------------------------------

    /**
     * 用电脑显示的配对码配对。两端各显示一个校验码，[confirmSas] 等用户在手机上
     * 确认一致（电脑上也要点允许）；电脑先拒绝或断开时它被取消。不一致、超时或被拒抛
     * [AnonRemoteException]。两边都确认，服务器才无法把手机引到一台冒充的电脑上。
     */
    suspend fun pairWithCode(rawCode: String, confirmSas: suspend (sas: String) -> Boolean): String = withContext(dispatchers.io) {
        val room = normalizeRoom(rawCode) ?: throw AnonRemoteException("bad_code", "请输入 8 位配对码")
        val join = postJson("anon_pair.php", buildJsonObject { put("op", "join"); put("room", room) }) { request, _ ->
            // join 无需签名：房间码本身就是限流主体。
            jsonHttp.newCall(request.build()).execute().use { parseBody(it) }
        }
        val deviceId = join.optString("deviceId") ?: throw AnonRemoteException("no_room", "房间不存在或已关闭")
        val roomId = AnonCrypto.unb64u(join.optString("roomId") ?: "") ?: throw AnonRemoteException("no_room", "房间不存在或已关闭")
        val ticket = join.optString("ticket") ?: throw AnonRemoteException("no_room", "房间不存在或已关闭")

        val grantPub = grants.generate(deviceId)
        val eph = AnonCrypto.newEphemeral()
        val nP = AnonCrypto.randomBytes(32)
        val handshakeId = AnonCrypto.randomBytes(16)
        val commit = AnonProtocol.commit(deviceId, roomId, room, handshakeId, grantPub, phoneName, eph.spki, nP)

        val finished = CompletableDeferred<JsonObject>()
        val offer = CompletableDeferred<JsonObject>()
        var socket: WebSocket? = null
        val url = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/agent-realtime"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $ticket").build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(http.json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("to", deviceId)
                    put("body", buildJsonObject {
                        put("v", 2)
                        put("ctl", "pair2_commit")
                        put("roomId", AnonCrypto.b64u(roomId))
                        put("handshakeId", AnonCrypto.b64u(handshakeId))
                        put("C", AnonCrypto.b64u(commit))
                        put("grantPub", AnonCrypto.b64u(grantPub))
                        put("phoneName", phoneName)
                    })
                })
            )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = http.json.parseToJsonElement(text).asObject() ?: return
                val body = frame["body"].asObject() ?: return
                when (body.optString("ctl")) {
                    "pair2_offer" -> offer.complete(body)
                    "pair2_finish" -> finished.complete(body)
                    "pair2_error" -> finished.completeExceptionally(
                        AnonRemoteException("declined", body.optString("reason")?.toUserText() ?: "配对被拒绝"))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finished.completeExceptionally(AnonRemoteException("closed", "配对未完成，请重试"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                offer.completeExceptionally(t)
                finished.completeExceptionally(AnonRemoteException("network", "无法连接服务，请检查网络"))
            }
        }
        socket = socketHttp.newWebSocket(request, listener)
        try {
            val desktopOffer = withTimeout(20_000) { offer.await() }
            val ephPubD = AnonCrypto.unb64u(desktopOffer.optString("ephPubD") ?: "") ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            val nD = AnonCrypto.unb64u(desktopOffer.optString("nD") ?: "") ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            val devicePub = AnonCrypto.unb64u(desktopOffer.optString("devicePub") ?: "") ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            val deviceSig = AnonCrypto.unb64u(desktopOffer.optString("deviceSig") ?: "") ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            if (!AnonCrypto.isP256Spki(devicePub) || nD.size != 32
                || deviceId != AnonCrypto.deviceIdOf(devicePub)
                || roomId.contentEquals(AnonCrypto.unb64u(desktopOffer.optString("roomId") ?: "")) != true
                || handshakeId.contentEquals(AnonCrypto.unb64u(desktopOffer.optString("handshakeId") ?: "")) != true) {
                throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            }
            // 电脑签名的 offer 绑定本次承诺；手输码路径这仍不能替代 SAS 核对。
            val offerInput = AnonProtocol.offerSignatureInput(
                deviceId, roomId, room, handshakeId, commit, grantPub, phoneName, ephPubD, nD, devicePub)
            if (!AnonCrypto.verifyDer(offerInput, deviceSig, devicePub)) {
                throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            }

            socket!!.send(http.json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("to", deviceId)
                put("body", buildJsonObject {
                    put("v", 2)
                    put("ctl", "pair2_reveal")
                    put("roomId", AnonCrypto.b64u(roomId))
                    put("handshakeId", AnonCrypto.b64u(handshakeId))
                    put("ephPubP", AnonCrypto.b64u(eph.spki))
                    put("nP", AnonCrypto.b64u(nP))
                })
            })
            )

            val z = eph.ecdhRaw(ephPubD)
            val transcript = AnonProtocol.transcript(
                deviceId, roomId, room, handshakeId, commit, grantPub, phoneName, eph.spki, nP, ephPubD, nD, devicePub)
            val keys = AnonProtocol.deriveMaster(z, transcript)
            val sas = AnonProtocol.sasCode(keys.sessionAuth, transcript)
            // The desktop declining or the socket closing cancels the question.
            val answer = scope.async(dispatchers.main) { confirmSas(sas) }
            val watch = finished.invokeOnCompletion { cause -> if (cause != null) answer.cancel() }
            val confirmed = try {
                answer.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                finished.await() // rethrows why the desktop side ended
                false
            } finally {
                watch.dispose()
                answer.cancel()
            }
            if (!confirmed) throw AnonRemoteException("mismatch", "校验码不一致，已取消配对")

            val finish = withTimeout(90_000) { finished.await() }
            val ct = AnonCrypto.unb64u(finish.optString("ct") ?: "") ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            val tag = AnonCrypto.unb64u(finish.optString("tag") ?: "") ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            val plain = http.json.parseToJsonElement(
                AnonProtocol.openFinish(keys.finish, transcript, ct, tag).toString(Charsets.UTF_8)).asObject()
                ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            val pairId = AnonCrypto.unb64u(plain.optString("pairId") ?: "") ?: throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            if (pairId.size != 16
                || AnonProtocol.confirmValue(keys.confirm, transcript, pairId) != plain.optString("confirm")) {
                throw AnonRemoteException("bad_reply", "身份验证失败，请重新配对")
            }
            val machineName = plain.optString("machineName")?.ifBlank { null } ?: "电脑"
            val directoryKey = AnonCrypto.unb64u(plain.optString("directoryKey") ?: "")
            pairs.save(AnonPairRecord(deviceId, pairId, keys.master, machineName, directoryKey))
            _listChanged.tryEmit(Unit)
            machineName
        } finally {
            socket?.close(1000, "done")
        }
    }

    private fun String.toUserText(): String = when (this) {
        "bad_commit", "bad_reveal", "handshake_failed" -> "配对数据校验失败，请重试"
        "timeout" -> "配对超时，请在电脑端重新生成配对码"
        "declined" -> "电脑端已拒绝"
        "server_unavailable" -> "电脑端暂时无法完成配对，请稍后重试"
        else -> "配对未完成"
    }

    /** 手机侧移除一台电脑：撤销服务端授权，删除本地密钥与记录。 */
    suspend fun removeDesktop(deviceId: String) = withContext(dispatchers.io) {
        connections.remove(deviceId)?.stop()
        pairs.load(deviceId)?.let { record ->
            runCatching {
                postJson("anon_grant.php", buildJsonObject { put("op", "remove_self") }) { request, bytes ->
                    signedHeaders(request, "anon_grant.php", "grant", deviceId, record.pairId, bytes)
                    jsonHttp.newCall(request.build()).execute().use { parseBody(it) }
                }
            }
        }
        pairs.remove(deviceId)
        grants.remove(deviceId)
        modelGroupsCache.remove(deviceId)
        refreshAggregate()
        _listChanged.tryEmit(Unit)
    }

    /** 已配对的电脑与它们的对话列表（密文目录，逐台获取）；目录取不到时仍列出电脑本身。 */
    suspend fun directoryInfos(): List<DesktopInfo> = withContext(dispatchers.io) {
        pairs.list().map { record ->
            directoryInfo(record.deviceId)
                ?: DesktopInfo(record.deviceId, record.machineName, "", online = false, lastSeenAtMs = 0, conversations = emptyList())
        }
    }

    fun pairedDeviceIds(): List<String> = pairs.list().map { it.deviceId }

    // ---- encrypted offline directory ---------------------------------------------

    /** 电脑离线时的对话列表（密文目录）。 */
    suspend fun directoryInfo(deviceId: String): DesktopInfo? = withContext(dispatchers.io) {
        val record = pairs.load(deviceId) ?: return@withContext null
        val directoryKey = record.directoryKey ?: return@withContext null
        val reply = runCatching {
            postJson("anon_directory.php", buildJsonObject { put("op", "get") }) { request, bytes ->
                signedHeaders(request, "anon_directory.php", "grant", deviceId, record.pairId, bytes)
                jsonHttp.newCall(request.build()).execute().use { parseBody(it) }
            }
        }.getOrNull() ?: return@withContext null
        val payload = reply.optObj("payload") ?: return@withContext null
        val nonce = AnonCrypto.unb64u(payload.optString("nonce") ?: "") ?: return@withContext null
        val ct = AnonCrypto.unb64u(payload.optString("ct") ?: "") ?: return@withContext null
        val tag = AnonCrypto.unb64u(payload.optString("tag") ?: "") ?: return@withContext null
        val plain = runCatching {
            AnonCrypto.open(directoryKey, nonce, ByteArray(0), ct, tag)
        }.getOrNull() ?: return@withContext null
        val root = http.json.parseToJsonElement(plain.toString(Charsets.UTF_8)).asObject() ?: return@withContext null
        val updatedAt = reply.optLong("updatedAt") ?: 0
        // 电脑有对话在忙或待审批时每 3 分钟重传目录；比这旧一倍还没更新，就当它已不在。
        val fresh = nowAdjusted() - updatedAt < DIRECTORY_FRESH_S
        DesktopInfo(
            machineId = deviceId,
            machineName = root.optString("machineName")?.ifBlank { null } ?: "电脑",
            appVersion = root.optString("appVersion") ?: "",
            online = fresh || deviceId in _onlineMachines.value,
            lastSeenAtMs = updatedAt * 1000,
            conversations = (root["conversations"] as? JsonArray).orEmpty().mapNotNull { item ->
                val conversation = item.asObject() ?: return@mapNotNull null
                DesktopConversation(
                    id = conversation.optString("id") ?: return@mapNotNull null,
                    title = conversation.optString("title")?.ifBlank { null } ?: "新对话",
                    modelLabel = conversation.optString("modelLabel"),
                    providerLabel = conversation.optString("providerLabel"),
                    updatedAtMs = conversation.optLong("updatedAtMs") ?: 0,
                    busy = conversation.optBool("busy") == true,
                    pendingApprovals = conversation.optLong("pendingApprovals")?.toInt() ?: 0,
                )
            },
        )
    }

    // ---- signed HTTP ----------------------------------------------------------------

    private fun nowAdjusted(): Long = System.currentTimeMillis() / 1000 + skew.get()

    private fun signedHeaders(request: Request.Builder, file: String, role: String, deviceId: String,
                              pairId: ByteArray?, body: ByteArray) {
        val ts = nowAdjusted()
        val nonce = AnonCrypto.randomBytes(16)
        val input = AnonProtocol.httpSignatureInput(role, deviceId, pairId, ts, nonce, AnonCrypto.sha256(body), "/v2/api/auth/$file")
        val signature = grants.sign(deviceId, input)
            ?: throw DesktopRemoteException("unpaired", "授权密钥已失效，请重新配对")
        request.header("X-Mola-Device", deviceId)
        request.header("X-Mola-Ts", ts.toString())
        request.header("X-Mola-Nonce", AnonCrypto.b64u(nonce))
        request.header("X-Mola-Signature", AnonCrypto.b64u(signature))
        if (pairId != null && pairId.isNotEmpty()) request.header("X-Mola-Pair", AnonCrypto.b64u(pairId))
    }

    private suspend fun postJson(
        file: String,
        body: JsonObject,
        send: (Request.Builder, ByteArray) -> JsonObject,
    ): JsonObject = withContext(dispatchers.io) {
        val bytes = http.json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url("$base/api/auth/$file")
            .post(okhttp3.RequestBody.create(null, bytes))
        send(request, bytes)
    }

    private fun parseBody(response: Response): JsonObject {
        val text = response.body?.string() ?: ""
        val parsed = runCatching { http.json.parseToJsonElement(text).asObject() }.getOrNull()
        if (response.code == 401 && parsed?.optString("code") == "stale") {
            response.header("X-Mola-Server-Time")?.toLongOrNull()?.let { server ->
                skew.set(server - System.currentTimeMillis() / 1000)
            }
        }
        if (!response.isSuccessful) {
            throw AnonRemoteException(parsed?.optString("code") ?: "http_${response.code}", parsed?.optString("message") ?: "服务不可用")
        }
        return parsed ?: throw AnonRemoteException("bad_reply", "服务响应无效")
    }

    private fun normalizeRoom(input: String): String? {
        val normalized = buildString {
            for (raw in input.trim().uppercase()) {
                val c = when (raw) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> raw
                }
                if (c in "0123456789ABCDEFGHJKMNPQRSTVWXYZ") append(c)
            }
        }
        return if (normalized.length == 8) normalized else null
    }

    // ---- one device connection ----------------------------------------------------

    private inner class AnonConnection(private val record: AnonPairRecord) {
        val deviceId = record.deviceId
        private var readyField = CompletableDeferred<Unit>()
        val ready: CompletableDeferred<Unit> get() = readyField
        /** 握手完成；连接失败时 [ready] 也算 completed，不能只看 isCompleted。 */
        val isReady: Boolean get() = readyField.let { it.isCompleted && !it.isCancelled }
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private var socket: WebSocket? = null
        private var loop: Job? = null
        private var outbound: AnonProtocol.Direction? = null
        private var inbound: AnonProtocol.Direction? = null
        private var closing = false
        private val sendMutex = Mutex()

        fun start() {
            if (loop?.isActive == true) return
            loop = scope.launch {
                var attempt = 0
                while (!closing) {
                    val opened = runCatching { connect() }.onFailure {
                        Logger.w(TAG, "anon connect ${record.deviceId}: ${it.message}")
                    }.getOrDefault(false)
                    if (opened) {
                        attempt = 0
                        refreshAggregate()
                        wake.receive()
                    } else {
                        readyField.completeExceptionally(DesktopRemoteException("disconnected", "连接已断开"))
                        delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)])
                        attempt++
                    }
                }
            }
        }

        fun stop() {
            closing = true
            wake.trySend(Unit)
            socket?.cancel()
            loop?.cancel()
            failPending(DesktopRemoteException("disconnected", "连接已断开"))
            readyField.completeExceptionally(DesktopRemoteException("disconnected", "连接已断开"))
        }

        fun reconnect() {
            socket?.cancel()
            wake.trySend(Unit)
        }

        private suspend fun connect(): Boolean {
            // 每条连接一个新 deferred：一次失败不能把之后的重连判死。
            readyField = CompletableDeferred()
            val ticket = fetchTicket()
            val nP = AnonCrypto.randomBytes(32)
            val opened = CompletableDeferred<Boolean>()
            val url = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/agent-realtime"
            val request = Request.Builder().url(url).header("Authorization", "Bearer $ticket").build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val connectKey = AnonProtocol.connectKey(record.master, record.pairId)
                    webSocket.send(http.json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("to", deviceId)
                        put("body", buildJsonObject {
                            put("v", 2)
                            put("ctl", "hello")
                            put("pair", AnonCrypto.b64u(record.pairId))
                            put("nP", AnonCrypto.b64u(nP))
                            put("proof", AnonProtocol.proofPhone(connectKey, deviceId, record.pairId, nP))
                        })
                    }))
                    opened.complete(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    closed()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.complete(false)
                    closed()
                }
            }
            val created = socketHttp.newWebSocket(request, listener)
            socket = created
            if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() } != true) {
                created.cancel()
                if (socket === created) socket = null
                return false
            }
            return true
        }

        private fun onFrame(text: String) {
            val frame = runCatching { http.json.parseToJsonElement(text).asObject() }.getOrNull() ?: return
            val body = frame["body"].asObject() ?: return
            val ctl = body.optString("ctl")
            if (ctl == "offer") return handleOffer(body)
            if (body["ct"] == null) {
                when (frame.optString("kind")) {
                    "connected" -> {}
                    "gone" -> failPending(DesktopRemoteException("offline", "电脑已离线"))
                    "undeliverable" -> frame.optString("ref")?.let { id ->
                        pending.remove(id)?.completeExceptionally(DesktopRemoteException("offline", "电脑已离线"))
                    }
                }
                return
            }
            val direction = inbound ?: return
            val plain = try {
                direction.open(body)
            } catch (error: Exception) {
                Logger.w(TAG, "anon frame auth failed for $deviceId; closing")
                reconnect()
                return
            }
            val payload = runCatching {
                http.json.parseToJsonElement(plain.second).asObject()
            }.getOrNull() ?: return
            val inner = payload.optString("ctl")
            if (inner == "ready_ack") {
                payload.optString("accountMachineId")?.takeIf { it.isNotBlank() }?.let { id ->
                    runCatching { onAccountTwin(id) }
                }
                ready.complete(Unit)
                refreshAggregate()
                return
            }
            payload.optString("re")?.let { id -> pending.remove(id)?.complete(payload) }
            val event = payload.optString("ev") ?: return
            val conversationId = payload.optString("conversationId") ?: return
            _pushes.tryEmit(
                DesktopPush(
                    machineId = deviceId,
                    conversationId = conversationId,
                    event = event,
                    seq = payload.optLong("seq") ?: 0,
                    data = payload.optObj("data") ?: JsonObject(emptyMap()),
                ),
            )
        }

        private fun handleOffer(body: JsonObject) {
            val nD = AnonCrypto.unb64u(body.optString("nD") ?: "") ?: return reconnect()
            val echoedNp = AnonCrypto.unb64u(body.optString("nP") ?: "") ?: return reconnect()
            val proof = body.optString("proof") ?: return reconnect()
            val connectKey = AnonProtocol.connectKey(record.master, record.pairId)
            val expected = AnonProtocol.proofDesktop(connectKey, deviceId, record.pairId, echoedNp, nD)
            // 电脑必须证明持有 master 才算会话建立；这是端到端认证，与中转无关。
            // 两边都是 base64url 文本，按文本比对。
            if (!AnonCrypto.fixedTimeEqual(proof.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) {
                Logger.w(TAG, "anon offer proof failed for $deviceId")
                return reconnect()
            }
            val keys = AnonProtocol.deriveSession(record.master, deviceId, record.pairId, echoedNp, nD)
            inbound = AnonProtocol.Direction(keys.desktopToPhone, deviceId, record.pairId, keys.sid, "d2p")
            outbound = AnonProtocol.Direction(keys.phoneToDesktop, deviceId, record.pairId, keys.sid, "p2d")
            // deviceId 让电脑认出这台手机此前的账号配对，删掉重复的那条。
            scope.launch { send(buildJsonObject { put("v", 2); put("ctl", "ready"); put("deviceId", phoneDeviceId) }) }
        }

        private suspend fun send(plaintext: JsonObject) {
            val direction = outbound ?: return
            val sealed = sendMutex.withLock { direction.seal(http.json.encodeToString(JsonObject.serializer(), plaintext)) }
            socket?.send(http.json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("to", deviceId)
                put("body", sealed)
            }))
        }

        suspend fun request(op: String, args: JsonObject): JsonObject {
            if (socket == null || outbound == null) {
                throw DesktopRemoteException("disconnected", "连接已断开")
            }
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JsonObject>()
            pending[id] = deferred
            try {
                send(buildJsonObject {
                    put("v", 2)
                    put("id", id)
                    put("op", op)
                    put("ts", System.currentTimeMillis())
                    put("a", http.json.encodeToString(JsonObject.serializer(), args))
                    put("pair", AnonCrypto.b64u(record.pairId))
                })
                val reply = withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
                if (reply.optBool("ok") != true) {
                    if (reply.optString("code") == "unpaired") scope.launch { removeDesktop(deviceId) }
                    throw DesktopRemoteException(reply.optString("code") ?: "error", reply.optString("message") ?: "操作失败")
                }
                return reply
            } finally {
                pending.remove(id)
            }
        }

        private suspend fun fetchTicket(): String {
            val reply = postJson("anon_ticket.php", JsonObject(emptyMap())) { request, bytes ->
                signedHeaders(request, "anon_ticket.php", "grant", deviceId, record.pairId, bytes)
                jsonHttp.newCall(request.build()).execute().use { parseBody(it) }
            }
            return reply.optString("ticket") ?: throw AnonRemoteException("bad_reply", "票据响应无效")
        }

        private fun closed() {
            socket = null
            inbound = null
            outbound = null
            failPending(DesktopRemoteException("disconnected", "连接已断开"))
            refreshAggregate()
            wake.trySend(Unit)
        }

        private fun failPending(error: Exception) {
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
    }

    private companion object {
        private const val TAG = "AnonRemote"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val IDLE_DISCONNECT_MS = 30_000L
        private const val DIRECTORY_FRESH_S = 6 * 60L
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
