package com.molagpt.app.core.network

import com.molagpt.app.core.model.DesktopLinkState
import com.molagpt.app.core.model.RemoteModelGroup
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 「与某台电脑对话」的通道抽象。账号模式由 [DesktopRemoteService] 实现（账号中转、
 * 明文帧 + 配对密钥签名）；匿名模式由 [AnonRemoteService] 实现（设备命名空间、
 * 端到端 AEAD）。会话页面只依赖此接口，同一套 UI 服务两种通道。
 */
interface RemoteDesktopChannel {
    /** 有页面需要实时内容；与 [release] 成对调用。 */
    fun acquire()

    fun release()

    /** 通道整体状态；匿名模式为所有已配对电脑的聚合。 */
    val link: StateFlow<DesktopLinkState>

    /** 此刻连在中转上的电脑（machineId / deviceId）。 */
    val onlineMachines: StateFlow<Set<String>>

    val pushes: SharedFlow<DesktopPush>

    /** 列表可能变化（中转提示）。 */
    val listChanged: SharedFlow<Unit>

    suspend fun request(machineId: String, op: String, args: JsonObject = JsonObject(emptyMap())): JsonObject

    fun requestQuietly(machineId: String, op: String, args: JsonObject)

    suspend fun modelGroups(machineId: String): List<RemoteModelGroup>

    fun cachedModelGroups(machineId: String): List<RemoteModelGroup>?

    /** 网络恢复、回到前台时立刻重连。 */
    fun reconnectNow()
}
