package com.molagpt.app.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.molagpt.app.core.model.StreamEvent

/** JWT 失效（401）。聊天界面据此清 token 并提示重新登录，而非反复重试注定失败的请求。 */
class MolaAuthExpiredException(message: String = "登录状态已失效，请重新登录") : java.io.IOException(message)

/** 后端返回的业务错误（非 2xx 或流内 error）。 */
class MolaApiException(val statusCode: Int?, message: String) : Exception(message)

internal class MolaChatRejectedException(val failure: StreamEvent.Failed) : Exception(failure.message)

internal fun parseMolaChatFailure(value: JsonElement?): StreamEvent.Failed? {
    val error = value as? JsonObject ?: return null
    fun text(key: String) = (error[key] as? JsonPrimitive)?.contentOrNull
    val message = text("message")?.takeIf(String::isNotBlank) ?: return null
    val action = when {
        text("reauth") == "true" -> "sign_in"
        text("next_action") != null -> text("next_action")
        text("type") == "quota_exhausted" -> "view_usage"
        text("retryable") == "true" -> "retry"
        else -> null
    }
    return StreamEvent.Failed(message, text("type"), action, text("retry_after")?.toIntOrNull(), error)
}

internal const val LEGACY_SHARED_QUOTA_NOTICE = "该供应商共享额度已用完，请稍后再试。"

internal fun sharedQuotaFailure() = StreamEvent.Failed(
    LEGACY_SHARED_QUOTA_NOTICE, "shared_quota_exhausted", "switch_model",
)

internal fun providerChatFailure(message: String?, statusCode: Int = 0, code: String? = null): StreamEvent.Failed {
    val probe = "$code $message"
    return when {
        "共享额度已用完" in probe -> sharedQuotaFailure()
        listOf("insufficient_quota", "quota_exceeded", "quota exceeded", "billing_hard_limit", "insufficient balance",
            "额度不足", "配额已用完", "余额不足").any { probe.contains(it, ignoreCase = true) } ->
            StreamEvent.Failed("当前模型的服务额度不足，请切换模型或稍后重试。", "upstream_quota_exhausted", "switch_model")
        statusCode == 429 || listOf("rate_limit_exceeded", "rate limit exceeded", "too many requests", "resource_exhausted")
            .any { probe.contains(it, ignoreCase = true) } ->
            StreamEvent.Failed("当前模型请求较多，请稍后重试。", "rate_limited", "retry")
        else -> StreamEvent.Failed("模型暂时无法响应，请稍后重试。", "upstream_unavailable", "retry")
    }
}

/** Only explicit error frames; normal text/tool chunks must remain normal content. */
internal fun parseMolaStreamFailure(root: JsonObject?, eventName: String? = null, statusCode: Int = 0): StreamEvent.Failed? {
    val named = eventName in listOf("error", "mola_error", "response.failed")
    if (root == null) return if (named) providerChatFailure(null, statusCode) else null
    parseMolaChatFailure(root["mola_error"])?.let { return it }
    fun text(value: JsonElement?) = (value as? JsonPrimitive)?.contentOrNull
    var node = root["error"]?.takeUnless { it.toString() in listOf("null", "false", "\"\"") }
    val objectNode = node as? JsonObject
    val conflict = text(node) ?: text(objectNode?.get("code"))
    if (statusCode == 409 && (conflict == "request_already_accepted" || conflict == "idempotency_conflict")) {
        return StreamEvent.Failed(
            text(root["message"]) ?: text(objectNode?.get("message")) ?: "请求已经接收，请读取原有结果。",
            type = conflict,
        )
    }
    if (objectNode != null && objectNode["retryable"] != null && objectNode["type"] != null)
        parseMolaChatFailure(objectNode)?.let { return it }
    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    val failed = named || text(root["type"]) in listOf("error", "response.failed")
        || text(choice?.get("finish_reason")) == "error"
        || (text(root["object"]) == "response" && text(root["status"]) == "failed")
    if (node == null && !failed) return null
    if (node == null) node = (root["response"] as? JsonObject)?.get("error") ?: root
    val obj = node as? JsonObject
    return providerChatFailure(
        text(node) ?: text(obj?.get("message")),
        if (statusCode != 0) statusCode else text(obj?.get("code"))?.toIntOrNull() ?: 0,
        text(obj?.get("code")) ?: text(obj?.get("type")) ?: text(obj?.get("status")),
    )
}

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
