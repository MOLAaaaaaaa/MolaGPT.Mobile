package com.molagpt.app.feature.agentcontrol.desktop

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.AttachmentMime
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.DesktopLinkState
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.RemoteApproval
import com.molagpt.app.core.model.RemoteAttachmentRules
import com.molagpt.app.core.model.RemoteModelChoice
import com.molagpt.app.core.model.RemoteModelGroup
import com.molagpt.app.core.model.RemotePersona
import com.molagpt.app.core.model.RemoteSettings
import com.molagpt.app.core.model.Role
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.network.DesktopPush
import com.molagpt.app.core.network.DesktopRemoteException
import com.molagpt.app.core.network.DesktopRemoteService
import com.molagpt.app.feature.chat.StreamRenderPacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class RemoteConversationUiState(
    val title: String = "",
    val modelLabel: String? = null,
    /** 与电脑实时同步中：快照已打开、连接在线。 */
    val live: Boolean = false,
    /** 电脑当前在线（实时连接看到它）。 */
    val desktopOnline: Boolean = false,
    /** 与中转的连接正在建立。 */
    val connecting: Boolean = true,
    /** 已连上中转（此时打不开就是电脑那边的问题）。 */
    val linkUp: Boolean = false,
    /** 正在向电脑取对话快照；在回复之前不算离线。 */
    val opening: Boolean = false,
    /** 用户点了重试、结果还没出来。 */
    val retrying: Boolean = false,
    val busy: Boolean = false,
    val readOnly: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val approvals: List<RemoteApproval> = emptyList(),
    /** 显示的是本机缓存，何时保存的。 */
    val cachedAtMs: Long? = null,
    val unpaired: Boolean = false,
    val notice: String? = null,
    /** 模型、网络访问、推理；只读对话为 null。 */
    val settings: RemoteSettings? = null,
    /** 可切换的模型，打开模型菜单时向电脑取。 */
    val modelGroups: List<RemoteModelGroup> = emptyList(),
    val loadingModels: Boolean = false,
    /** 待随下一条消息发送的附件：上传中、已在电脑上暂存或失败。 */
    val pendingAttachments: List<FileInfo> = emptyList(),
    /** 电脑上的对话 id；草稿时是首条消息发出后才会创建的那个。 */
    val conversationId: String = "",
    /** 在电脑上新建、首条消息还没发出；为 null 即普通对话。 */
    val draft: RemoteDraftUi? = null,
)

/** 草稿可选的角色，来自电脑。 */
data class RemoteDraftUi(val personas: List<RemotePersona>, val personaId: String?)

/**
 * 一个电脑上的对话。电脑是唯一事实来源：打开时取一份快照整体替换本地内容，之后按序号应用推送，
 * 跳号就重新打开。断线、电脑离线时显示本机缓存的最后一份快照，不能发送。
 *
 * [existingConversationId] 为 null 时是在电脑上新建的草稿：设置向电脑要（`new_defaults`），改动只记在手机上，
 * 首条消息用 `create` 发出，电脑这时才建对话，之后与已有对话相同。见协议文档 §5.8。
 */
