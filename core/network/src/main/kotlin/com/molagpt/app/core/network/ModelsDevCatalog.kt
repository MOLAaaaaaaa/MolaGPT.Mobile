package com.molagpt.app.core.network

import com.molagpt.app.core.model.ModelsDevPricing
import com.molagpt.app.core.model.ModelsDevProvider
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelsDevCatalog(private val http: MolaHttp, private val cache: File) {
    val cachedAt: Long? get() = cache.takeIf { it.exists() }?.lastModified()
    data class Loaded(val providers: List<ModelsDevProvider>, val warning: String? = null)

    suspend fun load(forceRefresh: Boolean = false): Loaded = withContext(Dispatchers.IO) {
        val cached = if (cache.exists()) runCatching { ModelsDevPricing.parse(cache.readText()) }
            .getOrNull()?.takeIf { it.isNotEmpty() } else null
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cache.lastModified() < 7 * 24 * 60 * 60 * 1000L) {
            return@withContext Loaded(cached)
        }
        try {
            val response = http.client.get("https://models.dev/api.json")
            check(response.status.isSuccess()) { "价目获取失败：HTTP ${response.status.value}" }
            val raw = response.bodyAsText()
            val parsed = ModelsDevPricing.parse(raw)
            check(parsed.isNotEmpty()) { "公开价目未包含有效价格" }
            cache.writeText(raw)
            Loaded(parsed)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (cached == null) throw e
            Loaded(cached, "刷新失败，已使用缓存价目：${e.message}")
        }
    }
}
