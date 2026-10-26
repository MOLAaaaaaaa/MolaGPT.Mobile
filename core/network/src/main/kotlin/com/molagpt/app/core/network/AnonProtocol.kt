package com.molagpt.app.core.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 匿名协议的转录与会话派生，冻结字段与顺序见电脑端 `AnonProtocolV2`。 */
object AnonProtocol {
    fun commit(
        deviceId: String, roomId: ByteArray, room: String, handshakeId: ByteArray,
        grantPub: ByteArray, phoneName: String, ephPubP: ByteArray, nP: ByteArray,
    ): ByteArray = AnonCrypto.sha256(
        AnonCrypto.e(
            "molagpt-anon-commit-v2", deviceId, roomId, room, handshakeId,
            grantPub, phoneName, ephPubP, nP,
        ),
    )

    fun offerSignatureInput(
        deviceId: String, roomId: ByteArray, room: String, handshakeId: ByteArray, commit: ByteArray,
        grantPub: ByteArray, phoneName: String, ephPubD: ByteArray, nD: ByteArray, devicePub: ByteArray,
    ): ByteArray = AnonCrypto.e(
        "molagpt-anon-offer-v2", deviceId, roomId, room, handshakeId, commit, grantPub,
        phoneName, ephPubD, nD, devicePub,
    )

    fun transcript(
        deviceId: String, roomId: ByteArray, room: String, handshakeId: ByteArray, commit: ByteArray,
        grantPub: ByteArray, phoneName: String, ephPubP: ByteArray, nP: ByteArray,
        ephPubD: ByteArray, nD: ByteArray, devicePub: ByteArray,
    ): ByteArray = AnonCrypto.sha256(
        AnonCrypto.e(
            "molagpt-anon-pair-v2", deviceId, roomId, room, handshakeId, commit, grantPub,
            phoneName, ephPubP, nP, ephPubD, nD, devicePub,
        ),
    )

    class MasterKeys(val master: ByteArray, val sessionAuth: ByteArray, val confirm: ByteArray, val finish: ByteArray)

    fun deriveMaster(z: ByteArray, transcript: ByteArray): MasterKeys {
        val master = AnonCrypto.hkdf(z, transcript, AnonCrypto.e("molagpt-anon-master-v2"))
        return MasterKeys(
            master,
            AnonCrypto.hkdf(master, transcript, AnonCrypto.e("molagpt-anon-sas-key-v2")),
            AnonCrypto.hkdf(master, transcript, AnonCrypto.e("molagpt-anon-confirm-v2")),
            AnonCrypto.hkdf(master, transcript, AnonCrypto.e("molagpt-anon-finish-v2")),
        )
    }

    fun sasCode(sessionAuth: ByteArray, transcript: ByteArray): String {
        val mac = AnonCrypto.hmac(sessionAuth, AnonCrypto.e("sas", transcript))
        val value = ((mac[0].toUInt() and 0xFFu) shl 24) or ((mac[1].toUInt() and 0xFFu) shl 16) or
            ((mac[2].toUInt() and 0xFFu) shl 8) or (mac[3].toUInt() and 0xFFu)
        return (value % 1_000_000u).toString().padStart(6, '0')
    }

    fun confirmValue(confirmKey: ByteArray, transcript: ByteArray, pairId: ByteArray): String =
        AnonCrypto.b64u(AnonCrypto.hmac(confirmKey, AnonCrypto.e("confirmed", transcript, pairId)))

    fun sealFinish(finishKey: ByteArray, transcript: ByteArray, payload: ByteArray): AnonCrypto.Sealed =
        AnonCrypto.seal(finishKey, ByteArray(12), AnonCrypto.e("pair-finish", transcript), payload)

