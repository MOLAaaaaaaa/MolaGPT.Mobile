package com.molagpt.app.core.model

import kotlinx.serialization.json.*

data class ServerSubagent(val batch: String, val data: JsonObject) {
    val id: String get() = "$batch:${text("id")}"
    fun text(key: String): String = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    val running: Boolean get() = text("status") == "running"
    val statusText: String get() = when (text("status")) {
        "running" -> "运行中"
        "completed" -> "已完成"
        "failed" -> "失败"
        "timeout" -> "已超时"
        "rejected" -> "未派出"
        else -> "已停止"
    }
    fun elapsed(now: Long): String {
        val seconds = ((data["ended_at"]?.jsonPrimitive?.longOrNull ?: now) -
            data["started_at"]!!.jsonPrimitive.long).coerceAtLeast(0) / 1000
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m${seconds % 60}s"
    }
    fun report(limit: Int = Int.MAX_VALUE): String = buildString {
        append(text("report").take(limit))
        (data["sources"] as? JsonArray)?.forEach { source ->
            val item = source.jsonObject
            append("\n\n[${item["title"]!!.jsonPrimitive.content}](${item["url"]!!.jsonPrimitive.content})")
        }
        (data["files"] as? JsonArray)?.forEach { file ->
            val item = file.jsonObject
            append("\n\n[${item["name"]!!.jsonPrimitive.content}](${item["url"]!!.jsonPrimitive.content})")
        }
    }
}

object ServerSubagents {
    const val KEY = "subagents"
    fun read(metadata: Map<String, String>): JsonObject = metadata[KEY]?.let {
        Json.parseToJsonElement(it).jsonObject
    } ?: JsonObject(emptyMap())

    fun agents(batches: JsonObject): List<ServerSubagent> = batches.flatMap { (batch, values) ->
        values.jsonArray.map { ServerSubagent(batch, it.jsonObject) }
    }

    fun merge(batches: JsonObject, frame: JsonObject): JsonObject {
        val batch = frame["batch"]!!.jsonPrimitive.content
        val agents = (batches[batch] as? JsonArray).orEmpty().associateByTo(linkedMapOf()) {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }
        frame["agents"]!!.jsonArray.forEach { item ->
            val agent = item.jsonObject
            val id = agent["id"]!!.jsonPrimitive.content
            agents[id] = JsonObject(agents[id]?.jsonObject.orEmpty() + agent)
        }
        val values = agents.values.map { agent ->
            frame["waiting"]?.let { JsonObject(agent.jsonObject + ("batch_waiting" to it)) } ?: agent
        }
        return JsonObject(batches + (batch to JsonArray(values)))
    }

    fun finish(batches: JsonObject): JsonObject = JsonObject(batches.mapValues { (_, values) ->
        JsonArray(values.jsonArray.map { value ->
            val agent = JsonObject(value.jsonObject - "batch_waiting")
            if (agent["status"]?.jsonPrimitive?.content != "running") agent else JsonObject(agent + mapOf(
                "status" to JsonPrimitive("stopped"), "ended_at" to JsonPrimitive(System.currentTimeMillis()),
            ))
        })
    })

    fun tool(tool: MessageFragment.ToolCall, batches: JsonObject): MessageFragment.ToolCall {
        val batch = tool.agentId?.takeIf { it.startsWith("server:") }?.removePrefix("server:") ?: return tool
        val agents = (batches[batch] as? JsonArray)?.map { ServerSubagent(batch, it.jsonObject) }.orEmpty()
        if (agents.isEmpty()) return tool
        val running = agents.any { it.running }
        val completed = agents.count { it.text("status") == "completed" }
        val waiting = agents.any { it.data["batch_waiting"]?.jsonPrimitive?.booleanOrNull == true }
        return tool.copy(
            label = if (waiting) "正在等待 ${agents.size} 个子代理" else "${agents.size} 个子代理",
            status = if (running) ToolStatus.RUNNING else if (completed == agents.size) ToolStatus.SUCCESS else ToolStatus.FAILED,
            statusLabel = if (running) "运行中" else "$completed 个已完成",
        )
    }

    fun history(metadata: Map<String, String>): String = agents(read(metadata)).joinToString("\n\n") { agent ->
        "${agent.text("label")}：${agent.statusText}\n${agent.report(4000)}\n${agent.text("error")}".trim()
    }.let { if (it.isEmpty()) "" else "\n\n[子代理任务记录]\n$it" }
}
