package com.molagpt.app.core.network

import android.util.Base64
import com.molagpt.app.core.network.dto.AltchaChallenge
import com.molagpt.app.core.network.dto.AltchaSolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * ALTCHA proof-of-work 求解器。
 *
 * 服务端 challenge.php 给出 `challenge = sha256(salt + number)`（number 在 0..maxnumber 之间），
 * 客户端必须暴力找回这个 number。maxnumber 当前 10 万，现代手机毫秒级即可解出。
 *
 * 求解止于本类：只产出 number 与可直接放进 `X-Altcha-Payload` 头的 base64 串；
 * 网络与缓存在 [ShortTokenManager]。
 */
class AltchaSolver(private val json: Json) {

    /**
     * 暴力求解。命中返回 number；越过 [AltchaChallenge.maxnumber] 仍未命中返回 null（由调用方判失败）。
     * 跑在 [Dispatchers.Default]（CPU 密集），每 8192 次 [yield] 一次以便可取消、不饿死线程。
     */
    suspend fun solve(challenge: AltchaChallenge): Int? = withContext(Dispatchers.Default) {
        val target = challenge.challenge.lowercase()
        val saltBytes = challenge.salt.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val max = challenge.maxnumber.coerceAtLeast(0)
        var n = 0
        while (n <= max) {
            if (n and 0x1FFF == 0) yield()
            digest.update(saltBytes)
            digest.update(n.toString().toByteArray(Charsets.UTF_8))
            if (toHex(digest.digest()) == target) return@withContext n // digest() 后自动 reset
            n++
        }
        null
    }

    /** 构造提交给 auth.php 的 base64(solution JSON)。Base64.NO_WRAP：单行、带 padding。 */
    fun buildPayload(challenge: AltchaChallenge, number: Int): String {
        val solution = AltchaSolution(
            algorithm = challenge.algorithm,
            challenge = challenge.challenge,
            number = number,
            salt = challenge.salt,
            signature = challenge.signature,
        )
        val jsonStr = json.encodeToString(AltchaSolution.serializer(), solution)
        return Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
