package com.molagpt.app.feature.agentcontrol.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.model.DesktopInfo
import com.molagpt.app.core.model.DesktopLinkState
import com.molagpt.app.core.network.DesktopRemoteException
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
)

data class DesktopsUiState(
    val desktops: List<DesktopRow> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val pairingMachineId: String? = null,
)

/** 「电脑」页：账号下开启了远控的电脑，以及每台电脑最近的对话。 */
class DesktopsViewModel(
    private val service: DesktopRemoteService,
    private val cache: DesktopSnapshotCache,
    private val deviceName: String,
    private val deviceId: String,
) : ViewModel() {
    private val desktops = MutableStateFlow<List<DesktopInfo>>(emptyList())
    private val status = MutableStateFlow(DesktopsUiState())
    private val pairedVersion = MutableStateFlow(0)

    val state: StateFlow<DesktopsUiState> = combine(
        desktops, status, service.link, service.onlineMachines, pairedVersion,
    ) { list, current, link, online, _ ->
        current.copy(
            desktops = list.map { info ->
                DesktopRow(
                    info = info,
                    // 实时连接在线时以它为准；否则用电脑最近一次心跳。
                    online = if (link == DesktopLinkState.Connected) info.machineId in online else info.online,
                    paired = service.isPaired(info.machineId),
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DesktopsUiState())

    init {
        service.acquire()
        refresh()
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            service.listChanged.debounce(400).collect { refresh(quiet = true) }
        }
    }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) status.value = status.value.copy(refreshing = true)
            runCatching { service.listDesktops() }
                .onSuccess { list ->
                    desktops.value = list
                    status.value = status.value.copy(loaded = true, refreshing = false, error = null)
                }
                .onFailure { error ->
                    status.value = status.value.copy(loaded = true, refreshing = false, error = error.message ?: "无法读取电脑列表")
                }
        }
    }

    /** 用电脑上显示的配对码配对。返回给人看的错误，成功为 null。 */
    suspend fun pair(machineId: String, code: String): String? =
        try {
            service.pair(machineId, code, deviceName, deviceId)
            pairedVersion.value++
            null
        } catch (error: DesktopRemoteException) {
            error.message
        } catch (error: Exception) {
            error.message ?: "配对未完成"
        }

    /** 打开一个对话；电脑还没配对时先用账号配对，成功后再打开。 */
    fun openConversation(row: DesktopRow, conversationId: String, onOpen: (String) -> Unit) {
        if (row.paired) onOpen(conversationId)
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

    fun reconnect() = service.reconnectNow()

    override fun onCleared() {
        service.release()
    }
}
