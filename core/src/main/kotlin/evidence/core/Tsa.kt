// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import org.bouncycastle.tsp.TimeStampToken
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Date

data class TsaProvider(val id: String, val name: String, val url: String)

class TokenInfo(
    val time: Date, val signer: String, val signerCertSha256: String, val policy: String,
    val imprintMatches: Boolean, val signatureValid: Boolean,
    /** The token itself carries the ETSI EN 319 422 "qualified timestamp" statement. */
    val declaresQualified: Boolean,
    /** Name of the EU-Trusted-List service this token chains to, or null if it chains to none we know. */
    val trustedService: String?,
) {
    val valid get() = signatureValid && imprintMatches
}

/** RFC 3161 client. Only a SHA-256 digest ever leaves the device. */
object Tsa {
    /** Free, public, no-account endpoints. Order = preference. */
    val DEFAULTS = listOf(
        TsaProvider("sectigo-qualified", "Sectigo (Europe) SL", "http://timestamp.sectigo.com/qualified"),
        TsaProvider("accv", "ACCV", "http://tss.accv.es:8318/tsa"),
        TsaProvider("izenpe", "Izenpe", "http://tsa.izenpe.com"),
    )

    /**
     * Trust anchors: SHA-256 of the public key (SubjectPublicKeyInfo) of services listed as
     * "QTST, granted" on Spain's national trusted list (https://tsl.digital.gob.es/TSL.xml,
     * list issued 2026-11-04, checked 2026-11-15 with tools/tsl_check.py).
     *
     * A token is trusted only if its signing certificate has one of these keys or was issued
     * by one of them. Timestamp requests travel over plain HTTP, so without this an attacker
     * on the network, or anyone forging a file, could substitute a self-made token.
     * Entries are only ever added, so recordings made with an older key stay verifiable.
     */
    val TRUSTED: Map<String, String> = mapOf(
        "dfa41c48c7bc91d8d6436b2b92a68f623ebbda823742e22968fcaeb8f10b7ba9" to "Sectigo Qualified Time Stamping CA R35 (Sectigo (Europe) SL, ES)",
        "002385de56e9951942f751ad704897d90814231cf5fc12c89e3b5fb02ab38ccc" to "TSA1 ACCV 2016 (ACCV, ES)",
        "add784374de4a7968e00e97f04e46b1820c71c936cf9cbb515cfaea083f17aac" to "tsa.izenpe.com 2025 (Izenpe S.A., ES)",
    )

    private val QTST = ASN1ObjectIdentifier("0.4.0.19422.1.1")
    private val QC_STATEMENTS = ASN1ObjectIdentifier("1.3.6.1.5.5.7.1.3")
    private const val MAX_REPLY = 64 * 1024

    /** Asks [url] to timestamp [digest] (SHA-256). Returns the DER token only if it is valid and from a trusted service. */
    fun request(url: String, digest: ByteArray, timeoutMs: Int = 8000): ByteArray {
        val gen = TimeStampRequestGenerator().apply { setCertReq(true) }
        val req = gen.generate(TSPAlgorithms.SHA256, digest, BigInteger(64, SecureRandom()))
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/timestamp-query")
            conn.setRequestProperty("Accept", "application/timestamp-reply")
            conn.outputStream.use { it.write(req.encoded) }
            if (conn.responseCode != 200) throw IOException("TSA HTTP ${conn.responseCode}")
            val resp = TimeStampResponse(conn.inputStream.use { readCapped(it, MAX_REPLY) })
            resp.validate(req)
            val token = resp.timeStampToken ?: throw IOException("TSA refused: ${resp.statusString}")
            val info = inspect(token.encoded, digest)
            if (!info.valid) throw IOException("TSA returned an invalid token")
            if (info.trustedService == null) throw IOException("TSA token is not from a trusted service: ${info.signer}")
            return token.encoded
        } finally { conn.disconnect() }
    }

    private fun spki(c: X509CertificateHolder) = sha256(c.subjectPublicKeyInfo.encoded).hex()

    /** Checks a token offline: signature, that it covers [expectedDigest], and which trusted service issued it. */
    fun inspect(token: ByteArray, expectedDigest: ByteArray): TokenInfo {
        val tst = TimeStampToken(CMSSignedData(token))
        val info = tst.timeStampInfo
        @Suppress("UNCHECKED_CAST")
        val all = tst.certificates.getMatches(null) as Collection<X509CertificateHolder>
        @Suppress("UNCHECKED_CAST")
        val cert = (tst.certificates.getMatches(tst.sid as org.bouncycastle.util.Selector<X509CertificateHolder>)).firstOrNull()
        var sigOk = false
        var trusted: String? = null
        if (cert != null) {
            // validate() also requires the certificate to be valid at the stamped time and
            // to be marked for time stamping only.
            sigOk = try { tst.validate(JcaSimpleSignerInfoVerifierBuilder().build(cert)); true } catch (e: Exception) { false }
            if (sigOk) {
                trusted = TRUSTED[spki(cert)]
                if (trusted == null) for (issuer in all) {
                    val name = TRUSTED[spki(issuer)] ?: continue
                    val issued = try {
                        issuer.subject == cert.issuer && issuer.isValidOn(info.genTime) && cert.isSignatureValid(JcaContentVerifierProviderBuilder().build(issuer))
                    } catch (e: Exception) { false }
                    if (issued) { trusted = name; break }
                }
            }
        }
        var qualified = false
        try {
            val ext = info.toASN1Structure().extensions?.getExtension(QC_STATEMENTS)
            if (ext != null) for (st in ASN1Sequence.getInstance(ext.parsedValue)) {
                if (ASN1Sequence.getInstance(st).getObjectAt(0) == QTST) qualified = true
            }
        } catch (_: Exception) {}
        return TokenInfo(
            info.genTime, cert?.subject?.toString() ?: "unknown", cert?.let { sha256(it.encoded).hex() } ?: "", info.policy.id,
            info.messageImprintDigest.contentEquals(expectedDigest) && info.messageImprintAlgOID == TSPAlgorithms.SHA256,
            sigOk, qualified, trusted,
        )
    }
}
