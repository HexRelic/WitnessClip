// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** What Android's hardware says about a key and the device it lives in. */
class AttestationInfo(
    val challenge: ByteArray,
    /** 0 software, 1 trusted environment (TEE), 2 StrongBox. */
    val securityLevel: Int,
    /** 0 verified, 1 self-signed, 2 unverified, 3 failed; null when absent. */
    val bootState: Int?,
    val deviceLocked: Boolean?,
    val bootKey: ByteArray?,
    val osPatch: Int?,
    val packages: List<String>,
    val appSigners: List<String>,
)

object Attestation {
    private const val OID = "1.3.6.1.4.1.11129.2.1.17"

    /** SHA-256 of the public keys of Google's hardware attestation roots (bundled, from android.googleapis.com). */
    val googleRoots: Set<String> by lazy {
        val json = Attestation::class.java.getResourceAsStream("/attestation_roots.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@lazy emptySet()
        J.decodeFromString(ListSerializer(String.serializer()), json).map { sha256(parse(it.toByteArray()).publicKey.encoded).hex() }.toSet()
    }

    fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    class Chain(val signaturesValid: Boolean, val googleRoot: Boolean, val error: String?)

    /** Each certificate must be signed by the next; the last must be self-signed with a known Google key. */
    fun checkChain(chain: List<X509Certificate>): Chain {
        if (chain.isEmpty()) return Chain(false, false, "empty chain")
        try {
            for (i in chain.indices) chain[i].verify(chain[minOf(i + 1, chain.size - 1)].publicKey)
        } catch (e: Exception) {
            return Chain(false, false, e.message)
        }
        return Chain(true, sha256(chain.last().publicKey.encoded).hex() in googleRoots, null)
    }

    fun read(cert: X509Certificate): AttestationInfo? {
        val raw = cert.getExtensionValue(OID) ?: return null
        val seq = ASN1Sequence.getInstance(ASN1OctetString.getInstance(raw).octets)
        val level = ASN1Enumerated.getInstance(seq.getObjectAt(1)).value.toInt()
        val challenge = ASN1OctetString.getInstance(seq.getObjectAt(4)).octets
        var bootState: Int? = null
        var locked: Boolean? = null
        var bootKey: ByteArray? = null
        var patch: Int? = null
        val packages = ArrayList<String>()
        val signers = ArrayList<String>()
        for (listIndex in 6..7) {
            for (item in ASN1Sequence.getInstance(seq.getObjectAt(listIndex))) {
                val t = ASN1TaggedObject.getInstance(item)
                val v: ASN1Primitive = t.baseObject.toASN1Primitive()
                when (t.tagNo) {
                    704 -> {
                        val rot = ASN1Sequence.getInstance(v)
                        bootKey = ASN1OctetString.getInstance(rot.getObjectAt(0)).octets
                        locked = ASN1Boolean.getInstance(rot.getObjectAt(1)).isTrue
                        bootState = ASN1Enumerated.getInstance(rot.getObjectAt(2)).value.toInt()
                    }
                    706 -> patch = ASN1Integer.getInstance(v).value.toInt()
                    709 -> {
                        val app = ASN1Sequence.getInstance(ASN1OctetString.getInstance(v).octets)
                        for (p in ASN1Set.getInstance(app.getObjectAt(0))) {
                            packages.add(String(ASN1OctetString.getInstance(ASN1Sequence.getInstance(p).getObjectAt(0)).octets))
                        }
                        for (d in ASN1Set.getInstance(app.getObjectAt(1))) signers.add(ASN1OctetString.getInstance(d).octets.hex())
                    }
                }
            }
        }
        return AttestationInfo(challenge, level, bootState, locked, bootKey, patch, packages, signers)
    }
}
