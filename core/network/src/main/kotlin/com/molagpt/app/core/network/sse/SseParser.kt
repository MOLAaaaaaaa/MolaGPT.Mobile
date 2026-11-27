package com.molagpt.app.core.network.sse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 一个 SSE 事件载荷。 */
data class SsePayload(
    val event: String?,
    val data: String,
    /** Number of legacy replay data lines in this fully delivered event. */
    val resumeLines: Int = 0,
) {
    val isDone: Boolean get() = data.trim() == "[DONE]"
}

/**
 * 通用 SSE 解析：data: 行拼接、event: 行、`:` 注释/心跳忽略、
 * 空行分隔事件、`data: [DONE]` 终止。用 [readLine] 抽象行源，使解析逻辑可纯单测
 * （测试传 list 迭代器，真实传 `channel.readUTF8Line`）。
 */
fun sseFlow(readLine: suspend () -> String?): Flow<SsePayload> = flow {
    val data = StringBuilder()
    var event: String? = null
    var dataLines = 0
    var resumeLines = 0
    while (true) {
        val line = readLine() ?: break
        if (line.isEmpty()) {
            if (dataLines != 0) {
                val payload = SsePayload(event, data.toString(), resumeLines)
                emit(payload)
                if (payload.isDone) return@flow
            }
            data.setLength(0)
            dataLines = 0
            resumeLines = 0
            event = null
            continue
        }
        if (line[0] == ':') continue // 注释/心跳
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }
        when (field) {
            "data" -> {
                if (dataLines++ > 0) data.append('\n')
                // stream_resume.php counts cached data lines, independent of JSON parsing.
                if (':' in line && !line.contains("data: [DONE]") && !line.contains("\"type\":\"session_init\"")) {
                    resumeLines++
                }
                data.append(value)
            }
            "event" -> event = value
        }
    }
    // EOF discards events without their separating blank line.
}
