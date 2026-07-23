package com.molagpt.app.feature.agentcontrol

import com.molagpt.app.core.model.AgentToolStatus
import com.molagpt.app.core.model.RelayEnvelope
import com.molagpt.app.core.model.RelayEvent

/** [AgentBlock.Permission.resolved] 的哨兵：被后续事件自动折叠（非用户当场点按），展示为中性"已处理"。 */
internal const val PermissionAutoResolved = "auto"

/**
 * 把粗粒度 relay 事件折成 [AgentBlock] 列表——本特性的核心，镜像桌面 `AgentTranscriptReducer`
 * 的思路，但消费的是 relay 的**快照**事件而非增量：`answerSnapshot`/`thinkingSnapshot`
 * 按事件 seq 保序成块，工具按 id upsert。
 *
 * 有状态、非线程安全：由 ViewModel 在单一协程里串行喂入。每个 `userPrompt` 开一个新回合
 * （[turnIndex]++），回合内的块共用 `"<turn>-..."` 前缀作稳定 key。
 *
 * **不**在此做 seq 去重：`AgentControlService` 已按 `since=lastSeq` 重连，stream 内事件单调；
 * 桌面重启导致 seq 重置时，重置后的 `userPrompt` 自然开新回合（宁可重复展示也不吞事件）。
 */
class AgentTranscriptReducer {
    private val blocks = ArrayList<AgentBlock>()
    private var turnIndex = -1
    private var optimisticUserText: String? = null

    /** 已处理到的最大 seq（供 VM 在重新订阅时作 since 用）。 */
    var lastSeq: Long = 0L
        private set

    fun reset() {
        blocks.clear()
        turnIndex = -1
        lastSeq = 0L
        optimisticUserText = null
    }

    fun beginOptimisticTurn(text: String): List<AgentBlock> {
        turnIndex++
        optimisticUserText = text
        clearPending()
        blocks.add(AgentBlock.User("$turnIndex-user-local", text))
        blocks.add(AgentBlock.Pending("$turnIndex-pending", "正在连接…"))
        return snapshot()
    }

    /** 折入一个事件。返回是否产生了可见变化。 */
    fun apply(env: RelayEnvelope): Boolean {
        if (env.seq > lastSeq) lastSeq = env.seq
        val ev = env.event
        // 任何"智能体继续"的事件到达，意味着此前待决的权限请求已被处理（批准/拒绝/超时）——
        // 把仍为 pending 的权限卡折叠掉。覆盖历史重放与他处批准（relay 不回"已解析"事件）。
        val permFolded = if (ev !is RelayEvent.Unknown) resolveOpenPermissions() else false
        val changed = when (ev) {
            is RelayEvent.UserPrompt -> {
                if (optimisticUserText == ev.text) {
                    optimisticUserText = null
                    false
                } else {
                    clearPending()
                    optimisticUserText = null
                    turnIndex++
                    blocks.add(AgentBlock.User("$turnIndex-user", ev.text))
                    true
                }
            }

            is RelayEvent.ThinkingSnapshot -> {
                if (turnIndex < 0) turnIndex = 0
                clearPending()
                // segmentId 存在时按 segment 原位替换（桌面流式期间同段反复全文快照）；
                // 老桌面/历史投影无 segmentId，退回按 seq 分块（回合末单段，行为同旧版）。
                val key = "$turnIndex-thinking-" + (ev.segmentId ?: env.seq.toString())
                upsert(key) { AgentBlock.Thinking(key, ev.text) }
                true
            }

            is RelayEvent.AnswerSnapshot -> {
                if (turnIndex < 0) turnIndex = 0
                clearPending()
                val key = "$turnIndex-assistant-" + (ev.segmentId ?: env.seq.toString())
                upsert(key) { AgentBlock.AssistantText(key, ev.text) }
                true
            }

            is RelayEvent.ToolProgress -> {
                if (turnIndex < 0) turnIndex = 0
                clearPending()
                val key = "$turnIndex-tool-${ev.id}"
                val existing = blocks.firstOrNull { it.key == key } as? AgentBlock.Tool
                upsert(key) {
                    AgentBlock.Tool(
                        key = key,
                        toolId = ev.id,
                        name = ev.name.takeIf { it.isNotBlank() && it != "tool" }
                            ?: existing?.name
                            ?: ev.name,
                        status = ev.status,
                        title = ev.title ?: existing?.title,
                        argumentsJson = ev.argumentsJson ?: existing?.argumentsJson,
                        resultPreview = ev.resultPreview ?: existing?.resultPreview,
                    )
                }
                true
            }

            is RelayEvent.PermissionPrompt -> {
                if (turnIndex < 0) turnIndex = 0
                clearPending()
                val key = "$turnIndex-perm-${ev.id}"
                val existing = blocks.firstOrNull { it.key == key } as? AgentBlock.Permission
                upsert(key) {
                    AgentBlock.Permission(
                        key = key,
                        permissionId = ev.id,
                        toolName = ev.toolName,
                        description = ev.description,
                        argumentsJson = ev.argumentsJson,
                        // keep a local optimistic resolution across re-applies
                        resolved = existing?.resolved,
                    )
                }
                true
            }

            is RelayEvent.TurnFailed -> {
                if (turnIndex < 0) turnIndex = 0
                clearPending()
                val key = "$turnIndex-error"
                upsert(key) { AgentBlock.Error(key, ev.message) }
                true
            }

            is RelayEvent.TurnDone -> {
                clearPending()
                if (ev.inputTokens != null || ev.outputTokens != null || ev.totalTokens != null) {
                    if (turnIndex < 0) turnIndex = 0
                    val key = "$turnIndex-usage"
                    upsert(key) { AgentBlock.Usage(key, ev.inputTokens, ev.outputTokens, ev.totalTokens) }
                    true
                } else {
                    false
                }
            }

            RelayEvent.Unknown -> false
        }
        return changed || permFolded
    }

