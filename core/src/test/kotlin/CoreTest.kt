// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

import evidence.core.*
import evidence.core.flac.FlacDecoder
import evidence.core.flac.FlacEncoder
import evidence.core.flac.FlacFile
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
