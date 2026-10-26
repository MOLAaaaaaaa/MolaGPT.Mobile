package com.molagpt.app.feature.agentcontrol.desktop

import com.molagpt.app.core.model.MessageFragment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 电脑上还在运行的后台任务：Python 或子 Agent。 */
data class RemoteRunningTask(
    val id: String,
    /** `python` 或 `agent`。 */
    val kind: String,
    val label: String,
    val agentId: String?,
    /** 电脑发出时已运行的毫秒数；界面从 [receivedAtMs] 往后数。 */
    val elapsedMs: Long,
    /** 收到时的单调时钟毫秒数。 */
    val receivedAtMs: Long,
)

/** 对话里的一个子 Agent。 */
data class RemoteAgent(
    val id: String,
    val label: String,
    val model: String,
    val taskId: String?,
    /** waiting / running / completed / failed / cancelled / interrupted */
    val status: String,
    /** 尚未执行任务时没有用时。 */
    val elapsedMs: Long?,
    val receivedAtMs: Long,
    val currentAction: String?,
    val failure: String?,
) {
    val isRunning: Boolean get() = status == "running"
}

/** 子 Agent 页的一条活动。[kind] 为 task / thinking / text / tool；tool 的卡在 [tool]。 */
data class RemoteAgentActivity(
    val id: String,
    val kind: String,
    val label: String,
    val text: String,
    val tool: MessageFragment.ToolCall?,
)

/** 协议见 `docs/desktop-remote-design.md` 的 tasks、agent 推送与 agent_open。 */
object RemoteTasks {
    fun parseRunning(data: JsonObject?, receivedAtMs: Long): List<RemoteRunningTask> =
        (data?.get("running") as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            RemoteRunningTask(
                id = item.string("id") ?: return@mapNotNull null,
                kind = item.string("kind") ?: "python",
                label = item.string("label").orEmpty(),
                agentId = item.string("agentId"),
                elapsedMs = item.long("elapsedMs") ?: 0,
                receivedAtMs = receivedAtMs,
            )
        }

    fun parseAgents(data: JsonObject?, receivedAtMs: Long): List<RemoteAgent> =
        (data?.get("agents") as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            RemoteAgent(
                id = item.string("id") ?: return@mapNotNull null,
                label = item.string("label").orEmpty(),
                model = item.string("model").orEmpty(),
                taskId = item.string("taskId"),
                status = item.string("status") ?: "waiting",
                elapsedMs = item.long("elapsedMs"),
                receivedAtMs = receivedAtMs,
                currentAction = item.string("currentAction"),
                failure = item.string("failure"),
            )
        }

    fun parseActivity(item: JsonObject): RemoteAgentActivity? {
        val id = item.string("id") ?: return null
        val kind = item.string("kind") ?: return null
        val tool = (item["tool"] as? JsonObject)?.let { DesktopMessageMapper.tool("agent", it, 0) }
        return RemoteAgentActivity(id, kind, item.string("label").orEmpty(), item.string("text").orEmpty(), tool)
    }

    /** 合进一次 `agent` 推送：`append` 拼到同 id 的正文后面，其余按 id 整条替换，新的追加到末尾。 */
    fun merge(current: List<RemoteAgentActivity>, items: List<JsonObject>): List<RemoteAgentActivity> {
        val result = current.toMutableList()
        for (item in items) {
            val id = item.string("id") ?: continue
            val index = result.indexOfFirst { it.id == id }
            val append = item.string("append")
            if (append != null) {
                if (index >= 0) result[index] = result[index].copy(text = result[index].text + append)
                continue
            }
            val activity = parseActivity(item) ?: continue
            if (index >= 0) result[index] = activity else result += activity
        }
        return result
    }

    /** 与电脑 `TaskTools.FormatElapsed` 相同：45s、3m07s、1h05m。 */
    fun formatElapsed(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m${"%02d".format(seconds % 60)}s"
            else -> "${seconds / 3600}h${"%02d".format((seconds % 3600) / 60)}m"
        }
    }

    fun statusText(status: String): String = when (status) {
        "running" -> "运行中"
        "completed" -> "已完成"
        "failed" -> "失败"
        "cancelled" -> "已停止"
        "interrupted" -> "已中断"
        else -> "等待中"
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
