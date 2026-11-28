// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

import evidence.core.*
import evidence.core.flac.BitWriter
import evidence.core.flac.Crc
import evidence.core.flac.FlacDecoder
import evidence.core.flac.FlacEncoder
import evidence.core.flac.FlacFile
import evidence.core.flac.FlacFormatException
import evidence.core.flac.StreamInfo
import kotlinx.serialization.encodeToString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.Random
import kotlin.math.sin

class SoftSigner : EvidenceSigner {
    val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val cert = JcaX509CertificateConverter().getCertificate(
        JcaX509v3CertificateBuilder(X500Name("CN=test"), BigInteger.ONE, Date(0), Date(4102444800000L), X500Name("CN=test"), pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)))
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    override val chain = listOf(cert.encoded)
}

class CoreTest {
    private val rate = 48000

    /** Speech-like test signal: tones, noise and silence. */
    private fun signal(frames: Int, channels: Int, seed: Long): IntArray {
        val rnd = Random(seed)
        return IntArray(frames * channels) { idx ->
            val i = idx / channels
            val t = i.toDouble() / rate
            val loud = if ((i / 24000) % 5 == 4) 0.0 else 1.0
            (loud * (6000 * sin(2 * Math.PI * 220 * t) + 2500 * sin(2 * Math.PI * 1333 * t + idx % channels) + rnd.nextGaussian() * 300)).toInt().coerceIn(-32768, 32767)
        }
    }

