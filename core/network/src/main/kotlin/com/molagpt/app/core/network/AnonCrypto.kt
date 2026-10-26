package com.molagpt.app.core.network

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 匿名远控协议的密码学层，与电脑端 `AnonV2`/`AnonProtocolV2` 和参考实现逐字节一致：
 * E() 长度前缀编码、P-256（原始 X 坐标 ECDH、DER ECDSA）、HKDF-SHA256、HMAC、AES-256-GCM。
 * 全部使用平台自带 JVM 加密，无新依赖；Android Keystore 密钥通过 [AnonGrantSigner] 注入。
 */
object AnonCrypto {
    private const val EC_CURVE = "secp256r1"
    val random: SecureRandom = SecureRandom()

    /** uint32_be(len) || bytes，label 在前；字段为文本（UTF-8）或原始字节。 */
    fun e(label: String, vararg fields: Any): ByteArray {
        val parts = ArrayList<ByteArray>(fields.size + 1)
        parts.add(label.toByteArray(Charsets.UTF_8))
        for (field in fields) parts.add(
            when (field) {
                is ByteArray -> field
                is String -> field.toByteArray(Charsets.UTF_8)
                is Int -> field.toString().toByteArray(Charsets.UTF_8)
                is Long -> field.toString().toByteArray(Charsets.UTF_8)
                else -> error("unsupported field ${field::class.java}")
            },
        )
        var size = 0
        for (part in parts) size += 4 + part.size
        val out = ByteArray(size)
        var at = 0
        for (part in parts) {
            out[at] = (part.size ushr 24).toByte()
            out[at + 1] = (part.size ushr 16).toByte()
            out[at + 2] = (part.size ushr 8).toByte()
            out[at + 3] = part.size.toByte()
            System.arraycopy(part, 0, out, at + 4, part.size)
            at += 4 + part.size
        }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    fun fixedTimeEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** RFC 5869 Extract+Expand，32 字节。 */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        val out = ByteArray(length)
        var generated = 0
        var counter = 1
        while (generated < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val take = minOf(t.size, length - generated)
            System.arraycopy(t, 0, out, generated, take)
            generated += take
            counter++
        }
        return out
    }

    // ---- base64url / crockford --------------------------------------------------

    private val B64_PAD = ByteArray(0)

    fun b64u(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64u(text: String): ByteArray? = runCatching {
        java.util.Base64.getUrlDecoder().decode(text)
    }.getOrNull()

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 哈希顶部 length×5 位，最高组在前（与 C#/PHP 相同的冻结方向）。 */
    fun crockford(hash: ByteArray, length: Int): String {
        val value = java.math.BigInteger(1, hash).shiftRight(hash.size * 8 - length * 5)
        val five = java.math.BigInteger.valueOf(31)
        val out = StringBuilder(length)
        for (i in 0 until length) {
            out.append(CROCKFORD[value.shiftRight((length - 1 - i) * 5).and(five).toInt()])
        }
        return out.toString()
    }

    fun deviceIdOf(spki: ByteArray): String = crockford(sha256(spki), 26)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    // ---- keys ---------------------------------------------------------------------

    /** 软件临时密钥（每次握手、每次连接新建，用后即弃）。 */
    class EphemeralKeyPair(val spki: ByteArray, val privateKey: PrivateKey) {
        fun ecdhRaw(theirSpki: ByteArray): ByteArray {
            val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirSpki))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(peer, true)
            return agreement.generateSecret() // P-256: 32 字节大端 X 坐标
        }
    }

    fun newEphemeral(): EphemeralKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(EC_CURVE))
        val pair = generator.generateKeyPair()
        return EphemeralKeyPair(pair.public.encoded, pair.private)
    }

    fun isP256Spki(spki: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        key.algorithm == "EC" && spki.size == 91
    } catch (error: Exception) {
        false
    }

    /** 验证设备公钥的 DER (r,s) 签名（SHA-256withECDSA 即 DER 输出）。 */
    fun verifyDer(data: ByteArray, signatureDer: ByteArray, theirSpki: ByteArray): Boolean = try {
        val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirSpki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signatureDer)
        }
    } catch (error: Exception) {
        false
    }

    // ---- AES-256-GCM -----------------------------------------------------------------

    class Sealed(val ciphertext: ByteArray, val tag: ByteArray)

    fun seal(key: ByteArray, nonce12: ByteArray, aad: ByteArray, plaintext: ByteArray): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce12))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val out = cipher.doFinal(plaintext)
        return Sealed(out.copyOfRange(0, out.size - 16), out.copyOfRange(out.size - 16, out.size))
    }

    fun open(key: ByteArray, nonce12: ByteArray, aad: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce12))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext + tag)
    }
}

/** 每次配对一把的授权密钥。生产实现放 Android Keystore（不可导出），测试用软件实现。 */
interface AnonGrantSigner {
    /** 生成新授权密钥并返回其 SPKI；同一 deviceId 重复调用会复用已有密钥。 */
    fun generate(deviceId: String): ByteArray

    fun sign(deviceId: String, data: ByteArray): ByteArray?

    fun has(deviceId: String): Boolean

    fun remove(deviceId: String)
}

/** 软件实现：JVM 单测与探针用。 */
class SoftwareGrantSigner : AnonGrantSigner {
    private val keys = HashMap<String, java.security.KeyPair>()

    override fun generate(deviceId: String): ByteArray {
        val existing = keys[deviceId]
        if (existing != null) return existing.public.encoded
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        keys[deviceId] = pair
        return pair.public.encoded
    }

    override fun sign(deviceId: String, data: ByteArray): ByteArray? {
        val pair = keys[deviceId] ?: return null
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
    }

    override fun has(deviceId: String): Boolean = keys.containsKey(deviceId)

    override fun remove(deviceId: String) {
        keys.remove(deviceId)
    }
}
