package com.molagpt.app.feature.agentcontrol.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.model.DesktopInfo
import com.molagpt.app.core.model.DesktopLinkState
import com.molagpt.app.core.network.AnonRemoteException
import com.molagpt.app.core.network.AnonRemoteService
import com.molagpt.app.core.network.DesktopRemoteService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DesktopRow(
    val info: DesktopInfo,
    val online: Boolean,
    val paired: Boolean,
    /** 无账号配对的电脑：连接走端到端通道。 */
    val anon: Boolean = false,
)

data class DesktopsUiState(
    val desktops: List<DesktopRow> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val pairingMachineId: String? = null,
)

/**
 * 「电脑」页：账号下开启了远控的电脑，加上用配对码配对的无账号电脑；
 * 未登录时只有无账号部分。每台电脑列出它最近的对话。
 */
class DesktopsViewModel(
    private val service: DesktopRemoteService,
    private val anon: AnonRemoteService,
    private val cache: DesktopSnapshotCache,
    private val deviceName: String,
    private val deviceId: String,
) : ViewModel() {
    private val desktops = MutableStateFlow<List<DesktopInfo>>(emptyList())
    private val anonDesktops = MutableStateFlow<List<DesktopInfo>>(emptyList())
    private val anonLoaded = MutableStateFlow(false)
    private val status = MutableStateFlow(DesktopsUiState())
    private val pairedVersion = MutableStateFlow(0)

    private val accountRows = combine(desktops, service.link, service.onlineMachines, service.pairsVersion) { list, link, online, _ ->
        list.map { info ->
            DesktopRow(
                info = info,
                online = if (link == DesktopLinkState.Connected) info.machineId in online else info.online,
                paired = service.isPaired(info.machineId),
            )
        }
    }

    private val anonPart = combine(anonDesktops, anonLoaded, anon.link, anon.onlineMachines) { list, ready, link, online ->
        ready to list.map { info ->
            DesktopRow(
                info = info,
                online = link == DesktopLinkState.Connected && info.machineId in online,
                paired = true,
                anon = true,
            )
        }
    }

    val state: StateFlow<DesktopsUiState> = combine(accountRows, anonPart, status, pairedVersion) { account, anon, current, _ ->
        val (anonReady, anonRows) = anon
        // 同一台电脑既在账号列表里、又已用配对码配对时，只留配对码那一行。
        val anonNames = anonRows.map { it.info.machineName }.toSet()
        val accountShown = account.filter { it.paired || it.info.machineName !in anonNames }
        current.copy(desktops = accountShown + anonRows, loaded = current.loaded || anonReady)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DesktopsUiState())

    init {
        service.acquire()
        anon.acquire()
        refresh()
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            service.listChanged.debounce(400).collect { refresh(quiet = true) }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            anon.listChanged.debounce(400).collect { refresh(quiet = true) }
        }
    }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) status.value = status.value.copy(refreshing = true)
            // 账号列表：未登录时为空，不报错。
            runCatching { service.listDesktops() }
                .onSuccess { list ->
                    desktops.value = list
                    status.value = status.value.copy(loaded = true, refreshing = false, error = null)
                }
                .onFailure { error ->
                    if (status.value.error == null) {
                        status.value = status.value.copy(loaded = true, refreshing = false, error = error.message ?: "无法读取电脑列表")
                    }
                }
            // 无账号电脑：本地记录 + 密文目录（电脑离线时也看得到对话列表）。
            runCatching { anon.directoryInfos() }
                .onSuccess { list ->
                    anonDesktops.value = list
                    anonLoaded.value = true
                    status.value = status.value.copy(refreshing = false)
                }
        }
    }

    /**
     * 无账号配对：[confirmSas] 展示校验码并等用户确认与电脑上一致。结果交给 [onDone]（错误或 null）。
     */
    fun pairAnon(code: String, confirmSas: suspend (String) -> Boolean, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val failure = try {
                anon.pairWithCode(code, confirmSas)
                pairedVersion.value++
                refresh(quiet = true)
                null
            } catch (error: AnonRemoteException) {
                error.message
            } catch (error: Exception) {
                error.message ?: "配对未完成"
            }
            onDone(failure)
        }
    }

    fun forgetAnon(deviceId: String) {
        viewModelScope.launch {
            anon.removeDesktop(deviceId)
            cache.forget(deviceId)
            pairedVersion.value++
            refresh(quiet = true)
        }
    }

    /** 打开一个对话；电脑还没配对时先用账号配对，成功后再打开。 */
    fun openConversation(row: DesktopRow, conversationId: String, onOpen: (String) -> Unit) {
        if (row.anon || row.paired) onOpen(conversationId)
        else pairWithAccount(row.info.machineId) { onOpen(conversationId) }
    }

    fun pairWithAccount(machineId: String, onPaired: () -> Unit = {}) {
        if (status.value.pairingMachineId != null) return
        viewModelScope.launch {
            status.value = status.value.copy(pairingMachineId = machineId, error = null)
            try {
                service.pairWithAccount(machineId, deviceName, deviceId)
                pairedVersion.value++
                onPaired()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.value = status.value.copy(error = error.message ?: "配对未完成")
            } finally {
                status.value = status.value.copy(pairingMachineId = null)
            }
        }
    }

    fun forget(machineId: String) {
        viewModelScope.launch {
            service.forgetDesktop(machineId)
            cache.forget(machineId)
            pairedVersion.value++
            refresh(quiet = true)
        }
    }

    fun reconnect() {
        service.reconnectNow()
        anon.reconnectNow()
    }

    override fun onCleared() {
        service.release()
        anon.release()
    }
}
