package com.molagpt.app.core.network

import com.molagpt.app.core.model.MessageFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ServerToolApi(private val http: MolaHttp, private val tokens: ShortTokenManager) {
    private val client = http.okHttp.newBuilder().readTimeout(2, TimeUnit.MINUTES).callTimeout(2, TimeUnit.MINUTES).build()

    private suspend fun request(ticket: String, action: String): Request {
        val token = tokens.freshToken()
        val body = buildJsonObject { put("ticket", ticket); put("action", action) }
        return Request.Builder().url(MolaEndpoints.absolute("api/auth/tool_retry.php"))
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    suspend fun retry(ticket: String): MessageFragment.ToolCall {
        val request = request(ticket, "retry")
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = http.json.parseToJsonElement(response.body!!.string()).jsonObject
                if (!response.isSuccessful) throw IOException(body["message"]?.jsonPrimitive?.contentOrNull ?: "请求失败（${response.code}）")
                val html = body["html"]!!.jsonPrimitive.content
                val tool = WebToolArtifactSplitter().feed(html).filterIsInstance<WebToolArtifact.Tool>().single()
                MessageFragment.ToolCall("tool_${tool.id}", tool.name, tool.status, label = tool.label,
                    resultPreview = tool.preview, provider = tool.provider, serverTool = tool.serverTool)
            }
        }
    }

    suspend fun download(ticket: String, destination: OutputStream) {
        val request = request(ticket, "file")
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = http.json.parseToJsonElement(response.body!!.string()).jsonObject
                    throw IOException(body["message"]?.jsonPrimitive?.contentOrNull ?: "下载失败（${response.code}）")
                }
                response.body!!.byteStream().use { it.copyTo(destination) }
            }
        }
    }
}
