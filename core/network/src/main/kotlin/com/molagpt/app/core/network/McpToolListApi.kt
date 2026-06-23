package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.Ids
import com.molagpt.app.core.model.McpToolInfo
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 列出单个 MCP 服务器暴露的工具（JSON-RPC `tools/list`）。
 * 供设置页「MCP 服务器详情」展示 + per-tool 开关；失败返回空列表（UI 显示失败提示，不阻塞）。
 */
class McpToolListApi(private val http: MolaHttp) {
    suspend fun listTools(server: ByokMcpServer): List<McpToolInfo> {
        val body = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(Ids.newFragmentId()),
                "method" to JsonPrimitive("tools/list"),
            ),
        )
        val resp = http.client.post(server.endpoint) {
            contentType(ContentType.Application.Json)
            server.token?.takeIf { it.isNotBlank() }?.let {
                header(server.headerName.ifBlank { "Authorization" }, "Bearer $it")
            }
            setBody(http.json.encodeToString(JsonObject.serializer(), body))
        }
        val text = resp.bodyAsText().trimStart('﻿')
        if (!resp.status.isSuccess()) return emptyList()
        val root = runCatching { http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val result = root["result"]?.jsonObject ?: return emptyList()
        val tools = (result["tools"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return tools.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpToolInfo(
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                inputSchema = obj["inputSchema"],
            )
        }
    }
}