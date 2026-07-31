package com.molagpt.app.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL

data class UpdateInfo(val version: String, val url: String, val notes: String?)

private const val GitHubApiUrl =
    "https://api.github.com/repos/MOLAaaaaaaa/MolaGPT.Mobile/releases/latest"
private const val ManifestUrl =
    "https://chatgpt.wljay.cn/v2/android-version.json"

internal val remoteFeedJson = Json { ignoreUnknownKeys = true }

/** Returns [UpdateInfo] if a newer version is available than [current], null otherwise. */
suspend fun checkForUpdate(current: String): UpdateInfo? = coroutineScope {
    val github = async { fetchGitHub() }
    val manifest = async { fetchManifest() }
    val candidates = listOfNotNull(github.await(), manifest.await())
    val best = candidates.maxWithOrNull { a, b -> compareAppVersion(a.version, b.version) }
        ?: return@coroutineScope null
    if (compareAppVersion(best.version, current) <= 0) return@coroutineScope null
    // 同版本优先带上 GitHub Release notes（Markdown changelog）。
    val notes = candidates
        .filter { compareAppVersion(it.version, best.version) == 0 }
        .mapNotNull { it.notes }
        .firstOrNull()
    best.copy(notes = notes ?: best.notes)
}

/** 语义化版本比较：`a > b` → 正数。用于更新检查与运营消息 `minAppVersion`。 */
internal fun compareAppVersion(a: String, b: String): Int {
    val left = parseVer(a)
    val right = parseVer(b)
    repeat(maxOf(left.size, right.size)) { i ->
        val d = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

private fun parseVer(v: String): List<Int> =
    v.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }

private suspend fun fetchGitHub(): UpdateInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(GitHubApiUrl).openConnection()
        conn.setRequestProperty("User-Agent", "MolaGPT-Android")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        val obj = remoteFeedJson.parseToJsonElement(conn.getInputStream().bufferedReader().readText()).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@runCatching null
        val url = obj["html_url"]?.jsonPrimitive?.content
            ?: "https://github.com/MOLAaaaaaaa/MolaGPT.Mobile/releases"
        val notes = obj["body"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
        UpdateInfo(tag.trimStart('v', 'V'), url, notes)
    }.getOrNull()
}

private suspend fun fetchManifest(): UpdateInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(ManifestUrl).openConnection()
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        val obj = remoteFeedJson.parseToJsonElement(conn.getInputStream().bufferedReader().readText()).jsonObject
        val ver = obj["version"]?.jsonPrimitive?.content ?: return@runCatching null
        val url = obj["url"]?.jsonPrimitive?.content
            ?: "https://github.com/MOLAaaaaaaa/MolaGPT.Mobile/releases"
        UpdateInfo(ver.trimStart('v', 'V'), url, null)
    }.getOrNull()
}
