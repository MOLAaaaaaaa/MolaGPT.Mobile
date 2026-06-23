package com.molagpt.app.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL

internal data class UpdateInfo(val version: String, val url: String, val notes: String?)

private const val GitHubApiUrl =
    "https://api.github.com/repos/MOLAaaaaaaa/MolaGPT.Mobile/releases/latest"
private const val ManifestUrl =
    "https://chatgpt.wljay.cn/v2/android-version.json"

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Returns [UpdateInfo] if a newer version is available than [current], null otherwise. */
internal suspend fun checkForUpdate(current: String): UpdateInfo? = coroutineScope {
    val github = async { fetchGitHub() }
    val manifest = async { fetchManifest() }
    listOfNotNull(github.await(), manifest.await())
        .maxWithOrNull { a, b -> parseVer(a.version).compareTo(parseVer(b.version)) }
        ?.takeIf { parseVer(it.version) > parseVer(current) }
}

private fun parseVer(v: String): List<Int> =
    v.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }

private operator fun List<Int>.compareTo(other: List<Int>): Int {
    repeat(maxOf(size, other.size)) { i ->
        val d = getOrElse(i) { 0 }.compareTo(other.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

private suspend fun fetchGitHub(): UpdateInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(GitHubApiUrl).openConnection()
        conn.setRequestProperty("User-Agent", "MolaGPT-Android")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        val obj = lenientJson.parseToJsonElement(conn.getInputStream().bufferedReader().readText()).jsonObject
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
        val obj = lenientJson.parseToJsonElement(conn.getInputStream().bufferedReader().readText()).jsonObject
        val ver = obj["version"]?.jsonPrimitive?.content ?: return@runCatching null
        val url = obj["url"]?.jsonPrimitive?.content
            ?: "https://github.com/MOLAaaaaaaa/MolaGPT.Mobile/releases"
        UpdateInfo(ver.trimStart('v', 'V'), url, null)
    }.getOrNull()
}