    /** 把所有仍待决（resolved==null）的权限卡折叠为"已处理"。返回是否有改动。 */
    private fun resolveOpenPermissions(): Boolean {
        var changed = false
        for (i in blocks.indices) {
            val b = blocks[i]
            if (b is AgentBlock.Permission && b.resolved == null) {
                blocks[i] = b.copy(resolved = PermissionAutoResolved)
                changed = true
            }
        }
        return changed
    }

    fun snapshot(): List<AgentBlock> = ArrayList(blocks)

    /**
     * 让"正在处理"指示器与会话真实状态同步。
     *
     * relay **不传** PendingShown（桌面把它当内部节奏标记折掉），而 [beginOptimisticTurn]
     * 的乐观 Pending 只活在本次进入会话期间——`reset()` 一进页面就清了。于是两种常见情况
     * 完全没有指示器：回合在电脑上发起，以及发送后退出再进入。叠加伪流式要攒 2.5s 才发
     * 第一个快照、模型思考期又可能几十秒无事件，用户看到的就是一片空白。
     *
     * 这里不新增协议字段，改由 phase 兜底：忙碌且末块不是"活的"内容时补一个 Pending。
     * “活的”指进行中的工具卡（它自己有转圈动画）和待决权限卡（等用户操作，不该显示忙）。
     * 非忙碌时移除，避免回合结束后残留。返回是否产生可见变化。
     */
    fun syncActivityIndicator(busy: Boolean, label: String = "正在处理…"): Boolean {
        val last = blocks.lastOrNull()
        if (!busy) return clearPending()
        if (last is AgentBlock.Pending) return false
        if (last is AgentBlock.Tool && last.status == AgentToolStatus.Started) return false
        if (last is AgentBlock.Tool && last.status == AgentToolStatus.Running) return false
        if (last is AgentBlock.Permission && last.resolved == null) return false
        if (turnIndex < 0) turnIndex = 0
        blocks.add(AgentBlock.Pending("$turnIndex-pending-live", label))
        return true
    }

    /**
     * 用户点了审批按钮后，乐观地把该卡片标成已处理（收起按钮、显示选择）。relay 不回"已解析"
     * 事件，所以由本地立即反馈。返回更新后的快照；找不到或选择未变则返回 null。
     */
    fun resolvePermission(permissionId: String, choice: String): List<AgentBlock>? {
        val idx = blocks.indexOfFirst { it is AgentBlock.Permission && it.permissionId == permissionId }
        if (idx < 0) return null
        val block = blocks[idx] as AgentBlock.Permission
        if (block.resolved == choice) return null
        blocks[idx] = block.copy(resolved = choice)
        return snapshot()
    }

    private fun clearPending(): Boolean {
        if (blocks.lastOrNull() !is AgentBlock.Pending) return false
        blocks.removeAt(blocks.lastIndex)
        return true
    }

    /** 按 key 替换已有块；不存在则追加。 */
    private inline fun upsert(key: String, build: () -> AgentBlock) {
        val idx = blocks.indexOfFirst { it.key == key }
        val block = build()
        if (idx >= 0) blocks[idx] = block else blocks.add(block)
    }
}
