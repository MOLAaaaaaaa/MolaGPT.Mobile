package com.molagpt.app.core.network

import com.molagpt.app.core.model.Usage
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 一次非流式文本请求的结果。 */
sealed interface TextCompletion {
    data class Success(val text: String, val usage: Usage?) : TextCompletion

    /** [overflow]：服务商以输入超长拒绝，调用方可把输入切小后重试。 */
    data class Failure(val message: String, val overflow: Boolean) : TextCompletion
}

/**
 * 可取消的同步请求：协程取消时断开连接。
 * 阻塞式 `execute()` 不响应协程取消，长请求会在用户停止后继续占着连接跑完。
 */
internal suspend fun OkHttpClient.executeCancellable(request: Request): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { it.code to it.body?.string().orEmpty() } }
                    result.onSuccess { if (cont.isActive) cont.resume(it) }
                        .onFailure { if (cont.isActive) cont.resumeWithException(it) }
                }
            },
        )
    }
