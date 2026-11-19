// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ASiC-E container (ETSI EN 319 162-1) with a CAdES signature: a ZIP holding the files, a
 * manifest of their SHA-256 digests, and a detached CMS signature over that manifest.
 */
object Asic {
    const val MIME = "application/vnd.etsi.asic-e+zip"

    class Item(val name: String, val mime: String, val file: File? = null, val bytes: ByteArray? = null) {
        fun open() = file?.inputStream() ?: bytes!!.inputStream()
    }

    /** Detached CMS / CAdES-BES signature (with signing-certificate-v2) over [data]. */
    fun cades(data: ByteArray, signer: EvidenceSigner): ByteArray {
        val content = object : ContentSigner {
            private val buf = ByteArrayOutputStream()
            override fun getAlgorithmIdentifier() = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
            override fun getOutputStream(): OutputStream = buf
            override fun getSignature(): ByteArray = signer.sign(buf.toByteArray())
        }
        val certs = signer.chain.map { X509CertificateHolder(it) }
        val ess = SigningCertificateV2(arrayOf(ESSCertIDv2(sha256(signer.chain.first()))))
        val attrs = ASN1EncodableVector().apply { add(Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(ess))) }
        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .setSignedAttributeGenerator(DefaultSignedAttributeTableGenerator(AttributeTable(attrs)))
                .build(content, certs.first()))
        gen.addCertificates(JcaCertStore(certs))
        return gen.generate(CMSProcessableByteArray(data), false).encoded
    }

    fun manifestXml(items: List<Item>): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<asic:ASiCManifest xmlns:asic=\"http://uri.etsi.org/02918/v1.2.1#\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">\n")
        sb.append("  <asic:SigReference URI=\"META-INF/signature001.p7s\" MimeType=\"application/x-pkcs7-signature\"/>\n")
        val buf = ByteArray(1 shl 16)
        for (it in items) {
            val md = MessageDigest.getInstance("SHA-256")
            it.open().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            sb.append("  <asic:DataObjectReference URI=\"${it.name}\" MimeType=\"${it.mime}\">\n")
            sb.append("    <ds:DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>\n")
            sb.append("    <ds:DigestValue>${md.digest().b64()}</ds:DigestValue>\n")
            sb.append("  </asic:DataObjectReference>\n")
        }
        sb.append("</asic:ASiCManifest>\n")
        return sb.toString().toByteArray()
    }

    fun write(out: OutputStream, items: List<Item>, signer: EvidenceSigner) {
        val manifest = manifestXml(items)
        ZipOutputStream(out).use { z ->
            val mime = MIME.toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            z.write(mime); z.closeEntry()
            for (it in items) {
                z.putNextEntry(ZipEntry(it.name))
                it.open().use { s -> s.copyTo(z, 1 shl 16) }
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("META-INF/ASiCManifest001.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/signature001.p7s")); z.write(cades(manifest, signer)); z.closeEntry()
        }
    }
}
