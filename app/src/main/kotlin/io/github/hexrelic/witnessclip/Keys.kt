// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import evidence.core.EvidenceSigner
import evidence.core.hex
import evidence.core.sha256
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** A P-256 key that lives in the phone's secure hardware and can never be exported. */
class KeystoreSigner(private val alias: String, val level: String) : EvidenceSigner {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val key = ks.getKey(alias, null) as PrivateKey
    override val chain: List<ByteArray> = ks.getCertificateChain(alias).map { it.encoded }
    val publicKey: ByteArray get() = ks.getCertificate(alias).publicKey.encoded

    @Synchronized
    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }
}

object Keys {
    private const val DEVICE = "device-identity"
    private val ks get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs get() = App.instance.getSharedPreferences("keys", 0)

    /**
     * Creates a signing key, preferring StrongBox, then the TEE, each with hardware attestation
     * bound to [challenge]. Falls back to an unattested key only if the device offers nothing else.
     */
    fun create(alias: String, challenge: ByteArray): KeystoreSigner {
        fun gen(strongBox: Boolean, attest: Boolean) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
            if (attest) spec.setAttestationChallenge(challenge)
            if (strongBox) spec.setIsStrongBoxBacked(true)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
        }
        var level = "strongbox"
        try { gen(strongBox = true, attest = true) } catch (e: StrongBoxUnavailableException) {
            level = "tee"
            try { gen(strongBox = false, attest = true) } catch (e2: Exception) { level = "unattested"; gen(strongBox = false, attest = false) }
        } catch (e: Exception) {
            level = "tee"
            try { gen(strongBox = false, attest = true) } catch (e2: Exception) { level = "unattested"; gen(strongBox = false, attest = false) }
        }
        prefs.edit().putString(alias, level).apply()
        return KeystoreSigner(alias, level)
    }

    fun load(alias: String): KeystoreSigner? =
        if (ks.containsAlias(alias)) KeystoreSigner(alias, prefs.getString(alias, "unknown")!!) else null

    fun delete(alias: String) { try { ks.deleteEntry(alias) } catch (_: Exception) {}; prefs.edit().remove(alias).apply() }

    /** The long-lived identity of this installation. Created on first use, never backed up. */
    @Synchronized
    fun device(): KeystoreSigner = load(DEVICE) ?: create(DEVICE, sha256("AE1-device".toByteArray(), ByteArray(32).also { SecureRandom().nextBytes(it) }))

    fun deviceId(): String = sha256(device().publicKey).hex()

    fun sessionAlias(id: String) = "session-$id"
}
