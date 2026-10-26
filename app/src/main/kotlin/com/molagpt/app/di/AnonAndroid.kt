package com.molagpt.app.di

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.molagpt.app.core.network.AnonCrypto
import com.molagpt.app.core.network.AnonGrantSigner
import com.molagpt.app.core.network.AnonPairRecord
import com.molagpt.app.core.network.AnonPairStore
import com.molagpt.app.core.storage.CredentialStore
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 授权密钥放 Android Keystore：EC P-256、只允许签名、私钥不可导出。
 * 每次配对生成一把，alias 绑定电脑的 deviceId。
 */
class AnonGrantKeys : AnonGrantSigner {
    private fun alias(deviceId: String) = "anon.grant.$deviceId"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun generate(deviceId: String): ByteArray {
        val name = alias(deviceId)
        val existing = keyStore().getCertificate(name)?.publicKey?.encoded
        if (existing != null) return existing
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair().public.encoded
    }

    override fun sign(deviceId: String, data: ByteArray): ByteArray? = runCatching {
        val entry = keyStore().getEntry(alias(deviceId), null) as? KeyStore.PrivateKeyEntry ?: return null
        Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(data)
            sign()
        }
    }.getOrNull()

    override fun has(deviceId: String): Boolean = runCatching {
        keyStore().containsAlias(alias(deviceId))
    }.getOrDefault(false)

    override fun remove(deviceId: String) {
        runCatching { keyStore().deleteEntry(alias(deviceId)) }
    }
}

/** 匿名配对记录：master 等同于凭据，放加密存储；不随账号隔离。 */
class EncryptedAnonPairStore(private val credentials: CredentialStore) : AnonPairStore {
    private val indexKey = "anon.pair.index"

    private fun ids(): List<String> =
        credentials.loadSecret(indexKey)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    private fun writeIds(ids: List<String>) {
        credentials.saveSecret(indexKey, ids.distinct().joinToString(","))
    }

    override fun list(): List<AnonPairRecord> = ids().mapNotNull { load(it) }

    override fun load(deviceId: String): AnonPairRecord? {
        val raw = credentials.loadSecret(key(deviceId)) ?: return null
        val parts = raw.split('|')
        if (parts.size < 4) return null
        val pairId = AnonCrypto.unb64u(parts[0]) ?: return null
        val master = AnonCrypto.unb64u(parts[1]) ?: return null
        if (pairId.size != 16 || master.size != 32) return null
        val directoryKey = parts[3].takeIf { it.isNotBlank() }?.let { AnonCrypto.unb64u(it) }
        return AnonPairRecord(deviceId, pairId, master, parts[2], directoryKey)
    }

    override fun save(record: AnonPairRecord) {
        credentials.saveSecret(
            key(record.deviceId),
            listOf(
                AnonCrypto.b64u(record.pairId),
                AnonCrypto.b64u(record.master),
                record.machineName.replace('|', ' '), // the record is '|'-joined
                record.directoryKey?.let { AnonCrypto.b64u(it) } ?: "",
            ).joinToString("|"),
        )
        writeIds(ids() + record.deviceId)
    }

    override fun remove(deviceId: String) {
        credentials.removeSecret(key(deviceId))
        writeIds(ids() - deviceId)
    }

    private fun key(deviceId: String) = "anon.pair.$deviceId"
}
