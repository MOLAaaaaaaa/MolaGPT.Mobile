package com.molagpt.app.core.network.sse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 一个 SSE 事件载荷。 */
data class SsePayload(val event: String?, val data: String) {
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
    while (true) {
        val line = readLine() ?: break
        if (line.isEmpty()) {
            if (data.isNotEmpty() || event != null) {
                emit(SsePayload(event, data.toString()))
                data.setLength(0)
                event = null
            }
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
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "event" -> event = value
        }
    }
    if (data.isNotEmpty() || event != null) emit(SsePayload(event, data.toString()))
}