    @Test fun merkleMatchesRfc6962() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", MerkleLog().root().hex())
        // RFC 6962 test vectors (certificate-transparency-go): roots for 1, 2, 3 and 8 leaves
        val inputs = listOf("", "00", "10", "2021", "3031", "40414243", "5051525354555657", "606162636465666768696a6b6c6d6e6f")
        val log = MerkleLog()
        val roots = inputs.map { log.append(MerkleLog.leaf(it.unhex())); log.root().hex() }
        assertEquals("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d", roots[0])
        assertEquals("fac54203e7cc696cf0dfcb42c92a1d9dbaf70ad9e621f4bd8d98662f00e3c125", roots[1])
        assertEquals("aeb6bcfe274b70a14fb067a5e5578264db0fa9b51af5e0ba159158f329e06e77", roots[2])
        assertEquals("5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328", roots[7])
    }

    @Test fun flacRoundTrip() {
        for (ch in 1..2) for (frames in listOf(1, 5, 4095, 4096, 4097, 100_003)) {
            val pcm = signal(frames, ch, frames.toLong())
            val fr = ByteArrayOutputStream()
            val enc = FlacEncoder(fr, rate, ch, 16)
            var off = 0
            val rnd = Random(1)
            while (off < frames) { // feed in uneven pieces
                val n = minOf(frames - off, 1 + rnd.nextInt(7000))
                enc.write(pcm.copyOfRange(off * ch, (off + n) * ch), n); off += n
            }
            val info = enc.finish()
            assertEquals(frames.toLong(), info.totalSamples)
            val file = ByteArrayOutputStream()
            FlacFile.write(file, info, "hello".toByteArray(), ByteArrayInputStream(fr.toByteArray()))
            val dec = FlacDecoder.open(ByteArrayInputStream(file.toByteArray()))
            assertArrayEquals("hello".toByteArray(), dec.application[FlacFile.APP_ID])
            val got = IntArray(frames * ch)
            assertEquals(frames, PcmReader(dec).read(got, frames + 10).coerceAtMost(frames))
            assertArrayEquals("ch=$ch frames=$frames", pcm, got)
            if (frames > 50_000) assertTrue("compresses", file.size() < frames * ch * 2 * 0.8)
        }
    }

    private fun record(seconds: Int, signer: SoftSigner, device: SoftSigner, out: File, tokens: List<Token> = emptyList(), open: OpenAnchor? = null): Bundle {
        val sid = ByteArray(16) { (it * 7).toByte() }
        val sealer = Sealer(sid)
        val frames = ByteArrayOutputStream()
        val enc = FlacEncoder(frames, rate, 1, 16)
        val bytes = ByteArray(rate * 2)
        sealer.addEvent("""{"type":"start"}""", 0, 1_700_000_000_000)
        for (s in 0 until seconds) {
            val pcm = signal(rate, 1, s.toLong())
            pcmBytes(pcm, rate, 16, bytes)
            enc.write(pcm, rate)
            sealer.addAudio(bytes, bytes.size, rate, (s + 1) * 1_000_000_000L, 1_700_000_000_000 + (s + 1) * 1000L, 0, 0)
            if (s == 1) sealer.addEvent("""{"type":"mark","pos":96000}""", 2_000_000_000L, 1_700_000_002_000)
            if (s % 2 == 1) sealer.checkpoint(signer, (s + 1) * 1_000_000_000L, 0)
        }
        sealer.addEvent("""{"type":"stop"}""", seconds * 1_000_000_000L, 0)
        sealer.checkpoint(signer, seconds * 1_000_000_000L, 0)
        val info = enc.finish()
        val m = Manifest(
            sessionId = sid.hex(), app = AppInfo("test.app", "0", 0),
            audio = AudioInfo(rate, 1, 16, info.totalSamples, rate, "test"),
            device = DeviceInfo("Test", "JVM", "0", 0, ""),
            keys = KeyInfo(sha256(device.pair.public.encoded).hex(), device.chain.map { it.b64() }, signer.chain.map { it.b64() },
                device.sign(Wire.endorsement(sid, signer.pair.public.encoded)).b64(), "software"),
            open = open, routes = listOf(Route(15, "Built-in mic", "test", true)),
            entries = sealer.entries, checkpoints = sealer.checkpoints, root = sealer.root().hex(), endReason = "user",
        )
        val mb = J.encodeToString(m).toByteArray()
        val bundle = Bundle(mb, signer.sign(mb), tokens)
        out.outputStream().use { FlacFile.write(it, info, bundle.encode(), ByteArrayInputStream(frames.toByteArray())) }
        return bundle
    }

    private fun verify(f: File) = f.inputStream().buffered().use { Verifier.verify(it) }

    @Test fun sealVerifyTamperSplit() {
        val dir = File("build/test-out").apply { mkdirs() }
        val f = File(dir, "rec.flac")
        record(7, SoftSigner(), SoftSigner(), f)
        val r = verify(f)
        r.checks.forEach(::println)
        assertFalse(r.failed)
        for (id in listOf("signature", "log", "audio", "device", "continuity")) assertEquals(id, Status.OK, r.status(id))
        assertEquals(Status.INFO, r.status("attestation"))
        assertTrue(r.shown.none { it.id == "attestation" })
        assertEquals("8B48-E36B-0264-B42D-8FD4-84CB-F10C-E857", shortDeviceId("8b48e36b0264b42d8fd484cbf10ce857773247b6e3927151229bf26b17a541a4"))
        assertEquals(7, r.entriesVerified)

        // flip one bit somewhere in the audio frames
        val bytes = f.readBytes()
        bytes[bytes.size - 5000] = (bytes[bytes.size - 5000].toInt() xor 1).toByte()
        val t = File(dir, "tampered.flac").apply { writeBytes(bytes) }
        assertEquals(Status.FAIL, verify(t).status("audio"))

        // re-encode with one sample changed: valid FLAC, wrong audio
        val dec = FlacDecoder.open(f.inputStream().buffered())
        val all = IntArray(7 * rate); PcmReader(dec).read(all, all.size)
        all[3 * rate + 17] += 1
        val fr = ByteArrayOutputStream(); val enc = FlacEncoder(fr, rate, 1, 16); enc.write(all, all.size)
        val t2 = File(dir, "edited.flac")
        t2.outputStream().use { FlacFile.write(it, enc.finish(), dec.application[FlacFile.APP_ID], ByteArrayInputStream(fr.toByteArray())) }
        val r2 = verify(t2)
        assertEquals(Status.FAIL, r2.status("audio"))
        assertTrue(r2.checks.first { it.id == "audio" }.detail.contains("second 4"))

        // split into parts: each verifies alone
        val parts = Exporter.split(f, f.length() / 3, dir, "rec")
        assertTrue(parts.size >= 3)
        var total = 0
        for (p in parts) { val pr = verify(p); assertFalse(p.name, pr.failed); assertEquals(Status.OK, pr.status("audio")); total += pr.entriesVerified }
        assertEquals(7, total)

        // evidence block can be replaced (e.g. adding a token later) without touching audio
        val f2 = File(dir, "rec2.flac")
        f.inputStream().use { i -> f2.outputStream().use { o -> FlacFile.replaceEvidence(i, o, Exporter.readBundle(f).encode()) } }
        assertFalse(verify(f2).failed)
    }

    @Test fun flac24BitRoundTrip() {
        val rnd = Random(9)
        val frames = 60_001
        val pcm = IntArray(frames) { i ->
            val loud = if (i in 20000..20400) 8_388_607.0 else 900_000.0   // includes full-scale clipping
            (loud * sin(i * 0.031) + rnd.nextGaussian() * 4000).toInt().coerceIn(-8_388_608, 8_388_607)
        }
        val fr = ByteArrayOutputStream()
        val enc = FlacEncoder(fr, rate, 1, 24); enc.write(pcm, frames)
        val file = File("build/test-out").apply { mkdirs() }.resolve("pcm24.flac")
        file.outputStream().use { FlacFile.write(it, enc.finish(), null, ByteArrayInputStream(fr.toByteArray())) }
        val got = IntArray(frames)
        assertEquals(frames, PcmReader(FlacDecoder.open(file.inputStream().buffered())).read(got, frames))
        assertArrayEquals(pcm, got)
        val raw = ByteArray(frames * 3); pcmBytes(pcm, frames, 24, raw)
        File("build/test-out/pcm24.raw").writeBytes(raw)
    }

    private fun res(name: String) = javaClass.getResourceAsStream("/tsa/$name")!!.readBytes()

    /** Real tokens from the three authorities must chain to the pinned EU Trusted List services. */
    @Test fun realTokensAreTrusted() {
        val digest = sha256(ByteArray(32) { it.toByte() })
        for (n in listOf("sectigo-qualified", "accv", "izenpe")) {
            val i = Tsa.inspect(res("$n.tsr"), digest)
            assertTrue(n, i.valid && i.declaresQualified)
            assertNotNull(n, i.trustedService)
            assertFalse(Tsa.inspect(res("$n.tsr"), sha256(ByteArray(1))).imprintMatches)
        }
    }

    /** A perfectly well-formed token from a made-up authority must not count as certified time. */
    @Test fun forgedAuthorityIsNotTrusted() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("C=ES,O=Sectigo (Europe) SL,CN=Sectigo Qualified Time Stamping Signer #3")
        val cert = JcaX509v3CertificateBuilder(name, BigInteger.TWO, Date(0), Date(4102444800000L), name, kp.public)
            .addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true, org.bouncycastle.asn1.x509.ExtendedKeyUsage(org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val digest = sha256("x".toByteArray())
        val gen = org.bouncycastle.tsp.TimeStampTokenGenerator(
            org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", kp.private, JcaX509CertificateConverter().getCertificate(cert)),
            org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().build().get(org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256)),
            org.bouncycastle.asn1.ASN1ObjectIdentifier("0.4.0.2023.1.1"))
        gen.addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(cert)))
        val req = org.bouncycastle.tsp.TimeStampRequestGenerator().apply { setCertReq(true) }.generate(org.bouncycastle.tsp.TSPAlgorithms.SHA256, digest)
        val token = gen.generate(req, BigInteger.ONE, Date()).encoded
        val info = Tsa.inspect(token, digest)
        assertTrue(info.valid)
        assertNull(info.trustedService)

        // sealed with that token as closing timestamp: the verifier must not report a certified time
        val dir = File("build/test-out").apply { mkdirs() }
        val f = File(dir, "forged-time.flac")
        val signer = SoftSigner()
        val b = record(2, signer, signer, f)
        val f2 = File(dir, "forged-time2.flac")
        val forged = org.bouncycastle.tsp.TimeStampTokenGenerator(
            org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", kp.private, JcaX509CertificateConverter().getCertificate(cert)),
            org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().build().get(org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256)),
            org.bouncycastle.asn1.ASN1ObjectIdentifier("0.4.0.2023.1.1")).apply { addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(cert))) }
            .generate(org.bouncycastle.tsp.TimeStampRequestGenerator().apply { setCertReq(true) }.generate(org.bouncycastle.tsp.TSPAlgorithms.SHA256, sha256(b.manifestBytes)), BigInteger.TEN, Date()).encoded
        f.inputStream().use { i -> f2.outputStream().use { o -> FlacFile.replaceEvidence(i, o, b.with(tokens = listOf(Token("sectigo-qualified", forged.b64()))).encode()) } }
        val r = verify(f2)
        assertNull(r.closeTime)
        assertEquals(Status.WARN, r.status("time-issuer"))
    }

    /** Fingerprint of everything that is evidence in a file: proof, signature, tokens and every audio sample. */
    private fun evidenceOf(file: ByteArray): String {
        val dec = FlacDecoder.open(ByteArrayInputStream(file))
        val b = Bundle.decode(dec.application[FlacFile.APP_ID]!!)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(b.manifestBytes); md.update(b.signature); b.closeTokens.forEach { md.update(it.token.toByteArray()) }
        while (true) { val blk = dec.readBlock() ?: break; for (i in 0 until dec.blockFrames * dec.channels) { md.update(blk[i].toByte()); md.update((blk[i] shr 8).toByte()); md.update((blk[i] shr 16).toByte()) } }
        return md.digest().hex()
    }

    /** Hostile or damaged files must produce a failed check, never an exception or a huge allocation. */
    @Test fun hostileFilesAreRejectedCleanly() {
        val dir = File("build/test-out").apply { mkdirs() }
        val f = File(dir, "hostile-src.flac")
        val signer = SoftSigner()
        val b = record(2, signer, signer, f)
        fun withManifest(edit: (String) -> String, part: PartInfo? = null): Report {
            val text = edit(b.manifestBytes.toString(Charsets.UTF_8))
            val out = File(dir, "hostile.flac")
            f.inputStream().use { i -> out.outputStream().use { o -> FlacFile.replaceEvidence(i, o, Bundle(text.toByteArray(), b.signature, emptyList(), part).encode()) } }
            return verify(out)
        }
        assertTrue(withManifest({ it.replace("\"f\":48000", "\"f\":2147483647") }).failed)
        assertTrue(withManifest({ it.replace("\"f\":48000", "\"f\":-5") }).failed)
        assertTrue(withManifest({ it.replace("\"sessionChain\":[\"", "\"sessionChain\":[\"AAAA") }).failed)
        assertTrue(withManifest({ it.replace("\"sessionId\":\"", "\"sessionId\":\"zz") }).failed)
        assertTrue(withManifest({ it }, PartInfo(1, 1, 5, 1000)).failed)
        assertTrue(withManifest({ it }, PartInfo(1, 1, -1, 1)).failed)
        // random corruption anywhere in the file
        val bytes = f.readBytes()
        val rnd = Random(5)
        repeat(300) {
            val c = bytes.copyOf()
            repeat(1 + rnd.nextInt(4)) { c[rnd.nextInt(c.size)] = rnd.nextInt().toByte() }
            // Either the verifier fails, or the damage hit bytes that carry no evidence
            // (container padding) and audio plus proof are still exactly the original.
            if (!Verifier.verify(ByteArrayInputStream(c)).failed) assertEquals("corruption $it changed evidence unnoticed", evidenceOf(bytes), evidenceOf(c))
        }
        // truncation at every kind of place
        for (cut in listOf(0, 3, 10, 41, 50, 200, bytes.size / 2, bytes.size - 1)) assertTrue(Verifier.verify(ByteArrayInputStream(bytes.copyOf(cut))).failed)
    }

    /** Evidence larger than one FLAC metadata block is spread over several and read back intact. */
    @Test fun largeEvidenceSpansBlocks() {
        val big = ByteArray(20 * 1024 * 1024) { (it * 31).toByte() }
        val fr = ByteArrayOutputStream(); val enc = FlacEncoder(fr, rate, 1, 16); enc.write(IntArray(100), 100)
        val out = ByteArrayOutputStream()
        FlacFile.write(out, enc.finish(), big, ByteArrayInputStream(fr.toByteArray()))
        val dec = FlacDecoder.open(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(big, dec.application[FlacFile.APP_ID])
        assertEquals(100, PcmReader(dec).read(IntArray(200), 200))
    }

    /** One FLAC metadata block: header (last flag, type, 24-bit length) and body. */
    private fun block(out: ByteArrayOutputStream, last: Boolean, type: Int, body: ByteArray) {
        out.write((if (last) 0x80 else 0) or type); out.write(body.size ushr 16); out.write(body.size ushr 8); out.write(body.size); out.write(body)
    }

    /** Evidence cut into thousands of small blocks reads back intact; the pieces are joined once, not once per block. */
    @Test fun fragmentedEvidenceIsJoined() {
        val fr = ByteArrayOutputStream(); val enc = FlacEncoder(fr, rate, 1, 16); enc.write(IntArray(100), 100)
        val info = enc.finish()
        val piece = 2048
        val count = 4000
        val big = ByteArray(count * piece) { (it * 31 + it / 7).toByte() }
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        block(out, false, 0, info.encode())
        for (p in 0 until count) block(out, p == count - 1, 2, "AEv1".toByteArray() + big.copyOfRange(p * piece, (p + 1) * piece))
        out.write(fr.toByteArray())
        val dec = FlacDecoder.open(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(big, dec.application[FlacFile.APP_ID])
        assertEquals(100, PcmReader(dec).read(IntArray(200), 200))
    }

    /** A bundle that inflates far beyond its own size is refused while unpacking; the most compressible honest one is not. */
    @Test fun inflatedBundleIsRefused() {
        fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
            val bo = ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bo).use { z -> for ((n, d) in files) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(d); z.closeEntry() } }
            return bo.toByteArray()
        }
        // Honest worst case: a long all-silent recording, every slice with the same hash, perfectly steady clocks.
        val h = sha256(ByteArray(rate * 2)).hex()
        val silent = J.encodeToString(List(20000) { Entry("a", it * 1_000_000_000L, 1_700_000_000_000 + it * 1000L, f = rate, fl = FLAG_SILENT, h = h) }).toByteArray()
        val honest = zip("evidence.json" to silent, "evidence.sig" to ByteArray(72))
        println("honest silent manifest: ${silent.size} bytes, ratio %.1f".format(silent.size.toDouble() / honest.size))
        assertTrue(silent.size > 1024 * 1024)   // large enough that the ratio, not the floor, applies
        assertArrayEquals(silent, Bundle.decode(honest).manifestBytes)

        val bomb = zip("evidence.json" to ByteArray(40 * 1024 * 1024) { ' '.code.toByte() }, "evidence.sig" to ByteArray(72))
        assertThrows(java.io.IOException::class.java) { Bundle.decode(bomb) }
        // Through the verifier it is a failed check, not an exception.
        val fr = ByteArrayOutputStream(); val enc = FlacEncoder(fr, rate, 1, 16); enc.write(IntArray(100), 100)
        val file = ByteArrayOutputStream().also { FlacFile.write(it, enc.finish(), bomb, ByteArrayInputStream(fr.toByteArray())) }
        val r = Verifier.verify(ByteArrayInputStream(file.toByteArray()))
        assertEquals(Status.FAIL, r.status("bundle"))
        assertTrue(r.checks.first().detail, r.checks.first().detail.contains("larger than"))
    }

    /** LPC subframes that reference decoders refuse (negative shift, precision code 1111) are refused here too, not decoded. */
    @Test fun invalidLpcFieldsAreRejected() {
        val n = 16
        fun frame(shift: Int, precisionCode: Int = 3): ByteArray {
            val w = BitWriter()
            w.bits(0x3FFE, 14); w.bits(0, 2)           // sync, reserved, fixed block size
            w.bits(7, 4); w.bits(10, 4)                // block size in 16 bits after the header, 48 kHz
            w.bits(0, 4); w.bits(4, 3); w.bits(0, 1)   // mono, 16 bit
            w.bits(0, 8)                               // frame number 0
            w.bits((n - 1).toLong(), 16)
            w.bits(Crc.crc8(w.raw(), w.byteLength).toLong(), 8)
            w.bits(0, 1); w.bits(32, 6); w.bits(0, 1)  // LPC subframe of order 1, no wasted bits
            w.signed(100, 16)                          // warm-up sample
            w.bits(precisionCode.toLong(), 4); w.signed(shift, 5); w.signed(1, precisionCode + 1)   // precision, shift, coefficient 1
            w.bits(0, 2); w.bits(0, 4); w.bits(0, 4)   // Rice coding, one partition, parameter 0
            repeat(n - 1) { w.unary(0) }               // every residual 0
            w.align()
            w.bits(Crc.crc16(w.raw(), w.byteLength).toLong(), 16)
            return w.toByteArray()
        }
        val ok = FlacDecoder.frames(ByteArrayInputStream(frame(0)), rate, 1, 16)
        val pcm = ok.readBlock()!!
        assertEquals(n, ok.blockFrames)
        assertTrue(pcm.take(n).all { it == 100 })
        val bad = FlacDecoder.frames(ByteArrayInputStream(frame(-1)), rate, 1, 16)
        assertThrows(FlacFormatException::class.java) { bad.readBlock() }
        val badPrecision = FlacDecoder.frames(ByteArrayInputStream(frame(0, 15)), rate, 1, 16)
        assertThrows(FlacFormatException::class.java) { badPrecision.readBlock() }
    }

    private fun BitWriter.sub(type: Int) { bits(0, 1); bits(type.toLong(), 6); bits(0, 1) }
    private fun BitWriter.constant(v: Int, depth: Int = 16) { sub(0); signed(v, depth) }

    /** One 16-bit frame with its header fields given as raw codes; [body] writes the subframes. */
    private fun rawFrame(n: Int, num: ByteArray = byteArrayOf(1), sr: Int = 10, srExt: Pair<Long, Int>? = null, ch: Int = 0, reserved: Int = 0,
                         body: BitWriter.() -> Unit): ByteArray {
        val w = BitWriter()
        w.bits(0x3FFE, 14); w.bits(0, 2)
        w.bits(7, 4); w.bits(sr.toLong(), 4); w.bits(ch.toLong(), 4); w.bits(4, 3); w.bits(reserved.toLong(), 1)
        for (b in num) w.bits(b.toLong() and 0xff, 8)
        w.bits((n - 1).toLong(), 16)
        srExt?.let { (v, len) -> w.bits(v, len) }
        w.bits(Crc.crc8(w.raw(), w.byteLength).toLong(), 8)
        body(w)
        w.align()
        w.bits(Crc.crc16(w.raw(), w.byteLength).toLong(), 16)
        return w.toByteArray()
    }

    /** Decodes a good frame, [mid], a good frame (48 kHz, 16 bit); returns the sample frames read. */
    private fun decodeAround(mid: ByteArray, channels: Int = 1, maxBlock: Int = 65535): Int {
        fun good(i: Int) = rawFrame(32, num = byteArrayOf(i.toByte()), ch = channels - 1) { repeat(channels) { constant(1000) } }
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        block(out, true, 0, StreamInfo(16, maxBlock, 0, 0, rate, channels, 16, 0, ByteArray(16)).encode())
        out.write(good(0)); out.write(mid); out.write(good(2))
        val d = FlacDecoder.open(ByteArrayInputStream(out.toByteArray()))
        var total = 0
        while (d.readBlock() != null) total += d.blockFrames
        return total
    }

    /**
     * Frames that ffmpeg drops or plays differently are refused here too, so a file cannot verify
     * while ordinary players skip or change part of its audio. Each case is one frame between two good ones.
     */
    @Test fun framesOtherDecodersRefuseAreRefused() {
        val n = 32
        fun refused(what: String, mid: ByteArray, channels: Int = 1, maxBlock: Int = 65535) =
            assertThrows(what, FlacFormatException::class.java) { decodeAround(mid, channels, maxBlock) }
        fun accepted(what: String, mid: ByteArray, size: Int = n, channels: Int = 1, maxBlock: Int = 65535) =
            assertEquals(what, 64 + size, decodeAround(mid, channels, maxBlock))

        // frame header
        refused("reserved header bit", rawFrame(n, reserved = 1) { constant(2000) })
        for (c in 11..15) refused("channel code $c", rawFrame(n, ch = c) { constant(2000); constant(-2000) }, channels = 2)
        refused("sample rate code 15", rawFrame(n, sr = 15) { constant(2000) })
        refused("8 kHz frame in a 48 kHz stream", rawFrame(n, sr = 4) { constant(2000) })
        refused("44.1 kHz frame in a 48 kHz stream", rawFrame(n, sr = 13, srExt = 44100L to 16) { constant(2000) })
        for (num in listOf(byteArrayOf(0x81.toByte()), byteArrayOf(0xC0.toByte(), 0x41), byteArrayOf(0xFE.toByte()) + ByteArray(6) { 0x80.toByte() },
                           byteArrayOf(0xFF.toByte()) + ByteArray(7) { 0x80.toByte() }))
            refused("frame number ${num.hex()}", rawFrame(n, num = num) { constant(2000) })
        refused("block above the STREAMINFO maximum", rawFrame(64) { constant(2000) }, maxBlock = 32)
        refused("block of 65536", rawFrame(65536) { constant(2000) })
        // residuals that do not fit 32 bits
        refused("Rice value 0xFFFFFFFF", rawFrame(n) { sub(9); signed(0, 16); bits(1, 2); bits(0, 4); bits(16, 5); unary(0xFFFF); bits(0xFFFF, 16); repeat(n - 2) { unary(0); bits(0, 16) } })
        refused("Rice value over 32 bits", rawFrame(n) { sub(9); signed(0, 16); bits(0, 2); bits(0, 4); bits(14, 4); unary(0x40001); bits(2, 14); repeat(n - 2) { unary(0); bits(0, 14) } })
        // samples outside the bit depth
        refused("left/side, right over range", rawFrame(n, ch = 8) { constant(32767); constant(-65536, 17) }, channels = 2)
        refused("right/side, left over range", rawFrame(n, ch = 9) { constant(-65536, 17); constant(32767) }, channels = 2)
        refused("mid/side, left over range", rawFrame(n, ch = 10) { constant(32767); constant(65535, 17) }, channels = 2)
        refused("LPC sum past 32 bits", rawFrame(n) { sub(32); signed(32767, 16); bits(14, 4); signed(14, 5); signed(16383, 15); bits(0, 2); bits(0, 4); bits(15, 4); bits(22, 5); signed(1 shl 20, 22); repeat(n - 2) { signed(0, 22) } })
        refused("fixed predictor past the bit depth", rawFrame(n) { sub(9); signed(30000, 16); bits(0, 2); bits(0, 4); bits(10, 4); unary(19); bits(544, 10); repeat(n - 2) { unary(0); bits(0, 10) } })

        // what stays valid
        accepted("rate taken from STREAMINFO", rawFrame(n, sr = 0) { constant(2000) })
        accepted("48 kHz as kHz", rawFrame(n, sr = 12, srExt = 48L to 8) { constant(2000) })
        accepted("48 kHz as Hz", rawFrame(n, sr = 13, srExt = 48000L to 16) { constant(2000) })
        accepted("48 kHz as tens of Hz", rawFrame(n, sr = 14, srExt = 4800L to 16) { constant(2000) })
        accepted("six-byte frame number", rawFrame(n, num = byteArrayOf(0xFC.toByte()) + ByteArray(5) { 0x80.toByte() }) { constant(2000) })
        accepted("block equal to the STREAMINFO maximum", rawFrame(32) { constant(2000) }, maxBlock = 32)
        accepted("left/side at both extremes", rawFrame(n, ch = 8) { constant(32767); constant(65535, 17) }, channels = 2)
        accepted("full-scale verbatim", rawFrame(n) { sub(1); repeat(n) { signed(if (it % 2 == 0) -32768 else 32767, 16) } })
        accepted("LPC at full precision", rawFrame(n) { sub(32); signed(20000, 16); bits(14, 4); signed(15, 5); signed(16383, 15); bits(0, 2); bits(0, 4); bits(0, 4); repeat(n - 1) { unary(0) } })
    }

    /** A well-formed timestamp token over [digest] from a made-up authority: valid, but not from a trusted service. */
    private fun selfMadeToken(digest: ByteArray): ByteArray {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Test Time Stamping")
        val cert = JcaX509v3CertificateBuilder(name, BigInteger.TWO, Date(0), Date(4102444800000L), name, kp.public)
            .addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true, org.bouncycastle.asn1.x509.ExtendedKeyUsage(org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val gen = org.bouncycastle.tsp.TimeStampTokenGenerator(
            org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", kp.private, JcaX509CertificateConverter().getCertificate(cert)),
            org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().build().get(org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256)),
            org.bouncycastle.asn1.ASN1ObjectIdentifier("0.4.0.2023.1.1"))
        gen.addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(cert)))
        val req = org.bouncycastle.tsp.TimeStampRequestGenerator().apply { setCertReq(true) }.generate(org.bouncycastle.tsp.TSPAlgorithms.SHA256, digest)
        return gen.generate(req, BigInteger.ONE, Date()).encoded
    }

    /**
     * Many checkpoints sharing one size cost one token check per intermediate timestamp, not one
     * per checkpoint, and the checkpoint a token really covers is still found among them.
     */
    @Test(timeout = 20_000) fun midTimestampsAreMatchedOnce() {
        val dir = File("build/test-out").apply { mkdirs() }
        val f = File(dir, "mid-src.flac")
        val signer = SoftSigner()
        val m = record(4, signer, signer, f).manifest
        val target = m.checkpoints.first()
        val digest = sha256(Wire.checkpoint(m.sessionId.unhex(), target.size, target.root.unhex(), target.en, target.w))
        val decoys = List(3000) { Checkpoint(target.size, "00".repeat(32), it.toLong(), 0, "") }
        val elsewhere = res("accv.tsr").b64()   // a real token, over something else
        val edited = m.copy(
            checkpoints = decoys + m.checkpoints,
            mid = List(100) { MidAnchor(target.size, "accv", elsewhere) } + MidAnchor(target.size, "test", selfMadeToken(digest).b64()),
        )
        val mb = J.encodeToString(edited).toByteArray()
        val out = File(dir, "mid.flac")
        f.inputStream().use { i -> out.outputStream().use { o -> FlacFile.replaceEvidence(i, o, Bundle(mb, signer.sign(mb), emptyList()).encode()) } }
        val r = verify(out)
        assertEquals(100, r.checks.count { it.id == "time-mid" && it.status == Status.FAIL })
        // the self-made token found its checkpoint: valid, so only its issuer is questioned
        assertEquals(1, r.checks.count { it.id == "time-issuer" && it.status == Status.WARN })
        assertEquals(Status.FAIL, r.status("log"))   // the decoys themselves are invalid checkpoints
    }

    /** Only ASCII hex digits decode; other characters, including other scripts' digits, are refused. */
    @Test fun hexIsStrict() {
        assertArrayEquals(byteArrayOf(0, 0x7f, -1, 0xab.toByte()), "007fFFab".unhex())
        for (bad in listOf("zz", "0g", "０１", "ａａ", "٠١", "-1", " 1", "1\u0000"))
            assertThrows(bad, IllegalArgumentException::class.java) { bad.unhex() }
    }

    /** Replacing the evidence keeps the file's other metadata; only the old evidence goes. Our own files come out exactly as before. */
    @Test fun replaceEvidenceKeepsOtherMetadata() {
        val fr = ByteArrayOutputStream(); val enc = FlacEncoder(fr, rate, 1, 16); enc.write(signal(5000, 1, 3), 5000)
        val info = enc.finish()
        val frames = fr.toByteArray()
        val tags = "tags".toByteArray() + ByteArray(40) { it.toByte() }
        val foreign = "TEST".toByteArray() + ByteArray(10) { 7 }
        val src = ByteArrayOutputStream()
        src.write("fLaC".toByteArray())
        block(src, false, 0, info.encode())
        block(src, false, 4, tags)
        block(src, false, 2, "AEv1old".toByteArray())
        block(src, false, 2, foreign)
        block(src, false, 9, ByteArray(5))     // reserved type: dropped, as before
        block(src, true, 1, ByteArray(100))    // padding
        src.write(frames)
        val b = ByteArrayOutputStream().also { FlacFile.replaceEvidence(ByteArrayInputStream(src.toByteArray()), it, "new".toByteArray()) }.toByteArray()

        val blocks = ArrayList<Triple<Boolean, Int, ByteArray>>()
        var p = 4
        while (true) {
            val h = b[p].toInt() and 0xff
            val len = ((b[p + 1].toInt() and 0xff) shl 16) or ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
            blocks += Triple(h and 0x80 != 0, h and 0x7f, b.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
            if (h and 0x80 != 0) break
        }
        assertEquals(listOf(0, 4, 2, 1, 2), blocks.map { it.second })
        assertEquals(listOf(false, false, false, false, true), blocks.map { it.first })
        assertArrayEquals(tags, blocks[1].third)
        assertArrayEquals(foreign, blocks[2].third)
        assertArrayEquals(ByteArray(100), blocks[3].third)
        assertArrayEquals(frames, b.copyOfRange(p, b.size))
        val dec = FlacDecoder.open(ByteArrayInputStream(b))
        assertArrayEquals("new".toByteArray(), dec.application[FlacFile.APP_ID])
        assertEquals(5000, PcmReader(dec).read(IntArray(6000), 6000))

        // A file as the app writes it (STREAMINFO + evidence) is rewritten byte for byte as before.
        val own = ByteArrayOutputStream().also { FlacFile.write(it, info, "old".toByteArray(), ByteArrayInputStream(frames)) }
        val again = ByteArrayOutputStream().also { FlacFile.replaceEvidence(ByteArrayInputStream(own.toByteArray()), it, "new".toByteArray()) }
        val expected = ByteArrayOutputStream().also { FlacFile.write(it, info, "new".toByteArray(), ByteArrayInputStream(frames)) }
        assertArrayEquals(expected.toByteArray(), again.toByteArray())

        // What is carried over is bounded.
        val many = ByteArrayOutputStream()
        many.write("fLaC".toByteArray()); block(many, false, 0, info.encode())
        repeat(300) { block(many, it == 299, 4, ByteArray(1)) }
        assertThrows(FlacFormatException::class.java) { FlacFile.replaceEvidence(ByteArrayInputStream(many.toByteArray()), ByteArrayOutputStream(), "x".toByteArray()) }
    }

    /** Stereo, 16 and 24 bit: similar channels, opposite channels and unrelated channels all survive exactly. */
    @Test fun stereoRoundTrip() {
        val dir = File("build/test-out").apply { mkdirs() }
        for (bits in listOf(16, 24)) {
            val rnd = Random(bits.toLong())
            val frames = 50_000
            val amp = if (bits == 16) 9000.0 else 2_300_000.0
            val full = if (bits == 16) 32767 else 8_388_607
            val pcm = IntArray(frames * 2) { idx ->
                val i = idx / 2
                val l = amp * sin(i * 0.02) + rnd.nextGaussian() * amp / 60
                val v = when {
                    i < 20000 -> if (idx % 2 == 0) l else l * 0.97 + rnd.nextGaussian() * amp / 200      // near-identical channels
                    i < 30000 -> if (idx % 2 == 0) full.toDouble() else -full.toDouble() - 1            // extreme opposite values
                    else -> rnd.nextGaussian() * amp                                                    // unrelated noise
                }
                v.toInt().coerceIn(-full - 1, full)
            }
            val fr = ByteArrayOutputStream()
            val enc = FlacEncoder(fr, rate, 2, bits); enc.write(pcm, frames)
            val f = File(dir, "stereo$bits.flac")
            f.outputStream().use { FlacFile.write(it, enc.finish(), null, ByteArrayInputStream(fr.toByteArray())) }
            val got = IntArray(frames * 2)
            assertEquals(frames, PcmReader(FlacDecoder.open(f.inputStream().buffered())).read(got, frames))
            assertArrayEquals("bits=$bits", pcm, got)
            val raw = ByteArray(frames * 2 * bits / 8); pcmBytes(pcm, frames * 2, bits, raw)
            File(dir, "stereo$bits.raw").writeBytes(raw)
        }
    }

    @Test fun pdfA() {
        val font = javaClass.getResourceAsStream("/font/LiberationSans-Regular.ttf")!!.readBytes()
        val pdf = evidence.core.pdf.PdfA(font, "LiberationSans", "Relazione tecnica", "it-IT", "2026-11-15T10:00:00Z", "test")
        pdf.text("Relazione tecnica su una registrazione audio", 17f, bold = true)
        pdf.text("Perché è così: àèéìòù ÀÈ · «citazione» – 48 kHz, 24 bit (senza perdita).", 10f)
        pdf.text("8b48e36b0264b42d8fd484cbf10ce857773247b6e3927151229bf26b17a541a48b48e36b0264b42d8fd484cbf10ce857773247b6e3927151229bf26b17a541a4", 9f, gray = 0.4f)
        repeat(90) { pdf.text("Riga $it: ogni secondo è bloccato; cambiare un istante rompe il sigillo.") }
        val out = File("build/test-out").apply { mkdirs() }.resolve("report.pdf")
        out.outputStream().use { pdf.writeTo(it) }
        val a = out.readBytes()
        val again = ByteArrayOutputStream().also { o ->
            evidence.core.pdf.PdfA(font, "LiberationSans", "Relazione tecnica", "it-IT", "2026-11-15T10:00:00Z", "test").apply { text("x") }.writeTo(o)
        }
        assertTrue(String(a, Charsets.ISO_8859_1).startsWith("%PDF-1.7"))
        assertTrue(String(a, Charsets.ISO_8859_1).contains("<pdfaid:part>2</pdfaid:part>"))
        assertTrue(again.size() > 1000)
    }

    @Test fun asicContainer() {
        val dir = File("build/test-out").apply { mkdirs() }
        val f = File(dir, "asic-src.flac")
        val signer = SoftSigner()
        record(2, signer, signer, f)
        val out = File(dir, "package.asice")
        out.outputStream().use { Asic.write(it, listOf(Asic.Item("recording.flac", "audio/flac", f), Asic.Item("VERIFY.txt", "text/plain", bytes = "hi".toByteArray())), signer) }
        val names = java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name } }
        assertEquals("mimetype", names.first())
        assertTrue(names.containsAll(listOf("recording.flac", "META-INF/ASiCManifest001.xml", "META-INF/signature001.p7s")))
        java.util.zip.ZipFile(out).use { z ->
            File(dir, "asic-manifest.xml").writeBytes(z.getInputStream(z.getEntry("META-INF/ASiCManifest001.xml")).readBytes())
            File(dir, "asic-signature.p7s").writeBytes(z.getInputStream(z.getEntry("META-INF/signature001.p7s")).readBytes())
        }
    }

    /** Records with real opening, intermediate and closing timestamps and verifies the result. Run with -Dlive=1. */
    @Test fun liveFullyTimestampedRecording() {
        if (System.getProperty("live") != "1") return
        val dir = File("build/test-out").apply { mkdirs() }
        val sid = ByteArray(16) { (it * 7).toByte() }
        val signer = SoftSigner()
        val nonce = ByteArray(32) { (it + 3).toByte() }
        val open = OpenAnchor("sectigo-qualified", nonce.hex(), Tsa.request(Tsa.DEFAULTS[0].url, sha256(nonce)).b64())
        val sealer = Sealer(sid)
        val frames = ByteArrayOutputStream(); val enc = FlacEncoder(frames, rate, 1, 16)
        val bytes = ByteArray(rate * 2)
        sealer.addEvent("""{"type":"anchored","pos":0}""", 0, 0)
        val mids = ArrayList<MidAnchor>()
        for (sec in 0 until 3) {
            val pcm = signal(rate, 1, sec.toLong()); pcmBytes(pcm, rate, 16, bytes); enc.write(pcm, rate)
            sealer.addAudio(bytes, bytes.size, rate, (sec + 1) * 1_000_000_000L, 0, 0, 0)
            Thread.sleep(1000)
            if (sec == 1) {
                val cp = sealer.checkpoint(signer, 2_000_000_000L, 0)
                mids += MidAnchor(cp.size, "accv", Tsa.request(Tsa.DEFAULTS[1].url, sha256(Wire.checkpoint(sid, cp.size, cp.root.unhex(), cp.en, cp.w))).b64())
            }
        }
        sealer.checkpoint(signer, 3_000_000_000L, 0)
        val info = enc.finish()
        val m = Manifest(sessionId = sid.hex(), app = AppInfo("test.app", "0", 0), audio = AudioInfo(rate, 1, 16, info.totalSamples, rate, "test"),
            device = DeviceInfo("Test", "JVM", "0", 0, ""),
            keys = KeyInfo(sha256(signer.pair.public.encoded).hex(), signer.chain.map { it.b64() }, signer.chain.map { it.b64() }, signer.sign(Wire.endorsement(sid, signer.pair.public.encoded)).b64(), "software"),
            open = open, routes = listOf(Route(15, "mic", "", true)), entries = sealer.entries, checkpoints = sealer.checkpoints, mid = mids, root = sealer.root().hex(), endReason = "user")
        val mb = J.encodeToString(m).toByteArray()
        val close = Token("izenpe", Tsa.request(Tsa.DEFAULTS[2].url, sha256(mb)).b64())
        val f = File(dir, "live-full.flac")
        f.outputStream().use { FlacFile.write(it, info, Bundle(mb, signer.sign(mb), listOf(close)).encode(), ByteArrayInputStream(frames.toByteArray())) }
        val r = verify(f)
        r.checks.forEach { println("LIVE-FULL $it") }
        assertFalse(r.failed)
        for (id in listOf("time-open", "time-mid", "time-close", "audio", "log")) assertEquals(id, Status.OK, r.status(id))
    }

    /** Talks to the real free qualified TSA. Run with -Dlive=1. */
    @Test fun liveQualifiedTimestamp() {
        if (System.getProperty("live") != "1") return
        val dir = File("build/test-out").apply { mkdirs() }
        val nonce = ByteArray(32) { it.toByte() }
        for (p in Tsa.DEFAULTS) {
            try {
                val tok = Tsa.request(p.url, sha256(nonce))
                val i = Tsa.inspect(tok, sha256(nonce))
                println("LIVE ${p.id}: ${i.time.toInstant()} signer=${i.signer} qualifiedStatement=${i.declaresQualified} policy=${i.policy} certSha256=${i.signerCertSha256}")
                File(dir, "${p.id}.tsr").writeBytes(tok)
            } catch (e: Exception) { println("LIVE ${p.id}: FAILED ${e}") }
        }
    }
}