class RemoteConversationViewModel(
    private val service: DesktopRemoteService,
    private val cache: DesktopSnapshotCache,
    private val files: RemoteAttachmentFiles,
    private val machineId: String,
    existingConversationId: String?,
    private val dispatchers: DispatcherProvider,
    /** 草稿起始的模型，来自首页模型菜单。 */
    private val draftProviderId: String? = null,
    private val draftModelId: String? = null,
    /** 消息已交给电脑：用于在回合结束时提醒。 */
    private val onSent: (conversationId: String) -> Unit = {},
) : ViewModel() {
    /** 草稿的 id 由手机生成，重发时电脑据此认出已建好的对话。 */
    private val conversationId: String = existingConversationId ?: newConversationId()
    private var drafting = existingConversationId == null
    private var draftSettings: RemoteSettings? = null
    private var draftPersonaId: String? = null
    private var draftPersonas: List<RemotePersona> = emptyList()
    private var draftJob: Job? = null

    private val raw = mutableListOf<JsonObject>()
    private val mapped = HashMap<String, Pair<JsonObject, ChatMessage>>()
    private val optimistic = mutableListOf<Pending>()
    private var conversation: JsonObject = JsonObject(emptyMap())
    private var seq = -1L
    private var opened = false
    private var openJob: Job? = null
    private var saveJob: Job? = null

    /** 正在逐字放出的回答：消息 id → 进度。见 [trackReveal]。 */
    private val reveals = HashMap<String, Reveal>()
    private var revealJob: Job? = null
    /** 放出过程中的映射缓存：同一份原文、同一字数不重复映射。 */
    private val revealMapped = HashMap<String, Triple<JsonObject, Int, ChatMessage>>()
    /** 消息已发出、电脑的回答还没出现：先放一条生成中的占位，与聊天页发送后立刻出现的占位一致。 */
    private var awaitingReply: AwaitingReply? = null

    /**
     * 一条回答的放出进度（按 [DesktopStreamReveal] 的时间线计字）。
     * [speed] 每收到一段新内容重算一次：按最近两次推送的间隔把积压放完，下一段到时正好接上。
     */
    private class Reveal(
        var shown: Double,
        var target: Int,
        var speed: Double,
        var lastGrowthAt: Long,
        var intervalMs: Double,
        var finished: Boolean,
    )

    private class AwaitingReply(val knownAssistantIds: Set<String>, val placeholder: ChatMessage) {
        /** 发出后见过电脑「回答中」；它再变回空闲还没有回答，就不再等（电脑那边没能开始）。 */
        var sawBusy = false
    }

    /** 已在界面上生效、电脑还没确认的设置；请求都回来后以电脑为准。 */
    private var pendingSettings: RemoteSettings? = null
    private var configuring = 0

    private data class Pending(val message: ChatMessage, var messageId: String? = null)

    /** 一个待发送的附件。上传逐个进行；完成后 [attachmentId] 指向电脑上暂存的那份。 */
    private class Outgoing(val id: String, val uri: Uri, val name: String, val mime: String) {
        var staged: RemoteAttachmentFiles.Staged? = null
        var uploadId: String? = null
        var attachmentId: String? = null
        var status: UploadStatus = UploadStatus.UPLOADING
        var job: Job? = null
    }

    private val outgoing = mutableListOf<Outgoing>()
    private val uploadLock = Mutex()

    private val _state = MutableStateFlow(RemoteConversationUiState())
    val state: StateFlow<RemoteConversationUiState> = _state.asStateFlow()

    init {
        service.acquire()
        viewModelScope.launch(dispatchers.io) { files.sweepStale() }
        if (!drafting) cache.load(machineId, conversationId)?.let { snapshot ->
            conversation = snapshot.conversation
            raw += snapshot.messages.mapNotNull { it as? JsonObject }
            publish { it.copy(cachedAtMs = snapshot.savedAtMs) }
        }
        viewModelScope.launch {
            combine(service.link, service.onlineMachines) { link, online -> link to (machineId in online) }
                .distinctUntilChanged()
                .collect { (link, online) ->
                    publish {
                        it.copy(
                            connecting = link == DesktopLinkState.Connecting,
                            linkUp = link == DesktopLinkState.Connected,
                            desktopOnline = online,
                            retrying = it.retrying && link != DesktopLinkState.Disconnected,
                        )
                    }
                    if (link == DesktopLinkState.Connected) {
                        // 不只信在线名单：电脑重连时中转的上下线通知可能乱序，名单会过时。
                        // 直接去打开，电脑真不在线时中转会立刻回「送不到」。
                        openOrDraft()
                    } else {
                        opened = false
                        publish { it.copy(live = false, opening = false) }
                    }
                }
        }
        viewModelScope.launch {
            service.pushes
                .filter { it.machineId == machineId && it.conversationId == conversationId }
                .collect(::onPush)
        }
    }

    // ---- 同步 ---------------------------------------------------------------

    private fun openOrDraft() {
        if (drafting) loadDraft(draftSettings?.providerId ?: draftProviderId, draftSettings?.modelId ?: draftModelId)
        else open()
    }

    /** 打开失败：电脑不在线或连接断了只更新状态（状态行会说明），其余错误才作为提示显示。 */
    private fun onOpenFailed(error: DesktopRemoteException) {
        if (error.code == "offline" || error.code == "disconnected") {
            opened = false
            publish {
                it.copy(
                    live = false,
                    opening = false,
                    retrying = false,
                    desktopOnline = if (error.code == "offline") false else it.desktopOnline,
                )
            }
        } else {
            publish { it.copy(opening = false, retrying = false, unpaired = error.code == "unpaired", notice = error.message) }
        }
    }

    private fun open() {
        openJob?.cancel()
        publish { it.copy(opening = true) }
        openJob = viewModelScope.launch {
            try {
                val data = service.request(machineId, "open", buildJsonObject {
                    put("conversationId", conversationId)
                    put("limit", 30)
                })
                conversation = data["conversation"] as? JsonObject ?: JsonObject(emptyMap())
                raw.clear()
                raw += (data["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                optimistic.removeAll { pending -> pending.messageId != null && raw.any { it.string("id") == pending.messageId } }
                seq = data.long("seq") ?: 0
                opened = true
                // 草稿刚建成对话时沿用草稿设置，直到电脑的快照带回正式设置，输入区的开关不会闪没。
                if (!drafting) draftSettings = null
                syncReveals()
                val approvals = (data["approvals"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(DesktopRemoteService::parseApproval) }
                publish {
                    it.copy(
                        live = true,
                        opening = false,
                        retrying = false,
                        desktopOnline = true,
                        cachedAtMs = null,
                        hasOlder = data.bool("hasOlder") == true,
                        approvals = approvals,
                        unpaired = false,
                        notice = null,
                    )
                }
                scheduleSave()
            } catch (error: DesktopRemoteException) {
                onOpenFailed(error)
            }
        }
    }

    /** 新对话的初始设置与可选角色；换模型、换角色时重取，草稿里改过的开关随之回到默认。 */
    private fun loadDraft(providerId: String?, modelId: String?, personaId: String? = draftPersonaId) {
        draftJob?.cancel()
        publish { it.copy(opening = true) }
        draftJob = viewModelScope.launch {
            try {
                val data = service.request(machineId, "new_defaults", buildJsonObject {
                    providerId?.let { put("providerId", it) }
                    modelId?.let { put("modelId", it) }
                    personaId?.let { put("personaId", it) }
                })
                val defaults = DesktopRemoteService.parseDraftDefaults(data)
                    ?: throw DesktopRemoteException("bad_reply", "电脑未响应")
                draftSettings = defaults.settings
                draftPersonas = defaults.personas
                draftPersonaId = defaults.personaId
                opened = true
                publish { it.copy(live = true, opening = false, retrying = false, desktopOnline = true, unpaired = false, notice = null) }
            } catch (error: DesktopRemoteException) {
                onOpenFailed(error)
            }
        }
    }

    fun selectPersona(personaId: String) {
        if (!drafting || personaId == draftPersonaId) return
        draftPersonaId = personaId
        publish { it }
        val settings = currentSettings()
        loadDraft(settings?.providerId ?: draftProviderId, settings?.modelId ?: draftModelId, personaId)
    }

    private fun onPush(push: DesktopPush) {
        if (!opened || push.seq <= seq) return
        if (push.seq != seq + 1) {
            // 漏了推送：不拼补，整体重取。
            open()
            return
        }
        seq = push.seq
        when (push.event) {
            "upsert" -> upsert(push.data)
            "remove" -> push.data.string("id")?.let { id -> raw.removeAll { it.string("id") == id } }
            "state" -> {
                conversation = JsonObject(conversation + buildMap {
                    push.data["busy"]?.let { put("busy", it) }
                    push.data["title"]?.let { put("title", it) }
                })
                awaitingReply?.let { awaiting ->
                    when (push.data.bool("busy")) {
                        true -> awaiting.sawBusy = true
                        false -> if (awaiting.sawBusy) awaitingReply = null
                        null -> Unit
                    }
                }
            }
            "settings" -> conversation = JsonObject(conversation + ("settings" to push.data))
            "approval" -> DesktopRemoteService.parseApproval(push.data)?.let { approval ->
                publish { state -> state.copy(approvals = state.approvals.filterNot { it.requestId == approval.requestId } + approval) }
            }
            "resolved" -> push.data.string("requestId")?.let { id ->
                publish { state -> state.copy(approvals = state.approvals.filterNot { it.requestId == id }) }
            }
            "reset" -> {
                open()
                return
            }
        }
        publish { it }
        scheduleSave()
    }

    private fun upsert(message: JsonObject) {
        val id = message.string("id") ?: return
        val index = raw.indexOfFirst { it.string("id") == id }
        if (index >= 0) raw[index] = message
        else {
            raw += message
            raw.sortBy { it.long("createdAtMs") ?: 0 }
        }
        trackReveal(message, fresh = index < 0)
        if (message.string("role") == "user") {
            val text = message.string("content").orEmpty().trim()
            // 送达的消息替换手机上「发送中」的那一条：按电脑回的消息 id，插话没有 id 就按原文。
            optimistic.removeAll { it.messageId == id || (it.messageId == null && it.message.rawText?.trim() == text) }
        }
    }

    // ---- 操作 ---------------------------------------------------------------

    /** 发出一条消息和已上传的附件；失败时把原文交给 [onFailed] 放回输入框，附件留在待发送区。 */
    fun send(text: String, onFailed: (String) -> Unit) {
        val trimmed = text.trim()
        val attached = outgoing.toList()
        if (attached.any { it.status != UploadStatus.UPLOADED }) return
        if (trimmed.isEmpty() && attached.isEmpty()) return
        val creating = drafting
        val settings = currentSettings()
        if (creating && settings == null) return
        val now = System.currentTimeMillis()
        val local = Pending(
            ChatMessage(
                messageId = "local:" + UUID.randomUUID(),
                sessionId = conversationId,
                role = Role.USER,
                status = MessageStatus.PENDING,
                createdAt = now,
                updatedAt = now,
                fragments = if (trimmed.isEmpty()) emptyList() else listOf(MessageFragment.Text("local:$now", trimmed)),
                rawText = trimmed,
                attachments = attached.map { item ->
                    Attachment(
                        id = "local:" + item.id,
                        name = item.name,
                        mimeType = item.mime,
                        label = if (AttachmentMime.isImage(item.mime)) "图片" else null,
                    )
                },
            ),
        )
        optimistic += local
        awaitingReply = AwaitingReply(
            knownAssistantIds = raw.filter { it.string("role") == "assistant" }.mapNotNullTo(HashSet()) { it.string("id") },
            placeholder = ChatMessage(
                messageId = "local:reply:" + UUID.randomUUID(),
                sessionId = conversationId,
                role = Role.ASSISTANT,
                status = MessageStatus.STREAMING,
                createdAt = now,
                updatedAt = now,
                fragments = emptyList(),
                rawText = "",
                model = settings?.model,
            ),
        )
        outgoing.removeAll(attached)
        publish { it }
        viewModelScope.launch {
            try {
                val data = service.request(machineId, if (creating) "create" else "send", buildJsonObject {
                    put("conversationId", conversationId)
                    put("text", trimmed)
                    put("cmdId", UUID.randomUUID().toString())
                    if (attached.isNotEmpty()) put("attachments", JsonArray(attached.map { JsonPrimitive(it.attachmentId) }))
                    if (creating && settings != null) {
                        put("providerId", settings.providerId)
                        put("modelId", settings.modelId)
                        draftPersonaId?.let { put("personaId", it) }
                        if (settings.networkAvailable) put("network", settings.networkOn)
                        if (settings.thinkingToggle) put("thinking", settings.thinkingOn)
                        settings.effort?.let { put("effort", it) }
                    }
                })
                val messageId = data.string("messageId")
                if (messageId != null && raw.any { it.string("id") == messageId }) optimistic.remove(local)
                else local.messageId = messageId
                attached.forEach(::deleteStaged)
                if (creating) becomeConversation()
                onSent(conversationId)
                publish { it }
            } catch (error: DesktopRemoteException) {
                if (creating && error.code == "conflict") {
                    // 只有本机生成的 id 会撞上：上一次 create 其实成功了，只是答复丢了。
                    optimistic.remove(local)
                    attached.forEach(::deleteStaged)
                    becomeConversation()
                    publish { it }
                    return@launch
                }
                optimistic.remove(local)
                awaitingReply = null
                // 电脑已不认这些附件（过期或电脑重启）：标为失败，点一下就重新上传。
                if (error.code == "not_found") attached.forEach { it.markFailed() }
                outgoing.addAll(0, attached)
                publish { it.copy(notice = error.message) }
                onFailed(text)
            }
        }
    }

    /** 电脑已建好对话：此后按普通对话同步。 */
    private fun becomeConversation() {
        drafting = false
        draftJob?.cancel()
        opened = false
        open()
    }

    // ---- 逐字放出 -------------------------------------------------------------

    /**
     * 电脑大约每秒才推一次整条回答，原样显示就是一秒一大段。这里把收到的内容按时间线逐字放出：
     * 速度按最近两次推送的间隔算，下一段到时正好放完；回答写完后剩下的在 [REVEAL_FINISH_MS] 内放完。
     * 推进节奏与聊天页的流式分批一样取 [StreamRenderPacing]：帧率健康时每帧推进，一卡就放宽。
     *
     * [fresh] 为真表示这条回答是这次在看时才出现的，从头放（写完才第一次出现的短回答也一样，只是放得快）；
     * 打开时已经写了一半的直接显示，只对之后的增长放字。
     */
    private fun trackReveal(message: JsonObject, fresh: Boolean) {
        if (message.string("role") != "assistant") return
        val id = message.string("id") ?: return
        val streaming = (message["meta"] as? JsonObject)?.bool("streaming") == true
        val total = DesktopStreamReveal.length(message)
        val now = monotonicMs()
        var reveal = reveals[id]
        if (reveal == null) {
            if (!fresh && !streaming) return
            reveal = Reveal(
                shown = if (fresh) 0.0 else total.toDouble(),
                target = total,
                speed = 0.0,
                lastGrowthAt = now,
                intervalMs = REVEAL_DEFAULT_INTERVAL_MS,
                finished = !streaming,
            )
            reveals[id] = reveal
        } else {
            if (total > reveal.target) {
                val gap = (now - reveal.lastGrowthAt).toDouble()
                reveal.intervalMs = (reveal.intervalMs * 0.6 + gap * 0.4)
                    .coerceIn(REVEAL_MIN_INTERVAL_MS, REVEAL_MAX_INTERVAL_MS)
                reveal.lastGrowthAt = now
            }
            reveal.target = total
            if (reveal.shown > total) reveal.shown = total.toDouble()
            reveal.finished = !streaming
        }
        val backlog = reveal.target - reveal.shown
        if (backlog <= 0.0) {
            if (reveal.finished) {
                reveals.remove(id)
                revealMapped.remove(id)
            }
            return
        }
        val window = if (reveal.finished) REVEAL_FINISH_MS else reveal.intervalMs
        reveal.speed = maxOf(backlog / window, backlog / REVEAL_MAX_LAG_MS, REVEAL_MIN_SPEED)
        ensureRevealTicker()
    }

    /** 整份快照换进来之后：已在放的回答继续放到新长度，不在了的丢掉；正等着的新回答从头放。 */
    private fun syncReveals() {
        val ids = raw.mapNotNullTo(HashSet()) { it.string("id") }
        reveals.keys.retainAll(ids)
        revealMapped.keys.retainAll(ids)
        val known = awaitingReply?.knownAssistantIds
        raw.forEach { message ->
            val id = message.string("id") ?: return@forEach
            trackReveal(message, fresh = known != null && id !in known)
        }
    }

    private fun ensureRevealTicker() {
        if (revealJob?.isActive == true) return
        revealJob = viewModelScope.launch {
            var last = monotonicMs()
            while (true) {
                delay(StreamRenderPacing.windowMillis(REVEAL_FRAME_MS))
                val now = monotonicMs()
                val elapsed = (now - last).toDouble()
                last = now
                var pending = false
                val done = ArrayList<String>()
                for ((id, reveal) in reveals) {
                    if (reveal.shown < reveal.target) {
                        reveal.shown = minOf(reveal.target.toDouble(), reveal.shown + reveal.speed * elapsed)
                    }
                    when {
                        reveal.shown < reveal.target -> pending = true
                        reveal.finished -> done += id
                    }
                }
                done.forEach {
                    reveals.remove(it)
                    revealMapped.remove(it)
                }
                publish { it }
                if (!pending) break
            }
        }
    }

    private fun revealed(id: String, item: JsonObject, reveal: Reveal): ChatMessage? {
        val budget = reveal.shown.toInt()
        revealMapped[id]?.takeIf { it.first === item && it.second == budget }?.let { return it.third }
        return DesktopMessageMapper.map(conversationId, DesktopStreamReveal.truncate(item, budget))
            ?.also { revealMapped[id] = Triple(item, budget, it) }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

    // ---- 附件 ---------------------------------------------------------------

    fun attach(uris: List<Uri>) = uris.forEach(::attach)

    fun attach(uri: Uri) {
        viewModelScope.launch {
            val meta = withContext(dispatchers.io) { files.probe(uri) }
            if (meta == null) {
                publish { it.copy(notice = "无法读取所选附件") }
                return@launch
            }
            val item = Outgoing(UUID.randomUUID().toString(), uri, meta.name, meta.mimeType)
            outgoing += item
            val refusal = refusalFor(item)
                ?: if (meta.sizeBytes > RemoteAttachmentFiles.MAX_BYTES) tooLarge(item) else null
            if (refusal != null) {
                item.status = UploadStatus.FAILED
                publish { it.copy(notice = refusal) }
                return@launch
            }
            startUpload(item)
        }
    }

    fun retryAttachment(id: String) {
        val item = outgoing.firstOrNull { it.id == id && it.status == UploadStatus.FAILED } ?: return
        refusalFor(item)?.let { refusal ->
            publish { it.copy(notice = refusal) }
            return
        }
        startUpload(item)
    }

    fun removeAttachment(id: String) {
        val item = outgoing.firstOrNull { it.id == id } ?: return
        outgoing.remove(item)
        item.job?.cancel()
        cancelOnDesktop(item)
        deleteStaged(item)
        publish { it }
    }

    /** 拍照、选图前的提示：当前模型看不了图。 */
    fun refuseImages() = publish { it.copy(notice = IMAGE_REFUSED) }

    /** 电脑给的准入规则先在手机上拦一遍，省得传完才被拒。 */
    private fun refusalFor(item: Outgoing): String? {
        val rules = attachmentRules() ?: return "电脑端版本不支持附件，请更新"
        if (AttachmentMime.isImage(item.mime) && !rules.images) return IMAGE_REFUSED
        return null
    }

    private fun attachmentRules(): RemoteAttachmentRules? = currentSettings()?.attachments

    private fun currentSettings(): RemoteSettings? = pendingSettings ?: draftSettings ?: settingsOf(conversation)

    private fun startUpload(item: Outgoing) {
        item.status = UploadStatus.UPLOADING
        item.uploadId = null
        item.attachmentId = null
        publish { it }
        item.job = viewModelScope.launch { upload(item) }
    }

    /**
     * 先在本机留一份副本并算出摘要，再按电脑给的分片大小逐片发送，每片等到答复再发下一片。
     * 同一时刻只传一个文件：分片与其他请求共用一条连接，电脑对每台手机的请求也是逐个处理。
     */
    private suspend fun upload(item: Outgoing) {
        try {
            uploadLock.withLock {
                val staged = item.staged
                    ?: withContext(dispatchers.io) { files.stage(item.uri) }?.also { item.staged = it }
                    ?: throw IOException()
                val begin = service.request(machineId, "upload_begin", buildJsonObject {
                    put("conversationId", conversationId)
                    put("name", item.name)
                    put("mime", item.mime)
                    put("size", staged.size)
                    put("sha256", staged.sha256)
                    // 对话还没建：电脑按草稿选的模型判断附件能否接收。
                    if (drafting) draftSettings?.let { settings ->
                        put("providerId", settings.providerId)
                        put("modelId", settings.modelId)
                        draftPersonaId?.let { put("personaId", it) }
                    }
                })
                val uploadId = begin.string("uploadId") ?: throw DesktopRemoteException("bad_reply", "电脑未响应")
                item.uploadId = uploadId
                val chunkSize = (begin.long("chunkSize") ?: DEFAULT_CHUNK_BYTES).toInt()
                    .coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
                val buffer = ByteArray(chunkSize)
                withContext(dispatchers.io) { staged.file.inputStream() }.use { input ->
                    var index = 0
                    while (true) {
                        val read = withContext(dispatchers.io) { input.readUpTo(buffer) }
                        if (read <= 0) break
                        val data = withContext(dispatchers.default) {
                            Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        }
                        service.request(machineId, "upload_chunk", buildJsonObject {
                            put("uploadId", uploadId)
                            put("index", index)
                            put("data", data)
                        })
                        index++
                    }
                }
                val end = service.request(machineId, "upload_end", buildJsonObject { put("uploadId", uploadId) })
                item.attachmentId = end.string("attachmentId") ?: uploadId
                item.status = UploadStatus.UPLOADED
                publish { it }
            }
        } catch (error: DesktopRemoteException) {
            failUpload(item, error.message)
        } catch (_: RemoteAttachmentFiles.TooLargeException) {
            failUpload(item, tooLarge(item))
        } catch (_: IOException) {
            failUpload(item, "无法读取 ${item.name}")
        }
    }

    private fun failUpload(item: Outgoing, reason: String?) {
        if (item !in outgoing) return
        cancelOnDesktop(item)
        item.markFailed()
        publish { it.copy(notice = reason) }
    }

    private fun Outgoing.markFailed() {
        status = UploadStatus.FAILED
        uploadId = null
        attachmentId = null
    }

    private fun cancelOnDesktop(item: Outgoing) {
        val id = item.attachmentId ?: item.uploadId ?: return
        service.requestQuietly(machineId, "upload_cancel", buildJsonObject { put("uploadId", id) })
    }

    private fun deleteStaged(item: Outgoing) {
        val file = item.staged?.file ?: return
        item.staged = null
        viewModelScope.launch(dispatchers.io) { files.delete(file) }
    }

    private fun tooLarge(item: Outgoing) =
        "${item.name} 超过 ${RemoteAttachmentFiles.MAX_BYTES / 1024 / 1024} MB，无法从手机发送"

    fun stop() = launchQuietly("stop", buildJsonObject { put("conversationId", conversationId) })

    fun approve(requestId: String, decision: String) {
        viewModelScope.launch {
            try {
                service.request(machineId, "approve", buildJsonObject {
                    put("requestId", requestId)
                    put("decision", decision)
                })
                publish { state -> state.copy(approvals = state.approvals.filterNot { it.requestId == requestId }) }
            } catch (error: DesktopRemoteException) {
                publish { state ->
                    state.copy(
                        approvals = if (error.code == "resolved") state.approvals.filterNot { it.requestId == requestId } else state.approvals,
                        notice = error.message,
                    )
                }
            }
        }
    }

    fun loadOlder() {
        val first = raw.firstOrNull()?.string("id") ?: return
        if (_state.value.loadingOlder || !opened) return
        publish { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val data = service.request(machineId, "older", buildJsonObject {
                    put("conversationId", conversationId)
                    put("before", first)
                    put("limit", 30)
                })
                val older = (data["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { item -> raw.none { it.string("id") == item.string("id") } }
                raw.addAll(0, older)
                publish { it.copy(loadingOlder = false, hasOlder = data.bool("hasOlder") == true) }
            } catch (error: DesktopRemoteException) {
                publish { it.copy(loadingOlder = false, notice = error.message) }
            }
        }
    }

    /** 拉取只发了预览的那条消息的全文。 */
    fun loadFull(messageId: String) {
        viewModelScope.launch {
            try {
                val data = service.request(machineId, "full", buildJsonObject {
                    put("conversationId", conversationId)
                    put("messageId", messageId)
                })
                (data["message"] as? JsonObject)?.let { message ->
                    val index = raw.indexOfFirst { it.string("id") == messageId }
                    if (index >= 0) raw[index] = message
                    publish { it }
                }
            } catch (error: DesktopRemoteException) {
                publish { it.copy(notice = error.message) }
            }
        }
    }

    // ---- 模型与开关 -----------------------------------------------------------

    fun loadModels() {
        if (_state.value.loadingModels || !opened) return
        // 取过就先显示上次的结果，后台刷新；只有第一次才显示「正在获取」。
        val cached = service.cachedModelGroups(machineId)
        publish { it.copy(loadingModels = cached == null, modelGroups = cached ?: it.modelGroups) }
        viewModelScope.launch {
            try {
                val groups = service.modelGroups(machineId)
                publish { it.copy(loadingModels = false, modelGroups = groups) }
            } catch (error: DesktopRemoteException) {
                publish { it.copy(loadingModels = false, notice = error.message.takeIf { cached == null }) }
            }
        }
    }

    fun selectModel(group: RemoteModelGroup, model: RemoteModelChoice) {
        if (drafting) {
            draftSettings = draftSettings?.copy(
                providerId = group.providerId,
                modelId = model.id,
                model = model.label,
                provider = group.provider,
                family = group.family,
            )
            publish { it }
            loadDraft(group.providerId, model.id)
            return
        }
        switchModel(group, model)
    }

    private fun switchModel(group: RemoteModelGroup, model: RemoteModelChoice) = configure(
        apply = {
            it.copy(
                providerId = group.providerId,
                modelId = model.id,
                model = model.label,
                provider = group.provider,
                family = group.family,
            )
        },
    ) {
        put("providerId", group.providerId)
        put("modelId", model.id)
    }

    fun setNetwork(on: Boolean) = configure(apply = { it.copy(networkOn = on) }) { put("network", on) }

    fun setThinking(on: Boolean) = configure(apply = { it.copy(thinkingOn = on) }) { put("thinking", on) }

    fun setEffort(level: String) = configure(apply = { it.copy(effort = level) }) { put("effort", level) }

    private fun configure(apply: (RemoteSettings) -> RemoteSettings, args: JsonObjectBuilder.() -> Unit) {
        val current = currentSettings() ?: return
        if (!opened) return
        if (drafting) {
            // 草稿的开关随 create 一起带过去。
            draftSettings = apply(current)
            publish { it }
            return
        }
        pendingSettings = apply(current)
        configuring++
        publish { it }
        viewModelScope.launch {
            try {
                val data = service.request(machineId, "configure", buildJsonObject {
                    put("conversationId", conversationId)
                    args()
                })
                (data["settings"] as? JsonObject)?.let { conversation = JsonObject(conversation + ("settings" to it)) }
            } catch (error: DesktopRemoteException) {
                publish { it.copy(notice = error.message) }
            } finally {
                if (--configuring == 0) pendingSettings = null
                publish { it }
                scheduleSave()
            }
        }
    }

    private fun settingsOf(conversation: JsonObject): RemoteSettings? =
        DesktopRemoteService.parseSettings(conversation["settings"] as? JsonObject)

    fun dismissNotice() = publish { it.copy(notice = null) }

    /** 状态行的「重试」：已连上中转就重新向电脑打开对话，否则立刻重连（跳过退避）。 */
    fun reconnect() {
        publish { it.copy(notice = null, retrying = true) }
        if (service.link.value == DesktopLinkState.Connected) openOrDraft() else service.reconnectNow()
    }

    private fun launchQuietly(op: String, args: JsonObject) {
        viewModelScope.launch {
            try { service.request(machineId, op, args) } catch (error: DesktopRemoteException) {
                publish { it.copy(notice = error.message) }
            }
        }
    }

    // ---- 状态 ---------------------------------------------------------------

    private fun publish(update: (RemoteConversationUiState) -> RemoteConversationUiState) {
        val awaiting = awaitingReply?.takeIf { waiting ->
            raw.none { it.string("role") == "assistant" && it.string("id") !in waiting.knownAssistantIds }
        }
        if (awaiting == null) awaitingReply = null
        val messages = raw.mapNotNull { item ->
            val id = item.string("id") ?: return@mapNotNull null
            reveals[id]?.let { reveal -> return@mapNotNull revealed(id, item, reveal) }
            mapped[id]?.takeIf { it.first === item }?.second
                ?: DesktopMessageMapper.map(conversationId, item)?.also { mapped[id] = item to it }
        } + optimistic.map { it.message } + listOfNotNull(awaiting?.placeholder)
        val readOnly = conversation.string("readOnly")
        val settings = currentSettings()
        _state.value = update(_state.value).copy(
            conversationId = conversationId,
            draft = if (drafting) RemoteDraftUi(draftPersonas, draftPersonaId) else null,
            title = conversation.string("title") ?: _state.value.title,
            modelLabel = settings?.model ?: conversation.string("modelLabel") ?: _state.value.modelLabel,
            settings = settings.takeIf { readOnly == null },
            busy = conversation.bool("busy") == true || messages.any { it.isStreaming && it.role == Role.ASSISTANT },
            readOnly = readOnly,
            messages = messages,
            pendingAttachments = outgoing.map { item ->
                FileInfo(
                    id = item.id,
                    name = item.name,
                    mimeType = item.mime,
                    sizeBytes = item.staged?.size,
                    url = item.uri.toString(),
                    uploadStatus = item.status,
                )
            },
        )
    }

    private fun scheduleSave() {
        if (drafting) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500)
            val messages = JsonArray(raw.toList())
            cache.save(machineId, conversationId, conversation, messages)
        }
    }

    override fun onCleared() {
        outgoing.forEach { item ->
            cancelOnDesktop(item)
            item.staged?.file?.delete()
        }
        if (opened && !drafting) service.requestQuietly(machineId, "close", buildJsonObject { put("conversationId", conversationId) })
        if (!drafting && raw.isNotEmpty()) cache.save(machineId, conversationId, conversation, JsonArray(raw.toList()))
        service.release()
    }
}

private const val IMAGE_REFUSED = "当前模型不支持图片，请切换到支持视觉的模型"

// 逐字放出：电脑约每秒推一次；间隔按实测平滑，限在合理范围内，免得一次长停顿把速度拖到很慢。
private const val REVEAL_FRAME_MS = 16L
private const val REVEAL_DEFAULT_INTERVAL_MS = 1_000.0
private const val REVEAL_MIN_INTERVAL_MS = 250.0
private const val REVEAL_MAX_INTERVAL_MS = 1_600.0
/** 回答写完后，剩下的在这段时间内放完。 */
private const val REVEAL_FINISH_MS = 400.0
/** 无论积压多少，最多落后这么久：一次来了很长一段（如工具结果后整段输出）不会慢慢爬。 */
private const val REVEAL_MAX_LAG_MS = 2_000.0
/** 字/毫秒；积压很少时也保持可见的书写速度，不会一个字一个字地挤。 */
private const val REVEAL_MIN_SPEED = 0.03

/** 与电脑生成的对话 id 同一格式：`chat_<毫秒时间戳>_<9 位小写字母数字>`。 */
private fun newConversationId(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val random = java.security.SecureRandom()
    val suffix = CharArray(9) { alphabet[random.nextInt(alphabet.length)] }
    return "chat_${System.currentTimeMillis()}_${String(suffix)}"
}
private const val DEFAULT_CHUNK_BYTES = 256L * 1024
private const val MIN_CHUNK_BYTES = 16 * 1024
private const val MAX_CHUNK_BYTES = 512 * 1024

/** 读满 [buffer] 或读到结尾，返回读到的字节数：除最后一片外每片都必须是整片。 */
private fun InputStream.readUpTo(buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