    fun openFinish(finishKey: ByteArray, transcript: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray =
        AnonCrypto.open(finishKey, ByteArray(12), AnonCrypto.e("pair-finish", transcript), ciphertext, tag)

    // ---- session -------------------------------------------------------------------

    fun connectKey(master: ByteArray, pairId: ByteArray): ByteArray =
        AnonCrypto.hkdf(master, AnonCrypto.sha256(AnonCrypto.e("pair", pairId)), AnonCrypto.e("molagpt-anon-connect-v2"))

    fun proofPhone(connectKey: ByteArray, deviceId: String, pairId: ByteArray, nP: ByteArray): String =
        AnonCrypto.b64u(AnonCrypto.hmac(connectKey, AnonCrypto.e("hello-p", "2", deviceId, pairId, nP)))

    fun proofDesktop(connectKey: ByteArray, deviceId: String, pairId: ByteArray, nP: ByteArray, nD: ByteArray): String =
        AnonCrypto.b64u(AnonCrypto.hmac(connectKey, AnonCrypto.e("hello-d", "2", deviceId, pairId, nP, nD)))

    class SessionKeys(val sid: ByteArray, val phoneToDesktop: ByteArray, val desktopToPhone: ByteArray) {
        val sidText: String = AnonCrypto.b64u(sid)
    }

    fun deriveSession(master: ByteArray, deviceId: String, pairId: ByteArray, nP: ByteArray, nD: ByteArray): SessionKeys {
        val s = AnonCrypto.sha256(AnonCrypto.e("molagpt-anon-session-v2", "2", deviceId, pairId, nP, nD))
        return SessionKeys(
            s,
            AnonCrypto.hkdf(master, s, AnonCrypto.e("molagpt-anon-frame-v2", "p2d")),
            AnonCrypto.hkdf(master, s, AnonCrypto.e("molagpt-anon-frame-v2", "d2p")),
        )
    }

    /** 一个方向的帧状态：密钥 + 收发的下一个序号。nonce = 0x00000000 || uint64_be(seq)。 */
    class Direction(private val key: ByteArray, private val deviceId: String, private val pairId: ByteArray,
                    private val sid: ByteArray, private val name: String) {
        var nextSequence = 0L
            private set

        private fun aad(sequence: Long): ByteArray =
            AnonCrypto.e("molagpt-anon-frame-v2", "2", deviceId, pairId, sid, name, sequence.toString())

        private fun nonce(sequence: Long): ByteArray {
            val nonce = ByteArray(12)
            for (i in 0 until 8) nonce[11 - i] = (sequence ushr (8 * i)).toByte()
            return nonce
        }

        fun seal(plaintextJson: String): JsonObject {
            val sequence = nextSequence++
            val sealed = AnonCrypto.seal(key, nonce(sequence), aad(sequence), plaintextJson.toByteArray(Charsets.UTF_8))
            return buildJsonObject {
                put("v", 2)
                put("pair", AnonCrypto.b64u(pairId))
                put("sid", AnonCrypto.b64u(sid))
                put("seq", sequence.toString())
                put("ct", AnonCrypto.b64u(sealed.ciphertext))
                put("tag", AnonCrypto.b64u(sealed.tag))
            }
        }

        /** 序号错乱或 tag 错误抛 [javax.crypto.AEADBadTagException]。 */
        fun open(frame: JsonObject): Pair<Long, String> {
            val sequence = frame["seq"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.toLongOrNull()
                ?: throw IllegalArgumentException("bad seq")
            if (sequence != nextSequence) throw IllegalArgumentException("out-of-order frame $sequence")
            val ct = AnonCrypto.unb64u(frame.optString("ct") ?: "") ?: throw IllegalArgumentException("bad ct")
            val tag = AnonCrypto.unb64u(frame.optString("tag") ?: "") ?: throw IllegalArgumentException("bad tag")
            val plain = AnonCrypto.open(key, nonce(sequence), aad(sequence), ct, tag)
            nextSequence++
            return sequence to plain.toString(Charsets.UTF_8)
        }
    }

    fun httpSignatureInput(role: String, deviceId: String, pairId: ByteArray?, ts: Long, nonce: ByteArray,
                           bodySha256: ByteArray, path: String): ByteArray = AnonCrypto.e(
        "molagpt-anon-http-v2", "POST", "chatgpt.wljay.cn", path, role, deviceId,
        pairId ?: ByteArray(0), ts.toString(), nonce, bodySha256,
    )
}

internal fun JsonObject.optString(name: String): String? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.optLong(name: String): Long? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.optInt(name: String): Int? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.optBool(name: String): Boolean? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.optObj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
