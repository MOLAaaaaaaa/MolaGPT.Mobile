package com.molagpt.app.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** JWT 失效（401）。聊天界面据此清 token 并提示重新登录，而非反复重试注定失败的请求。 */
class MolaAuthExpiredException(message: String = "登录状态已失效，请重新登录") : Exception(message)

/** 后端返回的业务错误（非 2xx 或流内 error）。 */
class MolaApiException(val statusCode: Int?, message: String) : Exception(message)

internal fun serverResponseError(summary: String, statusCode: Int, raw: String): String =
    "$summary：HTTP $statusCode\n\nRaw：\n$raw"

/**
 * MolaGPT 服务端非 2xx 响应里给用户看的那句话；读不出来返回 null，由调用方回落到状态码。
 *
 * 服务端的错误体有三种写法：`{"error":"…"}`、`{"error":{"message":"…"}}`、
 * `{"success":false,"message":"…"}`。nginx 长连接并发满时回的是一页 HTML（429），
 * 里面没有可读原因，单独给一句。
 */
internal fun molaServerErrorMessage(statusCode: Int, body: String): String? {
    val text = body.trim().trimStart('﻿')
    if (text.startsWith("<")) return if (statusCode == 429) "当前使用人数较多，请稍后再试" else null
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val error = root["error"]
    return error.text()
        ?: (error as? JsonObject)?.get("message").text()
        ?: root["message"].text()
}
