package com.molagpt.app.core.network

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 与一台电脑配对后保存的身份：请求都用 [secret] 签名。 */
class RemotePair(val pairId: String, val secret: ByteArray)

/**
 * 配对与签名的密码学部分，和电脑端 `RemotePairing` 逐字节对应。
 *
 * 配对码模式通过屏幕上的配对码验证身份；账号模式依赖中继已认证的同账号路由。
 * 两种模式均通过 ECDH 协商独立的请求签名密钥。
 */
object RemotePairing {
    private const val MAC_PREFIX = "molagpt-pair-1|"
    private const val SECRET_INFO = "molagpt-remote-pair-v1"
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 8
    const val ACCOUNT_PAIRING_CONTEXT = "molagpt-account-pair-v1"

    /** 宽松读取用户输入的配对码：大小写、分隔符，以及 Crockford 规定的易混字母。 */
    fun normalizeCode(input: String): String = buildString {
        for (raw in input) {
            val c = when (val upper = raw.uppercaseChar()) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> upper
            }
            if (c in ALPHABET) append(c)
        }
    }

    class Offer internal constructor(val publicKey: String, val mac: String, internal val keys: KeyPair)

    fun offer(code: String): Offer {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keys = generator.generateKeyPair()
        val publicKey = keys.public.encoded.toByteString().base64()
        return Offer(publicKey, hmac(code.toByteArray(), MAC_PREFIX + publicKey).toByteString().base64(), keys)
    }

    /** 校验电脑的答复并算出共享密钥；答复不是拿同一个配对码签的则返回 null。 */
    fun accept(code: String, offer: Offer, desktopKey: String, desktopMac: String, pairId: String): RemotePair? {
        val expected = hmac(code.toByteArray(), "$MAC_PREFIX${offer.publicKey}|$desktopKey|$pairId")
        val given = desktopMac.decodeBase64()?.toByteArray() ?: return null
        if (!MessageDigest.isEqual(expected, given)) return null
        return derivePair(code, offer, desktopKey, pairId)
    }

    fun acceptAccount(offer: Offer, desktopKey: String, desktopMac: String, pairId: String): RemotePair? {
        val pair = derivePair(ACCOUNT_PAIRING_CONTEXT, offer, desktopKey, pairId) ?: return null
        val expected = hmac(pair.secret, "$ACCOUNT_PAIRING_CONTEXT|${offer.publicKey}|$desktopKey|$pairId")
        val given = desktopMac.decodeBase64()?.toByteArray()
        if (given == null || !MessageDigest.isEqual(expected, given)) {
            pair.secret.fill(0)
            return null
        }
        return pair
    }

    private fun derivePair(salt: String, offer: Offer, desktopKey: String, pairId: String): RemotePair? {
        val encoded = desktopKey.decodeBase64()?.toByteArray() ?: return null
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(offer.keys.private)
        agreement.doPhase(peer, true)
        val shared = agreement.generateSecret()
        return try {
            RemotePair(pairId, hkdf(shared, salt.toByteArray(), SECRET_INFO.toByteArray()))
        } finally {
            shared.fill(0)
        }
    }

    /** 签名覆盖原样发送的参数字符串，两端不必约定 JSON 序列化方式。 */
    fun sign(pair: RemotePair, id: String, op: String, timestampMs: Long, args: String): String =
        hmac(pair.secret, "$id\n$op\n$timestampMs\n${pair.pairId}\n$args").toByteString().base64Url().trimEnd('=')

    private fun hmac(key: ByteArray, message: String): ByteArray = hmac(key, message.toByteArray())

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    /** RFC 5869，输出一个 SHA-256 块即够 32 字节。 */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1))
    }
}
